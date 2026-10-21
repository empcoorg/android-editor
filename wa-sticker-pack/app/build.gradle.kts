plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// applicationId can be overridden per pack so two different packs can coexist on
// one device:  ./gradlew assembleRelease -PpackAppId=com.stickers.pack.mycats
val packAppId = (project.findProperty("packAppId") as String?) ?: "com.stickers.pack"

android {
    namespace = "com.stickers.pack"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = packAppId
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debuggable so an unsigned/debug-signed build installs straight from adb.
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

    // Stickers ship uncompressed so WhatsApp can mmap them; keep webp as-is.
    androidResources {
        noCompress += "webp"
    }
}
