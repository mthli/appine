package io.github.mthli.appine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/** Starts Linux from a visible Android terminal without switching to the Appine UI. */
internal class CommandStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (Settings.terminalAccess(this)) {
                startForegroundService(Intent(this, TerminalService::class.java).apply {
                    action = TerminalService.ACTION_START_COMMAND
                })
            }
        } catch (_: IllegalStateException) {
            Log.w("AppineExec", "Android blocked the terminal service start")
        } finally {
            finish()
        }
    }
}
