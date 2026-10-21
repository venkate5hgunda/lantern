package dev.lantern.peer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal class PeerState(context: Context) {
    private val prefs = context.getSharedPreferences("peer", Context.MODE_PRIVATE)
    val id: String = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("id", it).commit())
    }
    val grants = Grants()
    val origins = Origins()
    init {
        val saved = JSONArray(prefs.getString("grants", "[]"))
        grants.load((0 until saved.length()).mapNotNull {
            try {
                val value = saved.getJSONObject(it)
                Grant(value.getString("id"), value.getString("name"), value.getString("hash"), value.getLong("expires"))
            } catch (_: Exception) { null }
        })
        val configured = JSONArray(prefs.getString("origins", "[]"))
        origins.set((0 until configured.length()).map { configured.getString(it) }.filter { Origins.canonical(it) == it })
    }
    fun name(): String = prefs.getString("name", "Lantern camera")!!
    fun identity(): Boolean = prefs.getBoolean("identity", false)
    fun automaticSnapshots(): Boolean = prefs.getBoolean("automaticSnapshots", false)
    fun automaticSnapshots(value: Boolean) { check(prefs.edit().putBoolean("automaticSnapshots", value).commit()) }
    fun armed(): Boolean = prefs.getBoolean("armed", false)
    fun name(value: String) { check(prefs.edit().putString("name", value).commit()) }
    fun identity(value: Boolean) { check(prefs.edit().putBoolean("identity", value).commit()) }
    fun armed(value: Boolean) { check(prefs.edit().putBoolean("armed", value).commit()) }
    fun saveOrigins(items: Set<String>) {
        check(prefs.edit().putString("origins", JSONArray(items.toList()).toString()).commit())
        origins.set(items)
    }
    fun saveSettings(value: String, items: Set<String>) {
        require(value.isNotEmpty() && value.length <= 80 && value.none(Char::isISOControl))
        require(items.all { Origins.canonical(it) == it })
        check(prefs.edit().putString("name", value)
            .putString("origins", JSONArray(items.toList()).toString()).commit())
        origins.set(items)
    }
    fun saveGrants() {
        val array = JSONArray()
        grants.list().forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("hash", it.hash).put("expires", it.expires))
        }
        check(prefs.edit().putString("grants", array.toString()).commit())
    }
}

