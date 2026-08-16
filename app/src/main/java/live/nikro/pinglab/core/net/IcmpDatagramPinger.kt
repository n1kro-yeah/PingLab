package live.nikro.pinglab.core.net

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.StructTimeval
import live.nikro.pinglab.core.model.ProbeStatus
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Real ICMP echo using unprivileged datagram sockets.
 *
 * Linux (and therefore Android) exposes `SOCK_DGRAM` + `IPPROTO_ICMP` sockets to
 * unprivileged processes whose GID falls inside `net.ipv4.ping_group_range`. On Android
 * that range covers every app UID, which means we can craft and time genuine ICMP echo
 * requests without root and without spawning a process.
 *
 * Compared to `InetAddress.isReachable()` this gives us:
 *  - true round-trip time measured around a single packet,
 *  - control over payload size, TTL and sequence numbers,
 *  - the ability to distinguish timeout / unreachable / TTL expired.
 *
 * The kernel rewrites the ICMP identifier field on datagram sockets, so replies are
 * matched on the sequence number and the echoed payload magic instead.
 */
class IcmpDatagramPinger {

    data class EchoReply(
        val status: ProbeStatus,
        val rttMs: Double?,
        val fromAddress: String?,
        val ttl: Int?,
        val payloadBytes: Int,
        val detail: String? = null,
    )

