package com.fieldgrade.app.provenance

import com.fieldgrade.app.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionProvenance] in isolation.
 *
 * The property under test is a safety one: generated data must never be able to
 * present itself as measured. Every ambiguity resolves toward SYNTHETIC.
 */
class DataProvenanceTest {

    @Test fun all_real_sources_produce_no_warning() {
        val p = SessionProvenance(
            listOf(
                SessionProvenance.real("design", "nunosurf.xml"),
                SessionProvenance.real("GNSS", "field_recording.nmea")
            )
        )
        assertEquals(Provenance.REAL, p.verdict)
        assertTrue(p.isAllReal)
        assertNull("a fully real session must not nag", p.banner())
    }

    @Test fun one_synthetic_input_makes_the_whole_session_synthetic() {
        // A run is only as trustworthy as its least trustworthy input. Averaging
        // that away is how a demo quietly becomes a claim.
        val p = SessionProvenance(
            listOf(
                SessionProvenance.real("design", "nunosurf.xml"),
                SessionProvenance.synthetic("GNSS", "track_SYNTHETIC.nmea")
            )
        )
        assertEquals(Provenance.SYNTHETIC, p.verdict)
        assertFalse(p.isAllReal)
        assertNotNull(p.banner())
    }

    @Test fun the_banner_names_which_inputs_are_invented() {
        // "synthetic" alone leaves the viewer guessing which half is made up.
        val p = SessionProvenance(
            listOf(
                SessionProvenance.real("design", "nunosurf.xml"),
                SessionProvenance.synthetic("ground", "existing_SYNTHETIC.xyz"),
                SessionProvenance.synthetic("GNSS", "track_SYNTHETIC.nmea")
            )
        )
        val banner = p.banner()!!
        assertTrue(banner.contains("ground"))
        assertTrue(banner.contains("GNSS"))
        assertFalse("real inputs must not be blamed", banner.contains("design"))
        assertTrue(banner.contains("not the machine"))
    }

    @Test fun an_unknown_source_is_never_treated_as_real() {
        val p = SessionProvenance(listOf(SessionProvenance.fromName("GNSS", "mystery.nmea")))
        assertEquals(Provenance.UNKNOWN, p.verdict)
        assertFalse(p.isAllReal)
        assertNotNull("unverified data must still warn", p.banner())
    }

    @Test fun an_empty_session_is_unknown_not_real() {
        val p = SessionProvenance(emptyList())
        assertEquals(Provenance.UNKNOWN, p.verdict)
        assertFalse(p.isAllReal)
    }

    // ---- filename classification ----

    @Test fun the_synthetic_marker_is_recognised_in_any_case() {
        assertEquals(Provenance.SYNTHETIC, SessionProvenance.classify("track_SYNTHETIC.nmea"))
        assertEquals(Provenance.SYNTHETIC, SessionProvenance.classify("track_synthetic.nmea"))
        assertEquals(Provenance.SYNTHETIC, SessionProvenance.classify("a_Synthetic_b.xyz"))
    }

    @Test fun demo_and_sim_names_are_treated_as_generated() {
        assertEquals(Provenance.SYNTHETIC, SessionProvenance.classify("demo_field.xyz"))
        assertEquals(Provenance.SYNTHETIC, SessionProvenance.classify("run_sim_01.nmea"))
    }

    @Test fun an_unmarked_file_is_unknown_never_real() {
        // Failing toward SYNTHETIC is the safe direction: mislabelling generated
        // data as measured is the dangerous one.
        assertEquals(Provenance.UNKNOWN, SessionProvenance.classify("nunosurf.xml"))
        assertEquals(Provenance.UNKNOWN, SessionProvenance.classify("survey_2026.xyz"))
    }

    // ---- the shipped sample data ----

    @Test fun every_generated_sample_file_announces_itself_in_its_name() {
        // If someone regenerates the data under a plain name, this fails — which
        // is the point. The filesystem should not be able to lie about this.
        assertEquals(
            Provenance.SYNTHETIC,
            SessionProvenance.classify(SampleData.rtkTrack().name)
        )
        assertEquals(
            Provenance.SYNTHETIC,
            SessionProvenance.classify(SampleData.design("nunosurf_existing_SYNTHETIC.xyz").name)
        )
    }

    @Test fun the_real_sample_files_carry_no_synthetic_marker() {
        for (f in listOf(SampleData.nunosurfXml(), SampleData.brattonFarmXml(),
                         SampleData.consumerNmea(), SampleData.nunosurfXyz())) {
            assertTrue(
                "${f.name} is real data but is named as synthetic",
                SessionProvenance.classify(f.name) != Provenance.SYNTHETIC
            )
        }
    }

    @Test fun describe_lists_every_source_with_its_verdict() {
        val text = SessionProvenance(
            listOf(
                SessionProvenance.real("design", "nunosurf.xml", "from landxml.org"),
                SessionProvenance.synthetic("GNSS", "track_SYNTHETIC.nmea")
            )
        ).describe()
        assertTrue(text.contains("SYNTHETIC"))
        assertTrue(text.contains("nunosurf.xml"))
        assertTrue(text.contains("from landxml.org"))
    }

    @Test fun the_demo_mode_declares_itself_entirely_generated() {
        val p = SessionProvenance.allSynthetic("no sample data loaded")
        assertEquals(Provenance.SYNTHETIC, p.verdict)
        assertNotNull(p.banner())
    }
}
