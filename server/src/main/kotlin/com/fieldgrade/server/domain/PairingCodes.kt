package com.fieldgrade.server.domain

import java.security.SecureRandom

/**
 * Generates and normalises the short code someone types into a tablet.
 *
 * One job: the code's alphabet and shape. It stores nothing and decides no
 * expiry policy.
 *
 * Every choice here exists because a human reads this off a screen, possibly
 * over a phone, and types it on a tablet with dusty hands:
 *
 *  - **No 0/O, 1/I/L, U/V.** The classic misreads. Removing them costs a little
 *    entropy and saves a support call.
 *  - **Grouped `XXXX-XXXX`.** Chunking is what makes a code readable aloud.
 *  - **Case-insensitive on entry**, upper-case on display.
 *  - **Hyphens and spaces ignored** when redeeming, because people will type
 *    them, or not, and either should work.
 *
 * 30 symbols over 8 characters is about 6.5e11 combinations. Paired with a
 * 15-minute expiry and single use, guessing is not a realistic attack — but the
 * expiry is doing as much of that work as the entropy, so do not lengthen it
 * casually.
 */
object PairingCodes {

    /** Deliberately excludes 0 O 1 I L U V. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTWXYZ"
    const val LENGTH = 8
    const val GROUP = 4

    private val random = SecureRandom()

    /** A fresh code in display form, e.g. `K7M2-9QXP`. */
    fun generate(): String {
        val raw = buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        return format(raw)
    }

    /** Insert the grouping hyphen for display. */
    fun format(raw: String): String =
        raw.chunked(GROUP).joinToString("-")

    /**
     * Reduce anything a human typed to the canonical stored form.
     *
     * @return the normalised code, or null if it is not a plausible code at all.
     *         Returning null rather than a best guess keeps a typo from becoming
     *         a lookup for somebody else's machine.
     */
    fun normalise(input: String): String? {
        val cleaned = input.uppercase()
            .filter { it != '-' && it != ' ' && it != '–' }
        if (cleaned.length != LENGTH) return null
        if (!cleaned.all { it in ALPHABET }) return null
        return format(cleaned)
    }
}
