package net.antibs.client.engine

import android.content.Context

/** Android mirrors UiSettings into DataStore; on desktop AppState (state.json) is the only store. */
class UserPrefsStore(@Suppress("UNUSED_PARAMETER") ctx: Context) {
    suspend fun readOrNull(): UiSettings? = null
    suspend fun write(@Suppress("UNUSED_PARAMETER") s: UiSettings) {}
}
