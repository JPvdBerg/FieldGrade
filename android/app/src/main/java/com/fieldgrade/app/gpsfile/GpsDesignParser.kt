package com.fieldgrade.app.gpsfile

import com.fieldgrade.app.design.DesignSurface
import java.io.InputStream

/**
 * Reader for the vendor `.gps` design file.
 *
 * `.gps` is the **Trimble AgGPS FieldLevel** machine-control format — that is what
 * design tools such as OptiSurface list when they "export machine control file as
 * AgGPS Field Level (*.gps)". It is proprietary and undocumented, and
 * PROJECT_PLAN section 12 rules out reimplementing a vendor's proprietary file
 * format. So this stays unimplemented on purpose, and throws rather than guesses.
 *
 * It is no longer on the critical path. The same designs are exchanged as XYZ text
 * and LandXML, both open and both implemented in [com.fieldgrade.app.design]:
 *   - [com.fieldgrade.app.design.XyzPointReader]
 *   - [com.fieldgrade.app.design.LandXmlSurfaceReader]
 *
 * Ask the design house for an XYZ or LandXML export of the same field. If a
 * documented `.gps` specification is ever licensed, implement it behind this
 * interface and everything downstream keeps working unchanged.
 */
interface GpsDesignParser {
    /** Parse the real vendor design file. Never silently guess a format. */
    fun parse(input: InputStream): DesignSurface
}

class UnsupportedGpsDesignParser : GpsDesignParser {
    override fun parse(input: InputStream): DesignSurface {
        // Read up to 32 header bytes without InputStream.readNBytes (API 33+); minSdk is 26.
        val header = ByteArray(32)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        val signature = header.copyOf(read).joinToString("") { "%02x".format(it) }
        throw UnsupportedOperationException(
            "'.gps' is the proprietary Trimble AgGPS FieldLevel format and is not " +
                "implemented — request an XYZ or LandXML export of the same design " +
                "instead. First $read bytes: $signature"
        )
    }
}
