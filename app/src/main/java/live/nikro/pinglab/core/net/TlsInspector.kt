package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.TlsReport
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Inspects what a TLS endpoint actually serves: certificate lifetime, issuer, names,
 * negotiated protocol and cipher, plus the first HTTP response line.
 *
 * The expiry date of a certificate is the single most common reason a service that "was
 * working yesterday" stops working, and no amount of pinging will reveal it, so the check
 * belongs in a network toolbox.
 *
 * The handshake is attempted twice on purpose. First with the system trust store, which
 * answers the question "would a browser or an Android app accept this?". If that fails the
 * handshake is repeated with validation disabled so that the certificate can still be
 * described - an expired or self-signed certificate is exactly what the user needs to see.
 */
class TlsInspector {

    data class Config(
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 5_000,
        val fetchHttp: Boolean = true,
    )

    suspend fun inspect(rawHost: String, port: Int = 443, config: Config = Config()): TlsReport =
        withContext(Dispatchers.IO) {
            val host = normalizeHost(rawHost)
            if (host.isEmpty()) {
                return@withContext TlsReport(host = rawHost, port = port, error = "empty host")
            }
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: return@withContext TlsReport(host = host, port = port, error = "cannot resolve " + host)

            var report = TlsReport(host = host, port = port, address = address.hostAddress)

            // ---- TLS layer -------------------------------------------------------
            var handshake = handshake(address, host, port, config, validate = true)
            var trusted = handshake.error == null
            if (handshake.error != null) {
                val permissive = handshake(address, host, port, config, validate = false)
                if (permissive.error == null) handshake = permissive
            }
            val leaf = handshake.chain.firstOrNull()
            if (leaf != null) {
                val names = subjectAlternativeNames(leaf)
                val notBefore = leaf.notBefore?.time
                val notAfter = leaf.notAfter?.time
                val subjectDn = leaf.subjectX500Principal?.name
                val issuerDn = leaf.issuerX500Principal?.name
                report = report.copy(
                    protocol = handshake.protocol,
                    cipherSuite = handshake.cipherSuite,
                    subject = commonName(subjectDn) ?: subjectDn,
                    issuer = commonName(issuerDn) ?: issuerDn,
                    sans = names,
                    validFrom = notBefore,
                    validTo = notAfter,
                    daysLeft = notAfter?.let { (it - System.currentTimeMillis()) / MILLIS_PER_DAY },
                    selfSigned = subjectDn != null && subjectDn == issuerDn,
                    hostnameMatches = matchesHost(host, names + listOfNotNull(commonName(subjectDn))),
                    chainLength = handshake.chain.size,
                    chainTrusted = trusted,
                    handshakeMs = handshake.elapsedMs,
                )
            } else {
                trusted = false
                report = report.copy(chainTrusted = null, error = handshake.error ?: "no certificate")
            }

            // ---- HTTP layer ------------------------------------------------------
            if (config.fetchHttp) {
                val http = fetchHttp(host, port, secure = leaf != null, config = config)
                report = report.copy(
                    httpStatus = http.status,
                    httpServer = http.server,
                    ttfbMs = http.ttfbMs,
                    redirect = http.location,
                    hsts = http.hsts,
                    error = report.error ?: http.error,
                )
            }
            report
        }

    // ------------------------------------------------------------------ TLS

    private class Handshake(
        val protocol: String? = null,
        val cipherSuite: String? = null,
        val chain: List<X509Certificate> = emptyList(),
        val elapsedMs: Double? = null,
        val error: String? = null,
    )

