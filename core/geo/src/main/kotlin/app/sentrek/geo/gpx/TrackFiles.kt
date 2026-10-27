package app.sentrek.geo.gpx

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads GPX, KML or KMZ, telling them apart by content: shared files often have no useful name or type. */
object TrackFiles {
    /** Larger files are certainly not hiking routes, and would not fit in memory on old phones. */
    private const val MAX_BYTES = 50L * 1024 * 1024

    fun read(input: InputStream): GpxFile {
        val bytes = readLimited(input)
        return when {
            bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> readKmz(bytes)
            else -> readXml(bytes)
        }
    }

    private fun readXml(bytes: ByteArray): GpxFile {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
        return when {
            head.contains("<gpx") -> Gpx.read(ByteArrayInputStream(bytes))
            head.contains("<kml") -> Kml.read(ByteArrayInputStream(bytes))
            else -> throw UnsupportedFormatException()
        }
    }

    /** KMZ is a zip with the KML inside (usually doc.kml, but any .kml works). */
    private fun readKmz(bytes: ByteArray): GpxFile {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)) {
                    return Kml.read(ByteArrayInputStream(readLimited(zip)))
                }
            }
        }
        throw UnsupportedFormatException()
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("File too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    class UnsupportedFormatException : IOException("Not a GPX, KML or KMZ file")
}
