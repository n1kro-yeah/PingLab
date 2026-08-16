package live.nikro.pinglab.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import live.nikro.pinglab.core.model.NetworkStatus
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Wraps [ConnectivityManager] and exposes the active transport as a cold [Flow].
 *
 * The dashboard uses this to explain *why* latency changed \u2014 a jump from Wi-Fi to
 * LTE is usually the answer.
 */
class NetworkInspector(context: Context) {

    private val appContext = context.applicationContext

    private val connectivityManager: ConnectivityManager?
        get() = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun currentStatus(): NetworkStatus {
        val manager = connectivityManager ?: return NetworkStatus.OFFLINE
        val network = manager.activeNetwork ?: return NetworkStatus.OFFLINE
        return buildStatus(manager, network)
    }

    /** Emits a fresh [NetworkStatus] whenever the active network or its properties change. */
    fun observe(): Flow<NetworkStatus> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(NetworkStatus.OFFLINE)
            awaitClose { }
            return@callbackFlow
        }

        fun push() {
            val active = manager.activeNetwork
            trySend(if (active == null) NetworkStatus.OFFLINE else buildStatus(manager, active))
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

        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun buildStatus(manager: ConnectivityManager, network: Network): NetworkStatus {
        val capabilities = manager.getNetworkCapabilities(network)
        val linkProperties = manager.getLinkProperties(network)

        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val transportLabel = when {
            capabilities == null -> "Unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Unknown"
        }

        val addresses = linkProperties?.linkAddresses.orEmpty()
        val localV4 = addresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        val localV6 = addresses.firstOrNull {
            it.address is Inet6Address &&
                !it.address.isLoopbackAddress &&
                !it.address.isLinkLocalAddress
        }
        val local = (localV4 ?: localV6)?.address?.hostAddress

        val gateway = linkProperties?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway
            ?.hostAddress

        val dnsServers = linkProperties?.dnsServers
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

        return NetworkStatus(
            isConnected = connected || capabilities != null,
            transportLabel = transportLabel,
            isMetered = manager.isActiveNetworkMetered,
            isVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            localAddress = local,
            gateway = gateway,
            dnsServers = dnsServers,
            linkDownstreamKbps = capabilities?.linkDownstreamBandwidthKbps ?: 0,
            linkUpstreamKbps = capabilities?.linkUpstreamBandwidthKbps ?: 0,
        )
    }

    /** Best-effort default gateway, used as the "first hop" quick target. */
    fun defaultGateway(): String? = currentStatus().gateway

    /** DNS resolvers handed out by the current network \u2014 great ICMP targets for a baseline. */
    fun dnsServers(): List<String> = currentStatus().dnsServers

    val supportsPerNetworkBinding: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}
