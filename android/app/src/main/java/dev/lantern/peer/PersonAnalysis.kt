package dev.lantern.peer

import android.content.Context
import android.graphics.BitmapFactory
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.core.BaseOptions
import org.json.JSONObject
import kotlin.math.max

internal class PersonDetector(context: Context) : AutoCloseable {
    private val detector = ObjectDetector.createFromFileAndOptions(context, "models/efficientdet-lite0.tflite",
        ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(BaseOptions.builder().setNumThreads(2).build()).setMaxResults(10).setScoreThreshold(0.6f).build())
    fun detect(jpeg: ByteArray): List<Float> {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Invalid analysis frame")
        return try { detector.detect(TensorImage.fromBitmap(bitmap)).mapNotNull { detection ->
            detection.categories.filter { it.label == "person" }.maxOfOrNull { it.score }
        } } finally { bitmap.recycle() }
    }
    override fun close() { detector.close() }
}

/** Debounced presence and bounded event cadence. Counts observations, never identities. */
internal class PresencePolicy(private val now: () -> Long = System::currentTimeMillis) {
    var present = false; private set
    var lastSeen: Long? = null; private set
    var people = 0; private set
    var peakPeople = 0; private set
    var observations = 0L; private set
    private var hits = 0
    private var misses = 0
    private var emittedAt: Long? = null
    @Synchronized fun observe(scores: List<Float>): Double? {
        require(scores.all { it.isFinite() && it in 0f..1f })
        people = scores.size
        peakPeople = max(peakPeople, people)
        observations++
        if (scores.isEmpty()) {
            hits = 0; misses++
            if (misses >= 3) present = false
            return null
        }
        lastSeen = now(); misses = 0; hits++
        if (hits < 2) return null
        present = true
        val last = emittedAt
        if (last != null && now() - last in 0 until 30_000) return null
        emittedAt = now()
        return scores.max().toDouble()
    }
    @Synchronized fun reset() { present = false; hits = 0; misses = 0; people = 0 }
    @Synchronized fun json(): JSONObject = JSONObject().put("present", present).put("people", people)
        .put("peakPeople", peakPeople).put("observations", observations).put("lastSeen", lastSeen?.let(::stamp) ?: JSONObject.NULL)
}
