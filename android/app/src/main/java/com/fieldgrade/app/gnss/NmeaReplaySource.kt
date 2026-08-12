package com.fieldgrade.app.gnss

/**
 * Replays a recorded NMEA log as a [GnssSource].
 *
 * One job: turn stored sentences into [GnssSample]s, one epoch at a time. It
 * owns no clock, no thread and no file handle — the caller supplies the lines
 * and says what time it is, which is what makes a replay deterministic and a
 * test able to run a 20-minute drive in milliseconds.
 *
 * It deliberately does not parse NMEA itself; it drives the existing
 * [NmeaGnssDecoder], so a replay exercises exactly the code path a live
 * receiver would. If replay works and hardware does not, the fault is in the
 * transport, not the decoding.
 *
 * [stats] exists because a real log is messy — truncated final lines, bad
 * checksums, sentence types we ignore. Counting what was skipped keeps an
 * honest replay from quietly looking like a clean one.
 */
class NmeaReplaySource(lines: Sequence<String>) : GnssSource {

    constructor(lines: Iterable<String>) : this(lines.asSequence())

    private val iterator = lines.iterator()
    private val decoder = NmeaGnssDecoder()
    private var last: GnssSample? = null

    data class Stats(
        var linesRead: Int = 0,
        var samplesProduced: Int = 0,
        var sentencesIgnored: Int = 0
    ) {
        override fun toString() =
            "$linesRead lines -> $samplesProduced fixes ($sentencesIgnored non-position sentences)"
    }

    val stats = Stats()

    /** True once the log is exhausted. */
    var isExhausted: Boolean = false
        private set

    /**
     * Consume lines until the next position fix, and return it.
     *
     * @param nowMs the simulated time to stamp this epoch with.
     * @return the new sample, or null at end of log.
     */
    fun advance(nowMs: Long): GnssSample? {
        while (iterator.hasNext()) {
            val line = iterator.next()
            stats.linesRead++
            val sample = decoder.offer(line, nowMs)
            if (sample != null) {
                stats.samplesProduced++
                last = sample
                return sample
            }
            stats.sentencesIgnored++
        }
        isExhausted = true
        return null
    }

    override fun latest(): GnssSample? = last
}
