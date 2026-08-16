package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.ProbeRequest
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.ProbeTransport
import live.nikro.pinglab.core.model.Protocol
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * HTTP(S) availability probe with a full waterfall breakdown.
 *
 * Instead of using [java.net.HttpURLConnection] (which hides where the time went) we drive
 * the socket ourselves so each phase can be timed independently:
 *
 * ```
 * |--- DNS ---|--- TCP connect ---|--- TLS handshake ---|--- server think time (TTFB) ---|
 * ```
 *
 * That breakdown is what turns "the site feels slow" into an actionable answer.
 */
class HttpPingEngine(
    private val resolver: AddressResolver,
) : PingEngine {

    override val supportedProtocols: Set<Protocol> = setOf(Protocol.HTTP, Protocol.HTTPS)

    override suspend fun probe(request: ProbeRequest): ProbeResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val secure = request.protocol == Protocol.HTTPS
        val port = request.effectivePort ?: if (secure) 443 else 80

        val resolution = resolver.resolve(request.target, request.preferIpv6)
        val address = resolution.address ?: return@withContext ProbeResult.failure(
            request = request,
            status = ProbeStatus.DNS_FAILURE,
            detail = resolution.error ?: "Could not resolve ${request.target}",
            transport = ProbeTransport.HTTP_REQUEST,
            timestampMs = startedAt,
        ).copy(dnsMs = resolution.durationMs)

        val dnsMs = resolution.durationMs.takeUnless { resolution.fromCache }
        var plainSocket: Socket? = null
        var tlsSocket: SSLSocket? = null

        try {
            val totalStartNs = System.nanoTime()

            // --- TCP ---
            plainSocket = Socket()
            plainSocket.tcpNoDelay = true
            plainSocket.soTimeout = request.timeoutMs
            plainSocket.connect(InetSocketAddress(address, port), request.timeoutMs)
            val connectMs = (System.nanoTime() - totalStartNs) / 1_000_000.0

            // --- TLS ---
            var tlsMs: Double? = null
            val input: InputStream
            val output: OutputStream
            if (secure) {
                val handshakeStartNs = System.nanoTime()
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                tlsSocket = factory.createSocket(plainSocket, request.target, port, true) as SSLSocket
                tlsSocket.soTimeout = request.timeoutMs
                // Server Name Indication is mandatory for virtually every modern host.
                runCatching {
                    val params: SSLParameters = tlsSocket.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(request.target))
                    if (request.validateTls) {
                        params.endpointIdentificationAlgorithm = "HTTPS"
                    }
                    tlsSocket.sslParameters = params
                }
                tlsSocket.startHandshake()
                tlsMs = (System.nanoTime() - handshakeStartNs) / 1_000_000.0
                input = tlsSocket.inputStream
                output = tlsSocket.outputStream
            } else {
                input = plainSocket.inputStream
                output = plainSocket.outputStream
            }

            // --- Request ---
            val path = request.httpPath.ifBlank { "/" }
            val hostHeader = if (isDefaultPort(secure, port)) request.target else "${request.target}:$port"
            val head = buildString {
                append("HEAD ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("User-Agent: ").append(USER_AGENT).append("\r\n")
                append("Accept: */*\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val requestSentNs = System.nanoTime()
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.flush()

            // --- Response ---
            val buffered = BufferedInputStream(input, 1024)
            val statusLine = readLine(buffered) ?: throw java.io.IOException("Empty response")
            val firstByteMs = (System.nanoTime() - requestSentNs) / 1_000_000.0
            val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000.0
            val statusCode = parseStatusCode(statusLine)

            // Drain a few headers so the RST we send does not truncate the server's log line.
            var headerCount = 0
            while (headerCount < MAX_HEADERS) {
                val line = readLine(buffered) ?: break
                if (line.isEmpty()) break
                headerCount++
            }

            val status = when {
                statusCode == null -> ProbeStatus.PROTOCOL_ERROR
                statusCode >= 500 -> ProbeStatus.PROTOCOL_ERROR
                else -> ProbeStatus.SUCCESS
            }

            ProbeResult(
                sequence = request.sequence,
                timestampMs = startedAt,
                protocol = request.protocol,
                status = status,
                rttMs = totalMs.takeIf { status == ProbeStatus.SUCCESS },
                hostname = request.target,
                resolvedAddress = address.hostAddress,
                transport = ProbeTransport.HTTP_REQUEST,
                detail = statusLine.take(80),
                dnsMs = dnsMs,
                connectMs = connectMs,
                tlsMs = tlsMs,
                firstByteMs = firstByteMs,
                httpStatusCode = statusCode,
            )
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            ProbeResult.failure(
                request = request,
                status = ProbeStatus.PROTOCOL_ERROR,
                detail = "TLS handshake failed: ${e.message?.take(100)}",
                transport = ProbeTransport.HTTP_REQUEST,
                resolvedAddress = address.hostAddress,
                timestampMs = startedAt,
            ).copy(dnsMs = dnsMs)
        } catch (e: Exception) {
            ProbeResult.failure(
                request = request,
                status = ProbeErrorMapper.statusFor(e),
                detail = ProbeErrorMapper.detailFor(e),
                transport = ProbeTransport.HTTP_REQUEST,
                resolvedAddress = address.hostAddress,
                timestampMs = startedAt,
            ).copy(dnsMs = dnsMs)
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { plainSocket?.close() }
        }
    }

    private fun isDefaultPort(secure: Boolean, port: Int): Boolean =
        (secure && port == 443) || (!secure && port == 80)

    /** Reads a single CRLF-terminated line without over-reading into the body. */
    private fun readLine(stream: InputStream): String? {
        val builder = StringBuilder(64)
        while (true) {
            val value = stream.read()
            if (value == -1) return builder.toString().ifEmpty { null }
            val char = value.toChar()
            if (char == '\n') return builder.toString().removeSuffix("\r")
            builder.append(char)
            if (builder.length > MAX_LINE) return builder.toString()
        }
    }

    private fun parseStatusCode(statusLine: String): Int? {
        val parts = statusLine.split(' ')
        if (parts.size < 2) return null
        return parts[1].toIntOrNull()
    }

    companion object {
        private const val USER_AGENT = "PingLab/1.0 (Android; network diagnostics)"
        private const val MAX_HEADERS = 40
        private const val MAX_LINE = 4096
    }
}
