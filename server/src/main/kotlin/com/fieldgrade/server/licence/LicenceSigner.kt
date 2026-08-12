package com.fieldgrade.server.licence

import com.fieldgrade.shared.Base64Url
import com.fieldgrade.shared.LicenceClaim
import com.fieldgrade.shared.LicenceCodec
import com.fieldgrade.shared.SignedLicence
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Mints licence tokens. Server-side only — this half of the keypair must never
 * reach a tablet.
 *
 * One job: turn a [LicenceClaim] into a signed token. It decides nothing about
 * who deserves a licence or for how long; [LicenceService] does that.
 *
 * The asymmetry is the design: the server signs with the private key, every
 * tablet verifies with the public one. A tablet can therefore check a licence
 * offline forever but cannot forge one, so a stolen or rooted device yields
 * nothing beyond its own licence.
 *
 * ECDSA P-256 to match [com.fieldgrade.shared.LicenceVerifier] — see the note
 * there about Android API levels.
 */
class LicenceSigner(private val privateKey: PrivateKey) {

    fun sign(claim: LicenceClaim): SignedLicence {
        val payload = LicenceCodec.payloadBytes(claim)
        val signature = Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
        return SignedLicence(payload, signature)
    }

    /** Wire-form token, ready to hand to a tablet. */
    fun token(claim: LicenceClaim): String = sign(claim).encode()

    companion object {
        const val ALGORITHM = "SHA256withECDSA"
        const val KEY_ALGORITHM = "EC"
        const val CURVE = "secp256r1"

        /**
         * Load from a base64url PKCS#8 DER blob — the form kept in configuration.
         *
         * The private key belongs in a secret manager or an environment
         * variable, never in the repository. [generateKeyPair] exists so a
         * developer can run the whole system locally without ever seeing the
         * production key.
         */
        fun fromEncodedPrivateKey(base64Url: String): LicenceSigner? {
            val der = Base64Url.decode(base64Url) ?: return null
            return try {
                LicenceSigner(
                    KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(der))
                )
            } catch (e: Exception) {
                null
            }
        }

        /** A fresh keypair, encoded for config. Used by tests and by setup tooling. */
        fun generateKeyPair(): EncodedKeyPair {
            val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
            generator.initialize(ECGenParameterSpec(CURVE))
            val pair = generator.generateKeyPair()
            return EncodedKeyPair(
                privateKeyBase64Url = Base64Url.encode(pair.private.encoded),
                publicKeyBase64Url = Base64Url.encode(pair.public.encoded)
            )
        }
    }

    /**
     * @param privateKeyBase64Url PKCS#8 — server secret.
     * @param publicKeyBase64Url X.509 — safe to compile into the tablet app.
     */
    data class EncodedKeyPair(
        val privateKeyBase64Url: String,
        val publicKeyBase64Url: String
    )
}
