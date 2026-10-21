package dev.lantern.peer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun mediaHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
internal fun randomSecret(): String = WebPushCrypto.encode(ByteArray(32).also(SecureRandom()::nextBytes))
internal data class BackupTarget(val id: String, val origin: String, val pin: String, val token: String, val key: ByteArray, val nextAttempt: Long, val attempts: Int)
internal data class BackupGrant(val source: String, val key: ByteArray)
internal data class ReplicaManifest(val source: String, val eventId: String, val created: Long, val trigger: String, val confidence: Double?, val plainBytes: Int, val sha256: String) {
    val id: String get() = mediaHash("$source:$eventId".toByteArray())
    val encryptedBytes: Int get() = plainBytes + 28
    fun json(): JSONObject = JSONObject().put("sourceDeviceId", source).put("eventId", eventId).put("created", created)
        .put("trigger", trigger).put("confidence", confidence ?: JSONObject.NULL).put("plainBytes", plainBytes).put("sha256", sha256)
    fun aad(): ByteArray = listOf("lantern-replica-v1", source, eventId, created.toString(), trigger, confidence?.toString() ?: "null", plainBytes.toString(), sha256).joinToString("\n").toByteArray()
    companion object {
        fun parse(value: JSONObject): ReplicaManifest {
            val source = value.getString("sourceDeviceId"); val event = value.getString("eventId")
            require(UUID.fromString(source).toString() == source && UUID.fromString(event).toString() == event)
            val created = value.getLong("created"); val size = value.getInt("plainBytes"); val trigger = value.getString("trigger")
            val confidence = if (value.isNull("confidence")) null else value.getDouble("confidence")
            val hash = value.getString("sha256")
            require(created > 0 && size in 5..8 * 1024 * 1024 && trigger in listOf("manual", "scheduled", "person") && hash.matches(Regex("[0-9a-f]{64}")))
            require(if (trigger == "person") confidence != null && confidence.isFinite() && confidence in 0.0..1.0 else confidence == null)
            return ReplicaManifest(source, event, created, trigger, confidence, size, hash)
        }
    }
}
internal object ReplicaCrypto {
    fun encrypt(bytes: ByteArray, manifest: ReplicaManifest, key: ByteArray): ByteArray {
        require(key.size == 32 && bytes.size == manifest.plainBytes && mediaHash(bytes) == manifest.sha256)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        return nonce + Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(manifest.aad()); doFinal(bytes)
        }
    }
    fun decrypt(bytes: ByteArray, manifest: ReplicaManifest, key: ByteArray): ByteArray {
        require(key.size == 32 && bytes.size == manifest.encryptedBytes)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(manifest.aad()); doFinal(bytes.copyOfRange(12, bytes.size))
        }
        require(plain.size == manifest.plainBytes && mediaHash(plain) == manifest.sha256 && plain[0] == 0xff.toByte() && plain[1] == 0xd8.toByte() && plain[2] == 0xff.toByte() && plain[plain.lastIndex - 1] == 0xff.toByte() && plain.last() == 0xd9.toByte())
        return plain
    }
}
internal class BackupStore(
    context: Context,
    private val seal: (String) -> String = SecretVault::seal,
    private val open: (String) -> String = SecretVault::open,
    private val now: () -> Long = System::currentTimeMillis,
    private val available: () -> Long = { context.filesDir.usableSpace },
    private val quota: Long = 256L * 1024 * 1024,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryToDisk,
    namespace: String = ""
) : SQLiteOpenHelper(context, "backups$namespace.db", null, 1) {
    private val directory = File(context.filesDir, "replicas$namespace").also { check(it.isDirectory || it.mkdirs()) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outgoing (target TEXT PRIMARY KEY, manifest TEXT NOT NULL, cipher_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE incoming (source TEXT PRIMARY KEY, secret TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE targets (id TEXT PRIMARY KEY, origin TEXT NOT NULL, pin TEXT NOT NULL, secret TEXT NOT NULL, next_attempt INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, last_sync INTEGER, verified INTEGER NOT NULL DEFAULT 0, error TEXT)")
        db.execSQL("CREATE TABLE replicas (id TEXT PRIMARY KEY, source TEXT NOT NULL, manifest TEXT NOT NULL, reserved INTEGER NOT NULL, started INTEGER NOT NULL, committed INTEGER NOT NULL DEFAULT 0, cipher_hash TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    @Synchronized fun issue(source: String, token: String = randomSecret(), key: ByteArray = WebPushCrypto.decode(randomSecret())): Pair<String, ByteArray> {
        require(UUID.fromString(source).toString() == source && key.size == 32 && token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM incoming WHERE active=1 AND source!=?", arrayOf(source)).use { it.moveToFirst(); it.getInt(0) }
        check(count < 4) { "At most four backup peers are supported" }
        // Re-enrollment keeps the encryption key for already retained evidence.
        val existing = readableDatabase.rawQuery("SELECT secret FROM incoming WHERE source=?", arrayOf(source)).use {
            if (it.moveToFirst()) JSONObject(open(it.getString(0))) else null
        }
        val stableKey = existing?.let { WebPushCrypto.decode(it.getString("key")) } ?: key
        val secret = JSONObject().put("tokenHash", digest(token).toHex()).put("key", WebPushCrypto.encode(stableKey))
        check(writableDatabase.insertWithOnConflict("incoming", null, ContentValues().apply { put("source", source); put("secret", seal(secret.toString())); put("active", 1) }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        return token to stableKey
    }
    @Synchronized fun authenticate(header: String?): BackupGrant? {
        if (header == null || !header.matches(Regex("Bearer [A-Za-z0-9_-]{43}"))) return null
        val hash = digest(header.substring(7))
        var found: BackupGrant? = null
        readableDatabase.rawQuery("SELECT source,secret FROM incoming WHERE active=1", null).use {
            while (it.moveToNext()) {
                val value = JSONObject(open(it.getString(1)))
                if (MessageDigest.isEqual(hash, value.getString("tokenHash").hexBytes())) found = BackupGrant(it.getString(0), WebPushCrypto.decode(value.getString("key")))
            }
        }
        return found
    }
    @Synchronized private fun key(source: String): ByteArray = readableDatabase.rawQuery("SELECT secret FROM incoming WHERE source=?", arrayOf(source)).use {
        check(it.moveToFirst()); WebPushCrypto.decode(JSONObject(open(it.getString(0))).getString("key"))
    }
    @Synchronized fun target(id: String, origin: String, pin: String, token: String, key: ByteArray) {
        require(UUID.fromString(id).toString() == id && Origins.canonical(origin) == origin && pin.matches(Regex("[0-9a-f]{64}")) && token.matches(Regex("[A-Za-z0-9_-]{43}")) && key.size == 32)
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM targets WHERE id!=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
        check(count < 4)
        check(writableDatabase.insertWithOnConflict("targets", null, ContentValues().apply {
            put("id", id); put("origin", origin); put("pin", pin); put("secret", seal(JSONObject().put("token", token).put("key", WebPushCrypto.encode(key)).toString()))
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    @Synchronized fun targets(): List<BackupTarget> {
        val result = mutableListOf<BackupTarget>()
        readableDatabase.rawQuery("SELECT id,origin,pin,secret,next_attempt,attempts FROM targets", null).use {
            while (it.moveToNext()) {
                val secret = JSONObject(open(it.getString(3)))
                result.add(BackupTarget(it.getString(0), it.getString(1), it.getString(2), secret.getString("token"), WebPushCrypto.decode(secret.getString("key")), it.getLong(4), it.getInt(5)))
            }
        }
        return result
    }
    @Synchronized fun unlink(id: String) {
        clearUpload(id)
        writableDatabase.delete("targets", "id=?", arrayOf(id))
        writableDatabase.update("incoming", ContentValues().apply { put("active", 0) }, "source=?", arrayOf(id))
    }
    @Synchronized fun pendingUpload(target: String): ReplicaManifest? = readableDatabase.rawQuery("SELECT manifest FROM outgoing WHERE target=?", arrayOf(target)).use { if (it.moveToFirst()) ReplicaManifest.parse(JSONObject(it.getString(0))) else null }
    @Synchronized fun clearUpload(target: String) {
        for (extension in listOf("bin", "partial")) File(directory, "out-$target.$extension").let { check(!it.exists() || it.delete()) }
        writableDatabase.delete("outgoing", "target=?", arrayOf(target))
    }
    @Synchronized fun uploadBytes(target: BackupTarget, manifest: ReplicaManifest, plain: ByteArray): ByteArray {
        val file = File(directory, "out-${target.id}.bin")
        val saved = readableDatabase.rawQuery("SELECT manifest,cipher_hash FROM outgoing WHERE target=?", arrayOf(target.id)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        if (saved != null && ReplicaManifest.parse(JSONObject(saved.first)) == manifest && file.isFile && file.length() == manifest.encryptedBytes.toLong()) {
            val bytes = file.readBytes()
            if (mediaHash(bytes) == saved.second) return bytes
        }
        check(available() >= 64L * 1024 * 1024 + manifest.encryptedBytes)
        val bytes = ReplicaCrypto.encrypt(plain, manifest, target.key)
        val partial = File(directory, "out-${target.id}.partial")
        FileOutputStream(partial).use { it.write(bytes); it.fd.sync() }
        check(partial.renameTo(file)); syncDirectory(directory)
        writableDatabase.insertWithOnConflict("outgoing", null, ContentValues().apply {
            put("target", target.id); put("manifest", manifest.json().toString()); put("cipher_hash", mediaHash(bytes))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        return bytes
    }
    @Synchronized fun synced(id: String, verified: Int) { writableDatabase.update("targets", ContentValues().apply {
        put("last_sync", now()); put("verified", verified); put("attempts", 0); put("next_attempt", now() + 15_000); putNull("error")
    }, "id=?", arrayOf(id)) }
    @Synchronized fun failed(target: BackupTarget) { writableDatabase.update("targets", ContentValues().apply {
        put("error", "Peer unavailable or transfer failed; retrying. Check trust, connection and quota."); put("attempts", target.attempts + 1)
        put("next_attempt", now() + minOf(300_000L, 5000L * (1L shl minOf(target.attempts, 6))) + SecureRandom().nextInt(1000))
    }, "id=?", arrayOf(target.id)) }
    @Synchronized fun manifest(id: String): ReplicaManifest? = readableDatabase.rawQuery("SELECT manifest FROM replicas WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) ReplicaManifest.parse(JSONObject(it.getString(0))) else null }
    @Synchronized private fun remove(id: String) {
        for (extension in listOf("partial", "bin")) File(directory, "$id.$extension").let { check(!it.exists() || it.delete()) }
        writableDatabase.delete("replicas", "id=?", arrayOf(id))
    }
    @Synchronized fun maintain() {
        val stale = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT id,manifest,committed,started FROM replicas", null).use {
            while (it.moveToNext()) {
                val manifest = ReplicaManifest.parse(JSONObject(it.getString(1)))
                val file = File(directory, "${it.getString(0)}.${if (it.getInt(2) == 1) "bin" else "partial"}")
                if (manifest.created <= now() - 7L * 86400_000 || it.getInt(2) == 0 && it.getLong(3) <= now() - 600_000 || it.getInt(2) == 1 && !file.isFile) stale.add(it.getString(0))
            }
        }
        stale.forEach(::remove)
        val known = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT id FROM replicas", null).use { while (it.moveToNext()) known.add(it.getString(0)) }
        directory.listFiles()?.filter { !it.name.startsWith("out-") && it.name.substringBefore('.') !in known }?.forEach { check(it.delete()) }
    }
    @Synchronized fun begin(manifest: ReplicaManifest, source: String): Int {
        require(manifest.source == source && manifest.created > now() - 7L * 86400_000 && manifest.created <= now() + 300_000)
        maintain()
        val saved = this.manifest(manifest.id)
        if (saved != null) { require(saved == manifest); return progress(manifest.id) }
        check(manifest.encryptedBytes <= quota && available() >= 64L * 1024 * 1024 + manifest.encryptedBytes) { "Replica storage reserve reached" }
        fun usage(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(reserved),0) FROM replicas", null).use { it.moveToFirst(); it.getLong(0) }
        var count = readableDatabase.rawQuery("SELECT COUNT(*) FROM replicas", null).use { it.moveToFirst(); it.getInt(0) }
        var used = usage()
        if (used + manifest.encryptedBytes > quota || count >= 500) {
            val candidates = readableDatabase.rawQuery("SELECT id,manifest,reserved FROM replicas WHERE committed=1", null).use { cursor ->
                val values = mutableListOf<Triple<String, Long, Long>>()
                while (cursor.moveToNext()) values.add(Triple(cursor.getString(0), ReplicaManifest.parse(JSONObject(cursor.getString(1))).created, cursor.getLong(2)))
                values.sortedBy { it.second }
            }
            val evictions = mutableListOf<String>()
            for (oldest in candidates) {
                if (used + manifest.encryptedBytes <= quota && count < 500) break
                // Old retry traffic must never rotate newer evidence out of a full receiver.
                check(oldest.second < manifest.created) { "Replica quota preserves newer evidence" }
                evictions.add(oldest.first); used -= oldest.third; count--
            }
            check(used + manifest.encryptedBytes <= quota && count < 500) { "Replica quota reached" }
            evictions.forEach(::remove)
        }
        check(available() >= 64L * 1024 * 1024 + manifest.encryptedBytes) { "Replica storage reserve reached" }
        check(writableDatabase.insertOrThrow("replicas", null, ContentValues().apply {
            put("id", manifest.id); put("source", source); put("manifest", manifest.json().toString()); put("reserved", manifest.encryptedBytes); put("started", now())
        }) != -1L)
        return 0
    }
    @Synchronized fun progress(id: String): Int = readableDatabase.rawQuery("SELECT committed FROM replicas WHERE id=?", arrayOf(id)).use {
        check(it.moveToFirst()); File(directory, "$id.${if (it.getInt(0) == 1 || File(directory, "$id.bin").isFile) "bin" else "partial"}").length().toInt()
    }
    @Synchronized fun chunk(id: String, source: String, offset: Int, bytes: ByteArray): Int {
        val manifest = manifest(id) ?: error("Unknown replica")
        require(manifest.source == source && offset >= 0 && bytes.size in 1..32_768 && offset.toLong() + bytes.size <= manifest.encryptedBytes)
        val committed = readableDatabase.rawQuery("SELECT committed FROM replicas WHERE id=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) == 1 }
        val file = File(directory, "$id.${if (committed) "bin" else "partial"}")
        val size = file.length()
        require(offset.toLong() <= size)
        RandomAccessFile(file, "rw").use {
            it.seek(offset.toLong())
            if (offset.toLong() < size) {
                require(offset.toLong() + bytes.size <= size)
                val existing = ByteArray(bytes.size); it.readFully(existing); require(existing.contentEquals(bytes))
            } else { check(!committed); it.write(bytes); it.fd.sync() }
        }
        return file.length().toInt()
    }
    @Synchronized fun commit(id: String, grant: BackupGrant): String {
        val manifest = manifest(id) ?: error("Unknown replica")
        require(manifest.source == grant.source)
        val partial = File(directory, "$id.partial"); val final = File(directory, "$id.bin")
        val bytes = (if (final.exists()) final else partial).let { require(it.length() == manifest.encryptedBytes.toLong()); it.readBytes() }
        try { ReplicaCrypto.decrypt(bytes, manifest, grant.key) } catch (error: Exception) { remove(id); throw error }
        if (!final.exists()) { check(partial.renameTo(final)); syncDirectory(directory) }
        if (partial.exists()) check(partial.delete())
        writableDatabase.update("replicas", ContentValues().apply { put("committed", 1); put("cipher_hash", mediaHash(bytes)) }, "id=?", arrayOf(id))
        return manifest.sha256
    }
    @Synchronized fun inventory(source: String): JSONArray {
        maintain()
        val result = JSONArray(); val broken = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT id,manifest,cipher_hash FROM replicas WHERE source=? AND committed=1 LIMIT 500", arrayOf(source)).use {
            while (it.moveToNext()) {
                val id = it.getString(0); val manifest = ReplicaManifest.parse(JSONObject(it.getString(1))); val file = File(directory, "$id.bin")
                if (file.length() != manifest.encryptedBytes.toLong() || mediaHash(file.readBytes()) != it.getString(2)) broken.add(id)
                else result.put(JSONObject().put("eventId", manifest.eventId).put("sha256", manifest.sha256))
            }
        }
        broken.forEach(::remove)
        return result
    }
    @Synchronized fun restore(id: String): ByteArray? {
        val manifest = manifest(id) ?: return null
        val committed = readableDatabase.rawQuery("SELECT committed FROM replicas WHERE id=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) == 1 }
        if (!committed) return null
        val file = File(directory, "$id.bin")
        return try {
            require(file.length() == manifest.encryptedBytes.toLong()); ReplicaCrypto.decrypt(file.readBytes(), manifest, key(manifest.source))
        } catch (_: Exception) { remove(id); null }
    }
    @Synchronized fun history(): JSONArray {
        maintain()
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT id,manifest FROM replicas WHERE committed=1 ORDER BY started DESC LIMIT 50", null).use {
            while (it.moveToNext()) result.put(JSONObject().put("id", it.getString(0)).put("evidence", JSONObject(it.getString(1))))
        }
        return result
    }
    @Synchronized fun status(desired: Int): JSONObject {
        val targets = JSONArray()
        readableDatabase.rawQuery("SELECT id,origin,last_sync,verified,error FROM targets", null).use {
            while (it.moveToNext()) targets.put(JSONObject().put("deviceId", it.getString(0)).put("origin", it.getString(1))
                .put("lastSync", if (it.isNull(2)) JSONObject.NULL else stamp(it.getLong(2))).put("verified", it.getInt(3))
                .put("pending", maxOf(0, desired - it.getInt(3))).put("error", if (it.isNull(4)) JSONObject.NULL else it.getString(4)))
        }
        return JSONObject().put("targets", targets).put("desired", desired).put("quotaBytes", quota)
            .put("replicaBytes", readableDatabase.rawQuery("SELECT COALESCE(SUM(reserved),0) FROM replicas", null).use { it.moveToFirst(); it.getLong(0) })
    }
}
