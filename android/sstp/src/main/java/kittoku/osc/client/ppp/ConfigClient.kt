package kittoku.osc.client.ppp

import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.unit.ppp.Frame
import kittoku.osc.unit.ppp.LCP_CODE_CONFIGURE_ACK
import kittoku.osc.unit.ppp.LCP_CODE_CONFIGURE_NAK
import kittoku.osc.unit.ppp.LCP_CODE_CONFIGURE_REJECT
import kittoku.osc.unit.ppp.LCP_CODE_CONFIGURE_REQUEST
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


private const val PPP_REQUEST_INTERVAL = 3000L
private const val PPP_REQUEST_COUNT = 10
internal const val PPP_NEGOTIATION_TIMEOUT = PPP_REQUEST_INTERVAL * PPP_REQUEST_COUNT


internal abstract class ConfigClient<T: Frame>(private val where: Where, protected val bridge: SharedBridge) {
    internal val mailbox = Channel<T>(Channel.BUFFERED)

    private var jobNegotiation: Job? = null

    private var requestID: Byte = 0 // for only client
    private var requestCount = PPP_REQUEST_COUNT

    private var isClientReady = false
    private var isServerReady = false

    private val isOpen: Boolean
        get() = isClientReady && isServerReady

    private val tag = where.name
    private fun hex(b: java.nio.ByteBuffer): String { val d = b.duplicate(); val sb = StringBuilder(); var n = 0; while (d.hasRemaining() && n < 64) { sb.append("%02x".format(d.get())); n++ }; return sb.toString() }
    private fun codeName(c: Byte) = when (c) { LCP_CODE_CONFIGURE_REQUEST -> "Conf-Req"; LCP_CODE_CONFIGURE_ACK -> "Conf-Ack"; LCP_CODE_CONFIGURE_NAK -> "Conf-Nak"; LCP_CODE_CONFIGURE_REJECT -> "Conf-Rej"; else -> "code$c" }
    private suspend fun sendLogged(f: T, what: String) {
        val b = f.toByteBuffer()
        kittoku.osc.SstpLog.i("$tag → $what id=${f.id} ${hex(b)}")
        bridge.sslTerminal!!.send(b)
    }

    private suspend fun consumeRequestCounter() { // consumed in any circumstance for converging
        requestCount--

        if (requestCount < 0) {
            bridge.controlMailbox.send(ControlMessage(where, Result.ERR_COUNT_EXHAUSTED, kittoku.osc.SstpLog.lastPpp[tag]))
        }
    }

    protected abstract fun tryCreateServerReject(request: T): T?

    protected abstract fun tryCreateServerNak(request: T): T?

    protected abstract fun createServerAck(request: T): T

    protected abstract fun createClientRequest(): T

    protected abstract suspend fun tryAcceptClientReject(reject: T)

    protected abstract suspend fun tryAcceptClientNak(nak: T)

    private suspend fun sendClientRequest() {
        consumeRequestCounter()
        requestID = bridge.allocateNewFrameID()

        createClientRequest().also {
            it.id = requestID
            sendLogged(it, "Conf-Req (попытка ${PPP_REQUEST_COUNT - requestCount})")
        }
    }

    internal fun launchJobNegotiation() {
        jobNegotiation = bridge.service.scope.launch(bridge.handler) {
            sendClientRequest()

            while (isActive) {
                val tried  = withTimeoutOrNull(PPP_REQUEST_INTERVAL) { mailbox.receive() }


                val received: T // accept only CONFIGURE_[REQUEST|ACK|NAK|REJECT]
                if (tried == null) {
                    kittoku.osc.SstpLog.i("$tag: нет ответа ${PPP_REQUEST_INTERVAL / 1000} с (clientReady=$isClientReady serverReady=$isServerReady)")
                    isClientReady = false

                    sendClientRequest()

                    continue
                } else {
                    received = tried
                }
                runCatching { "$tag ← ${codeName(received.code)} id=${received.id} ${hex(received.toByteBuffer())}" }.getOrNull()?.let {
                    kittoku.osc.SstpLog.i(it); kittoku.osc.SstpLog.lastPpp[tag] = it
                }


                if (received.code == LCP_CODE_CONFIGURE_REQUEST) {
                    isServerReady = false

                    val reject = tryCreateServerReject(received)
                    if (reject != null) {
                        sendLogged(reject, "Conf-Rej (отклоняем опции сервера)")
                        continue
                    }

                    val nak = tryCreateServerNak(received)
                    if (nak != null) {
                        sendLogged(nak, "Conf-Nak")
                        continue
                    }

                    createServerAck(received).also {
                        sendLogged(it, "Conf-Ack")
                        isServerReady = true
                    }
                } else {
                    if (isClientReady) {
                        isClientReady = false

                        sendClientRequest()

                        continue
                    }

                    if (received.id != requestID) {
                        continue
                    }

                    when(received.code) {
                        LCP_CODE_CONFIGURE_ACK -> {
                            isClientReady = true
                        }

                        LCP_CODE_CONFIGURE_NAK -> {
                            tryAcceptClientNak(received)
                            sendClientRequest()
                        }

                        LCP_CODE_CONFIGURE_REJECT -> {
                            tryAcceptClientReject(received)
                            sendClientRequest()
                        }
                    }
                }

                if (isOpen) {
                    requestCount = PPP_REQUEST_COUNT
                    bridge.controlMailbox.send(ControlMessage(where, Result.PROCEEDED))

                    break
                }
            }
        }
    }

    internal fun cancel() {
        jobNegotiation?.cancel()
        mailbox.close()
    }
}
