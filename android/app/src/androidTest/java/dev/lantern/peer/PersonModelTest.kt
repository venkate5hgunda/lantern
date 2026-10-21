package dev.lantern.peer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PersonModelTest {
    @Test fun bundledModelLoadsAndProcessesAFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output); output.toByteArray() }
        bitmap.recycle()
        PersonDetector(context).use { detector ->
            val result = detector.detect(bytes)
            assertTrue(result.size <= 10)
            assertTrue(result.all { it.isFinite() && it in 0.6f..1f })
            assertTrue("Blank frames must not trigger person events", result.isEmpty())
        }
    }
}
