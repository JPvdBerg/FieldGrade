package com.fieldgrade.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A machine's licence, in a form the tablet can check **without a network**.
 *
 * One job: describe what a licence permits and until when. It signs nothing,
 * verifies nothing and talks to no server — [LicenceVerifier] does that.
 *
 * This shape exists because of a hard safety constraint: the tablet is
 * supervisory, and a machine in a field must never stop working because a cell
 * tower did. So the server issues a *signed, self-contained* claim that the
 * tablet caches and checks offline, rather than an online permission call that
 * can fail at the worst moment.
 *
 * Three states, and the difference between them matters:
 *
 *  - **ACTIVE** — paid up. Everything works.
 *  - **GRACE** — payment lapsed, but within [graceDays]. Everything still
 *    works; the operator sees a warning. This is the state that covers a
 *    declined card, a farmer out of signal for three weeks, and a billing
 *    dispute. It is deliberately generous.
 *  - **EXPIRED** — past grace. Blocks *acquiring new work* — pulling down a new
 *    design. It never interrupts a job already loaded, and it never touches
 *    guidance, AUTO or the controller. A machine mid-pass keeps grading.
 *
 * Nothing in this file may ever be consulted inside the control loop.
 */
@Serializable
data class LicenceClaim(
    /** Schema version, so an old tablet can refuse a token it cannot understand. */
    val v: Int = 1,
    /** The machine this licence is bound to; a token is not transferable. */
    val machineId: String,
    val orgId: String,
    /** Plan code, for display and for feature gating that is *not* safety related. */
    val plan: String,
    /** Epoch millis the token was issued. */
    val issuedAtMs: Long,
    /** Epoch millis the paid period ends. */
    val expiresAtMs: Long,
    /** Days past [expiresAtMs] during which everything still works. */
    val graceDays: Int = DEFAULT_GRACE_DAYS
) {
    /** Epoch millis at which the licence stops permitting new design downloads. */
    val hardExpiryMs: Long
        get() = expiresAtMs + graceDays.toLong() * MILLIS_PER_DAY

    fun stateAt(nowMs: Long): LicenceState = when {
        nowMs <= expiresAtMs -> LicenceState.ACTIVE
        nowMs <= hardExpiryMs -> LicenceState.GRACE
        else -> LicenceState.EXPIRED
    }

    companion object {
        /**
         * Deliberately long. A month of slack covers a declined card, a season
         * out of signal, and a billing dispute, without anyone losing a day's
         * work over it. Shortening this is a commercial decision with a safety
         * smell — argue it out before touching it.
         */
        const val DEFAULT_GRACE_DAYS = 30
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

enum class LicenceState {
    ACTIVE,
    GRACE,
    EXPIRED;

    /**
     * Whether the tablet may pull down a *new* design. This is the only thing a
     * licence is permitted to gate. Guidance, AUTO, logging and the controller
     * are never conditional on it.
     */
    val allowsNewDesignDownload: Boolean get() = this != EXPIRED

    /** Whether the operator should be told something is wrong. */
    val needsAttention: Boolean get() = this != ACTIVE

    fun message(): String? = when (this) {
        ACTIVE -> null
        GRACE -> "Subscription lapsed — still working, renew to avoid interruption"
        EXPIRED -> "Subscription expired — existing jobs still run, new designs blocked"
    }
}

/**
 * A signed licence: the claim plus a detached signature over its exact bytes.
 *
 * Wire form is `base64url(payload).base64url(signature)` — one line, safe in a
 * URL, a header, a QR code or a text file emailed to a farmer with no data.
 * That last case is not hypothetical: handing someone a licence string to type
 * in is a real fallback when a machine has never had signal.
 */
data class SignedLicence(val payloadBytes: ByteArray, val signature: ByteArray) {

    fun encode(): String =
        "${Base64Url.encode(payloadBytes)}.${Base64Url.encode(signature)}"

    /** The claim, without checking the signature. Never trust this on its own. */
    fun claimUnverified(json: Json = LicenceCodec.json): LicenceClaim =
        json.decodeFromString(payloadBytes.decodeToString())

    override fun equals(other: Any?): Boolean =
        other is SignedLicence &&
            payloadBytes.contentEquals(other.payloadBytes) &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int =
        31 * payloadBytes.contentHashCode() + signature.contentHashCode()

    companion object {
        /** Parse the wire form. Returns null on anything malformed. */
        fun decode(text: String): SignedLicence? {
            val dot = text.indexOf('.')
            if (dot <= 0 || dot == text.length - 1) return null
            val payload = Base64Url.decode(text.substring(0, dot)) ?: return null
            val signature = Base64Url.decode(text.substring(dot + 1)) ?: return null
            if (payload.isEmpty() || signature.isEmpty()) return null
            return SignedLicence(payload, signature)
        }
    }
}

/** Canonical JSON for licence payloads, shared by signer and verifier. */
object LicenceCodec {
    /**
     * `encodeDefaults` is on so the signed bytes always contain every field.
     * Without it a default-valued field would be absent from the payload and a
     * later schema change could alter the bytes a signature was taken over.
     */
    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun payloadBytes(claim: LicenceClaim): ByteArray =
        json.encodeToString(LicenceClaim.serializer(), claim).encodeToByteArray()
}

/** Base64url without padding. Small enough to share rather than pull a dependency. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                .append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                    .append(ALPHABET[(n ushr 6) and 63])
            }
        }
        return out.toString()
    }

    /** Returns null on any character outside the alphabet, rather than guessing. */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 1)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val value = ALPHABET.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