internal data class Event(val id: String, val created: Long, val trigger: String = "manual", val bytes: Long = 0)
internal data class SnapshotRetention(
    val maxItems: Int = 200,
    val maxBytes: Long = 128L * 1024 * 1024,
    val maxAgeMs: Long = 7L * 24 * 3600 * 1000,
    val reserveBytes: Long = 64L * 1024 * 1024
) {
    init { require(maxItems > 0 && maxBytes > 0 && maxAgeMs > 0 && reserveBytes >= 0) }
}
internal fun syncDirectoryToDisk(directory: File) {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
internal class EventStore(
    context: Context,
    private val deviceId: String,
    private val retention: SnapshotRetention = SnapshotRetention(),
    private val now: () -> Long = System::currentTimeMillis,
    private val availableBytes: () -> Long = { context.filesDir.usableSpace },
    private val syncDirectory: (File) -> Unit = ::syncDirectoryToDisk
) : SQLiteOpenHelper(context, "events.db", null, 2) {
    private val directory = File(context.filesDir, "snapshots").also { check(it.isDirectory || it.mkdirs()) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, created INTEGER NOT NULL, trigger TEXT NOT NULL DEFAULT 'manual', bytes INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX events_recent ON events(created DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN trigger TEXT NOT NULL DEFAULT 'manual'")
            db.execSQL("ALTER TABLE events ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0")
            db.rawQuery("SELECT id FROM events", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    db.execSQL("UPDATE events SET bytes = ? WHERE id = ?", arrayOf(File(directory, "$id.jpg").length(), id))
                }
            }
        }
    }
    @Synchronized fun list(limit: Int = 50): List<Event> {
        require(limit in 1..10_000)
        val found = mutableListOf<Event>()
        readableDatabase.rawQuery("SELECT id, created, trigger, bytes FROM events ORDER BY created DESC, rowid DESC LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToNext()) found.add(Event(it.getString(0), it.getLong(1), it.getString(2), it.getLong(3)))
        }
        return found
    }
    @Synchronized fun snapshot(id: String): ByteArray? {
        if (!id.matches(Regex("[0-9a-f-]{36}"))) return null
        readableDatabase.rawQuery("SELECT id FROM events WHERE id = ?", arrayOf(id)).use {
            if (!it.moveToNext()) return null
        }
        return File(directory, "$id.jpg").takeIf { it.isFile && it.length() in 1..8L * 1024 * 1024 }?.readBytes()
    }
    @Synchronized fun pruneOrphans() {
        val known = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT id FROM events", null).use {
            while (it.moveToNext()) known.add("${it.getString(0)}.jpg")
        }
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".partial") || (file.name.endsWith(".jpg") && file.name !in known)) check(file.delete())
        }
        // A missing file is not saved evidence. Reconcile interrupted deletions.
        readableDatabase.rawQuery("SELECT id FROM events", null).use {
            val missing = mutableListOf<String>()
            while (it.moveToNext()) if (!File(directory, "${it.getString(0)}.jpg").isFile) missing.add(it.getString(0))
            missing.forEach { id -> writableDatabase.delete("events", "id = ?", arrayOf(id)) }
        }
    }
    @Synchronized fun maintain(incomingBytes: Long = 0) {
        require(incomingBytes in 0..retention.maxBytes)
        val keepCount = retention.maxItems - if (incomingBytes > 0) 1 else 0
        val budget = retention.maxBytes - incomingBytes
        val items = list(10_000)
        var bytes = 0L
        var count = 0
        var exhausted = false
        val remove = mutableListOf<Event>()
        for (event in items) {
            if (exhausted || event.created <= now() - retention.maxAgeMs || count >= keepCount || bytes + event.bytes > budget) {
                exhausted = true
                remove.add(event)
            }
            else { bytes += event.bytes; count++ }
        }
        // Delete bytes before rows: failed deletion keeps accounting and reports failure.
        for (event in remove) {
            val file = File(directory, "${event.id}.jpg")
            check(!file.exists() || file.delete()) { "Snapshot could not be evicted" }
            writableDatabase.delete("events", "id = ?", arrayOf(event.id))
        }
        check(incomingBytes == 0L || availableBytes() >= retention.reserveBytes + incomingBytes) { "Snapshot storage reserve reached" }
    }
    @Synchronized fun status(): JSONObject {
        val items = list(10_000)
        return JSONObject().put("items", items.size).put("bytes", items.sumOf { it.bytes })
            .put("maxItems", retention.maxItems).put("maxBytes", retention.maxBytes)
            .put("maxAgeSeconds", retention.maxAgeMs / 1000).put("reserveBytes", retention.reserveBytes)
            .put("availableBytes", availableBytes()).put("kind", "snapshots")
    }
    @Synchronized fun add(jpeg: ByteArray, trigger: String = "manual"): Event {
        require(trigger in listOf("manual", "scheduled"))
        require(jpeg.size in 5..8 * 1024 * 1024 &&
            jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() && jpeg[2] == 0xff.toByte() &&
            jpeg[jpeg.lastIndex - 1] == 0xff.toByte() && jpeg.last() == 0xd9.toByte())
        maintain(jpeg.size.toLong())
        val event = Event(UUID.randomUUID().toString(), now(), trigger, jpeg.size.toLong())
        val partial = File(directory, "${event.id}.partial")
        val final = File(directory, "${event.id}.jpg")
        try {
            FileOutputStream(partial).use { output -> output.write(jpeg); output.fd.sync() }
            check(partial.renameTo(final))
            syncDirectory(directory)
            val values = android.content.ContentValues().apply {
                put("id", event.id); put("created", event.created); put("trigger", trigger); put("bytes", event.bytes)
            }
            check(writableDatabase.insertOrThrow("events", null, values) != -1L)
            return event
        } catch (error: Exception) { partial.delete(); final.delete(); throw error }
    }
    fun json(event: Event): JSONObject = JSONObject()
        .put("id", event.id).put("deviceId", deviceId).put("createdAt", stamp(event.created))
        .put("trigger", event.trigger).put("confidence", JSONObject.NULL)
        .put("snapshotPath", "/api/v1/events/${event.id}/snapshot")
}
