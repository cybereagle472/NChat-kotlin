package com.nchat.app.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nchat.app.data.ServiceLocator

/**
 * "Decline" straight from the call notification.
 *
 * Rejecting a call should never require opening the app: the ringing notification is answered
 * where it appears. The receiver runs the same `CallRepository.decline()` the on-screen Decline
 * button does, so the caller hears the call end and the server is told, identically.
 *
 * A cold process is possible here (the app can be killed while the call rings), so the graph is
 * initialised first — `ServiceLocator.init` is idempotent. If there is nothing left to decline,
 * the notification is simply removed.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(NcNotifications.EXTRA_CALL_ID) ?: return
        if (intent.action != ACTION_DECLINE) return

        ServiceLocator.init(context.applicationContext)
        // Declining is a decision, not a miss: say so before the call state can turn Ended.
        ServiceLocator.markCallHandled(callId)
        runCatching { ServiceLocator.callRepository.decline() }
        ServiceLocator.notifications.cancelIncomingCall(callId)
    }

    companion object {
        const val ACTION_DECLINE = "com.nchat.app.action.DECLINE_CALL"
    }
}
