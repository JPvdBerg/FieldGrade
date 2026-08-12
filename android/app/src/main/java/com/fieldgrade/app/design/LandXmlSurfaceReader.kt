package com.fieldgrade.app.design

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Reads a TIN surface out of a LandXML file.
 *
 * One job: LandXML bytes -> [DesignSurface] (points + the designer's own faces).
 * It does not triangulate, interpolate, or open files.
 *
 * LandXML is an open, published schema (landxml.org), so this is a specification
 * implementation — not the reverse engineering of a proprietary format that
 * PROJECT_PLAN section 12 rules out.
 *
 * Parsed with SAX because it streams: the sample farm surface is 2.2 MB and a
 * real one is larger, and a DOM tree of that on a tablet is wasteful.
 *
 * Three details verified against real files rather than assumed — each one is a
 * silent-corruption bug if guessed wrong:
 *
 *  1. `<P>` payload order is **north east elevation**. Reading it as east/north
 *     mirrors the whole field about a diagonal.
 *  2. `<P id=...>` values are **not contiguous** — a real 7,048-point sample runs
 *     to id 7063 with gaps. Faces reference *ids*, so `index = id - 1` quietly
 *     shifts triangles onto the wrong vertices. Ids are mapped explicitly.
 *  3. `<F>` indices are 1-based ids, and per the schema a face may carry `i="1"`
 *     to mark it deleted/invisible; those are skipped.
 *  4. **Units are declared, not assumed.** One real sample is `<Metric
 *     linearUnit="meter">` and another is `<Imperial linearUnit="USSurveyFoot">`.
 *     [DesignPoint] is defined in metres, so coordinates are converted on the way
 *     in and an unrecognised unit is refused rather than passed through. Treating
 *     survey feet as metres inflates a field by 3.28x and every cut with it.
 */
object LandXmlSurfaceReader {

    /**
     * LandXML `linearUnit` -> metres. Values are the schema's enumeration.
     * A US survey foot is 1200/3937 m, which differs from the international
     * foot in the sixth decimal — irrelevant over a blade width, ~1 mm over a
     * kilometre of field, so it is kept exact rather than approximated.
     */
    private val LINEAR_UNITS_TO_METRES = mapOf(
        "meter" to 1.0,
        "metre" to 1.0,
        "millimeter" to 0.001,
        "millimetre" to 0.001,
        "centimeter" to 0.01,
        "centimetre" to 0.01,
        "kilometer" to 1000.0,
        "kilometre" to 1000.0,
        "foot" to 0.3048,
        "ussurveyfoot" to 1200.0 / 3937.0,
        "inch" to 0.0254,
        "mile" to 1609.344,
        "yard" to 0.9144
    )

    private fun metresPerUnit(linearUnit: String?): Double {
        if (linearUnit == null) return 1.0   // undeclared: LandXML default is metric
        return LINEAR_UNITS_TO_METRES[linearUnit.trim().lowercase()]
            ?: throw DesignFormatException(
                "unrecognised LandXML linearUnit '$linearUnit' — refusing to guess " +
                    "a scale factor; supported: ${LINEAR_UNITS_TO_METRES.keys.sorted()}"
            )
    }

    /**
     * @param surfaceName read this named surface; null takes the first one found.
     * @throws DesignFormatException when no usable surface is present.
     */
    fun read(input: InputStream, surfaceName: String? = null): DesignSurface {
        val handler = SurfaceHandler(surfaceName)
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                // Never resolve external entities from a file we did not write.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            factory.newSAXParser().parse(input, handler)
        } catch (stop: StopParsing) {
            // Wanted surface finished; the rest of the document is irrelevant.
        } catch (e: Exception) {
            throw DesignFormatException("could not parse LandXML: ${e.message}", e)
        }

        if (handler.points.isEmpty()) {
            throw DesignFormatException(
                if (surfaceName != null) "no surface named '$surfaceName' with points"
                else "no <Surface> with a <Pnts> point list found"
            )
        }

        // Faces reference point ids; resolve to list indices, dropping any face
        // that references an id the file never defined.
        val triangles = ArrayList<Triangle>(handler.faceIds.size / 3)
        var dangling = 0
        var i = 0
        while (i + 2 < handler.faceIds.size) {
            val a = handler.idToIndex[handler.faceIds[i]]
            val b = handler.idToIndex[handler.faceIds[i + 1]]
            val c = handler.idToIndex[handler.faceIds[i + 2]]
            if (a == null || b == null || c == null) dangling++ else triangles.add(Triangle(a, b, c))
            i += 3
        }

        // Convert to metres now, so nothing downstream has to care what the
        // file was authored in.
        val scale = metresPerUnit(handler.linearUnit)
        val points = if (scale == 1.0) handler.points else handler.points.map {
            DesignPoint(it.eastM * scale, it.northM * scale, it.elevationM * scale)
        }

        val metadata = HashMap<String, String>()
        handler.surfaceName?.let { metadata["surface"] = it }
        handler.surfType?.let { metadata["surfType"] = it }
        metadata["sourceLinearUnit"] = handler.linearUnit ?: "unspecified"
        metadata["metresPerSourceUnit"] = scale.toString()
        metadata["linearUnit"] = "meter"     // what the returned points are in
        if (dangling > 0) metadata["danglingFaces"] = dangling.toString()
        if (handler.skippedFaces > 0) metadata["skippedFaces"] = handler.skippedFaces.toString()

        return DesignSurface(
            name = handler.surfaceName ?: "LandXML surface",
            points = points,
            triangles = triangles,
            metadata = metadata
        )
    }

    private class StopParsing : RuntimeException(null, null, false, false)

    private class SurfaceHandler(private val wanted: String?) : DefaultHandler() {
        val points = ArrayList<DesignPoint>()
        val idToIndex = HashMap<Int, Int>()
        val faceIds = ArrayList<Int>()
        var surfaceName: String? = null
        var linearUnit: String? = null
        var surfType: String? = null
        var skippedFaces = 0

        private var inWantedSurface = false
        private var capturing: Capture? = null
        private var currentId = -1
        private val text = StringBuilder()

        private enum class Capture { POINT, FACE }

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            when (qName.substringAfter(':')) {
                "Metric", "Imperial" ->
                    linearUnit = linearUnit ?: attrs.getValue("linearUnit")

                "Surface" -> {
                    val name = attrs.getValue("name")
                    if (!inWantedSurface && points.isEmpty()) {
                        if (wanted == null || wanted == name) {
                            inWantedSurface = true
                            surfaceName = name
                        }
                    }
                }

                "Definition" -> if (inWantedSurface) surfType = attrs.getValue("surfType")

                "P" -> if (inWantedSurface) {
                    currentId = attrs.getValue("id")?.toIntOrNull() ?: -1
                    capturing = Capture.POINT
                    text.setLength(0)
                }

                "F" -> if (inWantedSurface) {
                    // Schema allows i="1" to mark a face deleted/invisible.
                    val invisible = attrs.getValue("i")?.takeIf { it.isNotEmpty() && it != "0" }
                    if (invisible != null) {
                        skippedFaces++
                        capturing = null
                    } else {
                        capturing = Capture.FACE
                        text.setLength(0)
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing != null) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName.substringAfter(':')) {
                "P" -> {
                    if (capturing == Capture.POINT) {
                        // LandXML PntList3D order: north, east, elevation.
                        val parts = text.toString().trim().split(WHITESPACE)
                        if (parts.size >= 3) {
                            val north = parts[0].toDoubleOrNull()
                            val east = parts[1].toDoubleOrNull()
                            val elev = parts[2].toDoubleOrNull()
                            if (north != null && east != null && elev != null) {
                                if (currentId >= 0) idToIndex[currentId] = points.size
                                points.add(DesignPoint(east, north, elev))
                            }
                        }
                    }
                    capturing = null
                }

                "F" -> {
                    if (capturing == Capture.FACE) {
                        val parts = text.toString().trim().split(WHITESPACE)
                        // Take the first three; a 4-index grid face degrades to
                        // its leading triangle rather than being dropped.
                        if (parts.size >= 3) {
                            val a = parts[0].toIntOrNull()
                            val b = parts[1].toIntOrNull()
                            val c = parts[2].toIntOrNull()
                            if (a != null && b != null && c != null) {
                                faceIds.add(a); faceIds.add(b); faceIds.add(c)
                            }
                        }
                    }
                    capturing = null
                }

                "Surface" -> if (inWantedSurface) {
                    inWantedSurface = false
                    if (points.isNotEmpty()) throw StopParsing()
                }
            }
        }

        private companion object {
            val WHITESPACE = Regex("\\s+")
        }
    }
}
