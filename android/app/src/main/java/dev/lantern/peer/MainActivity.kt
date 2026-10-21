package dev.lantern.peer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.text.DateFormat
import java.util.Date
import androidx.appcompat.app.AlertDialog

class MainActivity : ComponentActivity() {
    private enum class Page { SETUP, CAMERA, SETTINGS }
    private lateinit var state: PeerState
    private lateinit var importer: IdentityImportModel
    private val progress by lazy { getSharedPreferences("owner-setup", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var page = Page.SETUP
    private var step = SetupStep.NAME
    private var identity = false
    private var ownOrigins = emptySet<String>()
    private var selectedFile: Uri? = null
    private var fileName = ""
    private var nameDraft = ""
    private var originsDraft = ""
    private var cameraAddress = ""
    private var verifiedVideo = false
    private var startingUntil = 0L
    private var stopping = false
    private var pausing = false
    private var pairingCode: String?
        get() = importer.pairingCode
        set(value) { importer.pairingCode = value }
    private var codeUntil: Long
        get() = importer.codeUntil
        set(value) { importer.codeUntil = value }
    private var grantsBeforeCode: Set<String>
        get() = importer.grantsBeforeCode
        set(value) { importer.grantsBeforeCode = value }
    private var message: Int? = null
    private lateinit var root: LinearLayout
    private lateinit var column: LinearLayout
    private var banner: TextView? = null
    private var healthTitle: TextView? = null
    private var healthDetail: TextView? = null
    private var action: MaterialButton? = null
    private var finish: MaterialButton? = null
    private var codeView: TextView? = null
    private var countdown: TextView? = null
    private var viewerStatus: TextView? = null
    private var previewHost: LinearLayout? = null
    private var renderer: SurfaceViewRenderer? = null
    private var attached: VideoTrack? = null
    private var grantsHost: LinearLayout? = null
    private var displayedGrants = ""
    private val tick = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 1_000)
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        message = when {
            !hasMediaPermissions() -> R.string.permissions_denied
            Build.VERSION.SDK_INT >= 33 && !allowed(Manifest.permission.POST_NOTIFICATIONS) ->
                R.string.notifications_optional
            else -> null
        }
        render()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedFile = uri
            fileName = try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "device.p12"
            } catch (_: Exception) {
                Log.w("LanternSetup", "Selected identity filename could not be read")
                "device.p12"
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        state = PeerState(this)
        importer = ViewModelProvider(this)[IdentityImportModel::class.java]
        readIdentity()
        if (identity && !progress.contains("named")) progress.edit().putBoolean("named", true).apply()
        nameDraft = savedInstanceState?.getString("name") ?: state.name().takeUnless { it == "Lantern camera" }.orEmpty()
        originsDraft = savedInstanceState?.getString("origins") ?: (state.origins.list() - ownOrigins).joinToString("\n")
        selectedFile = savedInstanceState?.getString("file")?.let(Uri::parse)
        fileName = savedInstanceState?.getString("fileName").orEmpty()
        cameraAddress = savedInstanceState?.getString("address") ?: progress.getString("address", "").orEmpty()
        verifiedVideo = savedInstanceState?.getBoolean("verified") ?: false
        startingUntil = savedInstanceState?.getLong("startingUntil") ?: 0L
        if (message == null && savedInstanceState?.containsKey("message") == true) {
            message = savedInstanceState.getInt("message")
        }
        page = savedInstanceState?.getString("page")?.let(Page::valueOf)
            ?: if (complete()) Page.CAMERA else Page.SETUP
        step = savedInstanceState?.getString("step")?.let(SetupStep::valueOf) ?: readiness().firstMissing()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.lantern_surface))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) {
            if (importer.status.value == ImportState.Working) return@addCallback
            when {
                page == Page.SETTINGS -> if (complete()) showCamera() else showStep(readiness().firstMissing())
                page == Page.CAMERA || step == SetupStep.NAME -> finish()
                else -> showStep(SetupStep.entries[step.ordinal - 1])
            }
        }
        render()
        importer.status.observe(this) { result ->
            if (result is ImportState.Finished) {
                readIdentity()
                state = PeerState(this)
                originsDraft = (state.origins.list() - ownOrigins).joinToString("\n")
                message = result.message
                if (result.imported) selectedFile = null
                importer.acknowledge()
            }
            if (page == Page.SETUP && step == SetupStep.IDENTITY) render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) {
            readIdentity()
            render()
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        detachPreview()
        super.onPause()
    }
    override fun onDestroy() {
        detachPreview()
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page", page.name)
        out.putString("step", step.name)
        out.putString("name", nameDraft)
        out.putString("origins", originsDraft)
        out.putString("file", selectedFile?.toString())
        out.putString("fileName", fileName)
        out.putString("address", cameraAddress)
        out.putBoolean("verified", verifiedVideo)
        out.putLong("startingUntil", startingUntil)
        message?.let { out.putInt("message", it) }
        super.onSaveInstanceState(out)
    }

    private fun complete() = progress.getBoolean("complete", false)
    private fun allowed(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun hasMediaPermissions() = allowed(Manifest.permission.CAMERA) && allowed(Manifest.permission.RECORD_AUDIO)
    private fun readiness() = SetupReadiness(progress.getBoolean("named", false), identity && ownOrigins.isNotEmpty(),
        hasMediaPermissions(), PeerService.instance?.health == "running")
    private fun readIdentity() {
        try {
            identity = Identity.available()
            ownOrigins = Identity.origins()
        } catch (_: Exception) {
            identity = false
            ownOrigins = emptySet()
            message = R.string.identity_failed
            Log.w("LanternSetup", "Keystore identity could not be read")
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showStep(value: SetupStep) {
        hideKeyboard()
        page = Page.SETUP
        step = value
        message = null
        render()
    }
    private fun showCamera() {
        hideKeyboard()
        page = Page.CAMERA
        message = null
        render()
    }
    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun render() {
        if (importer.status.value == ImportState.Working) {
            page = Page.SETUP
            step = SetupStep.IDENTITY
        }
        if (page == Page.SETUP && !(complete() && step == SetupStep.PAIR && identity)) {
            step = readiness().accessible(step)
        }
        detachPreview()
        root.removeAllViews()
        healthTitle = null; healthDetail = null; action = null; finish = null
        codeView = null; countdown = null; viewerStatus = null; previewHost = null
        grantsHost = null; displayedGrants = ""
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(12), dp(4))
        }
        toolbar.addView(TextView(this).apply {
            setText(R.string.app_name); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.lantern_on_surface))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            id = R.id.help_action; setText(R.string.help); minHeight = dp(48)
            setOnClickListener { showGuide() }
        })
        root.addView(toolbar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            isFocusableInTouchMode = true
        }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        banner = text(message?.let(::getString).orEmpty()).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        when (page) {
            Page.SETUP -> renderSetup()
            Page.CAMERA -> renderCamera()
            Page.SETTINGS -> renderSettings()
        }
        if (importer.status.value == ImportState.Working) disableForm(column)
        column.requestFocus()
        updateLive()
    }
    private fun disableForm(view: View) {
        if (view is MaterialButton || view is TextInputEditText || view is MaterialCheckBox ||
            view is MaterialRadioButton) view.isEnabled = false
        if (view is ViewGroup) for (index in 0 until view.childCount) disableForm(view.getChildAt(index))
    }

    private fun text(value: String, heading: Boolean = false): TextView = TextView(this).also {
        it.text = value
        it.textSize = if (heading) 24f else 16f
        it.setTextColor(ContextCompat.getColor(this, if (heading) R.color.lantern_on_surface else R.color.lantern_secondary))
        if (heading) {
            it.setTypeface(it.typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(it, true)
        }
        it.setPadding(0, dp(4), 0, dp(12))
        column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun text(resource: Int, heading: Boolean = false) = text(getString(resource), heading)
    private fun button(resource: Int, id: Int = View.NO_ID, secondary: Boolean = false, click: () -> Unit): MaterialButton =
        MaterialButton(this, null, if (secondary) com.google.android.material.R.attr.materialButtonOutlinedStyle
            else com.google.android.material.R.attr.materialButtonStyle).also {
            it.id = id; it.setText(resource); it.minHeight = dp(52); it.textSize = 16f
            it.setSingleLine(false); it.maxLines = 3
            it.setOnClickListener { click() }
            column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            })
        }
    private fun field(label: Int, hint: Int, id: Int, value: String, password: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this).apply {
            this.hint = getString(label); helperText = getString(hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(layout.context).apply {
            this.id = id
            inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = !password
            minHeight = dp(56)
            setText(value)
        }
        layout.addView(input)
        column.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(12)
        })
        return layout to input
    }
    private fun nameField(): Pair<TextInputLayout, TextInputEditText> =
        field(R.string.name_label, R.string.name_hint, R.id.camera_name, nameDraft).also { (_, input) ->
            input.filters = arrayOf(InputFilter.LengthFilter(80))
            input.addTextChangedListener { nameDraft = it.toString() }
        }
    private fun validName(layout: TextInputLayout, input: TextInputEditText): String? {
        val value = input.text.toString().trim()
        if (value.isEmpty() || value.length > 80 || value.any(Char::isISOControl)) {
            layout.error = getString(R.string.name_error)
            input.requestFocus()
            return null
        }
        layout.error = null
        return value
    }
    private fun renderSetup() {
        text(getString(R.string.step_count, step.ordinal + 1))
        column.addView(LinearProgressIndicator(this).apply {
            id = R.id.setup_progress; max = 5; progress = step.ordinal + 1
            contentDescription = getString(R.string.step_count, step.ordinal + 1)
        })
        val titles = listOf(R.string.step_name, R.string.step_identity, R.string.step_permissions,
            R.string.step_start, R.string.step_pair)
        text(titles[step.ordinal], true).id = R.id.setup_title
        when (step) {
            SetupStep.NAME -> {
                text(R.string.name_intro)
                text(R.string.setup_requirements)
                val (layout, input) = nameField()
                button(R.string.name_save, R.id.setup_next) {
                    val value = validName(layout, input) ?: return@button
                    try {
                        val service = PeerService.instance
                        if (service != null) service.updateSettings(value, service.approvedOrigins())
                        else state.name(value)
                        progress.edit().putBoolean("named", true).apply()
                        nameDraft = value
                        showStep(SetupStep.IDENTITY)
                    } catch (_: Exception) { report(R.string.save_failed) }
                }
            }
            SetupStep.IDENTITY -> renderIdentity()
            SetupStep.PERMISSIONS -> {
                text(R.string.permissions_intro)
                listOf(R.string.camera_permission to Manifest.permission.CAMERA,
                    R.string.microphone_permission to Manifest.permission.RECORD_AUDIO).forEach { (label, permission) ->
                    text(getString(if (allowed(permission)) R.string.permission_granted else R.string.permission_needed, getString(label)))
                }
                if (Build.VERSION.SDK_INT >= 33) text(getString(
                    if (allowed(Manifest.permission.POST_NOTIFICATIONS)) R.string.permission_granted else R.string.permission_needed,
                    getString(R.string.notification_permission)))
                if (Build.VERSION.SDK_INT >= 33 && !allowed(Manifest.permission.POST_NOTIFICATIONS)) {
                    text(R.string.notifications_optional)
                }
                if (hasMediaPermissions()) {
                    button(R.string.permissions_continue, R.id.setup_next) { showStep(SetupStep.START) }
                } else {
                    button(R.string.permissions_allow, R.id.permissions_request) {
                        val requested = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
                        permissions.launch(requested.filterNot(::allowed).toTypedArray())
                    }
                }
                button(R.string.app_settings, R.id.app_settings, true) { openAppSettings() }
            }
            SetupStep.START -> {
                text(R.string.start_intro)
                healthBlock()
                action = button(R.string.start_camera, R.id.camera_start) {
                    if (PeerService.instance?.health == "running") showStep(SetupStep.PAIR) else startPeer()
                }
                previewBlock()
                text(R.string.phone_power_hint)
                button(R.string.app_settings, R.id.app_settings, true) { openAppSettings() }
                button(R.string.camera_settings, R.id.camera_settings, true) { showSettings() }
            }
            SetupStep.PAIR -> renderPairing()
        }
        if (step != SetupStep.NAME) button(R.string.back, R.id.setup_back, true) {
            if (complete()) showCamera() else showStep(SetupStep.entries[step.ordinal - 1])
        }
        if (step == SetupStep.NAME || step == SetupStep.START) text(R.string.prototype_notice)
    }
    private fun renderIdentity() {
        text(R.string.identity_intro)
        if (identity) {
            text(R.string.identity_ready, true)
            text(R.string.identity_ready_hint)
            if (ownOrigins.isEmpty()) text(R.string.address_missing)
            button(R.string.identity_continue, R.id.setup_next) { showStep(SetupStep.PERMISSIONS) }
                .isEnabled = ownOrigins.isNotEmpty()
        } else text(R.string.identity_prepare)
        button(R.string.identity_choose, R.id.identity_choose, identity) {
            picker.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
        }.isEnabled = importer.status.value != ImportState.Working
        if (selectedFile != null) {
            text(getString(R.string.identity_selected, fileName.filterNot(Char::isISOControl).take(120)))
            val (_, password) = field(R.string.password_label, R.string.password_hint, R.id.identity_password, "", true)
            button(R.string.identity_import, R.id.identity_import) {
                val file = selectedFile ?: return@button
                hideKeyboard()
                val import = {
                    val pass = password.text.toString().toCharArray()
                    password.text?.clear()
                    importer.importFile(file, pass)
                }
                if (identity) secureDialog(MaterialAlertDialogBuilder(this).setTitle(R.string.identity_replace)
                    .setMessage(R.string.identity_replace_warning).setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.identity_import) { _, _ -> import() })
                else import()
            }.isEnabled = importer.status.value != ImportState.Working
        }
        if (importer.status.value == ImportState.Working) {
            text(R.string.identity_importing).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            column.addView(LinearProgressIndicator(this).apply { isIndeterminate = true })
        }
        if (identity || PeerService.instance != null) {
            button(R.string.camera_settings, R.id.camera_settings, true) { showSettings() }
        }
    }
    private fun healthBlock() {
        healthTitle = text(R.string.camera_stopped, true).apply {
            id = R.id.camera_health; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        healthDetail = text(R.string.camera_stopped_hint)
    }
    private fun previewBlock() {
        text(R.string.preview_title, true)
        previewHost = LinearLayout(this).also {
            it.id = R.id.camera_preview
            it.setBackgroundColor(ContextCompat.getColor(this, R.color.lantern_surface_variant))
            column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply {
                bottomMargin = dp(16)
            })
        }
        showPreviewPlaceholder()
    }
    private fun addressBlock() {
        if (ownOrigins.isEmpty()) { text(R.string.address_missing); return }
        val addresses = ownOrigins.sorted()
        if (cameraAddress !in addresses) cameraAddress = addresses.first()
        val group = RadioGroup(this)
        addresses.forEach { address ->
            val choice = MaterialRadioButton(this).apply {
                id = View.generateViewId(); text = address; minHeight = dp(48); isChecked = address == cameraAddress
                setOnClickListener {
                    cameraAddress = address
                    progress.edit().putString("address", address).apply()
                }
            }
            group.addView(choice)
        }
        column.addView(group)
        button(R.string.copy_address, R.id.camera_address, true) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Camera address", cameraAddress))
            report(R.string.address_copied)
        }
    }
    private fun renderPairing() {
        text(R.string.pair_intro)
        text(R.string.pair_trust)
        text(R.string.pair_address)
        addressBlock()
        text(R.string.pair_code_hint)
        codeView = text("", true).apply {
            id = R.id.pair_code; textSize = 32f; typeface = Typeface.MONOSPACE
        }
        countdown = text("").apply { id = R.id.pair_countdown }
        button(R.string.generate_code, R.id.pair_generate) {
            val service = PeerService.instance
            if (service?.health !in listOf("running", "disarmed: HTTPS ready")) report(R.string.code_unavailable)
            else {
                pairingCode = service!!.newCode()
                importer.codeService = System.identityHashCode(service)
                codeUntil = SystemClock.elapsedRealtime() + 120_000
                grantsBeforeCode = activeGrants().map { it.id }.toSet()
                updateLive()
                codeView?.announceForAccessibility(getString(R.string.code_accessible, pairingCode!!.toList().joinToString(", ")))
            }
        }
        viewerStatus = text(R.string.viewer_waiting).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if (complete()) button(R.string.back_camera, R.id.setup_finish) { showCamera() }
        else {
            column.addView(MaterialCheckBox(this).apply {
                id = R.id.viewer_confirm; setText(R.string.viewer_confirm); minHeight = dp(48)
                isChecked = verifiedVideo
                setOnCheckedChangeListener { _, checked -> verifiedVideo = checked; updateLive() }
            })
            text(R.string.finish_hint)
            finish = button(R.string.finish_setup, R.id.setup_finish) {
                if (verifiedVideo && activeGrants().isNotEmpty() && PeerService.instance?.health == "running") {
                    progress.edit().putBoolean("complete", true).apply()
                    showCamera()
                }
            }
            button(R.string.camera_settings, R.id.camera_settings, true) { showSettings() }
        }
    }
    private fun renderCamera() {
        text(state.name(), true).id = R.id.setup_title
        healthBlock()
        previewBlock()
        action = button(R.string.start_camera, R.id.camera_pause) {
            when (PeerService.instance?.health) {
                "running" -> secureDialog(MaterialAlertDialogBuilder(this).setTitle(R.string.pause_title).setMessage(R.string.pause_hint)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.pause_camera) { _, _ ->
                        pausing = true
                        detachPreview()
                        PeerService.instance?.disarm()
                        updateLive()
                    })
                else -> startPeer()
            }
        }
        button(R.string.ring_doorbell, secondary = true) {
            PeerService.instance?.ringDoorbell { result ->
                if (!isFinishing && !isDestroyed) android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        button(R.string.pair_another) { verifiedVideo = false; showStep(SetupStep.PAIR) }
        button(R.string.camera_settings, R.id.camera_settings, true) { showSettings() }
        text(R.string.prototype_notice)
    }
    private fun showSettings() {
        hideKeyboard()
        page = Page.SETTINGS
        state = PeerState(this)
        nameDraft = state.name()
        originsDraft = (state.origins.list() - ownOrigins).joinToString("\n")
        message = null
        render()
    }
    private fun renderSettings() {
        text(R.string.camera_settings, true).id = R.id.setup_title
        text(R.string.settings_intro)
        val (nameLayout, nameInput) = nameField()
        val (originLayout, originInput) = field(R.string.origins_label, R.string.origins_hint, R.id.origins_input, originsDraft)
        originInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        originInput.minLines = 2
        originInput.addTextChangedListener { originsDraft = it.toString() }
        button(R.string.save_settings, R.id.settings_save) {
            val value = validName(nameLayout, nameInput) ?: return@button
            val approved = mutableSetOf<String>()
            for ((index, line) in originsDraft.lines().withIndex()) {
                val item = line.trim().removeSuffix("/")
                if (item.isEmpty()) continue
                val canonical = Origins.canonical(item)
                if (canonical == null) {
                    originLayout.error = getString(R.string.origins_error, index + 1)
                    originInput.requestFocus()
                    return@button
                }
                approved += canonical
            }
            originLayout.error = null
            try {
                val service = PeerService.instance
                if (service != null) service.updateSettings(value, approved + ownOrigins)
                else state.saveSettings(value, approved + ownOrigins)
                state = PeerState(this)
                nameDraft = value
                hideKeyboard()
                report(R.string.settings_saved)
            } catch (_: Exception) { report(R.string.save_failed) }
        }
        val automatic = MaterialCheckBox(this).apply {
            setText(R.string.automatic_snapshots)
            isChecked = PeerService.instance?.automaticSnapshots() ?: state.automaticSnapshots()
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { _, enabled ->
                try {
                    val service = PeerService.instance
                    if (service != null) service.automaticSnapshots(enabled) else state.automaticSnapshots(enabled)
                } catch (_: Exception) { report(R.string.save_failed) }
            }
        }
        column.addView(automatic)
        text(R.string.snapshot_retention_hint)
        text(R.string.viewers_title, true)
        grantsHost = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
            column.addView(it)
        }
        text(R.string.identity_replace_warning)
        button(R.string.identity_replace, secondary = true) { selectedFile = null; showStep(SetupStep.IDENTITY) }
        button(R.string.app_settings, R.id.app_settings, true) { openAppSettings() }
        button(R.string.stop_service, R.id.camera_stop, true) {
            secureDialog(MaterialAlertDialogBuilder(this).setTitle(R.string.stop_title).setMessage(R.string.stop_hint)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.stop_service) { _, _ ->
                    try {
                        state.armed(false)
                        detachPreview()
                        stopping = stopService(Intent(this, PeerService::class.java))
                        report(if (stopping) R.string.stopping else R.string.camera_stopped_hint)
                    } catch (_: Exception) { report(R.string.stop_failed) }
                })
        }
        button(R.string.back_camera, R.id.setup_back, true) { if (complete()) showCamera() else showStep(readiness().firstMissing()) }
    }
    private fun startPeer() {
        if (!identity || ownOrigins.isEmpty()) { showStep(SetupStep.IDENTITY); return }
        if (!hasMediaPermissions()) { showStep(SetupStep.PERMISSIONS); return }
        try {
            state = PeerState(this)
            val service = PeerService.instance
            val approved = (service?.approvedOrigins() ?: state.origins.list()) + ownOrigins
            if (service != null) service.updateSettings(state.name(), approved)
            else state.saveOrigins(approved)
            state.armed(true)
            startingUntil = SystemClock.elapsedRealtime() + 15_000
            ContextCompat.startForegroundService(this, Intent(this, PeerService::class.java))
            updateLive()
        } catch (_: Exception) {
            state.armed(false)
            startingUntil = 0
            report(R.string.start_failed)
        }
    }
    private fun report(resource: Int) {
        message = resource
        banner?.setText(resource)
        banner?.visibility = View.VISIBLE
    }
    private fun activeGrants() = (PeerService.instance?.grants() ?: PeerState(this).grants.list())
        .filter { it.expires > System.currentTimeMillis() }

    private fun updateLive() {
        val service = PeerService.instance
        val raw = service?.health ?: "stopped"
        if (raw == "running") startingUntil = 0
        if (service == null) stopping = false
        if (raw == "disarmed: HTTPS ready" || raw == "stopped") pausing = false
        val starting = startingUntil > SystemClock.elapsedRealtime() && raw == "stopped"
        val (title, detail) = when {
            pausing -> R.string.pausing to R.string.pausing_hint
            starting -> R.string.starting to R.string.start_intro
            raw == "running" -> R.string.camera_ready to R.string.camera_ready_hint
            raw == "disarmed: HTTPS ready" -> R.string.camera_paused to R.string.camera_paused_hint
            raw == "stopped" -> R.string.camera_stopped to R.string.camera_stopped_hint
            "permission" in raw -> R.string.camera_unavailable to R.string.health_permissions
            "STUN" in raw -> R.string.camera_unavailable to R.string.health_stun
            "capture" in raw -> R.string.camera_unavailable to R.string.health_capture
            "storage" in raw -> R.string.camera_unavailable to R.string.health_storage
            "foreground" in raw -> R.string.camera_unavailable to R.string.health_foreground
            else -> R.string.camera_unavailable to R.string.health_connection
        }
        if (healthTitle?.text != getString(title)) healthTitle?.setText(title)
        if (healthDetail?.text != getString(detail)) healthDetail?.setText(detail)
        action?.apply {
            setText(when {
                pausing -> R.string.pausing
                starting -> R.string.starting
                page == Page.SETUP && raw == "running" -> R.string.continue_pair
                page == Page.CAMERA && raw == "running" -> R.string.pause_camera
                raw == "disarmed: HTTPS ready" -> R.string.resume_camera
                else -> R.string.start_camera
            })
            isEnabled = !starting && !stopping && !pausing && importer.status.value != ImportState.Working
        }
        if (startingUntil != 0L && startingUntil <= SystemClock.elapsedRealtime()) {
            startingUntil = 0
            report(R.string.start_timeout)
        }
        if (raw == "running" && !pausing && previewHost != null && renderer == null) attachPreview(service!!)
        if (raw != "running" && renderer != null) detachPreview()
        val grants = activeGrants()
        if (pairingCode != null && (service == null ||
                System.identityHashCode(service) != importer.codeService ||
                grants.any { it.id !in grantsBeforeCode })) {
            pairingCode = null
            codeUntil = 0
        }
        val remaining = ((codeUntil - SystemClock.elapsedRealtime() + 999) / 1_000).coerceAtLeast(0)
        if (pairingCode != null && remaining > 0) {
            codeView?.text = pairingCode!!.chunked(3).joinToString(" ")
            countdown?.text = getString(R.string.code_remaining, remaining)
        } else {
            codeView?.text = ""
            countdown?.text = if (codeUntil != 0L) getString(R.string.code_expired) else ""
            pairingCode = null
        }
        val paired = grants.isNotEmpty()
        findViewById<MaterialCheckBox>(R.id.viewer_confirm)?.apply {
            isEnabled = paired && raw == "running"
            if (!isEnabled && isChecked) {
                verifiedVideo = false
                isChecked = false
            }
        }
        val pairingStatus = if (paired) R.string.viewer_paired else R.string.viewer_waiting
        if (viewerStatus?.text != getString(pairingStatus)) viewerStatus?.setText(pairingStatus)
        finish?.isEnabled = verifiedVideo && paired && raw == "running"
        val signature = grants.joinToString { "${it.id}:${it.expires}" }
        if (grantsHost != null && displayedGrants != signature) {
            displayedGrants = signature
            showGrants(grants)
        } else if (grantsHost?.childCount == 0) showGrants(grants)
    }
    private fun attachPreview(service: PeerService) {
        val track = service.preview() ?: return
        try {
            val preview = SurfaceViewRenderer(this)
            renderer = preview
            preview.init(service.eglContext(), null)
            preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            preview.contentDescription = getString(R.string.preview_title)
            previewHost?.removeAllViews()
            previewHost?.addView(preview, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attached = track
            track.addSink(preview)
        } catch (_: Exception) {
            detachPreview()
            report(R.string.preview_failed)
        }
    }
    private fun detachPreview() {
        renderer?.let {
            try { attached?.removeSink(it) }
            catch (_: IllegalStateException) { Log.w("LanternSetup", "Preview track was already stopped") }
            finally { it.release() }
        }
        renderer = null
        attached = null
        previewHost?.removeAllViews()
        showPreviewPlaceholder()
    }
    private fun showPreviewPlaceholder() {
        previewHost?.addView(TextView(this).apply {
            setText(R.string.preview_waiting); textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.lantern_on_surface))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
    }
    private fun showGrants(grants: List<Grant>) {
        val host = grantsHost ?: return
        host.removeAllViews()
        if (grants.isEmpty()) host.addView(TextView(this).apply { setText(R.string.viewers_empty); textSize = 16f })
        grants.forEach { grant ->
            host.addView(TextView(this).apply {
                text = grant.name; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(4))
            })
            host.addView(TextView(this).apply {
                text = getString(R.string.viewer_expiry, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(grant.expires)))
            })
            host.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.revoke)
                contentDescription = "${getString(R.string.revoke)}: ${grant.name}"
                minHeight = dp(48)
                setOnClickListener {
                    secureDialog(MaterialAlertDialogBuilder(this@MainActivity).setTitle(getString(R.string.revoke_title, grant.name))
                        .setMessage(R.string.revoke_hint).setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.revoke) { _, _ ->
                            try {
                                val service = PeerService.instance
                                if (service != null) service.revoke(grant.id)
                                else {
                                    val saved = PeerState(this@MainActivity)
                                    saved.grants.revoke(grant.id)
                                    saved.saveGrants()
                                }
                                report(R.string.revoked)
                                updateLive()
                            } catch (_: Exception) { report(R.string.revoke_failed) }
                        })
                }
            })
        }
    }
    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { report(R.string.settings_unavailable) }
    }
    private fun showGuide() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
        }
        val sections = listOf(
            R.string.guide_prepare_title to R.string.guide_prepare,
            R.string.guide_identity_title to R.string.guide_identity,
            R.string.guide_trust_title to R.string.guide_trust,
            R.string.guide_pair_title to R.string.guide_pair,
            R.string.guide_test_title to R.string.guide_test,
            R.string.guide_recovery_title to R.string.guide_recovery,
            R.string.guide_network_title to R.string.guide_network)
        sections.forEach { (title, detail) ->
            body.addView(TextView(this).apply {
                setText(title); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(8)); ViewCompat.setAccessibilityHeading(this, true)
            })
            body.addView(TextView(this).apply { setText(detail); textSize = 16f; setTextIsSelectable(true) })
            if (title == R.string.guide_identity_title) {
                body.addView(TextView(this).apply {
                    setText(R.string.guide_commands); textSize = 13f; typeface = Typeface.MONOSPACE
                    setPadding(0, dp(12), 0, dp(12)); setTextIsSelectable(true)
                })
                body.addView(TextView(this).apply { setText(R.string.guide_keys); textSize = 16f })
            }
        }
        body.addView(TextView(this).apply { setText(R.string.guide_full); setPadding(0, dp(16), 0, 0) })
        secureDialog(MaterialAlertDialogBuilder(this).setTitle(R.string.guide_title)
            .setView(ScrollView(this).apply { addView(body) }).setPositiveButton(R.string.done, null))
    }
    private fun secureDialog(builder: MaterialAlertDialogBuilder): AlertDialog =
        builder.create().also {
            it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            it.show()
        }
}
