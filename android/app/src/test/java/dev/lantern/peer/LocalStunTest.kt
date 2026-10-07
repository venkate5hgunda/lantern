package dev.lantern.peer

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.zip.CRC32

class LocalStunTest {
    private fun request(): ByteArray = ByteArray(20).apply {
        this[0] = 0
        this[1] = 1
        this[4] = 0x21
        this[5] = 0x12
        this[6] = 0xa4.toByte()
        this[7] = 0x42
        (8..19).forEach { this[it] = it.toByte() }
    }
    private fun u16(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    private fun u32(bytes: ByteArray, offset: Int): Int =
        (u16(bytes, offset) shl 16) or u16(bytes, offset + 2)
    private fun response(address: String, port: Int = 49152) =
        StunCodec.bindingReply(request(), 20, InetAddress.getByName(address), port)!!

    @Test fun `IPv4 binding reply encodes source XOR mapped address and fingerprint`() {
        val answer = response("100.81.4.2")
        assertEquals(40, answer.size)
        assertEquals(0x0101, u16(answer, 0))
        assertEquals(20, u16(answer, 2))
        assertArrayEquals(request().copyOfRange(8, 20), answer.copyOfRange(8, 20))
        assertEquals(0x0020, u16(answer, 20))
        assertEquals(8, u16(answer, 22))
        assertEquals(1, answer[25].toInt())
        assertEquals(49152, u16(answer, 26) xor 0x2112)
        val original = InetAddress.getByName("100.81.4.2").address
        for (i in 0..3) assertEquals(original[i], (answer[28 + i].toInt() xor answer[4 + i].toInt()).toByte())
        assertEquals(0x8028, u16(answer, 32))
        assertEquals(4, u16(answer, 34))
        assertEquals(CRC32().apply { update(answer, 0, 32) }.value.toInt() xor 0x5354554e, u32(answer, 36))
    }

    @Test fun `IPv6 address XOR uses cookie then transaction ID`() {
        val answer = response("2001:db8::7", 4242)
        assertEquals(52, answer.size)
        assertEquals(32, u16(answer, 2))
        assertEquals(2, answer[25].toInt())
        assertEquals(4242, u16(answer, 26) xor 0x2112)
        val source = InetAddress.getByName("2001:db8::7").address
        for (i in 0..15) {
            assertEquals(source[i], (answer[28 + i].toInt() xor answer[4 + i].toInt()).toByte())
        }
        assertEquals(CRC32().apply { update(answer, 0, 44) }.value.toInt() xor 0x5354554e, u32(answer, 48))
    }

    @Test fun `RFC 5769 IPv6 XOR mapped address matches independent wire vector`() {
        val input = request()
        val transaction = "b7e7a701bc34d686fa87dfae".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        System.arraycopy(transaction, 0, input, 8, 12)
        val answer = StunCodec.bindingReply(input, input.size,
            InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853)!!
        val expected = "0002a1470113a9faa5d3f179bc25f4b5bed2b9d9"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, answer.copyOfRange(24, 44))
    }

    @Test fun `malformed length cookie type attributes and ICE checks receive no answer`() {
        val source = InetAddress.getByName("127.0.0.1")
        fun rejects(bytes: ByteArray, size: Int = bytes.size) =
            assertNull(StunCodec.bindingReply(bytes, size, source, 12345))
        rejects(ByteArray(19))
        rejects(ByteArray(1201))
        rejects(request().also { it[1] = 2 })
        rejects(request().also { it[4] = 0 })
        rejects(request().also { it[3] = 4 })
        rejects(request().also { it[3] = 1 })
        rejects(request().also { it[2] = 0x80.toByte() })
        rejects(request().copyOf(24).also { it[3] = 4; it[20] = 0; it[21] = 6 })
        for (type in listOf(0x0006, 0x0008, 0x001c, 0x0024, 0x0025, 0x8029, 0x802a)) {
            rejects(request().copyOf(24).also {
                it[3] = 4; it[20] = (type ushr 8).toByte(); it[21] = type.toByte()
            })
        }
        rejects(request().copyOf(24).also { it[3] = 4; it[23] = 4 })
        rejects(request().copyOf(24).also { it[3] = 4; it[20] = 0x80.toByte(); it[21] = 0x28 })
        rejects(request(), 21)
        rejects(request(), 20_000)
        assertNull(StunCodec.bindingReply(request(), 20, source, 0))
    }

    @Test fun `optional request fingerprint must be valid and final`() {
        val input = request().copyOf(28)
        input[3] = 8
        input[20] = 0x80.toByte()
        input[21] = 0x28
        input[23] = 4
        val crc = CRC32().apply { update(input, 0, 20) }.value.toInt() xor 0x5354554e
        for (i in 0..3) input[24 + i] = (crc ushr (24 - 8 * i)).toByte()
        assertNotNull(StunCodec.bindingReply(input, input.size, InetAddress.getByName("127.0.0.1"), 45000))
        input[27] = (input[27].toInt() xor 1).toByte()
        assertNull(StunCodec.bindingReply(input, input.size, InetAddress.getByName("127.0.0.1"), 45000))
    }

    @Test fun `global monotonic limiter permits at most 32 per second`() {
        var time = 100L
        val rate = StunRate { time }
        repeat(32) { assertTrue(rate.allow()) }
        repeat(100) { assertFalse(rate.allow()) }
        time += 999_999_999
        assertFalse(rate.allow())
        time++
        assertTrue(rate.allow())
        time = 0
        assertTrue(rate.allow())
    }
}
