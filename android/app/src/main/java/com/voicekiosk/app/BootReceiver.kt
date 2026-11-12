package com.voicekiosk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver to auto-start Voice Kiosk and its foreground listening service
 * on device boot (BOOT_COMPLETED, QUICKBOOT_POWERON).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VoiceKioskBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Received boot broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            // Start the foreground listening service
            try {
                VoiceKioskService.start(context)
                Log.i(TAG, "VoiceKioskService started from boot receiver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VoiceKioskService from boot receiver", e)
            }

            // Launch MainActivity into foreground (Android 10+ requires foreground activity for real mic capture)
            try {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(launchIntent)
                Log.i(TAG, "MainActivity launched from boot receiver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity from boot receiver", e)
            }
        }
    }
}
