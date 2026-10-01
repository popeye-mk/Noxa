import java.util.Properties
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
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
    compileSdk = 36        // build against the current platform; targetSdk stays
                           // 34 until the newer runtime rules are tested on device

    defaultConfig {
        applicationId = "com.guardian.app"
        minSdk = 24            // Android 7.0 — covers ~99% of devices
        targetSdk = 34
        versionCode = 15
        versionName = "1.8"
    }

    signingConfigs {
        // TEST builds: a fixed, public key checked into the repo (password
        // "android", like Android's own default debug key) so every test APK —
        // from CI or any PC — installs OVER the previous one instead of
        // "package conflicts". It signs only "Noxa TEST"; real releases use
        // the private key below.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "noxadebug"
            keyPassword = "android"
        }
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
        // Test builds install NEXT TO the real app ("Noxa TEST", own package),
        // so trying one never replaces or wipes the user's installed Noxa.
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // Unit tests run on the plain JVM against stubbed Android classes; make
    // the stubs return defaults (instead of throwing) so pure logic under test
    // (DNS cache, filter, stats) never trips over an incidental Android call.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // The compiled Bloom filter ships as an asset; don't compress it.
    androidResources {
        noCompress += "gbf"   // guardian-default.gbf + threats.gbf
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Unit tests (app/src/test) — run by CI on every push: ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // The real org.json for tests (the Android stub jar's JSONObject is empty).
    testImplementation("org.json:json:20240303")
    // Tunnel (option 2): the official WireGuard library — Guardian's FIRST
    // third-party dependency. Used now to parse/validate a pasted config, and
    // to establish the tunnel in a later increment. It's open source, which
    // keeps the "provable, auditable, free" story intact.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
