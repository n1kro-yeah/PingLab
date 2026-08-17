package live.nikro.pinglab.core.model

/**
 * Which transport carries the default route right now.
 *
 * A VPN network reports the transport it runs on top of *plus* the VPN transport, so the
 * inspector looks at Wi-Fi and cellular first: a tunnel over LTE is still LTE as far as
 * latency and jitter are concerned.
 */
enum class TransportKind {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
    NONE,
}

/** Coarse radio generation, kept apart from the display label so the UI can colour by it. */
enum class MobileGeneration(val label: String) {
    G2("2G"),
    G3("3G"),
    G4("4G"),
    G5("5G"),
    UNKNOWN("Mobile data"),
}

/**
 * What we know about the mobile connection.
 *
 * @param carrier operator name reported by the network, falling back to the SIM operator
 * @param techLabel what the status bar would show: LTE, LTE+, 5G, H+, EDGE
 * @param generation coarse generation, used for the icon and the accent colour
 * @param roaming true when the device is registered on a roaming network
 * @param signalLevel 0..4 as reported by the modem, or -1 when unavailable
 * @param signalDbm raw RSRP/RSSI in dBm when the platform exposes it
 * @param techReadable false when the system refuses to name the radio technology, which is
 *   what happens below Android 12 unless the app holds READ_PHONE_STATE
 */
data class CellularLink(
    val carrier: String? = null,
    val techLabel: String? = null,
    val generation: MobileGeneration = MobileGeneration.UNKNOWN,
    val roaming: Boolean = false,
    val signalLevel: Int = -1,
    val signalDbm: Int? = null,
    val techReadable: Boolean = false,
) {
    val hasSignal: Boolean get() = signalLevel in 0..4
}

/**
 * What we know about the Wi-Fi link.
 *
 * The SSID is deliberately missing: since Android 10 it is redacted unless the app holds a
 * location permission, and a ping tool has no business asking for one. Band, negotiated link
 * speed and RSSI need nothing beyond ACCESS_WIFI_STATE.
 */
data class WifiLink(
    val bandLabel: String? = null,
    val frequencyMhz: Int = 0,
    val linkSpeedMbps: Int = -1,
    val rssiDbm: Int? = null,
    val signalLevel: Int = -1,
) {
    val hasSignal: Boolean get() = signalLevel in 0..4
}

/**
 * Transport specific detail that complements [NetworkStatus].
 *
 * [NetworkStatus] answers "is there a route and where does it point", this answers "which
 * radio is doing the work and how good is it right now".
 */
data class NetworkDetail(
    val kind: TransportKind = TransportKind.NONE,
    val cellular: CellularLink? = null,
    val wifi: WifiLink? = null,
) {
    val isMobile: Boolean get() = kind == TransportKind.CELLULAR

    /** Card title: the radio technology when the system names it, the transport otherwise. */
    val title: String
        get() = when (kind) {
            TransportKind.CELLULAR -> cellular?.techLabel
                ?: cellular?.generation?.label
                ?: MobileGeneration.UNKNOWN.label
            TransportKind.WIFI -> "Wi-Fi"
            TransportKind.ETHERNET -> "Ethernet"
            TransportKind.VPN -> "VPN"
            TransportKind.BLUETOOTH -> "Bluetooth"
            TransportKind.OTHER -> "Unknown transport"
            TransportKind.NONE -> "Offline"
        }

    /** Bar count for the little indicator, whichever transport is active. */
    val signalLevel: Int
        get() = when (kind) {
            TransportKind.CELLULAR -> cellular?.signalLevel ?: -1
            TransportKind.WIFI -> wifi?.signalLevel ?: -1
            else -> -1
        }

    companion object {
        val NONE = NetworkDetail()
    }
}
