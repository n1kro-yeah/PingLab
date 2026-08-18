package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.DnsLookupResult
import live.nikro.pinglab.core.model.ProbeRequest
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.ProbeTransport
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.util.HostValidator
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.random.Random

/**
 * Minimal DNS/UDP client (RFC 1035).
 *
 * Written by hand rather than reusing [InetAddress] because the JVM aggressively caches
 * resolutions \u2014 useless when the whole point is to measure how fast the resolver answers
 * *right now*. Sending our own query also lets us point at an arbitrary resolver and read
 * the real record TTLs.
 */
object DnsQueryClient {

    const val TYPE_A = 1
    const val TYPE_NS = 2
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val TYPE_MX = 15
    const val TYPE_TXT = 16

    data class Record(
        val name: String,
        val type: Int,
        val ttlSeconds: Long,
        val value: String,
    ) {
        val typeLabel: String
            get() = when (type) {
                TYPE_A -> "A"
                TYPE_AAAA -> "AAAA"
                TYPE_CNAME -> "CNAME"
                TYPE_NS -> "NS"
                TYPE_MX -> "MX"
                TYPE_TXT -> "TXT"
                else -> "TYPE$type"
            }
    }

    data class Response(
        val records: List<Record>,
        val responseCode: Int,
        val durationMs: Double,
        val serverAddress: String,
        val authoritative: Boolean,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null && responseCode == 0

        val responseCodeLabel: String
            get() = when (responseCode) {
                0 -> "NOERROR"
                1 -> "FORMERR"
                2 -> "SERVFAIL"
                3 -> "NXDOMAIN"
                4 -> "NOTIMP"
                5 -> "REFUSED"
                else -> "RCODE$responseCode"
            }
    }

    /** Sends one UDP query and waits for the answer. Blocking; call on [Dispatchers.IO]. */
    fun query(
        server: InetAddress,
        name: String,
        type: Int = TYPE_A,
        timeoutMs: Int = 2_000,
        port: Int = 53,
    ): Response {
        val transactionId = Random.nextInt(0, 0xFFFF)
        val request = buildQuery(transactionId, name, type)
        var socket: DatagramSocket? = null
        val startNs = System.nanoTime()

        return try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(request, request.size, server, port))

            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000.0

