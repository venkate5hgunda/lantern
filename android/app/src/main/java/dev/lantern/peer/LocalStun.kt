package dev.lantern.peer

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.zip.CRC32

internal object StunCodec {
    private const val COOKIE = 0x2112a442
    private const val FINGERPRINT_XOR = 0x5354554e
    const val PORT = 8444
    const val MAX_PACKET = 1200

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
    private fun u32(b: ByteArray, p: Int): Int =
        (u16(b, p) shl 16) or u16(b, p + 2)
    private fun put16(b: ByteArray, p: Int, v: Int) {
        b[p] = (v ushr 8).toByte()
        b[p + 1] = v.toByte()
    }
    private fun put32(b: ByteArray, p: Int, v: Int) {
        put16(b, p, v ushr 16)
        put16(b, p + 2, v)
    }

    fun bindingReply(input: ByteArray, size: Int, source: InetAddress, port: Int): ByteArray? {
        if (size !in 20..MAX_PACKET || size > input.size || port !in 1..65535 ||
            u16(input, 0) != 0x0001 || u32(input, 4) != COOKIE) return null
        val declared = u16(input, 2)
        if (declared % 4 != 0 || declared != size - 20) return null
        var cursor = 20
        while (cursor < size) {
            if (cursor + 4 > size) return null
            val type = u16(input, cursor)
            val length = u16(input, cursor + 2)
            val padded = (length + 3) and -4
            if (cursor + 4 + padded > size) return null
            if (type in setOf(0x0006, 0x0008, 0x001c, 0x0024, 0x0025, 0x8029, 0x802a)) return null
            if (type == 0x8028) {
                if (length != 4 || cursor + 8 != size) return null
                val crc = CRC32().apply { update(input, 0, cursor) }.value.toInt() xor FINGERPRINT_XOR
                if (u32(input, cursor + 4) != crc) return null
            }
            cursor += 4 + padded
        }
        val address = source.address
        if (address.size != 4 && address.size != 16) return null
        val addressLength = if (address.size == 4) 8 else 20
        val response = ByteArray(20 + 4 + addressLength + 8)
        put16(response, 0, 0x0101)
        put16(response, 2, response.size - 20)
        put32(response, 4, COOKIE)
        System.arraycopy(input, 8, response, 8, 12)
        put16(response, 20, 0x0020)
        put16(response, 22, addressLength)
        response[24] = 0
        response[25] = if (address.size == 4) 1 else 2
        put16(response, 26, port xor (COOKIE ushr 16))
        for (i in address.indices) {
            val mask = response[4 + i]
            response[28 + i] = (address[i].toInt() xor mask.toInt()).toByte()
        }
        val fingerprint = 24 + addressLength
        put16(response, fingerprint, 0x8028)
        put16(response, fingerprint + 2, 4)
        val crc = CRC32().apply { update(response, 0, fingerprint) }.value.toInt() xor FINGERPRINT_XOR
        put32(response, fingerprint + 4, crc)
        return response
    }
}

internal class StunRate(private val now: () -> Long = System::nanoTime) {
    private var start = Long.MIN_VALUE
    private var count = 0
    @Synchronized fun allow(): Boolean {
        val time = now()
        if (start == Long.MIN_VALUE || time < start || time - start >= 1_000_000_000L) {
            start = time
            count = 0
        }
        if (count >= 32) return false
        count++
        return true
    }
}

internal class LocalStun(private val rate: StunRate = StunRate()) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null
    val running: Boolean get() = socket?.isClosed == false && worker?.isAlive == true

    @Synchronized fun start() {
        if (running) return
        if (socket != null || worker != null) stop()
        val bound = DatagramSocket(StunCodec.PORT)
        socket = bound
        try {
            worker = Thread({
                val buffer = ByteArray(StunCodec.MAX_PACKET + 1)
                while (!bound.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        bound.receive(packet)
                        if (packet.length > StunCodec.MAX_PACKET) continue
                        val response = StunCodec.bindingReply(buffer, packet.length, packet.address, packet.port)
                            ?: continue
                        if (!rate.allow()) continue
                        bound.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    } catch (_: SocketException) {
                        if (!bound.isClosed) Log.e("LanternStun", "Local UDP discovery socket failed")
                        break
                    } catch (_: Exception) {
                        if (!bound.isClosed) Log.e("LanternStun", "Local UDP discovery processing failed")
                        break
                    }
                }
                bound.close()
            }, "lantern-stun").also { it.start() }
        } catch (failure: Exception) {
            bound.close()
            socket = null
            worker = null
            throw failure
        }
    }
    @Synchronized fun stop() {
        socket?.close()
        val active = worker
        if (active != null) {
            check(active !== Thread.currentThread()) { "Cannot retire UDP listener from its own thread" }
            try { active.join(2_000) } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("UDP listener retirement interrupted", interrupted)
            }
            check(!active.isAlive) { "UDP listener did not retire before port release" }
        }
        socket = null
        worker = null
    }
}
