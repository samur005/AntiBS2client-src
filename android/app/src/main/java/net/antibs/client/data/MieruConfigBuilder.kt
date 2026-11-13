package net.antibs.client.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON client config for the bundled mieru client (libmieru.so; 2.0.44: v3.39.0, was 1.15.1). Not used by plans since 1.0.19 (mieru runs on libmhx).
 * rpcPort=0 → mobile mode (no gRPC lifecycle daemon); socks5 on 127.0.0.1:[port].
 * Official mieru:// protobuf shares are kept in [XrayProfile.customJson] and handed to the binary
 * via a generated JSON (we don't depend on protobuf at runtime).
 */
object MieruConfigBuilder {
    const val BASE_PORT = 11000
    const val RPC_PORT = 0 // mobile: no RPC

    fun clientJson(p: XrayProfile, socksPort: Int): String {
        require(p.protocol.equals("mieru", true)) { "not mieru" }
        if (p.server.isBlank() || p.server == "—") throw IllegalStateException("Mieru: не указан адрес сервера")
        if (p.port !in 1..65535) throw IllegalStateException("Mieru: неверный порт")
        val user = p.username?.ifBlank { null } ?: throw IllegalStateException("Mieru: не указано имя пользователя")
        val pass = p.password?.ifBlank { null } ?: throw IllegalStateException("Mieru: не указан пароль")
        val transport = when (p.mieruTransport?.uppercase()) { "UDP" -> "UDP"; else -> "TCP" }
        val isIp = p.server.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || p.server.contains(':')
        val obj = buildJsonObject {
            putJsonArray("profiles") {
                add(buildJsonObject {
                    put("profileName", "default")
                    putJsonObject("user") { put("name", user); put("password", pass) }
                    putJsonArray("servers") {
                        add(buildJsonObject {
                            if (isIp) put("ipAddress", p.server) else {
                                put("ipAddress", "")
                                put("domainName", p.server)
                            }
                            putJsonArray("portBindings") {
                                add(buildJsonObject {
                                    put("port", p.port)
                                    put("protocol", transport)
                                })
                            }
                        })
                    }
                    put("mtu", p.mtu?.takeIf { it in 1280..1500 } ?: 1400)
                    // NB: the bundled mieru core (v1.15.1) has no "multiplexing" field in ClientProfile —
                    // emitting it makes protojson reject the whole config, so the subscription's level is ignored.
                })
            }
            put("activeProfile", "default")
            put("rpcPort", RPC_PORT)
            put("socks5Port", socksPort)
            put("loggingLevel", "INFO")
            put("socks5ListenLAN", false)
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }
}
