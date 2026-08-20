import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads keystore.properties (NEVER committed — .gitignored).
// If the file is missing, release builds fall back to unsigned (F-Droid signs
// its own builds anyway; your signed APK is for GitHub Releases).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "com.guardian.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guardian.app"
        minSdk = 24            // Android 7.0 — covers ~99% of devices
        targetSdk = 34
        versionCode = 6
        versionName = "1.1.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // No minification: keeps the build reproducible/auditable — anyone
            // can diff the APK against the source. Size cost is acceptable.
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // WireGuard's config classes use newer java.* APIs; desugar them so the
        // library works down to our minSdk (24). Harmless if not strictly needed.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // The compiled Bloom filter ships as an asset; don't compress it.
    androidResources {
        noCompress += "gbf"
    }
}

dependencies {
    // Tunnel (option 2): the official WireGuard library — Guardian's FIRST
    // third-party dependency. Used now to parse/validate a pasted config, and
    // to establish the tunnel in a later increment. It's open source, which
    // keeps the "provable, auditable, free" story intact.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
