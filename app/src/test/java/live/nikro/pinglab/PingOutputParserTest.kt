package live.nikro.pinglab

import live.nikro.pinglab.core.net.PingOutputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PingOutputParserTest {

    @Test
    fun `parses a plain reply line`() {
        val sample = PingOutputParser.parseSample(
            "64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=23.4 ms",
        )
        assertNotNull(sample)
        assertEquals(64, sample!!.bytes)
        assertEquals("8.8.8.8", sample.fromAddress)
        assertEquals(1, sample.sequence)
        assertEquals(117, sample.ttl)
        assertEquals(23.4, sample.timeMs, 0.001)
    }

    @Test
    fun `parses a reply that carries a resolved hostname`() {
        val sample = PingOutputParser.parseSample(
            "64 bytes from dns.google (8.8.8.8): icmp_seq=7 ttl=59 time=11.9 ms",
        )
        assertNotNull(sample)
        assertEquals("8.8.8.8", sample!!.fromAddress)
        assertEquals(7, sample.sequence)
        assertEquals(11.9, sample.timeMs, 0.001)
    }

    @Test
    fun `parses the toybox seq spelling and sub millisecond times`() {
        val sample = PingOutputParser.parseSample(
            "64 bytes from 192.168.1.1: seq=3 ttl=64 time<0.5 ms",
        )
        assertNotNull(sample)
        assertEquals(3, sample!!.sequence)
        assertEquals(0.5, sample.timeMs, 0.001)
    }

    @Test
    fun `non reply lines are ignored`() {
        assertNull(PingOutputParser.parseSample("PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data."))
        assertNull(PingOutputParser.parseSample(""))
        assertNull(PingOutputParser.parseSample("--- 8.8.8.8 ping statistics ---"))
    }

    @Test
    fun `ttl exceeded is classified`() {
        assertEquals(
            PingOutputParser.Failure.TTL_EXCEEDED,
            PingOutputParser.classifyFailure("From 10.0.0.1 icmp_seq=1 Time to live exceeded"),
        )
    }

    @Test
    fun `unknown host is classified before generic unreachable`() {
        assertEquals(
            PingOutputParser.Failure.UNKNOWN_HOST,
            PingOutputParser.classifyFailure("ping: unknown host nope.invalid"),
        )
        assertEquals(
            PingOutputParser.Failure.UNKNOWN_HOST,
            PingOutputParser.classifyFailure("ping: bad address 'nope.invalid'"),
        )
    }

    @Test
    fun `network and permission errors are classified`() {
        assertEquals(
            PingOutputParser.Failure.NETWORK_UNREACHABLE,
            PingOutputParser.classifyFailure("connect: Network is unreachable"),
        )
        assertEquals(
            PingOutputParser.Failure.PERMISSION_DENIED,
            PingOutputParser.classifyFailure("ping: socket: Operation not permitted"),
        )
        assertEquals(
            PingOutputParser.Failure.UNREACHABLE,
            PingOutputParser.classifyFailure("From 10.0.0.1: Destination Host Unreachable"),
        )
        assertEquals(
            PingOutputParser.Failure.NONE,
            PingOutputParser.classifyFailure("64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=23.4 ms"),
        )
    }

    @Test
    fun `source address is extracted from error lines`() {
        assertEquals(
            "10.0.0.1",
            PingOutputParser.extractSourceAddress("From 10.0.0.1 icmp_seq=1 Time to live exceeded"),
        )
        assertEquals(
            "10.0.0.1",
            PingOutputParser.extractSourceAddress("From gateway.lan (10.0.0.1) icmp_seq=2 Time to live exceeded"),
        )
    }

    @Test
    fun `summary block is parsed with rtt line`() {
        val output = """
            PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
            64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=21.1 ms
            64 bytes from 8.8.8.8: icmp_seq=2 ttl=117 time=26.0 ms

            --- 8.8.8.8 ping statistics ---
            5 packets transmitted, 4 received, 20% packet loss, time 4006ms
            rtt min/avg/max/mdev = 21.100/23.400/26.000/1.902 ms
        """.trimIndent()

        val summary = PingOutputParser.parseSummary(output)
        assertNotNull(summary)
        assertEquals(5, summary!!.transmitted)
        assertEquals(4, summary.received)
        assertEquals(20.0, summary.lossPercent, 0.001)
        assertEquals(21.1, summary.minMs!!, 0.001)
        assertEquals(23.4, summary.avgMs!!, 0.001)
        assertEquals(26.0, summary.maxMs!!, 0.001)
        assertEquals(1.902, summary.mdevMs!!, 0.001)
    }

    @Test
    fun `summary falls back to computed loss`() {
        val summary = PingOutputParser.parseSummary("10 packets transmitted, 8 received")
        assertNotNull(summary)
        assertEquals(20.0, summary!!.lossPercent, 0.001)
        assertNull(summary.avgMs)
    }

    @Test
    fun `no summary in partial output`() {
        assertNull(PingOutputParser.parseSummary("64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=23.4 ms"))
    }

    @Test
    fun `resolved address comes from the banner`() {
        assertEquals(
            "142.250.74.110",
            PingOutputParser.parseResolvedAddress("PING google.com (142.250.74.110) 56(84) bytes of data."),
        )
        assertEquals(
            "1.1.1.1",
            PingOutputParser.parseResolvedAddress("PING 1.1.1.1 56(84) bytes of data."),
        )
    }
}