    private fun handshake(
        address: InetAddress,
        host: String,
        port: Int,
        config: Config,
        validate: Boolean,
    ): Handshake {
        var plain: Socket? = null
        var tls: SSLSocket? = null
        val startNs = System.nanoTime()
        return try {
            val context = SSLContext.getInstance("TLS")
            if (validate) {
                context.init(null, null, null)
            } else {
                context.init(null, arrayOf(DescribeOnlyTrustManager()), SecureRandom())
            }
            plain = Socket()
            plain.connect(InetSocketAddress(address, port), config.connectTimeoutMs)
            plain.soTimeout = config.readTimeoutMs
            tls = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            // SNI: without it many hosts serve a default certificate for the wrong name.
            runCatching {
                val parameters = tls.sslParameters
                parameters.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                tls.sslParameters = parameters
            }
            tls.startHandshake()
            val session = tls.session
            val chain = runCatching {
                session.peerCertificates.mapNotNull { it as? X509Certificate }
            }.getOrDefault(emptyList())
            Handshake(
                protocol = session.protocol,
                cipherSuite = session.cipherSuite,
                chain = chain,
                elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0,
            )
        } catch (e: Exception) {
            Handshake(error = (e.message ?: e.toString()).take(160))
        } finally {
            runCatching { tls?.close() }
            runCatching { plain?.close() }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private class HttpProbe(
        val status: Int? = null,
        val server: String? = null,
        val ttfbMs: Double? = null,
        val location: String? = null,
        val hsts: String? = null,
        val error: String? = null,
    )

    private fun fetchHttp(host: String, port: Int, secure: Boolean, config: Config): HttpProbe {
        var connection: HttpURLConnection? = null
        return try {
            val scheme = if (secure) "https" else "http"
            val url = URL(scheme, host, port, "/")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "PingLab")
                setRequestProperty("Accept", "*/*")
            }
            val startNs = System.nanoTime()
            val status = connection.responseCode
            val ttfb = (System.nanoTime() - startNs) / 1_000_000.0
            HttpProbe(
                status = status,
                server = connection.getHeaderField("Server"),
                ttfbMs = ttfb,
                location = connection.getHeaderField("Location"),
                hsts = connection.getHeaderField("Strict-Transport-Security"),
            )
        } catch (e: Exception) {
            HttpProbe(error = (e.message ?: e.toString()).take(160))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Accepts `example.com`, `example.com:8443` and full URLs pasted from a browser. */
    private fun normalizeHost(raw: String): String {
        var value = raw.trim()
        val separator = ":" + "//"
        val schemeAt = value.indexOf(separator)
        if (schemeAt >= 0) value = value.substring(schemeAt + separator.length)
        value = value.substringBefore('/').substringBefore('?')
        if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']')
        if (value.count { it == ':' } == 1) value = value.substringBefore(':')
        return value
    }

    /** Port hidden inside a pasted host, so `example.com:8443` just works. */
    fun portHint(raw: String): Int? {
        var value = raw.trim()
        val separator = ":" + "//"
        val schemeAt = value.indexOf(separator)
        val scheme = if (schemeAt >= 0) value.substring(0, schemeAt).lowercase(Locale.US) else null
        if (schemeAt >= 0) value = value.substring(schemeAt + separator.length)
        value = value.substringBefore('/')
        val explicit = if (value.count { it == ':' } == 1) value.substringAfter(':').toIntOrNull() else null
        return explicit ?: when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> null
        }
    }

    private fun commonName(dn: String?): String? {
        if (dn == null) return null
        return dn.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun subjectAlternativeNames(certificate: X509Certificate): List<String> =
        runCatching {
            certificate.subjectAlternativeNames
                ?.mapNotNull { entry -> entry.getOrNull(1)?.toString() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?: emptyList()
        }.getOrDefault(emptyList())

    /** RFC 6125 style check, including single-label wildcards. */
    private fun matchesHost(host: String, names: List<String>): Boolean {
        val target = host.lowercase(Locale.US)
        return names.any { raw ->
            val name = raw.lowercase(Locale.US).trim()
            when {
                name.isEmpty() -> false
                name == target -> true
                name.startsWith("*.") -> {
                    val suffix = name.substring(1)
                    val head = target.removeSuffix(suffix)
                    target.endsWith(suffix) && head.isNotEmpty() && !head.contains('.')
                }

                else -> false
            }
        }
    }

    /**
     * Used only for the second, describe-the-certificate handshake. Trust is reported
     * separately from the first validating handshake, so nothing is silently accepted.
     */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class DescribeOnlyTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
