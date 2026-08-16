package live.nikro.pinglab.core.util

import live.nikro.pinglab.core.model.Protocol

/**
 * Parsed representation of whatever the user typed into the target field.
 *
 * Accepts: `8.8.8.8`, `google.com`, `google.com:443`, `https://example.com/health`,
 * `[2001:4860:4860::8888]`, `2001:4860:4860::8888`.
 */
data class ParsedTarget(
    val host: String,
    val port: Int? = null,
    val protocol: Protocol? = null,
    val path: String? = null,
    val isIpv4: Boolean = false,
    val isIpv6: Boolean = false,
) {
    val isLiteralAddress: Boolean get() = isIpv4 || isIpv6
}

sealed interface TargetValidation {
    data class Valid(val target: ParsedTarget) : TargetValidation
    data class Invalid(val reason: Reason) : TargetValidation

    enum class Reason {
        EMPTY,
        BAD_HOSTNAME,
        BAD_PORT,
        BAD_IP_LITERAL,
        TOO_LONG,
    }
}

/**
 * Pure, dependency-free target parsing so it can be unit tested on the JVM.
 */
object HostValidator {

    private const val MAX_HOSTNAME_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    private val LABEL_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun validate(raw: String): TargetValidation {
        val input = raw.trim()
        if (input.isEmpty()) return TargetValidation.Invalid(TargetValidation.Reason.EMPTY)
        if (input.length > 2048) return TargetValidation.Invalid(TargetValidation.Reason.TOO_LONG)

        var working = input
        var protocol: Protocol? = null
        var path: String? = null

        // 1. Strip a URL scheme if present and remember the implied protocol.
        val schemeSeparator = working.indexOf("://")
        if (schemeSeparator > 0) {
            when (working.substring(0, schemeSeparator).lowercase()) {
                "http" -> protocol = Protocol.HTTP
                "https" -> protocol = Protocol.HTTPS
                "ping", "icmp" -> protocol = Protocol.ICMP
                "tcp" -> protocol = Protocol.TCP
                else -> Unit
            }
            working = working.substring(schemeSeparator + 3)
        }

        // 2. Strip credentials (user:pass@host).
        val atIndex = working.lastIndexOf('@')
        if (atIndex >= 0) working = working.substring(atIndex + 1)

        // 3. Split off the path/query.
        val slashIndex = working.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (slashIndex >= 0) {
            val extracted = working.substring(slashIndex)
            path = extracted.ifBlank { "/" }
            working = working.substring(0, slashIndex)
        }
        if (working.isEmpty()) return TargetValidation.Invalid(TargetValidation.Reason.EMPTY)

        // 4. Bracketed IPv6 with optional port: [::1]:8080
        if (working.startsWith("[")) {
            val closing = working.indexOf(']')
            if (closing < 0) return TargetValidation.Invalid(TargetValidation.Reason.BAD_IP_LITERAL)
            val literal = working.substring(1, closing)
            if (!isIpv6Literal(literal)) {
                return TargetValidation.Invalid(TargetValidation.Reason.BAD_IP_LITERAL)
            }
            val remainder = working.substring(closing + 1)
            val port = when {
                remainder.isEmpty() -> null
                remainder.startsWith(":") -> parsePort(remainder.substring(1))
                    ?: return TargetValidation.Invalid(TargetValidation.Reason.BAD_PORT)

                else -> return TargetValidation.Invalid(TargetValidation.Reason.BAD_PORT)
            }
            return TargetValidation.Valid(
                ParsedTarget(literal, port, protocol, path, isIpv6 = true),
            )
        }

        // 5. Bare IPv6 (more than one colon and no brackets => never a host:port pair).
        if (working.count { it == ':' } > 1) {
            return if (isIpv6Literal(working)) {
                TargetValidation.Valid(ParsedTarget(working, null, protocol, path, isIpv6 = true))
            } else {
                TargetValidation.Invalid(TargetValidation.Reason.BAD_IP_LITERAL)
            }
        }

        // 6. host[:port]
        var port: Int? = null
        val colonIndex = working.indexOf(':')
        if (colonIndex >= 0) {
            port = parsePort(working.substring(colonIndex + 1))
                ?: return TargetValidation.Invalid(TargetValidation.Reason.BAD_PORT)
            working = working.substring(0, colonIndex)
        }
        if (working.isEmpty()) return TargetValidation.Invalid(TargetValidation.Reason.EMPTY)

        if (isIpv4Literal(working)) {
            return TargetValidation.Valid(ParsedTarget(working, port, protocol, path, isIpv4 = true))
        }
        if (!isHostname(working)) {
            return TargetValidation.Invalid(TargetValidation.Reason.BAD_HOSTNAME)
        }
        return TargetValidation.Valid(ParsedTarget(working.lowercase(), port, protocol, path))
    }

