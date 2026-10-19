import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val targetAbis = listOf("arm64-v8a", "x86_64")

// Signing: secrets are NOT in the repo. Read from keystore.properties (gitignored) or env vars:
//   ANTIBS_STORE_FILE, ANTIBS_STORE_PASSWORD, ANTIBS_KEY_ALIAS, ANTIBS_KEY_PASSWORD
val signProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signValue(prop: String, env: String): String? =
    (signProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val storePath = signValue("storeFile", "ANTIBS_STORE_FILE") ?: "keystore/antibs-debug.jks"
val hasSigning = rootProject.file(storePath).isFile && signValue("storePassword", "ANTIBS_STORE_PASSWORD") != null

android {
    namespace = "net.antibs.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "fun.antibs.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 53
        versionName = "1.0.53"
        ndk { abiFilters += targetAbis }
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "XRAY_VERSION", "\"26.9.30\"")
        buildConfigField("String", "SINGBOX_VERSION", "\"1.14.2\"")
        buildConfigField("String", "MIHOMO_VERSION", "\"1.19.32\"")
    }

    signingConfigs {
        // Stable project-local key so upgrades keep the same signature.
        if (hasSigning) create("antibs") {
            storeFile = rootProject.file(storePath)
            storePassword = signValue("storePassword", "ANTIBS_STORE_PASSWORD")
            keyAlias = signValue("keyAlias", "ANTIBS_KEY_ALIAS") ?: "antibs"
            keyPassword = signValue("keyPassword", "ANTIBS_KEY_PASSWORD")
            // same key as ≤1.0.18 (updates install over). minSdk 26 → v1 (JAR) not needed; v2 + v3.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*targetAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasSigning) signingConfig = signingConfigs.getByName("antibs")
        }
        getByName("release") {
            isDebuggable = false
            isJniDebuggable = false
            // R8 stays off: gomobile JNI classes (libv2ray), kotlinx.serialization and reflection-based Compose
            // tooling would need hand-written keep rules; a wrong one breaks the core at runtime.
            isMinifyEnabled = false
            isShrinkResources = false
            // without keystore.properties / env vars the release APK is built unsigned
            if (hasSigning) signingConfig = signingConfigs.getByName("antibs")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // 1.0.46: host-side Compose snapshots of the home globe / map (Robolectric native graphics, run with SNAP=1)
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "3g"; if (System.getenv("SNAP") != null) it.setForkEvery(1) }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    // 1.0.42: «Магазин» payment pages in a Custom Tab
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 1.0.22: speed test HTTP stack (connection pool, SOCKS proxy, network-bound sockets/DNS)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 1.0.25: «Автообновление подписок» (periodic background refresh)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
