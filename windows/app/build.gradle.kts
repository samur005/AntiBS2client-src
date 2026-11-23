import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val appVersion = "2.2.2"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    // the Windows runtime of Skiko is packed into the portable build explicitly (we build on Linux)
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "net.antibs.client.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AntiBS2client"
            packageVersion = appVersion
            windows { menuGroup = "AntiBS2client"; upgradeUuid = "6b3c1a0e-6c1e-4f4e-9a8d-2f1b0c9e7a51" }
        }
    }
}

tasks.withType<Test>().configureEach {
    // end-to-end test runs the real engines when the Linux builds are present on the build machine
    val eng = file("/workspace/win-engines/linux")
    if (eng.isDirectory) systemProperty("antibs.engines", eng.path)
    systemProperty("user.home", System.getenv("ANTIBS_SEED_HOME") ?: layout.buildDirectory.dir("test-home").get().asFile.path)
}

tasks.register("printVersion") { doLast { println(appVersion) } }

// net.antibs.client.BuildConfig (Android's generated class) for the shared sources
val genBuildConfig = tasks.register("genBuildConfig") {
    val out = layout.buildDirectory.dir("generated/buildconfig")
    inputs.property("v", appVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("net/antibs/client/BuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package net.antibs.client\n\nobject BuildConfig {\n    const val VERSION_NAME = \"$appVersion\"\n    const val VERSION_CODE = 2202\n    const val APPLICATION_ID = \"fun.antibs.client\"\n    const val DEBUG = false\n    const val XRAY_VERSION = \"26.11.9\"\n    const val SINGBOX_VERSION = \"1.14.2\"\n    const val MIHOMO_VERSION = \"—\"\n}\n")
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(genBuildConfig)

tasks.register("printClasspath") {
    dependsOn("jar")
    doLast { println((sourceSets["main"].runtimeClasspath + files(tasks.named<Jar>("jar").get().archiveFile)).asPath) }
}

// Windows package staging: app jar + runtime classpath without the Linux/macOS Skiko natives.
tasks.register<Sync>("stageWindowsLibs") {
    dependsOn("jar")
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath.get().filter { f -> listOf("linux", "macos").none { f.name.contains(it) } })
    into(layout.buildDirectory.dir("win/lib"))
}
