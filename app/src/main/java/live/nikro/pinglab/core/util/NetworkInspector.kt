package live.nikro.pinglab.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import live.nikro.pinglab.core.model.CellularLink
import live.nikro.pinglab.core.model.MobileGeneration
import live.nikro.pinglab.core.model.NetworkDetail
import live.nikro.pinglab.core.model.NetworkStatus
import live.nikro.pinglab.core.model.TransportKind
import live.nikro.pinglab.core.model.WifiLink
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Reads the state of the device's own connectivity: which transport is up, what the local
 * address and gateway are, and - for mobile data - which radio technology the modem is on.
 *
 * Everything here is best effort and permission free. The app asks for nothing beyond
 * INTERNET, ACCESS_NETWORK_STATE and ACCESS_WIFI_STATE, so every call that the platform may
 * refuse is wrapped in [runCatching] and degrades to "unknown" instead of throwing.
 */
class NetworkInspector(context: Context) {

    private val appContext = context.applicationContext

    private val connectivityManager: ConnectivityManager?
        get() = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val telephonyManager: TelephonyManager?
        get() = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val wifiManager: WifiManager?
        get() = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // ------------------------------------------------------------------ route level status

    /** One-shot snapshot, used when a screen opens before the callback has fired. */
    fun currentStatus(): NetworkStatus {
        val manager = connectivityManager ?: return NetworkStatus.OFFLINE
        val network = manager.activeNetwork ?: return NetworkStatus.OFFLINE
        return buildStatus(manager, network)
    }

