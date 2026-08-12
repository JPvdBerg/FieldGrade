package com.fieldgrade.app.design

import com.fieldgrade.app.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LandXmlSurfaceReader] in isolation: XML in, points and faces out. */
class LandXmlSurfaceReaderTest {

    private fun read(xml: String, name: String? = null) =
        LandXmlSurfaceReader.read(xml.byteInputStream(), name)

    private fun wrap(inner: String) = """
        <?xml version="1.0"?>
        <LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">
          <Units><Metric linearUnit="meter"/></Units>
          $inner
        </LandXML>
    """.trimIndent()

    @Test fun point_payload_is_north_east_elevation_not_east_north() {
        // The single most damaging thing to get wrong: swapping these mirrors
        // the entire field about a diagonal.
        val s = read(wrap("""
            <Surfaces><Surface name="s"><Definition surfType="TIN">
              <Pnts>
                <P id="1">500.0 100.0 42.0</P>
                <P id="2">600.0 200.0 43.0</P>
                <P id="3">550.0 300.0 44.0</P>
              </Pnts>
              <Faces><F>1 2 3</F></Faces>
            </Definition></Surface></Surfaces>
        """))
        assertEquals(3, s.points.size)
        assertEquals(100.0, s.points[0].eastM, 1e-9)   // second value
        assertEquals(500.0, s.points[0].northM, 1e-9)  // first value
        assertEquals(42.0, s.points[0].elevationM, 1e-9)
    }

    @Test fun non_contiguous_point_ids_still_index_faces_correctly() {
        // Real files skip ids. `index = id - 1` would silently attach the face to
        // the wrong vertices and produce a plausible-looking, wrong surface.
        val s = read(wrap("""
            <Surfaces><Surface name="gaps"><Definition surfType="TIN">
              <Pnts>
                <P id="1">0.0 0.0 10.0</P>
                <P id="7">0.0 10.0 20.0</P>
                <P id="93">10.0 0.0 30.0</P>
              </Pnts>
              <Faces><F>1 7 93</F></Faces>
            </Definition></Surface></Surfaces>
        """))
        assertEquals(3, s.points.size)
        assertEquals(1, s.triangles.size)
        val t = s.triangles[0]
        assertEquals(0, t.a)
        assertEquals(1, t.b)
        assertEquals(2, t.c)
        // And the elevations must follow the right vertices.
        assertEquals(10.0, s.points[t.a].elevationM, 1e-9)
        assertEquals(20.0, s.points[t.b].elevationM, 1e-9)
        assertEquals(30.0, s.points[t.c].elevationM, 1e-9)
    }

    @Test fun faces_referencing_undefined_ids_are_dropped_and_counted() {
        val s = read(wrap("""
            <Surfaces><Surface name="dangling"><Definition surfType="TIN">
              <Pnts>
                <P id="1">0.0 0.0 1.0</P>
                <P id="2">0.0 10.0 1.0</P>
                <P id="3">10.0 0.0 1.0</P>
              </Pnts>
              <Faces><F>1 2 3</F><F>1 2 999</F></Faces>
            </Definition></Surface></Surfaces>
        """))
        assertEquals(1, s.triangles.size)
        assertEquals("1", s.metadata["danglingFaces"])
    }

    @Test fun invisible_faces_are_skipped() {
        val s = read(wrap("""
            <Surfaces><Surface name="hidden"><Definition surfType="TIN">
              <Pnts>
                <P id="1">0.0 0.0 1.0</P><P id="2">0.0 10.0 1.0</P><P id="3">10.0 0.0 1.0</P>
              </Pnts>
              <Faces><F i="1">1 2 3</F></Faces>
            </Definition></Surface></Surfaces>
        """))
        assertEquals(0, s.triangles.size)
        assertEquals("1", s.metadata["skippedFaces"])
    }

    @Test fun a_named_surface_can_be_selected() {
        val xml = wrap("""
            <Surfaces>
              <Surface name="existing"><Definition surfType="TIN"><Pnts>
                <P id="1">0.0 0.0 1.0</P><P id="2">0.0 10.0 1.0</P><P id="3">10.0 0.0 1.0</P>
              </Pnts></Definition></Surface>
              <Surface name="design"><Definition surfType="TIN"><Pnts>
                <P id="1">0.0 0.0 99.0</P><P id="2">0.0 10.0 99.0</P><P id="3">10.0 0.0 99.0</P>
              </Pnts></Definition></Surface>
            </Surfaces>
        """)
        assertEquals(1.0, read(xml).points[0].elevationM, 1e-9)                 // first by default
        assertEquals(99.0, read(xml, "design").points[0].elevationM, 1e-9)      // named
    }

    @Test(expected = DesignFormatException::class)
    fun a_landxml_with_no_surface_is_rejected() {
        // The road-alignment sample is valid LandXML with no TIN in it at all.
        SampleData.design("landxml_road.xml").inputStream().use { LandXmlSurfaceReader.read(it) }
    }

    @Test(expected = DesignFormatException::class)
    fun malformed_xml_is_rejected() {
        read("<LandXML><Surfaces><Surface name=")
    }

    // ---- against the real files ----

    @Test fun reads_the_real_nunosurf_surface() {
        val s = SampleData.nunosurfXml().inputStream().use { LandXmlSurfaceReader.read(it) }
        assertEquals(7048, s.points.size)
        assertEquals(12798, s.triangles.size)
        assertEquals("TIN", s.metadata["surfType"])
        // Authored in US survey feet; returned in metres.
        assertEquals("USSurveyFoot", s.metadata["sourceLinearUnit"])
        assertEquals("meter", s.metadata["linearUnit"])

        val b = s.bounds()!!
        assertEquals(226.7, b.widthM, 0.5)
        assertEquals(196.2, b.heightM, 0.5)
        assertEquals(0.61, b.reliefM, 0.01)

        // Every face must reference a real vertex.
        for (t in s.triangles) {
            assertTrue(t.a in s.points.indices)
            assertTrue(t.b in s.points.indices)
            assertTrue(t.c in s.points.indices)
        }
    }

    @Test fun reads_the_real_bratton_farm_surface() {
        val s = SampleData.brattonFarmXml().inputStream().use { LandXmlSurfaceReader.read(it) }
        assertEquals(14424, s.points.size)
        assertEquals(28549, s.triangles.size)
        // This one really is authored in metres — which is exactly why the unit
        // must be read per file rather than assumed from a sibling.
        assertEquals("meter", s.metadata["sourceLinearUnit"])
        val b = s.bounds()!!
        assertEquals(65.21, b.reliefM, 0.01)
    }

    @Test fun an_unrecognised_unit_is_refused_rather_than_scaled_by_guesswork() {
        try {
            read("""
                <?xml version="1.0"?>
                <LandXML version="1.2">
                  <Units><Metric linearUnit="furlong"/></Units>
                  <Surfaces><Surface name="s"><Definition surfType="TIN"><Pnts>
                    <P id="1">0.0 0.0 1.0</P><P id="2">0.0 10.0 1.0</P><P id="3">10.0 0.0 1.0</P>
                  </Pnts></Definition></Surface></Surfaces>
                </LandXML>
            """.trimIndent())
            throw AssertionError("expected DesignFormatException")
        } catch (e: DesignFormatException) {
            assertTrue(e.message!!.contains("furlong"))
        }
    }
}
