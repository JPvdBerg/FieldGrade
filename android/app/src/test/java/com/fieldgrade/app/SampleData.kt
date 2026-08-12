package com.fieldgrade.app

import java.io.File

/**
 * Locates the checked-in sample data, whatever directory the test runner started in.
 *
 * One job: turn a name into a [File]. Tests that need real inputs use this rather
 * than each inventing its own relative path.
 */
object SampleData {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "tools/sampledata").isDirectory) return@lazy File(dir, "tools/sampledata")
            dir = dir.parentFile
        }
        error("could not locate tools/sampledata from ${File("").absolutePath}")
    }

    fun design(name: String): File = File(root, "design/$name").also {
        check(it.isFile) { "missing sample design file: ${it.absolutePath}" }
    }

    fun nmea(name: String): File = File(root, "nmea/$name").also {
        check(it.isFile) { "missing sample NMEA file: ${it.absolutePath}" }
    }

    /** The near-flat 48 ha surface — the land-levelling design stand-in. */
    fun nunosurfXml(): File = design("nunosurf.xml")

    /** Same surface re-encoded as XYZ text. */
    fun nunosurfXyz(): File = design("nunosurf_design.xyz")

    /** The 113 ha rolling farm — the existing-ground stand-in. */
    fun brattonFarmXml(): File = design("bratton_farm.xml")

    /** REAL consumer-grade log: quality 1, no GST, messy. */
    fun consumerNmea(): File = nmea("amod_agl3080_consumer.txt")

    /** SYNTHETIC RTK track over the nunosurf field. */
    fun rtkTrack(): File = nmea("site_track_rtk_SYNTHETIC.nmea")
}
