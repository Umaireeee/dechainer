package io.github.warleysr.dechainer.vpn

import java.io.ByteArrayOutputStream

/**
 * Pure Kotlin IPv4/IPv6 + UDP/53 + DNS wire-format parser and response builder.
 * Runs completely on the JVM so packet logic is 100% unit-testable without Android TUN.
 */
object DnsPacketCodec {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1
    private const val DNS_PORT = 53
    private const val PROTO_UDP = 17

    data class ParsedDnsPacket(
        val ipVersion: Int,
        val ipHeaderLength: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val txId: Int,
        val qName: String,
        val qType: Int,
        val qClass: Int,
        val questionEndOffset: Int,
        val dnsPayload: ByteArray
    )

    fun parseIpUdpDns(packet: ByteArray, length: Int): ParsedDnsPacket? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        val srcIp: ByteArray
        val dstIp: ByteArray
        val ipHeaderLen: Int

        when (version) {
            4 -> {
                ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
                if (ipHeaderLen < 20 || length < ipHeaderLen + 8 + 12) return null
                if ((packet[9].toInt() and 0xFF) != PROTO_UDP) return null
                srcIp = packet.copyOfRange(12, 16)
                dstIp = packet.copyOfRange(16, 20)
            }
            6 -> {
                ipHeaderLen = 40
                if (length < ipHeaderLen + 8 + 12) return null
                if ((packet[6].toInt() and 0xFF) != PROTO_UDP) return null
                srcIp = packet.copyOfRange(8, 24)
                dstIp = packet.copyOfRange(24, 40)
            }
            else -> return null
        }

        val udpStart = ipHeaderLen
        val srcPort = readUint16(packet, udpStart)
        val dstPort = readUint16(packet, udpStart + 2)
        if (dstPort != DNS_PORT) return null

        val udpLen = readUint16(packet, udpStart + 4)
        val dnsStart = udpStart + 8
        val available = length - dnsStart
        val dnsLen = (udpLen - 8).coerceAtMost(available)
        if (dnsLen < 12) return null

        val dnsPayload = packet.copyOfRange(dnsStart, dnsStart + dnsLen)
        val parsedQuestion = parseQuestion(dnsPayload) ?: return null