    fun isValid(raw: String): Boolean = validate(raw) is TargetValidation.Valid

    fun parseOrNull(raw: String): ParsedTarget? =
        (validate(raw) as? TargetValidation.Valid)?.target

    fun parsePort(raw: String): Int? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 5) return null
        if (!trimmed.all { it.isDigit() }) return null
        val value = trimmed.toIntOrNull() ?: return null
        return value.takeIf { it in 1..65_535 }
    }

    fun isIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it.isDigit() } &&
                (part.length == 1 || part[0] != '0') &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    fun isIpv6Literal(value: String): Boolean {
        if (value.isEmpty() || value.length > 45) return false
        // Strip a zone index such as %wlan0.
        val address = value.substringBefore('%')
        if (address.isEmpty()) return false
        if (!address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return false
        }
        if (address == "::") return true

        val doubleColonCount = Regex("::").findAll(address).count()
        if (doubleColonCount > 1) return false
        if (address.startsWith(":") && !address.startsWith("::")) return false
        if (address.endsWith(":") && !address.endsWith("::")) return false

        val compressed = doubleColonCount == 1
        var body = address
        var embeddedIpv4Groups = 0

        // Support the IPv4-mapped form ::ffff:192.168.0.1
        val lastColon = body.lastIndexOf(':')
        if (body.contains('.')) {
            val tail = body.substring(lastColon + 1)
            if (!isIpv4Literal(tail)) return false
            body = body.substring(0, lastColon + 1)
            embeddedIpv4Groups = 2
        }

        val groups = body
            .split(":")
            .filter { it.isNotEmpty() }

        if (groups.any { it.length > 4 }) return false
        if (groups.any { group -> !group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } }) {
            return false
        }

        val total = groups.size + embeddedIpv4Groups
        return if (compressed) total <= 7 else total == 8
    }

    fun isHostname(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_HOSTNAME_LENGTH) return false
        val normalized = value.removeSuffix(".")
        if (normalized.isEmpty()) return false
        val labels = normalized.split('.')
        if (labels.any { it.isEmpty() || it.length > MAX_LABEL_LENGTH }) return false
        // A purely numeric last label means someone typed a broken IP address.
        if (labels.size > 1 && labels.last().all { it.isDigit() }) return false
        return labels.all { LABEL_REGEX.matches(it) }
    }

    /** Suggests the protocol implied by a typed target, used to auto-switch the chip row. */
    fun suggestProtocol(raw: String, current: Protocol): Protocol {
        val parsed = parseOrNull(raw) ?: return current
        parsed.protocol?.let { return it }
        val port = parsed.port ?: return current
        return when (port) {
            80, 8080, 8000 -> Protocol.HTTP
            443, 8443 -> Protocol.HTTPS
            53 -> Protocol.DNS
            else -> Protocol.TCP
        }
    }

    /** Human friendly error text for a validation failure. */
    fun describe(reason: TargetValidation.Reason): String = when (reason) {
        TargetValidation.Reason.EMPTY -> "Enter a host or IP address"
        TargetValidation.Reason.BAD_HOSTNAME -> "That does not look like a valid hostname"
        TargetValidation.Reason.BAD_PORT -> "Port must be a number between 1 and 65535"
        TargetValidation.Reason.BAD_IP_LITERAL -> "Malformed IP address literal"
        TargetValidation.Reason.TOO_LONG -> "Target is too long"
    }
}
