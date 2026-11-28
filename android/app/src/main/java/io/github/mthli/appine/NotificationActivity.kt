package io.github.mthli.appine

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity

/** Copies notification text after the shared privacy gate opens. */
class NotificationActivity : FragmentActivity(), Privacy.Listener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }

    override fun onPrivacyUnlocked() {
        if (isFinishing) return
        Notifications.copyNotificationText(this, intent)
        intent = Intent()
        finish()
    }
}
