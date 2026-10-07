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

internal data class Event(val id: String, val created: Long)
internal fun syncDirectoryToDisk(directory: File) {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
internal class EventStore(
    context: Context,
    private val deviceId: String,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryToDisk
) :
    SQLiteOpenHelper(context, "events.db", null, 1) {
    private val directory = File(context.filesDir, "snapshots").also { check(it.isDirectory || it.mkdirs()) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX events_recent ON events(created DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    @Synchronized fun list(limit: Int = 50): List<Event> {
        val found = mutableListOf<Event>()
        readableDatabase.rawQuery("SELECT id, created FROM events ORDER BY created DESC, rowid DESC LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToNext()) found.add(Event(it.getString(0), it.getLong(1)))
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
            if (file.name.endsWith(".partial") || (file.name.endsWith(".jpg") && file.name !in known)) file.delete()
        }
    }
    @Synchronized fun add(jpeg: ByteArray): Event {
        require(jpeg.size in 5..8 * 1024 * 1024 &&
            jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() && jpeg[2] == 0xff.toByte() &&
            jpeg[jpeg.lastIndex - 1] == 0xff.toByte() && jpeg.last() == 0xd9.toByte())
        val event = Event(UUID.randomUUID().toString(), System.currentTimeMillis())
        val partial = File(directory, "${event.id}.partial")
        val final = File(directory, "${event.id}.jpg")
        try {
            FileOutputStream(partial).use { output ->
                output.write(jpeg)
                output.fd.sync()
            }
            check(partial.renameTo(final))
            syncDirectory(directory)
            val db = writableDatabase
            db.beginTransaction()
            try {
                val values = android.content.ContentValues().apply {
                    put("id", event.id)
                    put("created", event.created)
                }
                check(db.insertOrThrow("events", null, values) != -1L)
                val remove = mutableListOf<String>()
                db.rawQuery("SELECT id FROM events ORDER BY created DESC, rowid DESC LIMIT -1 OFFSET 200", null).use {
                    while (it.moveToNext()) remove.add(it.getString(0))
                }
                remove.forEach { db.delete("events", "id = ?", arrayOf(it)) }
                db.setTransactionSuccessful()
                db.endTransaction()
                remove.forEach { File(directory, "$it.jpg").delete() }
            } catch (error: Exception) {
                if (db.inTransaction()) db.endTransaction()
                throw error
            }
            return event
        } catch (error: Exception) {
            partial.delete()
            final.delete()
            throw error
        }
    }
    fun json(event: Event): JSONObject = JSONObject()
        .put("id", event.id).put("deviceId", deviceId).put("createdAt", stamp(event.created))
        .put("trigger", "manual").put("confidence", JSONObject.NULL)
        .put("snapshotPath", "/api/v1/events/${event.id}/snapshot")
}