    /**
     * Sends one echo request and waits at most [timeoutMs] for the matching reply.
     *
     * This call blocks; callers must run it on [kotlinx.coroutines.Dispatchers.IO].
     */
    fun ping(
        address: InetAddress,
        sequence: Int,
        payloadSize: Int = DEFAULT_PAYLOAD,
        timeoutMs: Int = 2_000,
        ttl: Int = 64,
    ): EchoReply {
        val isV6 = address is Inet6Address
        val identifier = Random.nextInt(1, 0xFFFF)
        val magic = Random.nextLong()
        val clampedPayload = payloadSize.coerceIn(MIN_PAYLOAD, MAX_PAYLOAD)

        var fd: FileDescriptor? = null
        return try {
            fd = openSocket(isV6)
            configureSocket(fd, isV6, timeoutMs, ttl)

            val packet = buildEchoRequest(isV6, identifier, sequence, clampedPayload, magic)
            val startNs = System.nanoTime()
            Os.sendto(fd, ByteBuffer.wrap(packet), 0, address, 0)

            awaitReply(
                fd = fd,
                isV6 = isV6,
                sequence = sequence,
                magic = magic,
                startNs = startNs,
                timeoutMs = timeoutMs,
                payloadBytes = clampedPayload + ICMP_HEADER_SIZE,
            )
        } catch (e: ErrnoException) {
            EchoReply(
                status = mapErrno(e),
                rttMs = null,
                fromAddress = address.hostAddress,
                ttl = null,
                payloadBytes = 0,
                detail = describeErrno(e),
            )
        } catch (e: Exception) {
            EchoReply(
                status = ProbeErrorMapper.statusFor(e),
                rttMs = null,
                fromAddress = address.hostAddress,
                ttl = null,
                payloadBytes = 0,
                detail = ProbeErrorMapper.detailFor(e),
            )
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    /**
     * Cheap capability probe: opens and immediately closes an ICMP socket.
     * Used once at startup to decide whether to prefer sockets or the `ping` binary.
     */
    fun isSupported(): Boolean = try {
        val fd = openSocket(isV6 = false)
        Os.close(fd)
        true
    } catch (e: ErrnoException) {
        false
    } catch (e: Exception) {
        false
    }

    private fun openSocket(isV6: Boolean): FileDescriptor = if (isV6) {
        Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMPV6)
    } else {
        Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
    }

    private fun configureSocket(fd: FileDescriptor, isV6: Boolean, timeoutMs: Int, ttl: Int) {
        runCatching {
            Os.setsockoptTimeval(
                fd,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(timeoutMs.toLong()),
            )
        }
        runCatching {
            if (isV6) {
                Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_UNICAST_HOPS, ttl)
            } else {
                Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, ttl)
            }
        }
    }

    /**
     * ICMP echo request layout (RFC 792 / RFC 4443):
     *
     * ```
     *  0      7 8     15 16            31
     * +--------+--------+----------------+
     * |  type  |  code  |    checksum    |
     * +--------+--------+----------------+
     * |   identifier    | sequence number|
     * +-----------------+----------------+
     * |            payload ...           |
     * ```
     */
    private fun buildEchoRequest(
        isV6: Boolean,
        identifier: Int,
        sequence: Int,
        payloadSize: Int,
        magic: Long,
    ): ByteArray {
        val packet = ByteArray(ICMP_HEADER_SIZE + payloadSize)
        packet[0] = if (isV6) ICMP6_ECHO_REQUEST else ICMP4_ECHO_REQUEST
        packet[1] = 0
        packet[2] = 0 // checksum placeholder
        packet[3] = 0
        packet[4] = (identifier ushr 8).toByte()
        packet[5] = identifier.toByte()
        packet[6] = (sequence ushr 8).toByte()
        packet[7] = sequence.toByte()

        // First 8 payload bytes carry a magic cookie so we can recognise our own echo.
        for (i in 0 until minOf(8, payloadSize)) {
            packet[ICMP_HEADER_SIZE + i] = ((magic ushr (8 * i)) and 0xFF).toByte()
        }
        // Remaining bytes follow the classic incrementing pattern used by iputils.
        for (i in 8 until payloadSize) {
            packet[ICMP_HEADER_SIZE + i] = (i and 0xFF).toByte()
        }

        if (!isV6) {
            // The kernel computes the ICMPv6 checksum for us, but not the ICMPv4 one.
            val sum = checksum(packet, packet.size)
            packet[2] = (sum ushr 8).toByte()
            packet[3] = sum.toByte()
        }
        return packet
    }

    private fun awaitReply(
        fd: FileDescriptor,
        isV6: Boolean,
        sequence: Int,
        magic: Long,
        startNs: Long,
        timeoutMs: Int,
        payloadBytes: Int,
    ): EchoReply {
        val buffer = ByteArray(RECEIVE_BUFFER)
        val deadlineNs = startNs + timeoutMs * 1_000_000L
        val pollFd = StructPollfd().apply {
            this.fd = fd
            this.events = OsConstants.POLLIN.toShort()
        }
        val pollArray = arrayOf(pollFd)

        while (true) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0) {
                return EchoReply(ProbeStatus.TIMEOUT, null, null, null, 0)
            }
            val remainingMs = (remainingNs / 1_000_000L).toInt().coerceAtLeast(1)

            val ready = try {
                Os.poll(pollArray, remainingMs)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return EchoReply(mapErrno(e), null, null, null, 0, describeErrno(e))
            }
            if (ready == 0) {
                return EchoReply(ProbeStatus.TIMEOUT, null, null, null, 0)
            }

            val source = InetSocketAddress(0)
            val byteBuffer = ByteBuffer.wrap(buffer)
            val read = try {
                Os.recvfrom(fd, byteBuffer, 0, source)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                // EWOULDBLOCK is an alias of EAGAIN on Linux and is not exposed by OsConstants.
                if (e.errno == OsConstants.EAGAIN) {
                    return EchoReply(ProbeStatus.TIMEOUT, null, null, null, 0)
                }
                return EchoReply(mapErrno(e), null, null, null, 0, describeErrno(e))
            }
            if (read <= 0) continue

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            val parsed = parseReply(buffer, read, isV6, sequence, magic) ?: continue

            return when (parsed.type) {
                ReplyType.ECHO_REPLY -> EchoReply(
                    status = ProbeStatus.SUCCESS,
                    rttMs = elapsedMs,
                    fromAddress = source.address?.hostAddress,
                    ttl = parsed.ttl,
                    payloadBytes = payloadBytes,
                )

                ReplyType.TTL_EXPIRED -> EchoReply(
                    status = ProbeStatus.TTL_EXPIRED,
                    rttMs = elapsedMs,
                    fromAddress = source.address?.hostAddress,
                    ttl = parsed.ttl,
                    payloadBytes = 0,
                    detail = "Time to live exceeded",
                )

                ReplyType.UNREACHABLE -> EchoReply(
                    status = ProbeStatus.UNREACHABLE,
                    rttMs = null,
                    fromAddress = source.address?.hostAddress,
                    ttl = parsed.ttl,
                    payloadBytes = 0,
                    detail = "Destination unreachable (code ${parsed.code})",
                )
            }
        }
    }

    private enum class ReplyType { ECHO_REPLY, TTL_EXPIRED, UNREACHABLE }

    private data class ParsedReply(val type: ReplyType, val code: Int, val ttl: Int?)

    /**
     * Datagram ICMP sockets usually strip the IP header, but some kernels keep it.
     * Detect and skip it, then validate that the echo belongs to this request.
     */
    private fun parseReply(
        buffer: ByteArray,
        length: Int,
        isV6: Boolean,
        sequence: Int,
        magic: Long,
    ): ParsedReply? {
        var offset = 0
        var ttl: Int? = null

        if (!isV6 && length >= 20 && (buffer[0].toInt() and 0xF0) == 0x40) {
            val headerLength = (buffer[0].toInt() and 0x0F) * 4
            if (headerLength in 20..60 && length > headerLength) {
                ttl = buffer[8].toInt() and 0xFF
                offset = headerLength
            }
        }
        if (length - offset < ICMP_HEADER_SIZE) return null

        val type = buffer[offset].toInt() and 0xFF
        val code = buffer[offset + 1].toInt() and 0xFF

        val echoReplyType = if (isV6) ICMP6_ECHO_REPLY_INT else ICMP4_ECHO_REPLY_INT
        val timeExceededType = if (isV6) 3 else 11
        val unreachableType = if (isV6) 1 else 3

        return when (type) {
            echoReplyType -> {
                val replySequence =
                    ((buffer[offset + 6].toInt() and 0xFF) shl 8) or (buffer[offset + 7].toInt() and 0xFF)
                if (replySequence != sequence) return null
                if (!magicMatches(buffer, offset + ICMP_HEADER_SIZE, length, magic)) return null
                ParsedReply(ReplyType.ECHO_REPLY, code, ttl)
            }

            timeExceededType -> ParsedReply(ReplyType.TTL_EXPIRED, code, ttl)
            unreachableType -> ParsedReply(ReplyType.UNREACHABLE, code, ttl)
            else -> null
        }
    }

    private fun magicMatches(buffer: ByteArray, start: Int, length: Int, magic: Long): Boolean {
        if (start + 8 > length) return true // payload smaller than the cookie; accept on sequence only
        for (i in 0 until 8) {
            val expected = ((magic ushr (8 * i)) and 0xFF).toByte()
            if (buffer[start + i] != expected) return false
        }
        return true
    }

    /** Standard internet checksum: 16-bit one's complement of the one's complement sum. */
    private fun checksum(data: ByteArray, length: Int): Int {
        var sum = 0L
        var index = 0
        while (index + 1 < length) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < length) {
            sum += (data[index].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun mapErrno(e: ErrnoException): ProbeStatus = when (e.errno) {
        OsConstants.EACCES, OsConstants.EPERM -> ProbeStatus.PERMISSION_DENIED
        OsConstants.ENETUNREACH, OsConstants.ENETDOWN -> ProbeStatus.NETWORK_UNAVAILABLE
        OsConstants.EHOSTUNREACH, OsConstants.ECONNREFUSED -> ProbeStatus.UNREACHABLE
        OsConstants.ETIMEDOUT, OsConstants.EAGAIN -> ProbeStatus.TIMEOUT
        OsConstants.EAFNOSUPPORT, OsConstants.EPROTONOSUPPORT -> ProbeStatus.PROTOCOL_ERROR
        else -> ProbeStatus.ERROR
    }

    private fun describeErrno(e: ErrnoException): String =
        "errno=" + OsConstants.errnoName(e.errno) + " " + (e.message ?: "")

    companion object {
        const val ICMP_HEADER_SIZE = 8
        const val DEFAULT_PAYLOAD = 32
        const val MIN_PAYLOAD = 0
        const val MAX_PAYLOAD = 1_472
        private const val RECEIVE_BUFFER = 2_048

        private const val ICMP4_ECHO_REQUEST: Byte = 8
        // Type 128 overflows a signed Byte; -128 is the very same 0x80 octet on the wire.
        private const val ICMP6_ECHO_REQUEST: Byte = -128
        private const val ICMP4_ECHO_REPLY_INT = 0
        private const val ICMP6_ECHO_REPLY_INT = 129
    }
}
