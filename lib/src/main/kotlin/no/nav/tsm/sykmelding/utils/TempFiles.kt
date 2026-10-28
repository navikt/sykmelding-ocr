package no.nav.tsm.sykmelding.utils

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A new folder in java.io.tmpdir, for resources that must be files: see [writingToTemp]. */
internal fun tempDirectory(prefix: String): Path =
    writingToTemp("create a $prefix folder") { Files.createTempDirectory(prefix) }

/**
 * Runs [write], which writes to java.io.tmpdir. The lib copies its templates and OCR models there, since they must be
 * files, so a failure to write says that java.io.tmpdir must be writable rather than just which file failed.
 */
internal inline fun <T> writingToTemp(what: String, write: () -> T): T = try {
    write()
} catch (e: IOException) {
    throw IllegalStateException("Could not $what in java.io.tmpdir (${System.getProperty("java.io.tmpdir")}): " +
        "sykmelding-ocr copies its templates and OCR models there, so it must be writable.", e)
}
