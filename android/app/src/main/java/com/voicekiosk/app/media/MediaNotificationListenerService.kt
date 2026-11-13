package com.voicekiosk.app.media

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * NotificationListenerService required by Android MediaSessionManager.getActiveSessions.
 * Does not inspect or process notification contents; its presence and granted permission
 * allow the system to authorize MediaSessionManager session queries.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaNotifListener"

        @Volatile
        var isConnected: Boolean = false
            private set

        private var connectionListener: ((Boolean) -> Unit)? = null

        fun setConnectionListener(listener: ((Boolean) -> Unit)?) {
            connectionListener = listener
        }

        /**
         * Checks if the user has granted notification listener access to this app.
         */
        fun isNotificationAccessGranted(context: Context): Boolean {
            val pkgName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.packageName == pkgName
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected")
        isConnected = true
        connectionListener?.invoke(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "NotificationListenerService disconnected")
        isConnected = false
        connectionListener?.invoke(false)
    }
}
