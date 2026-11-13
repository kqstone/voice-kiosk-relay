package com.voicekiosk.app

import android.content.Context
import android.content.SharedPreferences
import android.test.mock.MockContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class AppConfigTest {

    private fun locateSourceFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/java/com/voicekiosk/app/$relativePath"),
            File("app/src/main/java/com/voicekiosk/app/$relativePath"),
            File("android/app/src/main/java/com/voicekiosk/app/$relativePath"),
            File("../android/app/src/main/java/com/voicekiosk/app/$relativePath")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not find source file: $relativePath", file)
        return file!!
    }

    @Test
    fun testDefaultValue_isEmptyStringForAutomaticSelection() {
        val prefs = FakeSharedPreferences()
        val defaultVal = AppConfig.getOnDevicePlayerPackage(prefs)
        assertEquals("", defaultVal)
    }

    @Test
    fun testCustomPackagePersistenceAndReadBack() {
        val prefs = FakeSharedPreferences()

        AppConfig.setOnDevicePlayerPackage(prefs, "com.netease.cloudmusic")
        assertEquals("com.netease.cloudmusic", AppConfig.getOnDevicePlayerPackage(prefs))

        AppConfig.setOnDevicePlayerPackage(prefs, "com.android.music")
        assertEquals("com.android.music", AppConfig.getOnDevicePlayerPackage(prefs))
    }

    @Test
    fun testResetToAutomaticEmptyValue() {
        val prefs = FakeSharedPreferences()

        AppConfig.setOnDevicePlayerPackage(prefs, "com.netease.cloudmusic")
        assertEquals("com.netease.cloudmusic", AppConfig.getOnDevicePlayerPackage(prefs))

        AppConfig.setOnDevicePlayerPackage(prefs, "")
        assertEquals("", AppConfig.getOnDevicePlayerPackage(prefs))
    }

    @Test
    fun testNormalizePlayerPackageWhitespaceAndNull() {
        val prefs = FakeSharedPreferences()

        AppConfig.setOnDevicePlayerPackage(prefs, "   com.tencent.qqmusic \n ")
        assertEquals("com.tencent.qqmusic", AppConfig.getOnDevicePlayerPackage(prefs))

        AppConfig.setOnDevicePlayerPackage(prefs, "   ")
        assertEquals("", AppConfig.getOnDevicePlayerPackage(prefs))

        AppConfig.setOnDevicePlayerPackage(prefs, null)
        assertEquals("", AppConfig.getOnDevicePlayerPackage(prefs))
    }

    @Test
    fun testContextPrefsOverride() {
        val prefs = FakeSharedPreferences()
        try {
            AppConfig.prefsOverride = prefs
            // Context is an abstract class, so it cannot be faked with a dynamic
            // proxy (Proxy only works on interfaces). MockContext is never touched:
            // prefs() short-circuits on prefsOverride above.
            val dummyContext: Context = MockContext()

            AppConfig.setOnDevicePlayerPackage(dummyContext, "com.kugou.android")
            assertEquals("com.kugou.android", AppConfig.getOnDevicePlayerPackage(dummyContext))

            AppConfig.setOnDevicePlayerPackage(dummyContext, "")
            assertEquals("", AppConfig.getOnDevicePlayerPackage(dummyContext))
        } finally {
            AppConfig.prefsOverride = null
        }
    }

    @Test
    fun testAppConfigContract_keyAndDefaults() {
        assertEquals("pref_ondevice_player_package", AppConfig.KEY_ONDEVICE_PLAYER_PACKAGE)
        assertEquals("", AppConfig.DEFAULT_ONDEVICE_PLAYER_PACKAGE)

        val configFile = locateSourceFile("AppConfig.kt")
        val content = configFile.readText(StandardCharsets.UTF_8)
        assertTrue("Must declare KEY_ONDEVICE_PLAYER_PACKAGE", content.contains("KEY_ONDEVICE_PLAYER_PACKAGE"))
        assertTrue("Must declare getOnDevicePlayerPackage", content.contains("fun getOnDevicePlayerPackage"))
        assertTrue("Must declare setOnDevicePlayerPackage", content.contains("fun setOnDevicePlayerPackage"))
        assertTrue("Must declare normalizePlayerPackage", content.contains("fun normalizePlayerPackage"))
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)

        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
            private val staging = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) staging[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) {
                    removed.add(key)
                    staging.remove(key)
                }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                staging.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) parent.data.clear()
                for (key in removed) parent.data.remove(key)
                for ((key, value) in staging) parent.data[key] = value
            }
        }
    }
}
