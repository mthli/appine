package io.github.mthli.appine

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** Material 3 frame for the standard AndroidX settings list. */
internal class SettingsActivity : AppCompatActivity(), Privacy.Listener, Privacy.ExportListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.page)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.settings)
            setNavigationOnClickListener { finish() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content, SettingsFragment())
                .commitNow()
        }
        val appBar = findViewById<AppBarLayout>(R.id.app_bar)
        // The list is inside the fragment's container; layout and scroll callbacks must use
        // the same scrolling view when deciding whether to lift the app bar.
        appBar.liftOnScrollTargetViewId = androidx.preference.R.id.recycler_view
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, 0, bars.right, 0)
            appBar.updatePadding(top = bars.top)
            // The fragment applies the bottom inset to its RecyclerView.
            insets
        }
    }

    override fun onPrivacyUnlocked() {
        (supportFragmentManager.findFragmentById(R.id.content) as? SettingsFragment)?.onNotice()
    }

    override fun onLogExportAuthorized(authorization: Privacy.ExportAuthorization) {
        (supportFragmentManager.findFragmentById(R.id.content) as? SettingsFragment)?.exportLogs(authorization)
    }
}

/**
 * Displays service state and forwards changes to TerminalService or the authenticated Privacy
 * controller. Preferences never persist values themselves: service setters also update wake
 * locks, document roots and running terminals. Destructive operations still ask first and
 * return to the terminal when replacement or restart begins.
 */
class SettingsFragment : PreferenceFragmentCompat(), TerminalService.Listener {

    private var service: TerminalService? = null
    private var insetsApplied = false
    private lateinit var distro: Preference
    private lateinit var reset: Preference
    private lateinit var account: ListPreference

    /**
     * The regular users in `/etc/passwd` ([Rootfs.regularAccounts]), read in the background each
     * time the settings page is shown.
     */
    private var accounts: List<Rootfs.Account> = emptyList()
    private var accountLoad = 0

