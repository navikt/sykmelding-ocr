package no.nav.tsm.ocr.template.gcp

import java.awt.EventQueue

/** Standalone entry point: browse the OCR bucket and save files into ~/ocr/gcp/<folder>. */
fun main() {
    EventQueue.invokeLater { GcpBucketView.open(exitOnClose = true) }
}
