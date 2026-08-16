package live.nikro.pinglab

import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.util.HostValidator
import live.nikro.pinglab.core.util.TargetValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostValidatorTest {

    @Test
    fun `plain hostname is accepted`() {
        val result = HostValidator.validate("example.com")
        assertTrue(result is TargetValidation.Valid)
        val target = (result as TargetValidation.Valid).target
        assertEquals("example.com", target.host)
        assertNull(target.port)
    }

    @Test
    fun `empty input is rejected`() {
        val result = HostValidator.validate("   ")
        assertTrue(result is TargetValidation.Invalid)
        assertEquals(
            TargetValidation.Reason.EMPTY,
            (result as TargetValidation.Invalid).reason,
        )
    }

    @Test
    fun `host with port keeps both parts`() {
        val target = HostValidator.parseOrNull("example.com:8443")
        assertEquals("example.com", target?.host)
        assertEquals(8443, target?.port)
    }

    @Test
    fun `url form is unwrapped`() {
        val url = "https:" + "//" + "example.org" + "/status"
        val target = HostValidator.parseOrNull(url)
        assertEquals("example.org", target?.host)
        assertEquals(Protocol.HTTPS, target?.protocol)
    }

    @Test
    fun `ipv4 literals are detected`() {
        assertTrue(HostValidator.isIpv4Literal("8.8.8.8"))
        assertTrue(HostValidator.isIpv4Literal("192.168.0.1"))
        assertFalse(HostValidator.isIpv4Literal("256.1.1.1"))
        assertFalse(HostValidator.isIpv4Literal("1.2.3"))
    }

    @Test
    fun `ipv6 literals are detected`() {
        assertTrue(HostValidator.isIpv6Literal("::1"))
        assertTrue(HostValidator.isIpv6Literal("2001:4860:4860::8888"))
        assertFalse(HostValidator.isIpv6Literal("example.com"))
    }

    @Test
    fun `bracketed ipv6 with port parses`() {
        val target = HostValidator.parseOrNull("[2001:4860:4860::8888]:53")
        assertEquals(53, target?.port)
        assertTrue(target?.isIpv6 == true)
    }

    @Test
    fun `hostname rules reject broken labels`() {
        assertFalse(HostValidator.isHostname("-bad.example.com"))
        assertFalse(HostValidator.isHostname("bad-.example.com"))
        assertFalse(HostValidator.isHostname("two..dots.com"))
        assertTrue(HostValidator.isHostname("a-valid.host.name"))
    }

    @Test
    fun `port parsing rejects out of range values`() {
        assertEquals(443, HostValidator.parsePort("443"))
        assertNull(HostValidator.parsePort("0"))
        assertNull(HostValidator.parsePort("70000"))
        assertNull(HostValidator.parsePort("http"))
    }

    @Test
    fun `protocol suggestion follows well known ports`() {
        assertEquals(Protocol.HTTPS, HostValidator.suggestProtocol("example.com:443", Protocol.ICMP))
        assertEquals(Protocol.DNS, HostValidator.suggestProtocol("1.1.1.1:53", Protocol.ICMP))
        assertEquals(Protocol.ICMP, HostValidator.suggestProtocol("example.com", Protocol.ICMP))
    }

    @Test
    fun `every invalid reason has a human readable description`() {
        TargetValidation.Reason.entries.forEach { reason ->
            assertTrue(HostValidator.describe(reason).isNotBlank())
        }
    }
}