    private lateinit var tier: Preference
    private lateinit var export: Preference
    private var exporting = false
    private var exportGeneration = 0
    private val logSave by lazy { ViewModelProvider(this)[PendingLogSave::class.java] }
    private val createLogDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        // The process-local archive reference authorizes this result once, never a new export.
        val archive = logSave.take() ?: return@registerForActivityResult
        Privacy.deferNextUnlock(requireActivity())
        if (uri == null) {
            archive.delete()
            setExportProgress()
        } else {
            saveLogs(archive, uri)
        }
    }
    private lateinit var batteryUsage: Preference
    private lateinit var notifications: Preference
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updatePermissionActions()
    }
    private lateinit var runPolicy: ListPreference
    private lateinit var fileAccess: SwitchPreferenceCompat
    private lateinit var androidShare: Preference
    private lateinit var commandAccess: SwitchPreferenceCompat
    private lateinit var commandClient: Preference
    private var checkingCommandClientModule = false
    private var shareListDialog: AlertDialog? = null
    private var shareListRows: List<AndroidShare.Config> = emptyList()
    private var pendingEditTarget: String? = null
    private var folderRequest = 0
    private val pickSharedFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val editingTarget = pendingEditTarget
        pendingEditTarget = null
        if (uri != null) {
            Privacy.deferNextUnlock(requireActivity())
            confirmShare(
                source = AndroidShare.Source.Tree(uri),
                previousTarget = editingTarget,
                previousSource = editingTarget?.let { target ->
                    Settings.androidShares(requireContext()).firstOrNull { it.target == target }?.source
                },
            )
        }
    }
    private lateinit var privacy: SwitchPreferenceCompat
    private lateinit var fillWide: SwitchPreferenceCompat
    private lateinit var cursorStyle: ListPreference
    private lateinit var cursorBlink: SwitchPreferenceCompat
    private lateinit var volumeKeys: SwitchPreferenceCompat
    private lateinit var tabList: SwitchPreferenceCompat
    private lateinit var licenses: Preference

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as TerminalService.LocalBinder).service
            service = s
            s.addListener(this@SettingsFragment)
            render()
            onNotice()
            loadAccounts()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            render()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        pendingEditTarget = savedInstanceState?.getString(STATE_PENDING_EDIT_TARGET)
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        distro = action(R.string.settings_distro) { onDistro() }
        reset = action(R.string.settings_reset) { onReset() }
        account = choice(R.string.settings_login) { s, value ->
            s.setLogin(value)
            val who = if (value == ConsolePty.LOGIN_AUTO) {
                accounts.firstOrNull()?.name ?: ConsolePty.LOGIN_ROOT
            } else {
                value
            }
            Toast.makeText(requireContext(), getString(R.string.login_changed, who), Toast.LENGTH_SHORT).show()
        }
        tier = action(R.string.settings_tier) { onTier() }
        export = action(R.string.settings_export_logs) {
            chooseLogExport()
        }.apply {
            setSummary(R.string.export_logs_summary)
            isEnabled = !logSave.pending
        }
        NotificationPermission.initialize(requireContext())
        notifications = action(R.string.settings_notifications) {
            NotificationPermission.enable(requireActivity(), requestNotifications)
        }.apply { setSummary(R.string.settings_notifications_summary) }
        batteryUsage = action(R.string.settings_battery_usage) {
            BatteryOptimization.openAppSettings(requireActivity())
        }.apply { setSummary(R.string.battery_prompt) }
        updatePermissionActions()
        runPolicy = choice(R.string.settings_run_policy) { s, value ->
            s.setRunPolicy(RunPolicy.entries.first { it.value == value })
        }.apply {
            entries = RunPolicy.entries.map { getString(it.label) }.toTypedArray()
            entryValues = RunPolicy.entries.map { it.value }.toTypedArray()
        }
        fileAccess = toggle(R.string.settings_file_access, TerminalService::setFileAccess).apply {
            setOnPreferenceChangeListener { _, value ->
                if (service == null) return@setOnPreferenceChangeListener false
                if (value as Boolean) confirmFileAccess() else service?.setFileAccess(false)
                // Only the service's render changes the switch, after confirmation when enabling.
                false
            }
        }
        androidShare = action(R.string.share_title) { chooseAndroidShare() }
        commandAccess = toggle(R.string.command_access, TerminalService::setCommandAccess)
        commandClient = action(R.string.command_client_title) { chooseCommandClient() }
        privacy = SwitchPreferenceCompat(requireContext()).configure(R.string.settings_privacy).apply {
            widgetLayoutResource = R.layout.preference_switch
            setOnPreferenceChangeListener { _, value ->
                onPrivacyMode(value as Boolean)
                false
            }
        }
        fillWide = toggle(R.string.settings_fill_wide, TerminalService::setFillWide)
        cursorStyle = choice(R.string.settings_cursor_style) { s, value -> s.setCursorStyle(value.toInt()) }.apply {
            entries = CURSOR_LABELS.map { getString(it) }.toTypedArray()
            entryValues = CURSOR_STYLES.map { it.toString() }.toTypedArray()
        }
        cursorBlink = toggle(R.string.settings_cursor_blink, TerminalService::setCursorBlink)
        volumeKeys = toggle(R.string.settings_volume_keys, TerminalService::setVolumeKeysSwitchTabs)
        tabList = toggle(R.string.settings_show_tab_list, TerminalService::setShowTabList)
        // The license page includes the full text, copyright and warranty disclaimer.
        licenses = action(R.string.settings_licenses) {
            startActivity(Intent(requireContext(), LicensesActivity::class.java))
        }
        licenses.setSummary(R.string.licenses_summary)
        val telegram = link(R.string.settings_telegram, TELEGRAM_URL)
        val project = link(R.string.settings_project, PROJECT_URL)
        val privacyPolicy = link(R.string.settings_privacy_policy, PRIVACY_POLICY_URL)
        val version = action(R.string.settings_version) { onVersion() }.apply {
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        }

        group(R.string.settings_group_system, distro, account, reset)
        group(R.string.settings_group_android, notifications, batteryUsage, runPolicy, privacy, fileAccess, androidShare, commandAccess, commandClient)
        group(R.string.settings_group_terminal, fillWide, cursorStyle, cursorBlink, tabList, volumeKeys)
        group(R.string.settings_group_debug, tier, export)
        group(R.string.settings_group_about, telegram, project, privacyPolicy, licenses, version)
        render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        insetsApplied = false
        super.onViewCreated(view, savedInstanceState)
        listView.itemAnimator = null
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            listView.updatePadding(bottom = bars.bottom)
            insetsApplied = true
            allowStateRestoration()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(view)
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        super.onCreateAdapter(preferenceScreen).apply {
            // Empty summaries make rows shorter before binding; restoring now would clamp the
            // saved scroll position to that temporary, shorter list. The bottom inset must also
            // be applied before restoring a position anchored at the list's end.
            stateRestorationPolicy = if (service == null || !insetsApplied) {
                RecyclerView.Adapter.StateRestorationPolicy.PREVENT
            } else {
                RecyclerView.Adapter.StateRestorationPolicy.ALLOW
            }
        }

    override fun onStart() {
        super.onStart()
        val context = requireContext()
        val intent = Intent(context, TerminalService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionActions()
    }

    private fun updatePermissionActions() {
        notifications.isVisible = !NotificationPermission.enabled(requireContext())
        batteryUsage.isVisible = !BatteryOptimization.isExempt(requireContext())
    }

    private fun chooseLogExport() {
        if (exporting || logSave.pending || Privacy.locked(requireContext())) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_export_logs)
            .setItems(arrayOf(getString(R.string.export_logs_save), getString(R.string.export_logs_share))) { _, which ->
                logSave.saveRequested = which == 0
                Privacy.requestLogExport(requireActivity())
            }
            .showPrivate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_EDIT_TARGET, pendingEditTarget)
    }

    override fun onStop() {
        exportGeneration++
        service?.removeListener(this)
        requireContext().unbindService(connection)
        service = null
        accountLoad++
        render()
        super.onStop()
    }

    override fun onDestroy() {
        shareListDialog?.dismiss()
        shareListDialog = null
        super.onDestroy()
    }

    /** Never queue an export behind the service worker: the kernel may be stuck on its lock. */
    internal fun exportLogs(authorization: Privacy.ExportAuthorization) {
        if (exporting || logSave.pending || !isResumed || !Privacy.canExport(requireContext(), authorization)) return
        setExportProgress(R.string.export_logs_preparing)
        val owner = requireActivity()
        val context = owner.applicationContext
        val generation = exportGeneration
        val save = logSave.saveRequested
        logSave.saveRequested = false
        thread(name = "appine-export") {
            val result = runCatching { Diagnostics.export(context, forSave = save) }
            owner.runOnUiThread {
                setExportProgress()
                // A detached/replaced page or a lock must never replay a delayed export.
                if (!isAdded || !isResumed || generation != exportGeneration || !Privacy.canExport(context, authorization)) {
                    result.getOrNull()?.delete()
                    return@runOnUiThread
                }
                result.fold(onSuccess = { archive ->
                    if (save) chooseLogDestination(archive) else shareLogs(archive)
                }, onFailure = {
                    Toast.makeText(context, R.string.export_logs_failed, Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    private fun chooseLogDestination(archive: File) {
        // Authorize only this prepared archive before leaving for the picker.
        logSave.stage(archive)
        setExportProgress()
        try {
            createLogDocument.launch("appine-diagnostics-${System.currentTimeMillis()}.zip")
        } catch (_: ActivityNotFoundException) {
            logSave.take()?.delete()
            setExportProgress()
            Toast.makeText(requireContext(), R.string.export_logs_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogs(archive: File) {
        try {
            val intent = Diagnostics.shareIntent(requireContext(), archive)
            startActivity(Intent.createChooser(intent, getString(R.string.settings_export_logs)))
        } catch (_: ActivityNotFoundException) {
            archive.delete()
            Toast.makeText(requireContext(), R.string.export_logs_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun setExportProgress(summary: Int? = null) {
        exporting = summary != null
        if (isAdded) {
            export.isEnabled = !exporting && !logSave.pending
            export.setSummary(summary ?: R.string.export_logs_summary)
        }
    }

    private fun saveLogs(archive: File, destination: Uri) {
        setExportProgress(R.string.export_logs_saving)
        val owner = requireActivity()
        val context = owner.applicationContext
        // Like an intentional share, this already-authorized transfer can finish while locked.
        thread(name = "appine-save-logs") {
            val saved = runCatching {
                archive.inputStream().use { input ->
                    val output = context.contentResolver.openOutputStream(destination, "wt")
                        ?: throw IOException("Document provider returned no output stream")
                    output.use { input.copyTo(it) }
                }
            }
            archive.delete()
            owner.runOnUiThread {
                setExportProgress()
                if (isAdded) {
                    Toast.makeText(context, if (saved.isSuccess) R.string.export_logs_saved else R.string.export_logs_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Stable keys let AndroidX restore list/dialog state without owning the setting's storage. */
    private fun <T : Preference> T.configure(title: Int): T = apply {
        key = resources.getResourceEntryName(title)
        setTitle(title)
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
    }

    private fun action(title: Int, onClick: () -> Unit): Preference =
        Preference(requireContext()).configure(title).apply {
            setOnPreferenceClickListener {
                onClick()
                true
            }
        }

    private fun link(title: Int, url: String): Preference = action(title) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(requireContext(), url.toUri())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.link_no_app, Toast.LENGTH_SHORT).show()
        }
    }.apply { summary = url }

    private fun toggle(title: Int, change: (TerminalService, Boolean) -> Unit): SwitchPreferenceCompat =
        SwitchPreferenceCompat(requireContext()).configure(title).apply {
            widgetLayoutResource = R.layout.preference_switch
            setOnPreferenceChangeListener { _, value ->
                val s = service ?: return@setOnPreferenceChangeListener false
                change(s, value as Boolean)
                true
            }
        }

    private fun choice(title: Int, change: (TerminalService, String) -> Unit): ListPreference =
        ListPreference(requireContext()).configure(title).apply {
            setDialogTitle(title)
            setOnPreferenceChangeListener { _, value ->
                val s = service ?: return@setOnPreferenceChangeListener false
                if (!isEnabled) return@setOnPreferenceChangeListener false
                change(s, value as String)
                true
            }
        }

    private fun group(heading: Int, vararg rows: Preference) {
        val category = PreferenceCategory(requireContext()).configure(heading)
        preferenceScreen.addPreference(category)
        for (row in rows) category.addPreference(row)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference !is ListPreference) {
            super.onDisplayPreferenceDialog(preference)
            return
        }
        if (parentFragmentManager.findFragmentByTag(CHOICE_DIALOG) != null) return
        ChoiceDialog.newInstance(preference.key).apply {
            // Preference's dialog contract still uses the target fragment to find its preference.
            @Suppress("DEPRECATION")
            setTargetFragment(this@SettingsFragment, 0)
        }.show(parentFragmentManager, CHOICE_DIALOG)
    }

    /** AndroidX owns selection and saved state; only the builder changes for Material 3. */
    class ChoiceDialog : ListPreferenceDialogFragmentCompat() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.dialogTitle)
                .setNegativeButton(preference.negativeButtonText, this)
            onPrepareDialogBuilder(builder)
            return builder.create().also {
                Privacy.protect(it)
                // Back, Cancel and an outside tap must never commit the current selection.
                onClick(it, DialogInterface.BUTTON_NEGATIVE)
            }
        }

        companion object {
            fun newInstance(key: String) = ChoiceDialog().apply {
                arguments = Bundle().apply { putString(ARG_KEY, key) }
            }
        }
    }

    // ---- TerminalService.Listener ----

    /**
     * Replacing the store (importing a new distro) begins: go back to the terminal to see the
     * progress.
     */
    override fun onProgress(text: String) = requireActivity().finish()

    override fun onMachineReady(error: String?) {
        render()
        loadAccounts()
    }

    override fun onSettingsChanged() = render()

    /** The download for a distro switch failed. */
    override fun onNotice() {
        if (Privacy.locked(requireContext())) return
        val text = service?.takeNotice() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .showPrivate()
    }

    override fun onRestarting() = requireActivity().finish()

    override fun onExitAll() = requireActivity().finish()

    // ---- Display ----

    private fun render() {
        val s = service
        if (s == null) {
            for (row in listOf(distro, reset, account, tier, runPolicy, fileAccess, privacy, androidShare, commandAccess, commandClient,
                fillWide, cursorStyle, cursorBlink, volumeKeys, tabList)) row.isEnabled = false
            return
        }
        val running = s.machine != 0L
        val sw = s.switching
        val installed = Rootfs.installed(requireContext())
        val idle = running && sw == null
        distro.set(
            when {
                sw != null -> getString(R.string.switch_downloading, sw.distro.label, sw.done shr 20, sw.total shr 20)
                running || s.startError != null -> installed?.label.orEmpty()
                else -> s.progress ?: getString(R.string.starting)
            },
            sw != null || s.canSwitch,
        )
        // Reset is possible even when the start failed: it is the only way to recover a broken
        // store
        reset.set(getString(R.string.reset_summary), s.canSwitch)
        tier.set(getString(if (s.tier == 0) R.string.tier_0_summary else R.string.tier_1_summary), idle)
        account.entries = (listOf(getString(R.string.login_auto_choice), ConsolePty.LOGIN_ROOT) + accounts.map { it.name }).toTypedArray()
        account.entryValues = (listOf(ConsolePty.LOGIN_AUTO, ConsolePty.LOGIN_ROOT) + accounts.map { it.name }).toTypedArray()
        account.value = s.login
        account.set(loginSummary(s.login), installed != null && sw == null)
        // Can be changed at any time: while the kernel is not running it is only recorded, and the
        // lock is held accordingly once it is up
        runPolicy.value = s.runPolicy.value
        runPolicy.set(getString(s.runPolicy.summary), true)
        val privacyEnabled = Settings.privacyMode(requireContext())
        privacy.set(getString(R.string.privacy_summary), true, privacyEnabled)
        fileAccess.set(getString(if (privacyEnabled) R.string.privacy_file_access else R.string.file_access_summary), !privacyEnabled, s.fileAccess)
        commandAccess.set(
            getString(if (privacyEnabled) R.string.command_access_paused else R.string.command_access_summary),
            !privacyEnabled, Settings.terminalAccessEnabled(requireContext()),
        )
        commandClient.set(
            getString(when {
                privacyEnabled -> R.string.command_access_paused
                TerminalClient.isModifying -> R.string.command_client_working
                else -> R.string.command_client_summary
            }),
            !privacyEnabled && !TerminalClient.isModifying && !checkingCommandClientModule,
        )
        val shares = Settings.androidShares(requireContext())
        val shareSummary = if (shares.isEmpty()) {
            getString(R.string.share_off)
        } else {
            resources.getQuantityString(R.plurals.share_count, shares.size, shares.size)
        }
        androidShare.set(shareSummary, idle)
        refreshShareListDialog()
        fillWide.set(getString(if (s.fillWide) R.string.fill_wide_on else R.string.fill_wide_off), true, s.fillWide)
        cursorStyle.value = s.cursorStyle.toString()
        cursorStyle.set(getString(CURSOR_LABELS[CURSOR_STYLES.indexOf(s.cursorStyle).coerceAtLeast(0)]), true)
        cursorBlink.set(getString(if (s.cursorBlink) R.string.cursor_blink_on else R.string.cursor_blink_off), true, s.cursorBlink)
        volumeKeys.set(
            getString(if (s.volumeKeysSwitchTabs) R.string.volume_keys_on else R.string.volume_keys_off),
            true,
            s.volumeKeysSwitchTabs,
        )
        tabList.set(getString(if (s.showTabList) R.string.tab_list_on else R.string.tab_list_off), true, s.showTabList)
        allowStateRestoration()
    }

    private fun allowStateRestoration() {
        if (service != null && insetsApplied && view != null) {
            listView.adapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.ALLOW
        }
    }

    private fun Preference.set(text: CharSequence, enabled: Boolean) {
        summary = text
        isEnabled = enabled
    }

    private fun SwitchPreferenceCompat.set(text: CharSequence, enabled: Boolean, checked: Boolean) {
        set(text, enabled)
        isChecked = checked
    }

    private fun loginSummary(login: String): String = when (login) {
        ConsolePty.LOGIN_AUTO -> getString(R.string.login_auto, accounts.firstOrNull()?.name ?: ConsolePty.LOGIN_ROOT)
        ConsolePty.LOGIN_ROOT -> login
        else -> if (accounts.any { it.name == login }) login else getString(R.string.login_missing, login)
    }

    private fun loadAccounts() {
        val context = requireContext().applicationContext
        val activity = requireActivity()
        val generation = ++accountLoad
        thread(name = "appine-accounts") {
            val list = Rootfs.regularAccounts(context)
            activity.runOnUiThread {
                if (generation == accountLoad && service != null) {
                    accounts = list
                    render()
                }
            }
        }
    }

    // ---- Actions ----

    private fun onPrivacyMode(enabled: Boolean) {
        if (enabled) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_privacy)
                .setMessage(R.string.privacy_enable_message)
                .setPositiveButton(R.string.file_access_enable) { _, _ -> Privacy.requestMode(requireActivity(), true) }
                .setNegativeButton(android.R.string.cancel, null)
                .showPrivate()
        } else {
            Privacy.requestMode(requireActivity(), false)
        }
    }

    private fun confirmFileAccess() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.file_access_title)
            .setMessage(R.string.file_access_message)
            .setPositiveButton(R.string.file_access_enable) { _, _ -> service?.setFileAccess(true) }
            .setNegativeButton(android.R.string.cancel, null)
            .showPrivate()
    }

    private fun onDistro() {
        val s = service ?: return
        s.switching?.let { sw ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.cancel_download_title, sw.distro.label))
                .setPositiveButton(R.string.cancel_download) { _, _ -> s.cancelSwitch() }
                .setNegativeButton(R.string.keep_downloading, null)
                .showPrivate()
            return
        }
        val distros = Rootfs.Distro.entries
        val installed = Rootfs.installed(requireContext())
        val labels = distros.map { it.label }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_distro)
            .setSingleChoiceItems(labels.toTypedArray(), distros.indexOf(installed)) { dialog, i ->
                dialog.dismiss()
                if (distros[i] != installed) confirmSwitch(distros[i], getString(R.string.switch_title, distros[i].label))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showPrivate()
    }

    /** When the very first import failed (no store), resets to Alpine, as on the first run. */
    private fun onReset() {
        val installed = Rootfs.installed(requireContext()) ?: Rootfs.Distro.ALPINE
        confirmSwitch(installed, getString(R.string.reset_title))
    }

    /**
     * Confirms before switching to [distro] (a reset switches to the installed one): every file in
     * the rootfs is deleted.
     */
    private fun confirmSwitch(distro: Rootfs.Distro, title: String) {
        val download = distro.download
        val message = if (download != null) {
            getString(R.string.switch_message_download, distro.label, download.size shr 20)
        } else {
            getString(R.string.switch_message, distro.label)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.switch_confirm) { _, _ -> service?.switchTo(distro) }
            .setNegativeButton(android.R.string.cancel, null)
            .showPrivate()
    }

    private fun chooseCommandClient() {
        val activity = requireActivity()
        val context = activity.applicationContext
        if (Settings.privacyMode(context) || TerminalClient.isModifying || checkingCommandClientModule) return
        checkingCommandClientModule = true
        commandClient.isEnabled = false
        thread(name = "appine-cli-status") {
            val state = TerminalClient.moduleState(context)
            activity.runOnUiThread {
                checkingCommandClientModule = false
                if (isAdded) render()
                if (isResumed && !Settings.privacyMode(context)) showCommandClient(state)
            }
        }
    }

    private fun showCommandClient(state: TerminalClient.ModuleState) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.command_client_title)
            .setNegativeButton(android.R.string.cancel, null)
        if (state == TerminalClient.ModuleState.REMOVAL_PENDING) {
            dialog.setMessage(R.string.command_client_removal_pending)
                .setPositiveButton(R.string.command_client_copy_shell) { _, _ -> copyCommandClientShell() }
                .showPrivate()
            return
        }
        val installed = state == TerminalClient.ModuleState.INSTALLED
        val choices = arrayOf(
            getString(if (installed) R.string.command_client_uninstall else R.string.command_client_install),
            getString(R.string.command_client_copy_shell),
        )
        dialog.setItems(choices) { _, which ->
            when {
                which == 1 -> copyCommandClientShell()
                installed -> confirmCommandClientUninstall()
                else -> changeCommandClientModule(uninstall = false)
            }
        }.showPrivate()
    }

    private fun confirmCommandClientUninstall() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.command_client_uninstall)
            .setMessage(R.string.command_client_confirm_uninstall)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.command_client_uninstall) { _, _ -> changeCommandClientModule(uninstall = true) }
            .showPrivate()
    }

    private fun changeCommandClientModule(uninstall: Boolean) {
        val activity = requireActivity()
        val context = activity.applicationContext
        if (Settings.privacyMode(context) || TerminalClient.isModifying) return
        commandClient.set(getString(R.string.command_client_working), false)
        thread(name = "appine-cli-module") {
            val result = if (uninstall) TerminalClient.uninstall(context) else TerminalClient.install(context)
            activity.runOnUiThread {
                if (result.installed) {
                    val runtime = GuestCommands.service
                    if (runtime == null || Settings.privacyMode(context)) {
                        Settings.setTerminalAccess(context, true)
                    } else {
                        runtime.setCommandAccess(true)
                    }
                }
                if (isAdded) render()
                if (!isResumed || Settings.privacyMode(context)) return@runOnUiThread
                when (result) {
                    TerminalClient.Result.KSU_INSTALLED -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.command_client_installed_title)
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .showPrivate()
                    TerminalClient.Result.ALREADY_INSTALLED -> showCommandClient(TerminalClient.ModuleState.INSTALLED)
                    else -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyCommandClientShell() {
        val s = service ?: return
        val context = requireContext()
        if (Settings.privacyMode(context)) return
        val command = TerminalClient.interactiveCommand(context)
        if (command == null) {
            Toast.makeText(context, R.string.command_client_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.command_client_copy_shell), command))
        s.setCommandAccess(true)
        Toast.makeText(context, R.string.command_client_copied, Toast.LENGTH_SHORT).show()
    }

    private fun chooseAndroidShare() {
        val existing = shareListDialog
        if (existing != null && existing.isShowing) {
            refreshShareListDialog()
            return
        }
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Appine_FolderShareDialog)
            .setTitle(R.string.share_title)
            .setView(R.layout.share_list_dialog)
            .setPositiveButton(R.string.share_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .showPrivate()
        shareListDialog = dialog
        dialog.setOnDismissListener {
            if (shareListDialog === dialog) {
                shareListDialog = null
                shareListRows = emptyList()
            }
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            openFolderPicker(editingTarget = null, editingSource = null)
        }
        refreshShareListDialog()
    }

    private fun refreshShareListDialog() {
        val dialog = shareListDialog ?: return
        if (!dialog.isShowing || !isAdded) return
        val context = requireContext()
        val shares = Settings.androidShares(context)
        val statuses = service?.androidShareStatuses.orEmpty()
        val emptyView = dialog.findViewById<TextView>(R.id.share_list_empty) ?: return
        val container = dialog.findViewById<LinearLayout>(R.id.share_list_container) ?: return
        emptyView.isVisible = shares.isEmpty()
        container.isVisible = shares.isNotEmpty()
        if (shareListRows != shares) {
            shareListRows = shares
            container.removeAllViews()
            val inflater = LayoutInflater.from(dialog.context)
            for (config in shares) {
                val row = inflater.inflate(R.layout.share_list_item, container, false)
                row.findViewById<TextView>(R.id.share_item_mode).setText(shareModeLabel(config.source))
                row.findViewById<TextView>(R.id.share_item_target).text = config.target
                bindShareSourceName(row.findViewById(R.id.share_item_folder), config.source) {
                    dialog.isShowing && row.isAttachedToWindow
                }
                row.setOnClickListener { manageAndroidShare(config) }
                container.addView(row)
            }
        }
        shares.forEachIndexed { index, config ->
            val status = statuses[config.target] ?: AndroidShare.Status.OFF
            setShareStatus(container.getChildAt(index).findViewById(R.id.share_item_status), status)
        }
    }

    private fun manageAndroidShare(config: AndroidShare.Config) {
        val context = requireContext()
        val status = service?.androidShareStatuses?.get(config.target) ?: AndroidShare.Status.OFF
        val mounted = status == AndroidShare.Status.MOUNTED
        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Appine_FolderShareDialog)
            .setTitle(R.string.share_title)
            .setView(R.layout.share_manage_dialog)
            .setPositiveButton(R.string.share_edit) { _, _ ->
                confirmShare(
                    source = config.source,
                    previousTarget = config.target,
                    previousSource = config.source,
                )
            }
            .setNeutralButton(R.string.share_disconnect) { _, _ ->
                service?.removeAndroidShare(config.target)
            }
            .showPrivate()

        val folderView = dialog.requireViewById<TextView>(R.id.share_android_folder)
        dialog.requireViewById<TextView>(R.id.share_access_mode).setText(shareModeLabel(config.source))
        dialog.requireViewById<View>(R.id.share_change_folder).setOnClickListener {
            dialog.dismiss()
            openFolderPicker(editingTarget = config.target, editingSource = config.source)
        }
        bindShareSourceName(folderView, config.source) { dialog.isShowing }
        dialog.requireViewById<TextView>(R.id.share_linux_path).text = config.target
        val copyButton = dialog.requireViewById<ImageButton>(R.id.share_copy_path)
        copyButton.isVisible = mounted
        copyButton.setOnClickListener {
            if (Privacy.locked(context)) return@setOnClickListener
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: return@setOnClickListener
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.share_mount_point), config.target))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.share_path_copied, Toast.LENGTH_SHORT).show()
            }
        }
        setShareStatus(dialog.requireViewById(R.id.share_status), status)
    }

    private fun shareModeLabel(source: AndroidShare.Source): Int =
        if (source is AndroidShare.Source.Root && source.write) R.string.share_mode_read_write else R.string.share_mode_read_only

    private fun setShareStatus(view: TextView, status: AndroidShare.Status) {
        val label = when (status) {
            AndroidShare.Status.OFF -> R.string.share_off
            AndroidShare.Status.STARTING -> R.string.share_starting
            AndroidShare.Status.MOUNTED -> null
            AndroidShare.Status.ROOT_FAILED -> R.string.share_root_failed
            AndroidShare.Status.TREE_FAILED -> R.string.share_tree_failed
            AndroidShare.Status.MOUNT_FAILED -> R.string.share_mount_failed
        }
        view.isVisible = label != null
        if (label == null) return
        view.setText(label)
        val color = if (status.isFailure()) androidx.appcompat.R.attr.colorError
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        view.setTextColor(view.context.themeColor(color))
    }

    private fun AndroidShare.Status.isFailure(): Boolean = when (this) {
        AndroidShare.Status.ROOT_FAILED,
        AndroidShare.Status.TREE_FAILED,
        AndroidShare.Status.MOUNT_FAILED -> true
        AndroidShare.Status.OFF,
        AndroidShare.Status.STARTING,
        AndroidShare.Status.MOUNTED -> false
    }

    private fun bindShareSourceName(
        view: TextView,
        source: AndroidShare.Source,
        rootFullPath: Boolean = false,
        isActive: () -> Boolean,
    ) {
        when (source) {
            is AndroidShare.Source.Root -> {
                view.tag = null
                view.text = if (rootFullPath) source.path else File(source.path).name.ifEmpty { "/" }
            }
            is AndroidShare.Source.Tree -> {
                val uri = source.uri
                view.tag = uri
                view.setText(R.string.share_android_folder_unknown)
                val appContext = requireContext().applicationContext
                val activity = requireActivity()
                thread(isDaemon = true, name = "appine-share-label") {
                    val name = resolveTreeDisplayName(appContext, uri)
                    activity.runOnUiThread {
                        if (isAdded && isActive() && view.tag == uri && !Privacy.locked(appContext)) {
                            view.text = name ?: getString(R.string.share_android_folder_unknown)
                        }
                    }
                }
            }
        }
    }

    private fun resolveTreeDisplayName(context: Context, treeUri: Uri): String? =
        try {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }

    private fun openFolderPicker(
        editingTarget: String? = null,
        editingSource: AndroidShare.Source? = null,
    ) {
        val context = requireContext().applicationContext
        val activity = requireActivity()
        val preferred = (editingSource as? AndroidShare.Source.Root)?.path ?: "/data/media/0"
        val request = ++folderRequest
        thread(isDaemon = true, name = "appine-folder-picker") {
            val root = AndroidShare.rootDirectories(context, "/")
            val folders = if (root != null && preferred != "/") AndroidShare.rootDirectories(context, preferred) else null
            activity.runOnUiThread {
                if (!pickerActive(request)) return@runOnUiThread
                when {
                    folders != null -> showRootFolders(preferred, folders, editingTarget, editingSource)
                    root != null -> showRootFolders("/", root, editingTarget, editingSource)
                    else -> {
                        pendingEditTarget = editingTarget
                        try {
                            pickSharedFolder.launch(null)
                        } catch (_: ActivityNotFoundException) {
                            pendingEditTarget = null
                            Toast.makeText(requireContext(), R.string.share_tree_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun pickerActive(request: Int): Boolean =
        request == folderRequest && isAdded &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
            !Privacy.locked(requireContext())

    private fun browseRootFolder(
        path: String,
        editingTarget: String?,
        editingSource: AndroidShare.Source?,
    ) {
        val context = requireContext().applicationContext
        val activity = requireActivity()
        val request = ++folderRequest
        thread(isDaemon = true, name = "appine-folder-picker") {
            val folders = AndroidShare.rootDirectories(context, path)
            activity.runOnUiThread {
                if (!pickerActive(request)) return@runOnUiThread
                if (folders != null) showRootFolders(path, folders, editingTarget, editingSource) else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.share_folder_read_failed)
                        .setPositiveButton(R.string.share_pick) { _, _ -> openFolderPicker(editingTarget, editingSource) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showPrivate()
                }
            }
        }
    }

    private fun showRootFolders(
        path: String,
        folders: List<String>,
        editingTarget: String?,
        editingSource: AndroidShare.Source?,
    ) {
        val parent = File(path).parent
        val labels = (if (parent != null) listOf(getString(R.string.share_parent)) else emptyList()) + folders
        val paths = listOfNotNull(parent) + folders.map { File(path, it).path }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(path)
            .setItems(labels.toTypedArray()) { _, index -> browseRootFolder(paths[index], editingTarget, editingSource) }
            .setNeutralButton(R.string.share_root) { _, _ -> chooseRootPath(path, editingTarget, editingSource) }
            .setPositiveButton(R.string.share_mount) { _, _ ->
                confirmShare(
                    source = AndroidShare.Source.Root(path, write = false),
                    previousTarget = editingTarget,
                    previousSource = editingSource,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showPrivate()
    }

    private fun chooseRootPath(
        current: String,
        editingTarget: String?,
        editingSource: AndroidShare.Source?,
    ) {
        val context = requireContext()
        val path = EditText(context).apply {
            setSingleLine()
            setText(current)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.share_root)
            .setView(path)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .showPrivate()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val selected = path.text.toString()
            if (AndroidShare.validRoot(selected)) {
                dialog.dismiss()
                browseRootFolder(File(selected).path, editingTarget, editingSource)
            } else {
                path.error = getString(R.string.share_invalid_path)
            }
        }
    }

    private fun confirmShare(
        source: AndroidShare.Source,
        previousTarget: String?,
        previousSource: AndroidShare.Source?,
    ) {
        val context = requireContext()
        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Appine_FolderShareDialog)
            .setTitle(R.string.share_confirm)
            .setView(R.layout.share_root_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (previousTarget == null) R.string.share_mount else R.string.share_save, null)
            .showPrivate()

        val selectedPathView = dialog.requireViewById<TextView>(R.id.share_selected_path)
        bindShareSourceName(selectedPathView, source, rootFullPath = true) { dialog.isShowing }

        val targetLayout = dialog.requireViewById<TextInputLayout>(R.id.share_target_layout)
        val targetInput = dialog.requireViewById<TextInputEditText>(R.id.share_target_input)
        targetLayout.placeholderText = AndroidShare.DEFAULT_TARGET
        if (previousTarget == null) {
            targetLayout.helperText = getString(R.string.share_target_helper, AndroidShare.DEFAULT_TARGET)
            targetInput.setText("")
        } else {
            targetLayout.helperText = null
            targetInput.setText(previousTarget)
            targetInput.setSelection(previousTarget.length)
        }
        targetInput.doAfterTextChanged {
            targetLayout.error = null
        }

        val writable = dialog.requireViewById<MaterialSwitch>(R.id.share_write_switch)
        val summary = dialog.requireViewById<TextView>(R.id.share_access_summary)
        if (source is AndroidShare.Source.Root) {
            writable.isVisible = true
            val keepPriorWrite = previousSource is AndroidShare.Source.Root && previousSource.path == source.path
            writable.isChecked = keepPriorWrite && previousSource.write
            summary.isVisible = writable.isChecked
            writable.setOnCheckedChangeListener { _, enabled ->
                summary.isVisible = enabled
            }
        } else {
            writable.isVisible = false
            writable.isChecked = false
            summary.isVisible = false
        }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val s = service ?: return@setOnClickListener
            val finalSource = when (source) {
                is AndroidShare.Source.Root -> AndroidShare.Source.Root(source.path, writable.isChecked)
                is AndroidShare.Source.Tree -> source
            }
            val errorRes = s.saveAndroidShare(previousTarget, finalSource, targetInput.text?.toString().orEmpty())
            if (errorRes != null) {
                targetLayout.error = getString(errorRes)
            } else {
                dialog.dismiss()
            }
        }
    }

    private fun onTier() {
        val s = service ?: return
        val tiers = intArrayOf(1, 0)
        val labels = arrayOf(getString(R.string.tier_1), getString(R.string.tier_0))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_tier)
            .setSingleChoiceItems(labels, tiers.indexOf(s.tier)) { dialog, i ->
                dialog.dismiss()
                if (tiers[i] == s.tier) return@setSingleChoiceItems
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.restart_title)
                    .setMessage(R.string.restart_message)
                    .setPositiveButton(R.string.restart) { _, _ -> service?.setTier(tiers[i]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showPrivate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showPrivate()
    }

    private fun onVersion() {
        val intent = Intent(Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}".toUri())
        try {
            startActivity(intent.setPackage("com.android.vending"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(intent.setPackage(null))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.link_no_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TELEGRAM_URL = "https://t.me/appinesh"
        private const val PROJECT_URL = "https://github.com/mthli/appine"
        private const val PRIVACY_POLICY_URL = "https://appine.sh/privacy"
        private const val CHOICE_DIALOG = "settings.choice"
        private const val STATE_PENDING_EDIT_TARGET = "settings.share.pendingEditTarget"

        /** The cursor shapes offered, in order, and their names. */
        private val CURSOR_STYLES = intArrayOf(VtNative.CURSOR_BLOCK, VtNative.CURSOR_BAR, VtNative.CURSOR_UNDERLINE)
        private val CURSOR_LABELS = intArrayOf(R.string.cursor_block, R.string.cursor_bar, R.string.cursor_underline)
    }
}

/** Retain one authorized archive across picker recreation, never across process death. */
internal class PendingLogSave : ViewModel() {
    var saveRequested = false
    private var archive: File? = null
    val pending: Boolean get() = archive != null

    fun stage(file: File) {
        check(archive == null) { "A log save is already pending" }
        archive = file
    }

    fun take(): File? = archive.also { archive = null }

    override fun onCleared() {
        take()?.delete()
    }
}
