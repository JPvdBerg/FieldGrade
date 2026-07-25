package com.fieldgrade.app.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {

    private val ggaBody = "GNGGA,172814.0,3723.46587704,N,12202.26957864,W,4,10,0.8,18.893,M,-25.669,M,,"
    private val gstBody = "GNGST,172814.0,1.2,0.9,0.6,120.0,0.014,0.011,0.021"
    private val rmcBody = "GNRMC,172814.0,A,3723.46587704,N,12202.26957864,W,1.5,54.7,010125,,,A"

    @Test fun parses_gga_position_fix_and_height() {
        val s = NmeaParser.parse(NmeaParser.withChecksum(ggaBody)) as GgaData
        assertEquals(37.39109795, s.latitudeDeg, 1e-7)
        assertEquals(-122.03782631, s.longitudeDeg, 1e-7)
        assertEquals(FixQuality.FIXED, s.quality)
        assertEquals(10, s.satellites)
        assertEquals(-6.776, s.ellipsoidHeightM, 1e-3)
    }

    @Test fun maps_all_fix_qualities() {
        fun q(code: Int) = (NmeaParser.parse(NmeaParser.withChecksum(
            "GNGGA,1,3723.0,N,12202.0,W,$code,5,1,10,M,0,M,,")) as GgaData).quality
        assertEquals(FixQuality.NONE, q(0))
        assertEquals(FixQuality.AUTONOMOUS, q(1))
        assertEquals(FixQuality.DGPS, q(2))
        assertEquals(FixQuality.FIXED, q(4))
        assertEquals(FixQuality.FLOAT, q(5))
    }

    @Test fun parses_gst_accuracy() {
        val s = NmeaParser.parse(NmeaParser.withChecksum(gstBody)) as GstData
        assertEquals(0.017804, s.horizontalAccuracyM, 1e-5)
        assertEquals(0.021, s.altStdDevM, 1e-9)
    }

    @Test fun parses_rmc_speed_and_heading() {
        val s = NmeaParser.parse(NmeaParser.withChecksum(rmcBody)) as RmcData
        assertTrue(s.valid)
        assertEquals(0.771666, s.speedMps, 1e-5)
        assertEquals(54.7, s.headingDeg, 1e-9)
    }

    @Test fun rejects_bad_checksum() {
        assertNull(NmeaParser.parse("\$$ggaBody*00"))
    }

    @Test fun accepts_missing_checksum() {
        assertNotNull(NmeaParser.parse("\$$ggaBody"))
    }

    @Test fun ignores_unknown_and_empty() {
        assertNull(NmeaParser.parse(""))
        assertNull(NmeaParser.parse("\$GNVTG,0,T*00"))
        assertNull(NmeaParser.parse("garbage"))
    }

    @Test fun decoder_fuses_gst_rmc_into_gga_sample() {
        val d = NmeaGnssDecoder()
        assertNull(d.offer(NmeaParser.withChecksum(gstBody), 1000))
        assertNull(d.offer(NmeaParser.withChecksum(rmcBody), 1000))
        val sample = d.offer(NmeaParser.withChecksum(ggaBody), 1000)
        assertNotNull(sample)
        assertEquals(FixQuality.FIXED, sample!!.quality)
        assertEquals(0.017804, sample.horizontalAccuracyM, 1e-5)   // from GST
        assertEquals(0.771666, sample.speedMps, 1e-5)              // from RMC
        assertEquals(54.7, sample.headingDeg, 1e-9)
    }
}
