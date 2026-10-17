package net.antibs.client.data

import kotlinx.serialization.json.Json
import net.antibs.client.engine.AppState
import org.junit.Assert.assertTrue
import org.junit.Test

class HwidAlwaysOnTest {
    @Test fun defaultAndMissingKeySendHwidTrue() {
        assertTrue(AppState().sendHwid)
        val st = Json { ignoreUnknownKeys = true }.decodeFromString(AppState.serializer(), """{"seeded":true,"rev":1}""")
        assertTrue(st.sendHwid)
    }
}
