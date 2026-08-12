package com.fieldgrade.app.design

import com.fieldgrade.app.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [XyzPointReader] in isolation: text in, points out. No files, no geometry. */
class XyzPointReaderTest {

    // ---- layouts ----

    @Test fun plain_three_column_is_east_north_elev() {
        val r = XyzPointReader.read("100.0,200.0,15.5\n101.0,201.0,15.6\n102.0,202.0,15.7")
        assertEquals(3, r.points.size)
        assertEquals(100.0, r.points[0].eastM, 1e-9)
        assertEquals(200.0, r.points[0].northM, 1e-9)
        assertEquals(15.5, r.points[0].elevationM, 1e-9)
    }

    @Test fun five_column_industry_layout_skips_point_number_and_code() {
        val r = XyzPointReader.read(
            """
            Point,Easting,Northing,Elevation,Code
            1,1000.5,2000.25,390.125,DESIGN
            2,1001.5,2001.25,390.225,DESIGN
            3,1002.5,2002.25,390.325,DESIGN
            """.trimIndent()
        )
        assertEquals(3, r.points.size)
        assertEquals(1000.5, r.points[0].eastM, 1e-9)
        assertEquals(2000.25, r.points[0].northM, 1e-9)
        assertEquals(390.125, r.points[0].elevationM, 1e-9)
    }

    @Test fun header_names_override_column_order() {
        // Northing first: only the header reveals it, so the header must win.
        val r = XyzPointReader.read(
            """
            Northing,Easting,Elevation
            2000.0,1000.0,390.0
            2001.0,1001.0,391.0
            2002.0,1002.0,392.0
            """.trimIndent()
        )
        assertTrue(r.headerUsed)
        assertEquals(1000.0, r.points[0].eastM, 1e-9)
        assertEquals(2000.0, r.points[0].northM, 1e-9)
    }

    @Test fun explicit_layout_beats_inference() {
        val r = XyzPointReader.read(
            "1,10.0,20.0,30.0",
            layout = XyzPointReader.Layout.POINT_ENZ
        )
        assertEquals(10.0, r.points[0].eastM, 1e-9)
        assertEquals(30.0, r.points[0].elevationM, 1e-9)
    }

    // ---- delimiters and noise ----

    @Test fun accepts_tab_semicolon_and_space_delimiters() {
        val tab = XyzPointReader.read("1.0\t2.0\t3.0")
        val semi = XyzPointReader.read("1.0;2.0;3.0")
        val space = XyzPointReader.read("1.0  2.0   3.0")
        for (r in listOf(tab, semi, space)) {
            assertEquals(1, r.points.size)
            assertEquals(3.0, r.points[0].elevationM, 1e-9)
        }
    }

    @Test fun blank_lines_and_comments_are_ignored() {
        val r = XyzPointReader.read(
            """
            # exported 2026-08-12

            1.0,2.0,3.0
            // a note
            4.0,5.0,6.0
            """.trimIndent()
        )
        assertEquals(2, r.points.size)
    }

    // ---- refusal ----

    @Test(expected = DesignFormatException::class)
    fun empty_input_is_rejected() {
        XyzPointReader.read("\n\n   \n")
    }

    @Test(expected = DesignFormatException::class)
    fun two_columns_cannot_be_a_surface() {
        XyzPointReader.read("1.0,2.0\n3.0,4.0")
    }

    @Test(expected = DesignFormatException::class)
    fun mostly_rubbish_is_rejected_rather_than_partially_imported() {
        // 1 good row in 10: importing that silently would be a surface with a hole.
        val text = buildString {
            append("1.0,2.0,3.0\n")
            repeat(9) { append("not,a,point\n") }
        }
        XyzPointReader.read(text)
    }

    @Test fun a_few_bad_rows_are_skipped_and_counted() {
        val text = buildString {
            repeat(50) { append("${it}.0,${it}.0,${it}.0\n") }
            append("bad,row,here\n")
        }
        val r = XyzPointReader.read(text)
        assertEquals(50, r.points.size)
        assertEquals(1, r.skippedLines)
    }

    // ---- against the real file ----

    @Test fun reads_the_real_nunosurf_export() {
        val r = XyzPointReader.read(SampleData.nunosurfXyz().readText())
        assertEquals(7048, r.points.size)
        assertEquals(0, r.skippedLines)
        assertTrue("header should have been recognised", r.headerUsed)

        val b = DesignSurface("x", r.points).bounds()
        assertNotNull(b)
        // Local grid: shifted so the min corner is the origin.
        assertEquals(0.0, b!!.minE, 0.5)
        assertEquals(0.0, b.minN, 0.5)
        // Metres, converted from the file's USSurveyFoot on the way in.
        assertEquals(226.7, b.widthM, 1.0)
        assertEquals(196.2, b.heightM, 1.0)
        assertEquals(0.61, b.reliefM, 0.01)
    }
}
