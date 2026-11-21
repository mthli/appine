package io.github.mthli.appine

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The host CLI in the installed APK, optionally registered on the Android system PATH. */
internal object TerminalClient {
    private const val MODULE_ID = "appine-cli"
    private const val MODULE_PRESENT = 26
    private const val MODULE_REMOVING = 27
    private const val INSTALLED_WITH_KSU = 28
    private const val REMOVE_FAILED = 29
    private val modifying = AtomicBoolean()
    val isModifying: Boolean get() = modifying.get()

    enum class ModuleState { ABSENT, INSTALLED, REMOVAL_PENDING }

    enum class Result(val message: Int) {
        INSTALLED(R.string.command_client_installed),
        KSU_INSTALLED(R.string.command_client_installed_ksu),
        ALREADY_INSTALLED(R.string.command_client_installed_title),
        REMOVAL_PENDING(R.string.command_client_removal_pending),
        UNINSTALLED(R.string.command_client_uninstalled),
        UNINSTALL_FAILED(R.string.command_client_uninstall_failed),
        NO_ROOT(R.string.command_client_no_root),
        NO_MANAGER(R.string.command_client_no_manager),
        NO_MOUNT(R.string.command_client_no_mount),
        MOUNT_PENDING(R.string.command_client_mount_pending),
        MOUNT_DISABLED(R.string.command_client_mount_disabled),
        FAILED(R.string.command_client_install_failed),
        BUSY(R.string.command_client_working);

        val installed: Boolean get() = this == INSTALLED || this == KSU_INSTALLED
    }

    private fun executable(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, "libappine-cli.so").takeIf { it.isFile && it.canExecute() }

    fun interactiveCommand(context: Context): String? =
        executable(context)?.let { shellPath(it) + " exec -it -- bash" }

    fun moduleState(context: Context): ModuleState {
        val known = Settings.commandClientModuleKnown(context)
        // Copy-only users do not need to request Root just to open the menu.
        if (!known && !File("/system/bin/appine").exists()) return ModuleState.ABSENT
        val state = when (runRoot("$modulePaths\nmodule_state", timeoutSeconds = 5)) {
            0 -> ModuleState.ABSENT
            MODULE_PRESENT -> ModuleState.INSTALLED
            MODULE_REMOVING -> ModuleState.REMOVAL_PENDING
            else -> return if (known) ModuleState.INSTALLED else ModuleState.ABSENT
        }
        val present = state != ModuleState.ABSENT
        if (present != known) Settings.setCommandClientModuleKnown(context, present)
        return state
    }

    fun install(context: Context): Result {
        if (!modifying.compareAndSet(false, true)) return Result.BUSY
        try {
            val executable = executable(context) ?: return Result.FAILED
            val archive = File.createTempFile("appine-cli-", ".zip", context.cacheDir)
            try {
                pack(executable, archive)
                val result = installArchive(executable, archive)
                if (result.installed || result == Result.ALREADY_INSTALLED || result == Result.REMOVAL_PENDING) {
                    Settings.setCommandClientModuleKnown(context, true)
                }
                return result
            } finally {
                archive.delete()
            }
        } catch (_: IOException) {
            return Result.FAILED
        } finally {
            modifying.set(false)
        }
    }

    fun uninstall(context: Context): Result {
        if (!modifying.compareAndSet(false, true)) return Result.BUSY
        try {
            val result = runRoot("""
                $modulePaths
                module_state
                state="${'$'}?"
                [ "${'$'}state" = $MODULE_PRESENT ] || exit "${'$'}state"
                $findKernelSu
                if [ -x "${'$'}ksu" ]; then
                    "${'$'}ksu" module uninstall $MODULE_ID || exit $REMOVE_FAILED
                elif [ -x /data/adb/apd ]; then
                    /data/adb/apd module uninstall $MODULE_ID || exit $REMOVE_FAILED
                elif command -v magisk >/dev/null 2>&1; then
                    is_appine_module "${'$'}live" && touch "${'$'}live/remove" || exit $REMOVE_FAILED
                else
                    exit 21
                fi
                [ -f "${'$'}live/remove" ] || exit $REMOVE_FAILED
                exit $MODULE_REMOVING
            """.trimIndent())
            return when (result) {
                0 -> {
                    Settings.setCommandClientModuleKnown(context, false)
                    Result.UNINSTALLED
                }
                MODULE_REMOVING -> Result.REMOVAL_PENDING
                21 -> Result.NO_MANAGER
                REMOVE_FAILED -> Result.UNINSTALL_FAILED
                else -> Result.NO_ROOT
            }
        } finally {
            modifying.set(false)
        }
    }