            parseResponse(buffer, packet.length, transactionId, durationMs, server.hostAddress ?: "")
        } catch (e: java.net.SocketTimeoutException) {
            Response(
                emptyList(), -1, (System.nanoTime() - startNs) / 1_000_000.0,
                server.hostAddress ?: "", false, "Timed out after $timeoutMs ms",
            )
        } catch (e: Exception) {
            Response(
                emptyList(), -1, (System.nanoTime() - startNs) / 1_000_000.0,
                server.hostAddress ?: "", false, e.message?.take(120) ?: "Query failed",
            )
        } finally {
            runCatching { socket?.close() }
        }
    }

    fun buildQuery(transactionId: Int, name: String, type: Int): ByteArray {
        val labels = name.trim('.').split('.').filter { it.isNotEmpty() }
        val nameSize = labels.sumOf { it.length + 1 } + 1
        val packet = ByteArray(12 + nameSize + 4)

        packet[0] = (transactionId ushr 8).toByte()
        packet[1] = transactionId.toByte()
        packet[2] = 0x01 // recursion desired
        packet[3] = 0x00
        packet[5] = 0x01 // QDCOUNT = 1

        var offset = 12
        for (label in labels) {
            packet[offset++] = label.length.toByte()
            for (char in label) {
                packet[offset++] = char.code.toByte()
            }
        }
        packet[offset++] = 0 // root label

        packet[offset++] = (type ushr 8).toByte()
        packet[offset++] = type.toByte()
        packet[offset++] = 0
        packet[offset] = 1 // QCLASS = IN
        return packet
    }

    private fun parseResponse(
        buffer: ByteArray,
        length: Int,
        expectedId: Int,
        durationMs: Double,
        serverAddress: String,
    ): Response {
        if (length < 12) {
            return Response(emptyList(), -1, durationMs, serverAddress, false, "Truncated response")
        }
        val id = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        if (id != expectedId) {
            return Response(emptyList(), -1, durationMs, serverAddress, false, "Transaction id mismatch")
        }
        val flags2 = buffer[3].toInt() and 0xFF
        val responseCode = flags2 and 0x0F
        val authoritative = (buffer[2].toInt() and 0x04) != 0
        val questionCount = readShort(buffer, 4)
        val answerCount = readShort(buffer, 6)

        var offset = 12
        repeat(questionCount) {
            offset = skipName(buffer, offset, length)
            offset += 4
        }

        val records = ArrayList<Record>(answerCount)
        repeat(answerCount) {
            if (offset >= length) return@repeat
            val nameResult = decodeName(buffer, offset, length)
            offset = nameResult.second
            if (offset + 10 > length) return@repeat
            val type = readShort(buffer, offset)
            val ttl = readInt(buffer, offset + 4)
            val rdLength = readShort(buffer, offset + 8)
            offset += 10
            if (offset + rdLength > length) return@repeat

            val value = when (type) {
                TYPE_A -> if (rdLength == 4) {
                    (0 until 4).joinToString(".") { (buffer[offset + it].toInt() and 0xFF).toString() }
                } else {
                    null
                }

                TYPE_AAAA -> if (rdLength == 16) formatIpv6(buffer, offset) else null
                TYPE_CNAME, TYPE_NS -> decodeName(buffer, offset, length).first
                TYPE_MX -> {
                    val preference = readShort(buffer, offset)
                    val exchange = decodeName(buffer, offset + 2, length).first
                    "$preference $exchange"
                }

                TYPE_TXT -> {
                    val textLength = buffer[offset].toInt() and 0xFF
                    String(buffer, offset + 1, minOf(textLength, rdLength - 1), Charsets.UTF_8)
                }

                else -> null
            }
            if (value != null) {
                records += Record(nameResult.first, type, ttl, value)
            }
            offset += rdLength
        }

        return Response(records, responseCode, durationMs, serverAddress, authoritative)
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun readInt(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun formatIpv6(buffer: ByteArray, offset: Int): String =
        (0 until 8).joinToString(":") { index ->
            val high = buffer[offset + index * 2].toInt() and 0xFF
            val low = buffer[offset + index * 2 + 1].toInt() and 0xFF
            Integer.toHexString((high shl 8) or low)
        }

    /** Returns the decoded name and the offset just past it (following one compression pointer). */
    private fun decodeName(buffer: ByteArray, start: Int, limit: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var offset = start
        var jumped = false
        var endOffset = start
        var guard = 0

        while (offset < limit && guard++ < 128) {
            val lengthByte = buffer[offset].toInt() and 0xFF
            if (lengthByte == 0) {
                offset++
                if (!jumped) endOffset = offset
                break
            }
            if ((lengthByte and 0xC0) == 0xC0) {
                if (offset + 1 >= limit) break
                val pointer = ((lengthByte and 0x3F) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                if (!jumped) endOffset = offset + 2
                jumped = true
                offset = pointer
                continue
            }
            if (offset + 1 + lengthByte > limit) break
            if (builder.isNotEmpty()) builder.append('.')
            builder.append(String(buffer, offset + 1, lengthByte, Charsets.UTF_8))
            offset += 1 + lengthByte
            if (!jumped) endOffset = offset
        }
        return builder.toString() to endOffset
    }

    private fun skipName(buffer: ByteArray, start: Int, limit: Int): Int {
        var offset = start
        var guard = 0
        while (offset < limit && guard++ < 128) {
            val lengthByte = buffer[offset].toInt() and 0xFF
            if (lengthByte == 0) return offset + 1
            if ((lengthByte and 0xC0) == 0xC0) return offset + 2
            offset += 1 + lengthByte
        }
        return offset
    }
}

/**
 * Treats a DNS resolver as a pingable target: each probe is a real query, so the RTT
 * reflects resolver health rather than a cached answer.
 */
class DnsPingEngine(
    private val resolver: AddressResolver,
    private val probeName: String = "example.com",
) : PingEngine {

    override val supportedProtocols: Set<Protocol> = setOf(Protocol.DNS)

    override suspend fun probe(request: ProbeRequest): ProbeResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val parsed = HostValidator.parseOrNull(request.target)

        // An IP literal means "probe this resolver"; a hostname means "resolve this name".
        val serverIsTarget = parsed?.isLiteralAddress == true
        val serverHost = if (serverIsTarget) request.target else request.target
        val queryName = if (serverIsTarget) probeName else request.target

        val serverResolution = resolver.resolve(if (serverIsTarget) serverHost else "1.1.1.1", false)
        val server = serverResolution.address ?: return@withContext ProbeResult.failure(
            request = request,
            status = ProbeStatus.DNS_FAILURE,
            detail = serverResolution.error ?: "Unknown resolver",
            transport = ProbeTransport.DNS_QUERY,
            timestampMs = startedAt,
        )

        val response = DnsQueryClient.query(
            server = server,
            name = queryName,
            type = if (request.preferIpv6) DnsQueryClient.TYPE_AAAA else DnsQueryClient.TYPE_A,
            timeoutMs = request.timeoutMs,
            port = request.effectivePort ?: 53,
        )

        val status = when {
            response.error != null -> ProbeStatus.TIMEOUT
            response.responseCode == 3 -> ProbeStatus.SUCCESS // NXDOMAIN still proves the resolver is alive
            response.responseCode != 0 -> ProbeStatus.PROTOCOL_ERROR
            else -> ProbeStatus.SUCCESS
        }

        ProbeResult(
            sequence = request.sequence,
            timestampMs = startedAt,
            protocol = Protocol.DNS,
            status = status,
            rttMs = response.durationMs.takeIf { status == ProbeStatus.SUCCESS },
            hostname = request.target,
            resolvedAddress = server.hostAddress,
            transport = ProbeTransport.DNS_QUERY,
            detail = response.error ?: "${response.responseCodeLabel}, ${response.records.size} answer(s)",
            dnsMs = response.durationMs,
        )
    }
}

/** Backing logic for the DNS lookup tool screen. */
class DnsLookupTool {

    suspend fun lookup(host: String, includeReverse: Boolean = true): DnsLookupResult =
        withContext(Dispatchers.IO) {
            val startNs = System.nanoTime()
            try {
                val addresses = InetAddress.getAllByName(host)
                val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
                val sorted = addresses.sortedBy { it !is Inet4Address }
                val reverse = if (includeReverse && sorted.isNotEmpty()) {
                    runCatching { sorted.first().canonicalHostName }.getOrNull()
                        ?.takeIf { it != sorted.first().hostAddress }
                } else {
                    null
                }
                DnsLookupResult(
                    query = host,
                    addresses = sorted.mapNotNull { it.hostAddress },
                    canonicalName = sorted.firstOrNull()?.hostName?.takeIf { it != host },
                    reverseName = reverse,
                    durationMs = durationMs,
                )
            } catch (e: Exception) {
                DnsLookupResult(
                    query = host,
                    durationMs = (System.nanoTime() - startNs) / 1_000_000.0,
                    error = e.message?.take(140) ?: "Lookup failed",
                )
            }
        }

    /** Full record dump against a specific resolver, used by the advanced DNS panel. */
    suspend fun records(
        host: String,
        serverAddress: String,
        types: List<Int> = listOf(
            DnsQueryClient.TYPE_A,
            DnsQueryClient.TYPE_AAAA,
            DnsQueryClient.TYPE_CNAME,
            DnsQueryClient.TYPE_MX,
        ),
        timeoutMs: Int = 2_000,
    ): List<DnsQueryClient.Record> = withContext(Dispatchers.IO) {
        val server = runCatching { InetAddress.getByName(serverAddress) }.getOrNull()
            ?: return@withContext emptyList()
        types.flatMap { type ->
            DnsQueryClient.query(server, host, type, timeoutMs).records
        }.distinctBy { it.typeLabel + it.value }
    }

    /** Reverse lookup for a literal address. */
    suspend fun reverse(address: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val inet = InetAddress.getByName(address)
            val name = inet.canonicalHostName
            name.takeIf { it != inet.hostAddress }
        }.getOrNull()
    }

    fun isIpv6(address: InetAddress): Boolean = address is Inet6Address
}
