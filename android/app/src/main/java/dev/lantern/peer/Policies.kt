package dev.lantern.peer

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Semaphore

internal fun stamp(time: Long): String = Instant.ofEpochMilli(time).toString()
internal fun digest(value: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

internal class PairingPolicy(
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var secret: ByteArray? = null
    private var deadline = 0L
    private var attempts = 0

    @Synchronized fun generate(): String {
        val code = "%06d".format(java.util.Locale.ROOT, random.nextInt(1_000_000))
        secret = digest(code)
        deadline = now() + 120_000
        attempts = 0
        return code
    }

    @Synchronized fun redeem(code: String): PairResult {
        val expected = secret ?: return PairResult.UNAVAILABLE
        if (now() >= deadline) { secret = null; return PairResult.EXPIRED }
        if (attempts >= 5) return PairResult.LIMITED
        attempts++
        val matches = MessageDigest.isEqual(expected, digest(code))
        if (!matches) return if (attempts >= 5) PairResult.LIMITED else PairResult.INVALID
        secret = null
        return PairResult.OK
    }
}

internal enum class PairResult { OK, UNAVAILABLE, EXPIRED, INVALID, LIMITED }

internal data class Grant(val id: String, val name: String, val hash: String, val expires: Long)

internal class Grants(private val now: () -> Long = System::currentTimeMillis) {
    private val active = mutableListOf<Grant>()
    @Synchronized fun load(items: List<Grant>) { active.clear(); active.addAll(items) }
    @Synchronized fun list(): List<Grant> = active.toList()
    @Synchronized fun issue(name: String, random: SecureRandom = SecureRandom()): Pair<String, Grant> {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val grant = Grant(UUID.randomUUID().toString(), name, digest(token).toHex(), now() + 30L * 24 * 3600 * 1000)
        active.add(grant)
        return token to grant
    }
    @Synchronized fun authenticate(header: String?): Grant? {
        if (header == null || !header.startsWith("Bearer ") || header.length != 50) return null
        val value = header.substring(7)
        if (!value.matches(Regex("[A-Za-z0-9_-]{43}"))) return null
        val hash = digest(value)
        var match: Grant? = null
        for (grant in active) {
            val expected = try { grant.hash.hexBytes() } catch (_: Exception) { ByteArray(32) }
            if (MessageDigest.isEqual(hash, expected) && grant.expires > now()) match = grant
        }
        return match
    }
    @Synchronized fun revoke(id: String): Boolean = active.removeAll { it.id == id }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.hexBytes(): ByteArray {
    require(length == 64)
    return ByteArray(32) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal class Origins {
    companion object {
        fun canonical(input: String): String? {
            return try {
                val uri = URI(input)
                if (uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null ||
                    uri.rawPath !in listOf(null, "") || uri.rawQuery != null || uri.rawFragment != null ||
                    uri.port !in -1..65535 || uri.port == 0 || uri.toString() != input ||
                    uri.host.contains('%')) null
                else "https://${uri.host.lowercase(java.util.Locale.ROOT)}${if (uri.port == -1) "" else ":${uri.port}"}"
            } catch (_: Exception) { null }
        }
    }
    private val allowed = mutableSetOf<String>()
    @Synchronized fun set(origins: Collection<String>) {
        require(origins.all { canonical(it) == it })
        allowed.clear()
        allowed.addAll(origins)
    }
    @Synchronized fun list(): Set<String> = allowed.toSet()
    @Synchronized fun accepts(origin: String?): Boolean = origin == null || (canonical(origin) == origin && origin in allowed)
}

internal data class Slot(val id: String, val owner: String, val audio: Boolean, var expires: Long, var deadline: Long)

internal class SessionDelivery(
    private val slots: Slots, private val slot: Slot, private val closePeer: (String) -> Unit
) {
    private val failed = java.util.concurrent.atomic.AtomicBoolean()
    fun failed() {
        if (failed.compareAndSet(false, true)) {
            slots.remove(slot.id, slot.owner)
            closePeer(slot.id)
        }
    }
}

internal data class IceInput(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int) {
    companion object {
        fun validate(candidate: Any?, mid: Any?, index: Any?): IceInput? {
            if (candidate !is String || candidate.isEmpty() || candidate.length > 4096 ||
                candidate.any(Char::isISOControl) || mid != null && (mid !is String ||
                    mid.length > 64 || mid.any(Char::isISOControl)) ||
                index !is Int || index !in 0..1) return null
            return IceInput(candidate, mid as String?, index)
        }
    }
}

internal enum class SnapshotAdmission { READY, BUSY, RATE_LIMITED }
internal class SnapshotGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val semaphore = Semaphore(1)
    private var last = Long.MIN_VALUE
    fun enter(): SnapshotAdmission {
        if (!semaphore.tryAcquire()) return SnapshotAdmission.BUSY
        synchronized(this) {
            val current = now()
            if (last != Long.MIN_VALUE && current - last < 2_000) {
                semaphore.release()
                return SnapshotAdmission.RATE_LIMITED
            }
            last = current
        }
        return SnapshotAdmission.READY
    }
    fun exit() { semaphore.release() }
}

internal class Slots(
    private val now: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onExpire: (String) -> Unit = {}
) {
    private val slots = mutableMapOf<String, Slot>()
    @Synchronized fun reserve(owner: String, audio: Boolean): Slot? {
        expire()
        if (slots.size >= 3 || (audio && slots.values.any { it.audio })) return null
        val slot = Slot(UUID.randomUUID().toString(), owner, audio, now() + 30_000, monotonic() + 30_000)
        slots[slot.id] = slot
        return slot
    }

    @Synchronized fun owned(id: String, owner: String): Slot? {
        expire()
        return slots[id]?.takeIf { it.owner == owner }
    }

    @Synchronized fun heartbeat(id: String, owner: String): Slot? =
        owned(id, owner)?.also { it.expires = now() + 30_000; it.deadline = monotonic() + 30_000 }
    @Synchronized fun remove(id: String, owner: String): Boolean =
        owned(id, owner)?.let { slots.remove(id); true } ?: false
    @Synchronized fun removeOwner(owner: String): List<String> =
        slots.values.filter { it.owner == owner }.map { it.id }.also { ids -> ids.forEach(slots::remove) }
    @Synchronized fun clear(): List<String> = slots.keys.toList().also { slots.clear() }
    @Synchronized fun expire(): List<String> =
        slots.values.filter { it.deadline <= monotonic() || it.expires <= now() }.map { it.id }.also { ids ->
            ids.forEach(slots::remove)
            ids.forEach(onExpire)
        }
}
