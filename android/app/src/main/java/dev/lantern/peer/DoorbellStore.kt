package dev.lantern.peer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

internal data class Ring(val id: String, val created: Long, val acknowledged: Boolean)
internal class DoorbellStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) : SQLiteOpenHelper(context, "doorbell.db", null, 1) {
    private var lastPress: Long? = null
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rings (id TEXT PRIMARY KEY, created INTEGER NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    @Synchronized fun ring(): Ring? {
        val current = now()
        if (lastPress?.let { monotonic() - it < 5_000 } == true) return null
        val latest = list().firstOrNull()
        // Persisted cooldown protects against rapid service restarts too.
        if (latest != null && current - latest.created in 0 until 5_000) return null
        val ring = Ring(UUID.randomUUID().toString(), current, false)
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow("rings", null, ContentValues().apply { put("id", ring.id); put("created", ring.created) })
            writableDatabase.execSQL("DELETE FROM rings WHERE id NOT IN (SELECT id FROM rings ORDER BY created DESC, rowid DESC LIMIT 50)")
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        lastPress = monotonic()
        return ring
    }
    @Synchronized fun list(): List<Ring> {
        val result = mutableListOf<Ring>()
        readableDatabase.rawQuery("SELECT id, created, acknowledged FROM rings ORDER BY created DESC, rowid DESC LIMIT 50", null).use {
            while (it.moveToNext()) result.add(Ring(it.getString(0), it.getLong(1), it.getInt(2) != 0))
        }
        return result
    }
    @Synchronized fun acknowledge(id: String): Boolean = writableDatabase.update("rings",
        ContentValues().apply { put("acknowledged", 1) }, "id = ?", arrayOf(id)) > 0
    fun json(ring: Ring): JSONObject = JSONObject().put("id", ring.id).put("createdAt", stamp(ring.created))
        .put("acknowledged", ring.acknowledged)
}
