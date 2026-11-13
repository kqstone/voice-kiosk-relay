plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voicekiosk.app"
    compileSdk = 34
    // NDK/CMake pinned for the on-device LLM native library (llama.cpp, see src/main/cpp).
    ndkVersion = "29.0.14206865"

    val defaultVersionCode = 2
    val defaultVersionName = "0.1.0"

    val rawVersionCode = (project.findProperty("versionCode") as? String)
        ?: System.getenv("VERSION_CODE")
    val rawVersionName = (project.findProperty("versionName") as? String)
        ?: System.getenv("VERSION_NAME")

    val hasVersionCodeInput = rawVersionCode != null
    val hasVersionNameInput = rawVersionName != null
    if (hasVersionCodeInput != hasVersionNameInput) {
        throw GradleException(
            "Partial Android release version injection is not allowed: both versionCode/VERSION_CODE " +
                "and versionName/VERSION_NAME must be provided together, or neither for local default " +
                "(versionCode=$defaultVersionCode, versionName=$defaultVersionName)."
        )
    }

    val resolvedVersionCode = if (hasVersionCodeInput) {
        val trimmedCode = rawVersionCode!!.trim()
        val parsedCode = trimmedCode.toLongOrNull()
        if (
            !Regex("^[1-9]\\d*$").matches(trimmedCode) ||
            parsedCode == null ||
            parsedCode <= 0L ||
            parsedCode > 2100000000L
        ) {
            throw GradleException(
                "Invalid injected Android versionCode '$trimmedCode': must be a positive integer <= 2100000000."
            )
        }
        parsedCode.toInt()
    } else {
        defaultVersionCode
    }

    val resolvedVersionName = if (hasVersionNameInput) {
        val trimmedName = rawVersionName!!.trim().removePrefix("v")
        if (!Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$").matches(trimmedName)) {
            throw GradleException(
                "Invalid injected Android versionName '$trimmedName': must be a stable semantic version X.Y.Z."
            )
        }
        trimmedName
    } else {
        defaultVersionName
    }

    defaultConfig {
        applicationId = "com.voicekiosk.app"
        minSdk = 28
        targetSdk = 31
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Release-optimised native code even in debug APKs: the S0 benchmark
                // must measure realistic inference speed.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    val keystoreEnvKeys = listOf(
        "ANDROID_KEYSTORE_PATH",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEY_PASSWORD"
    )
    val presentKeystoreKeys = keystoreEnvKeys.filter { !System.getenv(it).isNullOrBlank() }
    val missingKeystoreKeys = keystoreEnvKeys.filter { System.getenv(it).isNullOrBlank() }
    if (presentKeystoreKeys.isNotEmpty() && missingKeystoreKeys.isNotEmpty()) {
        throw GradleException(
            "Incomplete Android release signing environment configuration. " +
                "Missing required variable(s): ${missingKeystoreKeys.joinToString(", ")}. " +
                "Either set all of (${keystoreEnvKeys.joinToString(", ")}) or set none for an unsigned release build."
        )
    }
    val hasFullReleaseSigning = presentKeystoreKeys.size == keystoreEnvKeys.size

    signingConfigs {
        if (hasFullReleaseSigning) {
            create("release") {
                val ksFile = file(System.getenv("ANDROID_KEYSTORE_PATH")!!.trim())
                if (!ksFile.isFile) {
                    throw GradleException(
                        "ANDROID_KEYSTORE_PATH does not point to an existing file."
                    )
                }
                storeFile = ksFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (hasFullReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Voice Kiosk intentionally targets API 31 (Android 12) on general Android phones
        // per docs/SPEC-v1.md §0 and is distributed exclusively via GitHub Releases sideloading
        // rather than Google Play. Narrowly downgrade only the Google Play store policy check
        // ExpiredTargetSdkVersion to informational so lintVitalRelease does not fail on targetSdk 31,
        // while keeping all security, correctness, and release vital lint checks active.
        // Note: Downgrading this store-policy lint rule only bypasses the Google Play submission
        // targetSdk policy error and is not a runtime compatibility guarantee for newer Android APIs.
        informational += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("com.github.stuxuhai:jpinyin:1.1.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
