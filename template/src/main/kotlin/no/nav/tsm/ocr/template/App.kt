package no.nav.tsm.ocr.template

import no.nav.tsm.ocr.template.view.MainView
import java.awt.EventQueue

fun main() {
    EventQueue.invokeAndWait {
        MainView().isVisible = true
    }
}