    private fun pack(executable: File, archive: File) {
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            val properties = """
                id=${MODULE_ID}
                name=Appine CLI
                version=${BuildConfig.VERSION_NAME}
                versionCode=${BuildConfig.VERSION_CODE}
                author=Appine contributors
                description=Adds appine to the Android system PATH for ${BuildConfig.APPLICATION_ID}.
            """.trimIndent()
            zip.file("module.prop", properties)
            zip.file("customize.sh", """
                [ "${'$'}ARCH" = arm64 ] || abort "Appine CLI requires ARM64"
                if [ -e /system/bin/appine ] || [ -L /system/bin/appine ]; then
                    [ -f /data/adb/modules/${MODULE_ID}/module.prop ] || abort "Another appine is already on the system PATH"
                fi
                set_perm "${'$'}MODPATH/system/bin/appine" 0 0 0755
            """.trimIndent())
            zip.putNextEntry(ZipEntry("system/bin/appine"))
            executable.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun ZipOutputStream.file(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write((text + "\n").encodeToByteArray())
        closeEntry()
    }

    private fun shellPath(file: File): String = "'" + file.absolutePath.replace("'", "'\\''") + "'"

    private val modulePaths = """
        live=/data/adb/modules/$MODULE_ID
        staged=/data/adb/modules_update/$MODULE_ID
        is_appine_module() {
            [ -f "${'$'}1/module.prop" ] && grep -Fqx 'id=$MODULE_ID' "${'$'}1/module.prop"
        }
        module_state() {
            if ! is_appine_module "${'$'}live" && ! is_appine_module "${'$'}staged"; then return 0; fi
            if [ -f "${'$'}live/remove" ]; then return $MODULE_REMOVING; fi
            return $MODULE_PRESENT
        }
    """.trimIndent()

    private val findKernelSu = """
        ksu=/data/adb/ksu/bin/ksud
        [ -x "${'$'}ksu" ] || ksu=/data/adb/ksud
    """.trimIndent()

    private fun installArchive(executable: File, archive: File): Result {
        val zip = shellPath(archive)
        val client = shellPath(executable)
        // Use the root manager's installer so it can stage and label the module.
        val script = """
            $modulePaths
            module_state || exit "${'$'}?"
            $findKernelSu
            installed_result=0
            if [ -x "${'$'}ksu" ]; then
                installed_result=$INSTALLED_WITH_KSU
                version="${'$'}("${'$'}ksu" --version 2>/dev/null)"
                version="${'$'}{version#ksud }"
                major="${'$'}{version%%.*}"
                case "${'$'}major" in
                    ''|*[!0-9]*) ;;
                    *) if [ "${'$'}major" -ge 3 ] && [ ! -f /data/adb/metamodule/module.prop ]; then exit 23; fi ;;
                esac
                # KernelSU reports the mount module state in its installer output.
                if ! output="${'$'}("${'$'}ksu" module install $zip 2>&1)"; then
                    case "${'$'}output" in
                        *"Current state: Pending changes"*"Metamodule installation blocked"*) exit 24 ;;
                        *"Current state: Disabled"*"Metamodule installation blocked"*) exit 25 ;;
                    esac
                    exit 22
                fi
            elif [ -x /data/adb/apd ]; then
                /data/adb/apd module install $zip || exit 22
            elif magisk="${'$'}(command -v magisk)"; then
                "${'$'}magisk" --install-module $zip || exit 22
            else
                exit 21
            fi
            for root in /data/adb/modules_update /data/adb/modules; do
                installed="${'$'}root/${MODULE_ID}/system/bin/appine"
                if [ -x "${'$'}installed" ] && cmp -s "${'$'}installed" $client; then exit "${'$'}installed_result"; fi
            done
            exit 22
        """.trimIndent()
        return when (runRoot(script)) {
            0 -> Result.INSTALLED
            INSTALLED_WITH_KSU -> Result.KSU_INSTALLED
            MODULE_PRESENT -> Result.ALREADY_INSTALLED
            MODULE_REMOVING -> Result.REMOVAL_PENDING
            21 -> Result.NO_MANAGER
            22 -> Result.FAILED
            23 -> Result.NO_MOUNT
            24 -> Result.MOUNT_PENDING
            25 -> Result.MOUNT_DISABLED
            else -> Result.NO_ROOT
        }
    }

    private fun runRoot(script: String, timeoutSeconds: Long? = null): Int? {
        val command = """
            export PATH=/system/bin:/system/xbin:/sbin:/debug_ramdisk
            [ "${'$'}(id -u)" = 0 ] || exit 20
            $script
        """.trimIndent()
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return null
        }
        try {
            if (timeoutSeconds == null) {
                // Drain but do not log output from the privileged installer.
                process.inputStream.use { output ->
                    val buffer = ByteArray(4096)
                    while (output.read(buffer) != -1) { /* drain */ }
                }
            } else if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return null
            }
            return process.waitFor()
        } catch (_: IOException) {
            return null
        } finally {
            process.destroy()
        }
    }
}
