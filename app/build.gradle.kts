plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

import java.util.Properties

// Crashlytics only once the owner adds a Firebase project (app/google-services.json, gitignored).
// Without it the app builds and ships with no Firebase code at all (RELEASE.md § Crashlytics).
val firebase = file("google-services.json").exists()
if (firebase) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Release signing from keystore.properties (gitignored; RELEASE.md § Signing). Absent = unsigned release.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Committed schemas are how migrations get written and tested safely (D-13, RELEASE.md).
room3 {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "app.dukaan.android"

    // compileSdk 37 is forced by the AndroidX baseline (core 1.19, Compose 1.12, lifecycle 2.11).
    // targetSdk stays at 36: bumping it opts in to runtime behaviour changes, so it is a tested step.
    compileSdk = 37

    defaultConfig {
        // PLACEHOLDER (D-12): applicationId is permanent once uploaded to Play. Lock it first.
        applicationId = "app.dukaan.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.16.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // In-app language picker (M6): every language must be in the base APK, or Play's per-language
    // split leaves an English phone without Urdu strings.
    bundle {
        language { enableSplit = false }
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Every user-visible string lives in strings.xml from day one, so the Urdu pass (M6)
        // is translation work, not a hunt through Kotlin files.
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // M3 scanning: CameraX preview + on-device ML Kit barcode model (bundled: works offline on first install).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.androidx.biometric)
    // Accounts + cloud copy (M7, docs/ACCOUNTS.md). Always compiled; without google-services.json the
    // app runs in local-only mode (AccountRepository checks FirebaseApp) so CI and other devs still build.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    if (firebase) implementation(libs.firebase.crashlytics)

    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real SQLite on the JVM: ledger and FIFO tests run without an emulator.
    testImplementation(libs.androidx.sqlite.bundled.jvm)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
