package com.fieldgrade.app.gnss

import com.fieldgrade.app.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NmeaReplaySource] in isolation: recorded sentences in, timed fixes out. */
class NmeaReplaySourceTest {

    @Test fun produces_one_sample_per_gga() {
        val src = NmeaReplaySource(
            listOf(
                NmeaParser.withChecksum("GNGST,080000.00,0.012,0.015,0.011,32.1,0.011,0.013,0.021"),
                NmeaParser.withChecksum("GNGGA,080000.00,2756.96751,S,02449.83658,E,4,18,0.7,364.637,M,28.000,M,1.0,0000"),
                NmeaParser.withChecksum("GNGGA,080000.20,2756.96732,S,02449.83658,E,4,18,0.7,364.700,M,28.000,M,1.0,0000")
            )
        )
        val a = src.advance(1000)
        assertNotNull(a)
        assertEquals(FixQuality.FIXED, a!!.quality)
        // Ellipsoidal = orthometric + geoid separation.
        assertEquals(364.637 + 28.0, a.ellipsoidHeightM, 1e-6)
        // GST arrived first, so accuracy is measured not guessed: hypot(0.011, 0.013).
        assertEquals(0.01703, a.horizontalAccuracyM, 1e-5)

        assertNotNull(src.advance(1200))
        assertNull(src.advance(1400))
        assertTrue(src.isExhausted)
    }

    @Test fun latest_tracks_the_most_recent_fix() {
        val src = NmeaReplaySource(SampleData.rtkTrack().readLines())
        assertNull(src.latest())
        val first = src.advance(0)
        assertEquals(first, src.latest())
        val second = src.advance(200)
        assertEquals(second, src.latest())
        assertTrue(first != second)
    }

    @Test fun a_bad_checksum_never_becomes_a_fix() {
        val good = NmeaParser.withChecksum(
            "GNGGA,080000.00,2756.96751,S,02449.83658,E,4,18,0.7,364.637,M,28.000,M,1.0,0000")
        val corrupted = good.dropLast(2) + "FF"
        val src = NmeaReplaySource(listOf(corrupted))
        assertNull(src.advance(0))
        assertTrue(src.isExhausted)
    }

    // ---- REAL consumer-grade log ----

    @Test fun survives_a_real_messy_consumer_log() {
        // AMOD AGL3080, 2012. Contains $ADVER, GSA, GSV, VTG and a truncated tail —
        // sentence types we ignore plus genuine real-world noise. The point of this
        // test is that none of it throws and none of it becomes a bogus fix.
        val src = NmeaReplaySource(SampleData.consumerNmea().readLines())
        var t = 0L
        var count = 0
        var sample = src.advance(t)
        while (sample != null) {
            count++
            assertTrue("latitude out of range", sample.latitudeDeg in -90.0..90.0)
            assertTrue("longitude out of range", sample.longitudeDeg in -180.0..180.0)
            t += 1000
            sample = src.advance(t)
        }
        assertTrue("expected many fixes from a 2835-line log, got $count", count > 500)
        assertTrue(src.stats.linesRead > 2000)
    }

    @Test fun a_consumer_log_never_reports_rtk_quality() {
        // This is why the real log cannot drive guidance: it is metre-accurate.
        // It contains three grades — no-fix at power-up, autonomous, and
        // DGPS/SBAS once corrections arrive. All three sit below the FIXED gate,
        // which is the property that matters: nothing in a consumer log can
        // ever unlock AUTO.
        val src = NmeaReplaySource(SampleData.consumerNmea().readLines())
        val seen = HashMap<FixQuality, Int>()
        var i = 0
        var sample = src.advance(0)
        while (sample != null) {
            assertTrue(
                "consumer log reported RTK quality ${sample.quality}",
                sample.quality.ordinal < FixQuality.FLOAT.ordinal
            )
            seen[sample.quality] = (seen[sample.quality] ?: 0) + 1
            i++
            sample = src.advance(i * 1000L)
        }
        assertTrue("expected many fixes, got $seen", (seen.values.sum()) > 500)
        assertTrue("expected some autonomous fixes, got $seen",
            (seen[FixQuality.AUTONOMOUS] ?: 0) > 0)
    }

    // ---- SYNTHETIC RTK track ----

    @Test fun the_rtk_track_is_fixed_quality_throughout() {
        val src = NmeaReplaySource(SampleData.rtkTrack().readLines())
        var count = 0
        var sample = src.advance(0)
        while (sample != null) {
            assertEquals(FixQuality.FIXED, sample.quality)
            assertEquals(18, sample.satellites)
            count++
            sample = src.advance(count * 200L)
        }
        assertEquals(4052, count)
        assertEquals(4052, src.stats.samplesProduced)
    }

    @Test fun the_first_fix_has_no_measured_accuracy_and_fails_the_auto_gate() {
        // Receivers emit GGA before GST within an epoch, so the very first fix
        // has no measured accuracy and falls back to HDOP x 2.5 — metres, not
        // centimetres. That correctly fails the AUTO gate rather than letting
        // the blade move on an unqualified first reading. Behaviour worth
        // pinning: a "fix" is not the same as a *qualified* fix.
        val src = NmeaReplaySource(SampleData.rtkTrack().readLines())
        val first = src.advance(0)!!
        assertEquals(FixQuality.FIXED, first.quality)
        assertEquals(0.7 * 2.5, first.horizontalAccuracyM, 1e-9)
        assertTrue("first fix should not pass a 50 mm gate", first.horizontalAccuracyM > 0.05)

        // From the second epoch on, GST has been seen and accuracy is real.
        val second = src.advance(200)!!
        assertEquals(0.01703, second.horizontalAccuracyM, 1e-5)
        assertEquals(0.021, second.verticalAccuracyM, 1e-9)
    }

    @Test fun the_rtk_track_stays_inside_the_field() {
        val src = NmeaReplaySource(SampleData.rtkTrack().readLines())
        var minLat = 90.0; var maxLat = -90.0
        var sample = src.advance(0)
        var i = 0
        while (sample != null) {
            minLat = minOf(minLat, sample.latitudeDeg)
            maxLat = maxOf(maxLat, sample.latitudeDeg)
            i++
            sample = src.advance(i * 200L)
        }
        // ~196 m of northing at this latitude is about 0.00177 deg.
        assertEquals(0.00177, maxLat - minLat, 0.0004)
    }
}
