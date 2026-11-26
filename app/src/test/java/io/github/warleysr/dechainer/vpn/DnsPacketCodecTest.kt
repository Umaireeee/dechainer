package io.github.warleysr.dechainer.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsPacketCodecTest {

    private fun buildDnsQuery(txId: Int, domain: String, qType: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // RD=1
        out.write(0x00)
        out.write(0); out.write(1) // QDCOUNT=1
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        for (label in domain.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        out.write((qType ushr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0); out.write(1) // IN
        return out.toByteArray()
    }

    private fun buildIpv4UdpPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45
        pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[8] = 64
        pkt[9] = 17
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        pkt[20] = ((srcPort ushr 8) and 0xFF).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = ((dstPort ushr 8) and 0xFF).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        pkt[24] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        return pkt
    }

    private fun buildIpv6UdpPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val pkt = ByteArray(40 + udpLen)
        pkt[0] = 0x60
        pkt[4] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[5] = (udpLen and 0xFF).toByte()
        pkt[6] = 17
        pkt[7] = 64
        System.arraycopy(srcIp, 0, pkt, 8, 16)
        System.arraycopy(dstIp, 0, pkt, 24, 16)
        pkt[40] = ((srcPort ushr 8) and 0xFF).toByte()
        pkt[41] = (srcPort and 0xFF).toByte()
        pkt[42] = ((dstPort ushr 8) and 0xFF).toByte()
        pkt[43] = (dstPort and 0xFF).toByte()
        pkt[44] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[45] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, 48, payload.size)
        return pkt
    }

    @Test
    fun `parses IPv4 DNS A query and builds 0_0_0_0 blocked response wrapped in IPv4`() {
        val query = buildDnsQuery(0x1234, "PorNHub.com", DnsPacketCodec.TYPE_A)
        val srcIp = byteArrayOf(10, 111, 222.toByte(), 2)
        val dstIp = byteArrayOf(10, 111, 222.toByte(), 1)
        val pkt = buildIpv4UdpPacket(srcIp, dstIp, 54321, 53, query)

        val parsed = DnsPacketCodec.parseIpUdpDns(pkt, pkt.size)
        assertNotNull(parsed)
        assertEquals("pornhub.com", parsed!!.qName)
        assertEquals(DnsPacketCodec.TYPE_A, parsed.qType)
        assertEquals(0x1234, parsed.txId)

        val blockedDns = DnsPacketCodec.buildBlockedDnsPayload(parsed.dnsPayload, parsed.qType, parsed.questionEndOffset)
        assertEquals(0x81, blockedDns[2].toInt() and 0xFF) // QR=1, RD=1
        assertEquals(0x80, blockedDns[3].toInt() and 0xFF) // RA=1, NOERROR
        assertEquals(1, ((blockedDns[6].toInt() and 0xFF) shl 8) or (blockedDns[7].toInt() and 0xFF)) // ANCOUNT=1

        val addrs = DnsPacketCodec.extractAddressRecords(blockedDns, DnsPacketCodec.TYPE_A)
        assertEquals(1, addrs.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), addrs.first())

        val wrapped = DnsPacketCodec.wrapUdpInIpPacket(parsed, blockedDns)
        assertEquals(4, (wrapped[0].toInt() ushr 4) and 0x0F)
        assertArrayEquals(dstIp, wrapped.copyOfRange(12, 16))
        assertArrayEquals(srcIp, wrapped.copyOfRange(16, 20))
    }

    @Test
    fun `parses IPv6 DNS AAAA query and ignores non-DNS port`() {
        val query = buildDnsQuery(0xABCD, "xvideos.com", DnsPacketCodec.TYPE_AAAA)
        val srcIp = ByteArray(16).apply { this[0] = 0xFD.toByte(); this[15] = 2 }
        val dstIp = ByteArray(16).apply { this[0] = 0xFD.toByte(); this[15] = 1 }

        val badPort = buildIpv6UdpPacket(srcIp, dstIp, 40000, 443, query)
        assertNull(DnsPacketCodec.parseIpUdpDns(badPort, badPort.size))

        val goodPkt = buildIpv6UdpPacket(srcIp, dstIp, 40000, 53, query)
        val parsed = DnsPacketCodec.parseIpUdpDns(goodPkt, goodPkt.size)!!
        assertEquals(6, parsed.ipVersion)
        assertEquals("xvideos.com", parsed.qName)
        assertEquals(DnsPacketCodec.TYPE_AAAA, parsed.qType)

        val blocked = DnsPacketCodec.buildBlockedDnsPayload(parsed.dnsPayload, parsed.qType, parsed.questionEndOffset)
        val addrs = DnsPacketCodec.extractAddressRecords(blocked, DnsPacketCodec.TYPE_AAAA)
        assertEquals(1, addrs.size)
        assertArrayEquals(ByteArray(16), addrs.first())

        val wrapped = DnsPacketCodec.wrapUdpInIpPacket(parsed, blocked)
        assertEquals(6, (wrapped[0].toInt() ushr 4) and 0x0F)
        assertArrayEquals(dstIp, wrapped.copyOfRange(8, 24))
        assertArrayEquals(srcIp, wrapped.copyOfRange(24, 40))
    }

    @Test
    fun `rewrites SafeSearch query and patches upstream answer for original question`() {
        val originalQuery = buildDnsQuery(0x5555, "www.google.com.pk", DnsPacketCodec.TYPE_A)
        val rewrittenQuery = DnsPacketCodec.rewriteDnsQueryName(
            originalQuery, "forcesafesearch.google.com", DnsPacketCodec.TYPE_A, DnsPacketCodec.CLASS_IN
        )
        val parsedRewritten = DnsPacketCodec.buildRewrittenAnswerPayload(
            originalQuery = rewrittenQuery,
            questionEndOffset = rewrittenQuery.size,
            requestedQType = DnsPacketCodec.TYPE_A,
            upstreamResponse = buildUpstreamAResponse(0x5555, "forcesafesearch.google.com", byteArrayOf(216.toByte(), 239.toByte(), 38, 120))
        )
        assertNotNull(parsedRewritten)

        val upstream = buildUpstreamAResponse(
            0x5555, "forcesafesearch.google.com", byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        )
        val finalResp = DnsPacketCodec.buildRewrittenAnswerPayload(
            originalQuery = originalQuery,
            questionEndOffset = originalQuery.size,
            requestedQType = DnsPacketCodec.TYPE_A,
            upstreamResponse = upstream
        )
        assertEquals(0x55, finalResp[0].toInt() and 0xFF)
        assertEquals(0x55, finalResp[1].toInt() and 0xFF)
        val qNameInResp = DnsPacketCodec.readName(finalResp, 12)?.first
        assertEquals("www.google.com.pk", qNameInResp)
        val addrs = DnsPacketCodec.extractAddressRecords(finalResp, DnsPacketCodec.TYPE_A)
        assertEquals(1, addrs.size)
        assertArrayEquals(byteArrayOf(216.toByte(), 239.toByte(), 38, 120), addrs.first())
    }

    private fun buildUpstreamAResponse(txId: Int, name: String, ip4: ByteArray): ByteArray {
        val q = buildDnsQuery(txId, name, DnsPacketCodec.TYPE_A)
        val out = ByteArrayOutputStream()
        out.write(q)
        val bytes = out.toByteArray()
        bytes[2] = 0x81.toByte()
        bytes[3] = 0x80.toByte()
        bytes[6] = 0x00
        bytes[7] = 0x01
        val full = ByteArrayOutputStream()
        full.write(bytes)
        full.write(0xC0); full.write(0x0C)
        full.write(0); full.write(1) // A
        full.write(0); full.write(1) // IN
        full.write(0); full.write(0); full.write(0); full.write(60)
        full.write(0); full.write(4)
        full.write(ip4)
        return full.toByteArray()
    }
}
