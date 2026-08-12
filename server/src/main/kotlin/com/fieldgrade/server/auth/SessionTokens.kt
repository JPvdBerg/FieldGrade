package com.fieldgrade.server.auth

import com.fieldgrade.shared.Base64Url
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Mints and fingerprints bearer tokens.
 *
 * One job: generate an unguessable token, and reduce it to the value we are
 * willing to store. No database, no expiry policy, no users.
 *
 * The asymmetry is deliberate. The customer holds the token; the database holds
 * only its SHA-256. A dump of the sessions table therefore grants nobody a
 * login, in the same way a leaked table of password hashes grants nobody an
 * account. Sessions are usually the forgotten half of that lesson.
 *
 * SHA-256 with no salt and no work factor is right here and wrong for passwords:
 * a 256-bit random token has no dictionary to attack, so the only property
 * needed is that the stored form differs from the presented one.
 */
object SessionTokens {

    /** 256 bits from a CSPRNG — not guessable, not enumerable. */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64Url.encode(bytes)
    }

    /** The value safe to persist. */
    fun fingerprint(token: String): String =
        Base64Url.encode(
            MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())
        )

    private val random = SecureRandom()
    const val TOKEN_BYTES = 32
}
