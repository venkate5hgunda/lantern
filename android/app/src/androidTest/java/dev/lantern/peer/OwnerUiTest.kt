package dev.lantern.peer

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import android.content.SharedPreferences
import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.widget.Button
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import java.io.File
import java.net.URL
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import com.google.android.material.checkbox.MaterialCheckBox

@RunWith(AndroidJUnit4::class)
class OwnerUiTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var savedSetup: Map<String, *>
    private lateinit var savedPeer: Map<String, *>

    @Before fun freshOwnerSetup() {
        assumeFalse("Dedicated unprovisioned test device only", Identity.available() || PeerService.instance != null)
        savedSetup = context.getSharedPreferences("owner-setup", Context.MODE_PRIVATE).all
        savedPeer = context.getSharedPreferences("peer", Context.MODE_PRIVATE).all
        context.getSharedPreferences("owner-setup", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun restorePreferences() {
        if (!::savedSetup.isInitialized) return
        val file = File(context.filesDir, "owner-ui-test.p12")
        if (file.exists()) {
            context.stopService(Intent(context, PeerService::class.java))
            val until = System.currentTimeMillis() + 15_000
            while (PeerService.instance != null && System.currentTimeMillis() < until) Thread.sleep(50)
            assertNull("Test-owned service failed to stop", PeerService.instance)
            KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.deleteEntry("lantern-server")
            check(file.delete())
        }
        restore(context.getSharedPreferences("owner-setup", Context.MODE_PRIVATE), savedSetup)
        restore(context.getSharedPreferences("peer", Context.MODE_PRIVATE), savedPeer)
    }
    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                else -> error("Unexpected test preference type")
            }
        }
        check(edit.commit())
    }

    @Test fun firstRunShowsOneTaskAndInlineNameValidation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            captureUnprovisionedScreen(scenario)
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.step_name), activity.findViewById<TextView>(R.id.setup_title).text)
                assertNull(activity.findViewById<MaterialButton>(R.id.camera_start))
                val field = activity.findViewById<TextInputEditText>(R.id.camera_name)
                field.setText("   ")
                activity.findViewById<MaterialButton>(R.id.setup_next).performClick()
                val layout = field.parent.parent as TextInputLayout
                assertNotNull(layout.error)
                field.setText("Front door")
                activity.findViewById<MaterialButton>(R.id.setup_next).performClick()
                assertEquals(activity.getString(R.string.step_identity), activity.findViewById<TextView>(R.id.setup_title).text)
                assertNotNull(activity.findViewById<MaterialButton>(R.id.identity_choose))
                assertFalse(PeerState(activity).armed())
            }

        }
    }

    private fun captureUnprovisionedScreen(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("lantern.ownerScreenshot") != "1") return
        scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            instrumentation.waitForIdleSync()
            Thread.sleep(150)
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.filesDir, "owner-setup-screen.png").outputStream().use {
                    check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
        } finally {
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    @Test fun rotationAndRelaunchResumeAtIdentityWithoutArming() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<TextInputEditText>(R.id.camera_name).setText("Kitchen")
                activity.findViewById<MaterialButton>(R.id.setup_next).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.step_identity), activity.findViewById<TextView>(R.id.setup_title).text)
                activity.findViewById<MaterialButton>(R.id.setup_back).performClick()
                assertEquals("Kitchen", activity.findViewById<TextInputEditText>(R.id.camera_name).text.toString())
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.step_identity), activity.findViewById<TextView>(R.id.setup_title).text)
                assertFalse(PeerState(activity).armed())
            }
        }
    }

    @Test fun reachableTouchTargetsLabelsAndHelp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<MaterialButton>(R.id.setup_next)
                assertTrue(button.minHeight >= (48 * activity.resources.displayMetrics.density).toInt())
                assertNotNull(activity.findViewById<MaterialButton>(R.id.help_action))
                val field = activity.findViewById<TextInputEditText>(R.id.camera_name)
                assertEquals(activity.getString(R.string.name_label), field.hint.toString())
                assertTrue(activity.findViewById<TextView>(R.id.setup_title).isAccessibilityHeading)
            }
        }
    }

    @Test fun builtInGuideIncludesTrustCommandsAndCompleteTesting() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<MaterialButton>(R.id.help_action).performClick()
                val texts = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::allText)
                assertTrue(texts.contains(activity.getString(R.string.guide_title)))
                assertTrue(texts.contains(activity.getString(R.string.guide_network_title)))
                assertTrue(texts.any { "--name \"Front door\"" in it })
                assertTrue(texts.contains(activity.getString(R.string.guide_test)))
            }
        }
    }

    private fun allText(view: android.view.View): List<String> = when (view) {
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { allText(view.getChildAt(it)) }
        is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }

    @Test fun fullSetupImportsIdentityStartsExplicitlyAndRequiresViewerConfirmation() {
        createTestIdentity().use { fixture ->
            val file = File(context.filesDir, "owner-ui-test.p12").also { it.writeBytes(fixture.bytes) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                selectIdentityFile(scenario, file)
                scenario.onActivity { activity ->
                    activity.findViewById<TextInputEditText>(R.id.identity_password).setText("never persist this")
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val password = activity.findViewById<TextInputEditText>(R.id.identity_password)
                    assertEquals("", password.text.toString())
                    password.setText(String(fixture.password))
                    activity.findViewById<MaterialButton>(R.id.identity_import).performClick()
                }
                waitForUi(scenario) { it.findViewById<MaterialButton>(R.id.setup_next) != null }
                scenario.onActivity { activity ->
                    assertNull("Import must not arm the camera", PeerService.instance)
                    assertEquals(setOf("https://127.0.0.1:8443"), Identity.origins())
                    activity.findViewById<MaterialButton>(R.id.setup_next).performClick()
                    assertEquals(activity.getString(R.string.step_permissions), activity.findViewById<TextView>(R.id.setup_title).text)
                    activity.findViewById<MaterialButton>(R.id.setup_next).performClick()
                    assertNull("Permissions must not auto-arm", PeerService.instance)
                    PeerState(activity).saveOrigins(emptySet())
                    activity.findViewById<MaterialButton>(R.id.camera_start).performClick()
                }
                waitForUi(scenario) { it.findViewById<TextView>(R.id.camera_health)?.text == it.getString(R.string.camera_ready) }
                assertTrue("Startup must recover certificate origins after interrupted setup",
                    "https://127.0.0.1:8443" in PeerService.instance!!.approvedOrigins())
                scenario.onActivity { it.findViewById<MaterialButton>(R.id.camera_start).performClick() }
                scenario.onActivity { activity ->
                    activity.findViewById<MaterialButton>(R.id.camera_settings).performClick()
                    val origins = activity.findViewById<TextInputEditText>(R.id.origins_input)
                    origins.setText("http://not-allowed.example")
                    activity.findViewById<MaterialButton>(R.id.settings_save).performClick()
                    assertNotNull((origins.parent.parent as TextInputLayout).error)
                    origins.setText("https://viewer.example/")
                    activity.findViewById<MaterialButton>(R.id.settings_save).performClick()
                    assertEquals(setOf("https://127.0.0.1:8443", "https://viewer.example"),
                        PeerService.instance!!.approvedOrigins())
                    activity.findViewById<MaterialButton>(R.id.setup_back).performClick()
                }
                var code = ""
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<MaterialButton>(R.id.setup_finish).isEnabled)
                    activity.findViewById<MaterialButton>(R.id.pair_generate).performClick()
                    code = activity.findViewById<TextView>(R.id.pair_code).text.toString().replace(" ", "")
                    assertTrue(code.matches(Regex("[0-9]{6}")))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue("Pairing code must survive rotation",
                        code == activity.findViewById<TextView>(R.id.pair_code).text.toString().replace(" ", ""))
                }
                val trust = KeyStore.getInstance(KeyStore.getDefaultType()).also {
                    it.load(null); it.setCertificateEntry("test-ca", fixture.root)
                }
                val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(trust) }
                val tls = SSLContext.getInstance("TLS").also { it.init(null, managers.trustManagers, null) }
                val connection = URL("https://127.0.0.1:8443/api/v1/pair").openConnection() as HttpsURLConnection
                try {
                    connection.sslSocketFactory = tls.socketFactory
                    connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    val body = org.json.JSONObject().put("code", code).put("viewerName", "UI test viewer").toString()
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    assertEquals(201, connection.responseCode)
                    connection.inputStream.use { it.readBytes() }
                } finally { connection.disconnect() }
                waitForUi(scenario) { it.findViewById<TextView>(R.id.pair_code)?.text?.isEmpty() == true }
                scenario.onActivity { activity ->
                    assertFalse("Pairing alone is not video verification", activity.findViewById<MaterialButton>(R.id.setup_finish).isEnabled)
                    activity.findViewById<MaterialCheckBox>(R.id.viewer_confirm).isChecked = true
                    assertTrue(activity.findViewById<MaterialButton>(R.id.setup_finish).isEnabled)
                    activity.findViewById<MaterialButton>(R.id.setup_finish).performClick()
                    assertNotNull(activity.findViewById<MaterialButton>(R.id.camera_pause))
                    activity.findViewById<MaterialButton>(R.id.camera_pause).performClick()
                    assertEquals("running", PeerService.instance?.health)
                    val confirm = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .mapNotNull { it.findViewById<Button>(android.R.id.button1) }.first()
                    confirm.performClick()
                }
                waitForUi(scenario) { it.findViewById<TextView>(R.id.camera_health)?.text == it.getString(R.string.camera_paused) }
            }
        }
    }

    @Test fun invalidIdentityShowsActionableFailureWithoutArming() {
        val file = File(context.filesDir, "owner-ui-test.p12").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            selectIdentityFile(scenario, file)
            scenario.onActivity { it.findViewById<MaterialButton>(R.id.identity_import).performClick() }
            waitForUi(scenario) { activity ->
                allText(activity.window.decorView).contains(activity.getString(R.string.identity_failed))
            }
            scenario.onActivity { activity ->
                assertFalse(Identity.available())
                assertNull(PeerService.instance)
                assertEquals("", activity.findViewById<TextInputEditText>(R.id.identity_password).text.toString())
            }
        }

    }

    @Test fun identityWithoutCertificateAddressCannotAdvance() {
        createTestIdentity(includeAddress = false).use { fixture ->
            val file = File(context.filesDir, "owner-ui-test.p12").also { it.writeBytes(fixture.bytes) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                selectIdentityFile(scenario, file)
                scenario.onActivity { activity ->
                    activity.findViewById<TextInputEditText>(R.id.identity_password).setText(String(fixture.password))
                    activity.findViewById<MaterialButton>(R.id.identity_import).performClick()
                }
                waitForUi(scenario) { it.findViewById<MaterialButton>(R.id.setup_next) != null }
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<MaterialButton>(R.id.setup_next).isEnabled)
                    assertTrue(allText(activity.window.decorView).contains(activity.getString(R.string.address_missing)))
                    assertNull(PeerService.instance)
                }
            }
        }
    }

    private fun selectIdentityFile(scenario: ActivityScenario<MainActivity>, file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))), true)
        try {
            scenario.onActivity { activity ->
                activity.findViewById<TextInputEditText>(R.id.camera_name).setText("Front door")
                activity.findViewById<MaterialButton>(R.id.setup_next).performClick()
                activity.findViewById<MaterialButton>(R.id.identity_choose).performClick()
            }
            assertEquals("Document picker fixture did not intercept the launch", 1, monitor.hits)
            waitForUi(scenario) { it.findViewById<TextInputEditText>(R.id.identity_password) != null }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun waitForUi(scenario: ActivityScenario<MainActivity>, ready: (MainActivity) -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            var found = false
            scenario.onActivity { found = ready(it) }
            if (found) return
            Thread.sleep(50)
        }
        fail("Owner UI did not reach its expected state")
    }
}
