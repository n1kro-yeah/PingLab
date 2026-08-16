package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN magic packet sender.
 *
 * The packet is six `0xFF` bytes followed by the target MAC repeated sixteen times, sent as
 * a UDP broadcast to port 9 (or 7). It is fired several times because a single broadcast is
 * easy to lose on Wi-Fi, and the protocol has no acknowledgement of any kind.
 */
class WolSender {

    data class Result(val success: Boolean, val message: String)

    suspend fun wake(
        mac: String,
        broadcast: String = DEFAULT_BROADCAST,
        port: Int = DEFAULT_PORT,
        repeats: Int = 3,
    ): Result = withContext(Dispatchers.IO) {
        val hardwareAddress = parseMac(mac)
            ?: return@withContext Result(false, "MAC must look like AA:BB:CC:DD:EE:FF")
        val destination = broadcast.trim().ifEmpty { DEFAULT_BROADCAST }
        val target = runCatching { InetAddress.getByName(destination) }.getOrNull()
            ?: return@withContext Result(false, "cannot resolve " + destination)

        val payload = ByteArray(6 + 16 * hardwareAddress.size)
        for (index in 0 until 6) payload[index] = 0xFF.toByte()
        for (repeat in 0 until 16) {
            System.arraycopy(hardwareAddress, 0, payload, 6 + repeat * hardwareAddress.size, hardwareAddress.size)
        }

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            val attempts = repeats.coerceIn(1, 10)
            for (attempt in 0 until attempts) {
                socket.send(DatagramPacket(payload, payload.size, target, port))
                if (attempt < attempts - 1) delay(150)
            }
            Result(
                true,
                "Sent " + attempts + " magic packets to " + formatMac(hardwareAddress) +
                    " via " + destination + " port " + port,
            )
        } catch (e: Exception) {
            Result(false, (e.message ?: "send failed").take(160))
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Accepts `AA:BB:CC:DD:EE:FF`, `aa-bb-cc-dd-ee-ff` and `aabbccddeeff`. */
    fun parseMac(raw: String): ByteArray? {
        val cleaned = raw.trim().filter { it.isLetterOrDigit() }
        if (cleaned.length != 12) return null
        val bytes = ByteArray(6)
        for (index in 0 until 6) {
            val octet = cleaned.substring(index * 2, index * 2 + 2)
            val value = octet.toIntOrNull(16) ?: return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun formatMac(bytes: ByteArray): String =
        bytes.joinToString(":") { byte ->
            val value = byte.toInt() and 0xFF
            value.toString(16).uppercase().padStart(2, '0')
        }

    companion object {
        const val DEFAULT_BROADCAST = "255.255.255.255"
        const val DEFAULT_PORT = 9
    }
}
