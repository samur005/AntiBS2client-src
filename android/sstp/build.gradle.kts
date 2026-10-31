plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Open SSTP Client (https://github.com/kittoku/Open-SSTP-Client, MIT, commit 5f97511), trimmed to the protocol core.
android {
    namespace = "kittoku.osc"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
