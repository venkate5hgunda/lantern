package dev.lantern.peer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.net.URL
import java.security.KeyPair
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal data class PushJob(val id: String, val grant: String, val payload: String, val attempts: Int)
internal class PushStore(
    context: Context,
    private val seal: (String) -> String = SecretVault::seal,
    private val open: (String) -> String = SecretVault::open,
    private val now: () -> Long = System::currentTimeMillis
) : SQLiteOpenHelper(context, "push.db", null, 1) {
    private val prefs = context.getSharedPreferences("push-keys", Context.MODE_PRIVATE)
    @Synchronized fun keys(): KeyPair {
        val saved = prefs.getString("vapid", null)
        if (saved != null) {
            val json = JSONObject(open(saved))
            return KeyPair(WebPushCrypto.publicKey(WebPushCrypto.decode(json.getString("public"))), WebPushCrypto.privateKey(WebPushCrypto.decode(json.getString("private"))))
        }
        val keys = WebPushCrypto.generate()
        check(prefs.edit().putString("vapid", seal(JSONObject().put("public", WebPushCrypto.encode(WebPushCrypto.publicBytes(keys.public)))
            .put("private", WebPushCrypto.encode(keys.private.encoded)).toString())).commit())
        return keys
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE subscriptions (grant_id TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE push_jobs (id TEXT NOT NULL, grant_id TEXT NOT NULL, payload TEXT NOT NULL, created INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id,grant_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    @Synchronized fun subscribe(grant: String, value: JSONObject) {
        require(WebPushCrypto.validEndpoint(value.getString("endpoint")))
        val keys = value.getJSONObject("keys")
        WebPushCrypto.publicKey(WebPushCrypto.decode(keys.getString("p256dh")))
        require(WebPushCrypto.decode(keys.getString("auth")).size == 16)
        val exists = readableDatabase.rawQuery("SELECT grant_id FROM subscriptions WHERE grant_id=?", arrayOf(grant)).use { it.moveToFirst() }
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM subscriptions", null).use { it.moveToFirst(); it.getInt(0) }
        check(exists || count < 64) { "Notification registration capacity reached" }
        val sanitized = JSONObject().put("endpoint", value.getString("endpoint")).put("keys", JSONObject()
            .put("p256dh", keys.getString("p256dh")).put("auth", keys.getString("auth")))
        writableDatabase.insertWithOnConflict("subscriptions", null, ContentValues().apply {
            put("grant_id", grant); put("value", seal(sanitized.toString()))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    @Synchronized fun remove(grant: String) {
        writableDatabase.delete("subscriptions", "grant_id=?", arrayOf(grant))
        writableDatabase.delete("push_jobs", "grant_id=?", arrayOf(grant))
    }
    @Synchronized fun prune(active: Set<String>) {
        val saved = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT grant_id FROM subscriptions", null).use { while (it.moveToNext()) saved.add(it.getString(0)) }
        saved.filter { it !in active }.forEach(::remove)
        writableDatabase.delete("push_jobs", "created<=?", arrayOf((now() - TimeUnit.HOURS.toMillis(1)).toString()))
    }
    @Synchronized fun enqueue(id: String, payload: JSONObject, active: Set<String>) {
        require(payload.toString().toByteArray().size <= 3000)
        prune(active)
        val targets = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT grant_id FROM subscriptions", null).use { while (it.moveToNext()) targets.add(it.getString(0)) }
        for (grant in targets) writableDatabase.insertWithOnConflict("push_jobs", null, ContentValues().apply {
            put("id", id); put("grant_id", grant); put("payload", payload.toString()); put("created", now())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.execSQL("DELETE FROM push_jobs WHERE rowid NOT IN (SELECT rowid FROM push_jobs ORDER BY created DESC, rowid DESC LIMIT 2048)")
    }
    @Synchronized fun due(): List<PushJob> {
        val jobs = mutableListOf<PushJob>()
        readableDatabase.rawQuery("SELECT id,grant_id,payload,attempts FROM push_jobs WHERE next_attempt<=? ORDER BY created LIMIT 8", arrayOf(now().toString())).use {
            while (it.moveToNext()) jobs.add(PushJob(it.getString(0), it.getString(1), it.getString(2), it.getInt(3)))
        }
        return jobs
    }
    @Synchronized fun subscription(grant: String): JSONObject? = readableDatabase.rawQuery("SELECT value FROM subscriptions WHERE grant_id=?", arrayOf(grant)).use {
        if (it.moveToFirst()) JSONObject(open(it.getString(0))) else null
    }
    @Synchronized fun complete(job: PushJob) { writableDatabase.delete("push_jobs", "id=? AND grant_id=?", arrayOf(job.id, job.grant)) }
    @Synchronized fun retry(job: PushJob) {
        val delay = minOf(300_000L, 15_000L * (1L shl minOf(job.attempts, 5)))
        writableDatabase.update("push_jobs", ContentValues().apply { put("attempts", job.attempts + 1); put("next_attempt", now() + delay) }, "id=? AND grant_id=?", arrayOf(job.id, job.grant))
    }
    @Synchronized fun status(grant: String): JSONObject = JSONObject().put("registered", subscription(grant) != null)
        .put("pending", readableDatabase.rawQuery("SELECT COUNT(*) FROM push_jobs WHERE grant_id=?", arrayOf(grant)).use { it.moveToFirst(); it.getInt(0) })
}

internal class PushDelivery(private val store: PushStore, private val grants: () -> List<Grant>) {
    fun run() {
        store.prune(grants().filter { it.expires > System.currentTimeMillis() }.map { it.id }.toSet())
        for (job in store.due()) {
            // Recheck immediately before each network operation after revocation.
            if (grants().none { it.id == job.grant && it.expires > System.currentTimeMillis() }) { store.remove(job.grant); continue }
            var connection: HttpsURLConnection? = null
            try {
                val subscription = store.subscription(job.grant) ?: continue
                val endpoint = subscription.getString("endpoint")
                check(WebPushCrypto.validEndpoint(endpoint))
                val keys = subscription.getJSONObject("keys")
                val bytes = WebPushCrypto.encrypt(job.payload.toByteArray(), WebPushCrypto.decode(keys.getString("p256dh")), WebPushCrypto.decode(keys.getString("auth")))
                connection = URL(endpoint).openConnection() as HttpsURLConnection
                connection.apply {
                    requestMethod = "POST"; instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Authorization", WebPushCrypto.authorization(endpoint, store.keys(), System.currentTimeMillis() / 1000))
                    setRequestProperty("Content-Encoding", "aes128gcm"); setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("TTL", "3600"); setRequestProperty("Urgency", "high")
                    setRequestProperty("Topic", WebPushCrypto.encode(digest(job.id)).take(32))
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
                when (connection.responseCode) {
                    in 200..299 -> store.complete(job) // Accepted by provider, not proof of display.
                    404, 410 -> store.remove(job.grant)
                    else -> store.retry(job)
                }
            } catch (_: Exception) { store.retry(job) }
            finally { connection?.disconnect() }
        }
    }
}
