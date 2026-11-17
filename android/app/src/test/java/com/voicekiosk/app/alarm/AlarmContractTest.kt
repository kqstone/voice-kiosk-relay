package com.voicekiosk.app.alarm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class AlarmContractTest {

    private fun locateFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("android/$relativePath"),
            File("app/$relativePath"),
            File("android/app/$relativePath"),
            File("../$relativePath"),
            File("../android/$relativePath")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("Could not find required file: $relativePath", file)
        return file!!
    }

    @Test
    fun testManifestPermissionsContract() {
        val manifestFile = locateFile("src/main/AndroidManifest.xml")
        val content = manifestFile.readText(StandardCharsets.UTF_8)

        assertTrue(
            "Must declare SCHEDULE_EXACT_ALARM permission",
            content.contains("android.permission.SCHEDULE_EXACT_ALARM")
        )
        assertTrue(
            "Must declare USE_EXACT_ALARM permission",
            content.contains("android.permission.USE_EXACT_ALARM")
        )
        assertTrue(
            "Must declare WAKE_LOCK permission",
            content.contains("android.permission.WAKE_LOCK")
        )
    }

    @Test
    fun testManifestComponentsContract() {
        val manifestFile = locateFile("src/main/AndroidManifest.xml")
        val content = manifestFile.readText(StandardCharsets.UTF_8)

        // AlarmBroadcastReceiver: app-internal non-exported receiver
        assertTrue(
            "Must declare .alarm.AlarmBroadcastReceiver",
            content.contains(".alarm.AlarmBroadcastReceiver")
        )
        // AlarmRingingService: foreground service
        assertTrue(
            "Must declare .alarm.AlarmRingingService",
            content.contains(".alarm.AlarmRingingService")
        )
        // AlarmAlertActivity: full-screen minimal alert activity
        assertTrue(
            "Must declare .alarm.AlarmAlertActivity",
            content.contains(".alarm.AlarmAlertActivity")
        )
        assertTrue(
            "AlarmAlertActivity must have showWhenLocked=true",
            content.contains("android:showWhenLocked=\"true\"")
        )
        assertTrue(
            "AlarmAlertActivity must have turnScreenOn=true",
            content.contains("android:turnScreenOn=\"true\"")
        )
    }

    @Test
    fun testBootReceiverReschedulingContract() {
        val bootReceiverFile = locateFile("src/main/java/com/voicekiosk/app/BootReceiver.kt")
        val content = bootReceiverFile.readText(StandardCharsets.UTF_8)

        assertTrue(
            "BootReceiver must call AlarmScheduler.rescheduleAllActiveAlarms",
            content.contains("AlarmScheduler.rescheduleAllActiveAlarms")
        )
    }

    @Test
    fun testAlarmAlertLayoutsViewIdsContract() {
        val portraitFile = locateFile("src/main/res/layout/activity_alarm_alert.xml")
        val landscapeFile = locateFile("src/main/res/layout-land/activity_alarm_alert.xml")

        val portraitContent = portraitFile.readText(StandardCharsets.UTF_8)
        val landscapeContent = landscapeFile.readText(StandardCharsets.UTF_8)

        val requiredIds = listOf(
            "@+id/alarmAlertRoot",
            "@+id/tvAlarmTime",
            "@+id/tvAlarmTitle",
            "@+id/tvVoiceHint",
            "@+id/btnDismiss",
            "@+id/btnSnooze"
        )

        for (id in requiredIds) {
            assertTrue(
                "Portrait activity_alarm_alert.xml must contain view id $id",
                portraitContent.contains(id)
            )
            assertTrue(
                "Landscape activity_alarm_alert.xml must contain view id $id",
                landscapeContent.contains(id)
            )
        }
    }

    @Test
    fun testBundledOfflineRingtoneResourceContract() {
        val ringtoneFile = locateFile("src/main/res/raw/alarm_ringtone.wav")
        assertTrue("Bundled offline ringtone file must exist", ringtoneFile.isFile)
        assertTrue("Bundled offline ringtone file must not be empty", ringtoneFile.length() > 1000)
    }
}
