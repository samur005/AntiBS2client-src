plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ZeroTierOne core (MPL-2.0) through its Java JNI SDK (com.zerotier.sdk, GPL-3.0); prebuilt for arm64-v8a only.
android {
    namespace = "net.antibs.zt"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

android {
    testOptions { unitTests { isReturnDefaultValues = true; all { t ->
        t.systemProperty("java.library.path", "/workspace/ztbuild/host")
        listOf("ZT_LIVE", "ZT_LIVE_FRESH").forEach { k -> System.getenv(k)?.let { t.environment(k, it) } }
        t.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
    } } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