    /**
     * Emits a new [NetworkStatus] whenever the default network, its capabilities or its link
     * properties change. Wi-Fi to LTE handovers land here as a capabilities change.
     */
    fun observe(): Flow<NetworkStatus> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(NetworkStatus.OFFLINE)
            awaitClose { }
            return@callbackFlow
        }

        fun push() {
            val network = manager.activeNetwork
            trySend(if (network == null) NetworkStatus.OFFLINE else buildStatus(manager, network))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = push()
            override fun onLost(network: Network) = push()
            override fun onUnavailable() = push()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = push()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                push()
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching { manager.registerNetworkCallback(request, callback) }

        push()

        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun buildStatus(manager: ConnectivityManager, network: Network): NetworkStatus {
        val capabilities = manager.getNetworkCapabilities(network)
        val linkProperties = manager.getLinkProperties(network)

        val connected = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val label = when {
            capabilities == null -> "Unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                cellularShortLabel()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Unknown"
        }

        var localV4: String? = null
        var localV6: String? = null
        linkProperties?.linkAddresses?.forEach { linkAddress ->
            val address = linkAddress.address
            when {
                address is Inet4Address && localV4 == null -> localV4 = address.hostAddress
                address is Inet6Address && !address.isLinkLocalAddress && localV6 == null ->
                    localV6 = address.hostAddress
            }
        }

        val gateway = linkProperties?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway
            ?.hostAddress

        return NetworkStatus(
            isConnected = connected,
            transportLabel = label,
            isMetered = runCatching { manager.isActiveNetworkMetered }.getOrDefault(false),
            isVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            localAddress = localV4 ?: localV6,
            gateway = gateway,
            dnsServers = linkProperties?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
            linkDownstreamKbps = capabilities?.linkDownstreamBandwidthKbps ?: 0,
            linkUpstreamKbps = capabilities?.linkUpstreamBandwidthKbps ?: 0,
        )
    }

    /**
     * Short label for the mobile transport, used by the live screen. It reads the technology
     * directly, which only succeeds when the system is willing to name it; otherwise the
     * generic "Mobile data" is honest about what we know.
     */
    private fun cellularShortLabel(): String {
        val telephony = telephonyManager ?: return MobileGeneration.UNKNOWN.label
        val networkType = runCatching { telephony.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        return techLabel(networkType, OVERRIDE_NONE) ?: MobileGeneration.UNKNOWN.label
    }

    // ------------------------------------------------------------------ transport detail

    /**
     * Mutable snapshot of the radio, filled in by the telephony callbacks while
     * [observeDetail] is collected. Written from the main executor, read from the flow, hence
     * the volatile fields.
     */
    private class RadioState {
        @Volatile
        var networkType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN

        /** TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, spelled out for pre-API-30. */
        @Volatile
        var overrideType: Int = 0

        @Volatile
        var level: Int = -1

        @Volatile
        var dbm: Int? = null

        @Volatile
        var displayInfoSeen: Boolean = false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class DisplayInfoCallback(
        private val radio: RadioState,
        private val onChange: () -> Unit,
    ) : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            radio.networkType = telephonyDisplayInfo.networkType
            radio.overrideType = telephonyDisplayInfo.overrideNetworkType
            radio.displayInfoSeen = true
            onChange()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class SignalStrengthCallback(
        private val radio: RadioState,
        private val onChange: () -> Unit,
    ) : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            radio.level = signalStrength.level
            radio.dbm = signalStrength.cellSignalStrengths.firstOrNull()?.dbm
            onChange()
        }
    }

    /** One-shot detail snapshot without any registered callback. */
    fun currentDetail(): NetworkDetail = buildDetail(RadioState())

    /**
     * Emits transport detail: operator, radio technology and signal level on mobile data, band
     * and negotiated link speed on Wi-Fi.
     *
     * Three sources are merged:
     *  - the connectivity callback fires when the default transport changes, so a Wi-Fi to LTE
     *    handover repaints the card immediately;
     *  - [TelephonyCallback.DisplayInfoListener] (Android 12+) names the radio technology
     *    *including the carrier's marketing override*, which is how the status bar can show 5G
     *    while the radio is technically on LTE with EN-DC. Since Android 12 this listener
     *    needs no permission at all for apps compiled against SDK 31 or newer;
     *  - [TelephonyCallback.SignalStrengthsListener] keeps the bar count fresh.
     *
     * Below Android 12 the technology is only reachable through
     * [TelephonyManager.getDataNetworkType], which requires READ_PHONE_STATE. This app does
     * not ask for a permission that intrusive, so on older phones the card shows the generic
     * "Mobile data" plus the operator, signal and bandwidth estimate.
     */
    fun observeDetail(): Flow<NetworkDetail> = callbackFlow {
        val radio = RadioState()
        val manager = connectivityManager

        fun push() {
            trySend(buildDetail(radio))
        }

        val telephony = telephonyManager
        // The returned lambda unregisters whatever managed to register. Keeping every API-31
        // type inside an annotated helper is what lets a minSdk-26 build stay lint clean.
        val stopTelephonyWatch: () -> Unit =
            if (telephony != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startTelephonyWatch(telephony, radio) { push() }
            } else {
                { }
            }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = push()
            override fun onLost(network: Network) = push()
            override fun onUnavailable() = push()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = push()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                push()
        }

        if (manager != null) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { manager.registerNetworkCallback(request, networkCallback) }
        }

        push()

        awaitClose {
            stopTelephonyWatch()
            if (manager != null) {
                runCatching { manager.unregisterNetworkCallback(networkCallback) }
            }
        }
    }.distinctUntilChanged()

    /**
     * Registers both telephony listeners and hands back a stop function.
     *
     * They go in as two separate callbacks so that a platform refusing one still leaves the
     * other working, and the whole helper sits behind [RequiresApi] so none of the Android 12
     * telephony classes are touched on older releases.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startTelephonyWatch(
        telephony: TelephonyManager,
        radio: RadioState,
        onChange: () -> Unit,
    ): () -> Unit {
        val executor = appContext.mainExecutor
        val registered = mutableListOf<TelephonyCallback>()

        val display = DisplayInfoCallback(radio, onChange)
        if (runCatching { telephony.registerTelephonyCallback(executor, display) }.isSuccess) {
            registered += display
        }

        val signal = SignalStrengthCallback(radio, onChange)
        if (runCatching { telephony.registerTelephonyCallback(executor, signal) }.isSuccess) {
            registered += signal
        }

        return {
            registered.forEach { callback ->
                runCatching { telephony.unregisterTelephonyCallback(callback) }
            }
        }
    }

    private fun buildDetail(radio: RadioState): NetworkDetail {
        val manager = connectivityManager ?: return NetworkDetail.NONE
        val network = manager.activeNetwork ?: return NetworkDetail.NONE
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkDetail.NONE

        // Wi-Fi and cellular are tested before VPN deliberately: a tunnel advertises its own
        // transport *and* the one it runs over, and the physical link is what shapes latency.
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                NetworkDetail(kind = TransportKind.WIFI, wifi = readWifi(capabilities))

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                NetworkDetail(kind = TransportKind.CELLULAR, cellular = readCellular(radio))

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkDetail(kind = TransportKind.ETHERNET)

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                NetworkDetail(kind = TransportKind.VPN)

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ->
                NetworkDetail(kind = TransportKind.BLUETOOTH)

            else -> NetworkDetail(kind = TransportKind.OTHER)
        }
    }

    private fun readCellular(radio: RadioState): CellularLink {
        val telephony = telephonyManager ?: return CellularLink()

        val carrier = listOfNotNull(
            runCatching { telephony.networkOperatorName }.getOrNull(),
            runCatching { telephony.simOperatorName }.getOrNull(),
        ).firstOrNull { it.isNotBlank() }?.trim()

        val roaming = runCatching { telephony.isNetworkRoaming }.getOrDefault(false)

        // Preferred source is the display-info callback. Only if it has not reported yet do we
        // try the direct read, which throws SecurityException without READ_PHONE_STATE.
        var networkType = radio.networkType
        var readable = radio.displayInfoSeen
        if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            val direct = runCatching { telephony.dataNetworkType }.getOrNull()
            if (direct != null && direct != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                networkType = direct
                readable = true
            }
        }

        var level = radio.level
        var dbm = radio.dbm
        if ((level < 0 || dbm == null) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val strength = runCatching { telephony.signalStrength }.getOrNull()
            if (strength != null) {
                if (level < 0) level = strength.level
                if (dbm == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dbm = runCatching { strength.cellSignalStrengths.firstOrNull()?.dbm }.getOrNull()
                }
            }
        }

        return CellularLink(
            carrier = carrier?.takeIf { it.isNotBlank() },
            techLabel = techLabel(networkType, radio.overrideType),
            generation = generationOf(networkType, radio.overrideType),
            roaming = roaming,
            signalLevel = if (level in 0..4) level else -1,
            signalDbm = dbm?.takeIf { it < 0 && it > MIN_PLAUSIBLE_DBM },
            techReadable = readable,
        )
    }

    private fun readWifi(capabilities: NetworkCapabilities): WifiLink {
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            runCatching { wifiManager?.connectionInfo }.getOrNull()
        }
        if (info == null) return WifiLink()

        val frequency = runCatching { info.frequency }.getOrDefault(0)
        val linkSpeed = runCatching { info.linkSpeed }.getOrDefault(-1)
        val rssi = runCatching { info.rssi }.getOrNull()
            ?.takeIf { it < 0 && it > MIN_PLAUSIBLE_DBM }

        val level = when {
            rssi == null -> -1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                runCatching { wifiManager?.calculateSignalLevel(rssi) }.getOrNull() ?: -1

            else -> {
                @Suppress("DEPRECATION")
                WifiManager.calculateSignalLevel(rssi, WIFI_SIGNAL_LEVELS)
            }
        }

        return WifiLink(
            bandLabel = bandLabel(frequency),
            frequencyMhz = frequency,
            linkSpeedMbps = linkSpeed,
            rssiDbm = rssi,
            signalLevel = if (level in 0..4) level else -1,
        )
    }

    /**
     * Maps the radio technology to the label a user recognises.
     *
     * The carrier override wins when present: Android itself upgrades the indicator that way,
     * so a phone camped on LTE with 5G non-standalone shows "5G", exactly like the status bar.
     */
    private fun techLabel(networkType: Int, overrideType: Int): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (overrideType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> return "5G+"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> return "5G"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> return "LTE Pro"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> return "LTE+"
            }
        }
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "VoWiFi"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            -> "H"

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            -> "3G"

            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM,
            -> "2G"

            else -> null
        }
    }

    private fun generationOf(networkType: Int, overrideType: Int): MobileGeneration {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (
                overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                )
        ) {
            return MobileGeneration.G5
        }
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> MobileGeneration.G5
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN,
            -> MobileGeneration.G4

            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            -> MobileGeneration.G3

            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM,
            -> MobileGeneration.G2

            else -> MobileGeneration.UNKNOWN
        }
    }

    /** 6 GHz starts at 5955 MHz, the 5 GHz band covers 4900..5895, 2.4 GHz starts at 2401. */
    private fun bandLabel(frequencyMhz: Int): String? = when {
        frequencyMhz >= 5955 -> "6 GHz"
        frequencyMhz >= 4900 -> "5 GHz"
        frequencyMhz >= 2400 -> "2.4 GHz"
        else -> null
    }

    // ------------------------------------------------------------------ helpers

    /** Default gateway of the active network, handy for the "ping my router" shortcut. */
    fun defaultGateway(): String? = currentStatus().gateway

    /** DNS servers the system hands out, used to seed the DNS tool. */
    fun dnsServers(): List<String> = currentStatus().dnsServers

    /** Binding a socket to a specific network needs API 23; kept for the tools screen. */
    val supportsPerNetworkBinding: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    private companion object {
        /** TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE without touching the API-30 class. */
        const val OVERRIDE_NONE = 0

        /** Anything below this is a sentinel such as WifiInfo.INVALID_RSSI (-127). */
        const val MIN_PLAUSIBLE_DBM = -160

        /** Bucket count for the pre-API-30 static signal level helper. */
        const val WIFI_SIGNAL_LEVELS = 5
    }
}