        return ParsedDnsPacket(
            ipVersion = version,
            ipHeaderLength = ipHeaderLen,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            txId = readUint16(dnsPayload, 0),
            qName = parsedQuestion.first,
            qType = parsedQuestion.second,
            qClass = parsedQuestion.third,
            questionEndOffset = parsedQuestion.fourth,
            dnsPayload = dnsPayload
        )
    }

    private data class Quad(val first: String, val second: Int, val third: Int, val fourth: Int)

    private fun parseQuestion(dns: ByteArray): Quad? {
        if (dns.size < 17) return null
        val qdCount = readUint16(dns, 4)
        if (qdCount < 1) return null
        val decoded = readName(dns, 12) ?: return null
        val endOfName = decoded.second
        if (endOfName + 4 > dns.size) return null
        val qType = readUint16(dns, endOfName)
        val qClass = readUint16(dns, endOfName + 2)
        return Quad(decoded.first, qType, qClass, endOfName + 4)
    }

    internal fun readName(dns: ByteArray, startOffset: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var offset = startOffset
        var jumped = false
        var endOffset = -1
        var hops = 0

        while (offset < dns.size && hops++ < 64) {
            val len = dns[offset].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) endOffset = offset + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (offset + 1 >= dns.size) return null
                val ptr = ((len and 0x3F) shl 8) or (dns[offset + 1].toInt() and 0xFF)
                if (!jumped) endOffset = offset + 2
                jumped = true
                offset = ptr
            } else {
                offset++
                if (offset + len > dns.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, offset, len, Charsets.US_ASCII))
                offset += len
            }
        }
        if (endOffset == -1) return null
        return sb.toString().lowercase() to endOffset
    }

    fun buildBlockedDnsPayload(dnsQuery: ByteArray, qType: Int, questionEndOffset: Int): ByteArray {
        val questionEnd = questionEndOffset.coerceIn(12, dnsQuery.size)
        val out = ByteArrayOutputStream(questionEnd + 32)
        // TxID
        out.write(dnsQuery[0].toInt() and 0xFF)
        out.write(dnsQuery[1].toInt() and 0xFF)

        val rd = dnsQuery[2].toInt() and 0x01
        val isAddressQuery = qType == TYPE_A || qType == TYPE_AAAA
        // QR=1, RD copied
        out.write(0x80 or rd)
        // RA=1, RCODE=0 (NOERROR for A/AAAA 0.0.0.0/::) or RCODE=3 (NXDOMAIN for HTTPS/SVCB/etc.)
        out.write(if (isAddressQuery) 0x80 else 0x83)

        // QDCOUNT = 1
        writeUint16(out, 1)
        // ANCOUNT
        writeUint16(out, if (isAddressQuery) 1 else 0)
        // NSCOUNT, ARCOUNT
        writeUint16(out, 0)
        writeUint16(out, 0)

        // Question section verbatim
        out.write(dnsQuery, 12, questionEnd - 12)

        if (isAddressQuery) {
            // Name pointer to offset 12 (0xC00C)
            writeUint16(out, 0xC00C)
            writeUint16(out, qType)
            writeUint16(out, CLASS_IN)
            writeUint32(out, 60L)
            if (qType == TYPE_A) {
                writeUint16(out, 4)
                out.write(byteArrayOf(0, 0, 0, 0))
            } else {
                writeUint16(out, 16)
                out.write(ByteArray(16))
            }
        }
        return out.toByteArray()
    }

    fun rewriteDnsQueryName(dnsQuery: ByteArray, newQName: String, qType: Int, qClass: Int): ByteArray {
        val out = ByteArrayOutputStream(64)
        out.write(dnsQuery[0].toInt() and 0xFF)
        out.write(dnsQuery[1].toInt() and 0xFF)
        // Standard recursive query: RD = 1
        out.write(0x01)
        out.write(0x00)
        writeUint16(out, 1)
        writeUint16(out, 0)
        writeUint16(out, 0)
        writeUint16(out, 0)
        for (label in newQName.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        out.write(0)
        writeUint16(out, qType)
        writeUint16(out, qClass)
        return out.toByteArray()
    }

    fun buildRewrittenAnswerPayload(
        originalQuery: ByteArray,
        questionEndOffset: Int,
        requestedQType: Int,
        upstreamResponse: ByteArray
    ): ByteArray {
        val answers = extractAddressRecords(upstreamResponse, requestedQType)
        val questionEnd = questionEndOffset.coerceIn(12, originalQuery.size)
        val out = ByteArrayOutputStream(questionEnd + answers.size * 32)
        out.write(originalQuery[0].toInt() and 0xFF)
        out.write(originalQuery[1].toInt() and 0xFF)
        val rd = originalQuery[2].toInt() and 0x01
        out.write(0x80 or rd)
        out.write(0x80) // RA=1, NOERROR
        writeUint16(out, 1)
        writeUint16(out, answers.size)
        writeUint16(out, 0)
        writeUint16(out, 0)
        out.write(originalQuery, 12, questionEnd - 12)

        for (rdata in answers) {
            writeUint16(out, 0xC00C)
            writeUint16(out, requestedQType)
            writeUint16(out, CLASS_IN)
            writeUint32(out, 60L)
            writeUint16(out, rdata.size)
            out.write(rdata)
        }
        return out.toByteArray()
    }

    internal fun extractAddressRecords(dnsResponse: ByteArray, targetType: Int): List<ByteArray> {
        if (dnsResponse.size < 12) return emptyList()
        val qdCount = readUint16(dnsResponse, 4)
        val anCount = readUint16(dnsResponse, 6)
        var offset = 12
        repeat(qdCount) {
            val q = readName(dnsResponse, offset) ?: return emptyList()
            offset = q.second + 4
            if (offset > dnsResponse.size) return emptyList()
        }
        val expectedLen = if (targetType == TYPE_A) 4 else if (targetType == TYPE_AAAA) 16 else return emptyList()
        val results = mutableListOf<ByteArray>()
        repeat(anCount) {
            val name = readName(dnsResponse, offset) ?: return results
            offset = name.second
            if (offset + 10 > dnsResponse.size) return results
            val type = readUint16(dnsResponse, offset)
            val rdLen = readUint16(dnsResponse, offset + 8)
            offset += 10
            if (offset + rdLen > dnsResponse.size) return results
            if (type == targetType && rdLen == expectedLen) {
                results += dnsResponse.copyOfRange(offset, offset + rdLen)
            }
            offset += rdLen
        }
        return results
    }

    fun wrapUdpInIpPacket(request: ParsedDnsPacket, dnsResponse: ByteArray): ByteArray {
        val udpLen = 8 + dnsResponse.size
        return if (request.ipVersion == 4) {
            val totalLen = 20 + udpLen
            val pkt = ByteArray(totalLen)
            pkt[0] = 0x45
            pkt[1] = 0x00
            putUint16(pkt, 2, totalLen)
            putUint16(pkt, 4, request.txId and 0xFFFF)
            pkt[6] = 0x40 // DF
            pkt[7] = 0x00
            pkt[8] = 64 // TTL
            pkt[9] = PROTO_UDP.toByte()
            // Swap src & dst IP
            System.arraycopy(request.dstIp, 0, pkt, 12, 4)
            System.arraycopy(request.srcIp, 0, pkt, 16, 4)
            val ipChecksum = ipv4Checksum(pkt, 0, 20)
            putUint16(pkt, 10, ipChecksum)

            // UDP header
            putUint16(pkt, 20, request.dstPort)
            putUint16(pkt, 22, request.srcPort)
            putUint16(pkt, 24, udpLen)
            System.arraycopy(dnsResponse, 0, pkt, 28, dnsResponse.size)
            val udpSum = udpChecksumV4(request.dstIp, request.srcIp, pkt, 20, udpLen)
            putUint16(pkt, 26, udpSum)
            pkt
        } else {
            val totalLen = 40 + udpLen
            val pkt = ByteArray(totalLen)
            pkt[0] = 0x60
            putUint16(pkt, 4, udpLen)
            pkt[6] = PROTO_UDP.toByte()
            pkt[7] = 64
            System.arraycopy(request.dstIp, 0, pkt, 8, 16)
            System.arraycopy(request.srcIp, 0, pkt, 24, 16)

            putUint16(pkt, 40, request.dstPort)
            putUint16(pkt, 42, request.srcPort)
            putUint16(pkt, 44, udpLen)
            System.arraycopy(dnsResponse, 0, pkt, 48, dnsResponse.size)
            val udpSum = udpChecksumV6(request.dstIp, request.srcIp, pkt, 40, udpLen)
            putUint16(pkt, 46, udpSum)
            pkt
        }
    }

    private fun ipv4Checksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + len) {
            if (i != offset + 10) {
                sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            }
            i += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun udpChecksumV4(src: ByteArray, dst: ByteArray, udp: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = sumBytes(src) + sumBytes(dst) + PROTO_UDP + udpLen
        sum += sumUdpSegment(udp, udpOffset, udpLen)
        val result = foldChecksum(sum)
        return if (result == 0) 0xFFFF else result
    }

    private fun udpChecksumV6(src: ByteArray, dst: ByteArray, udp: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = sumBytes(src) + sumBytes(dst) + PROTO_UDP + udpLen
        sum += sumUdpSegment(udp, udpOffset, udpLen)
        val result = foldChecksum(sum)
        return if (result == 0) 0xFFFF else result
    }

    private fun sumBytes(bytes: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < bytes.size) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
        }
        return sum
    }

    private fun sumUdpSegment(buf: ByteArray, offset: Int, len: Int): Long {
        var sum = 0L
        var i = 0
        while (i + 1 < len) {
            if (i != 6) { // skip checksum field at UDP+6
                sum += ((buf[offset + i].toInt() and 0xFF) shl 8) or (buf[offset + i + 1].toInt() and 0xFF)
            }
            i += 2
        }
        if (i < len) {
            sum += (buf[offset + i].toInt() and 0xFF) shl 8
        }
        return sum
    }

    private fun foldChecksum(initial: Long): Int {
        var sum = initial
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readUint16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun putUint16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUint16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeUint32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }
}
