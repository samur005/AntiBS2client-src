@file:Suppress("PackageDirectoryMismatch")
package net.antibs.client.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Compose access to the desktop app context (Android's LocalContext). */
val LocalContext = staticCompositionLocalOf<android.content.Context> { android.app.Application.instance }
