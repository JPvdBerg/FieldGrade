package com.fieldgrade.shared

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Checks a licence signature. Runs on the tablet, offline, forever.
 *
 * One job: is this token genuinely ours, and what does it say? It fetches
 * nothing, stores nothing, and has no opinion about what a licence permits —
 * [LicenceState] owns that.
 *
 * **ECDSA over P-256, not Ed25519.** Ed25519 in `java.security` needs Android
 * API 33; minSdk here is 26. P-256 has been available since API 11, so this
 * verifies on every tablet the app supports without dragging in BouncyCastle.
 *
 * The tablet holds only the **public** key, compiled in. It can therefore check
 * a licence but never mint one, which is the whole point: a stolen tablet
 * yields nothing useful.
 */
class LicenceVerifier(private val publicKey: PublicKey) {

    /**
     * Verify a wire-form token.
     *
     * @return the claim if the signature is genuine, otherwise null. A null here
     *         means forged, corrupt, or signed by a different key — never
     *         "expired". Expiry is a *valid* token in a later state, and
     *         conflating the two would let a corrupt token look merely lapsed.
     */
    fun verify(token: String): LicenceClaim? {
        val signed = SignedLicence.decode(token) ?: return null
        return verify(signed)
    }

    fun verify(signed: SignedLicence): LicenceClaim? {
        val ok = try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(publicKey)
                update(signed.payloadBytes)
                verify(signed.signature)
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) return null

        return try {
            signed.claimUnverified()
        } catch (e: Exception) {
            null   // signed by us but unparseable: a newer schema. Refuse it.
        }
    }

    /**
     * Verify and evaluate in one step — what a caller actually wants.
     *
     * @param nowMs current time. Injected rather than read, so a test can sit
     *        on any side of an expiry boundary and so a device with a wrong
     *        clock is the caller's problem to notice, not a hidden one here.
     */
    fun evaluate(token: String?, nowMs: Long): Evaluation {
        if (token.isNullOrBlank()) return Evaluation(null, LicenceState.EXPIRED, "no licence")
        val claim = verify(token)
            ?: return Evaluation(null, LicenceState.EXPIRED, "licence signature invalid")
        if (claim.v != SUPPORTED_VERSION) {
            return Evaluation(null, LicenceState.EXPIRED, "licence version ${claim.v} not supported")
        }
        val state = claim.stateAt(nowMs)
        return Evaluation(claim, state, state.message())
    }

    data class Evaluation(
        val claim: LicenceClaim?,
        val state: LicenceState,
        val message: String?
    ) {
        val isUsable: Boolean get() = claim != null
        val allowsNewDesignDownload: Boolean get() = state.allowsNewDesignDownload
    }

    companion object {
        const val ALGORITHM = "SHA256withECDSA"
        const val KEY_ALGORITHM = "EC"
        const val SUPPORTED_VERSION = 1

        /** Build from an X.509 (SubjectPublicKeyInfo) DER blob, base64url encoded. */
        fun fromEncodedPublicKey(base64Url: String): LicenceVerifier? {
            val der = Base64Url.decode(base64Url) ?: return null
            return try {
                LicenceVerifier(
                    KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(der))
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
