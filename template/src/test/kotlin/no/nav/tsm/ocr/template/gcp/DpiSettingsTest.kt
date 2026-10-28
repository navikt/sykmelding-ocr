package no.nav.tsm.ocr.template.gcp

import no.nav.tsm.sykmelding.template.HomographyMethod
import no.nav.tsm.sykmelding.template.KeypointDetector
import no.nav.tsm.sykmelding.template.MatchFilter
import no.nav.tsm.sykmelding.template.MatchingMethods
import no.nav.tsm.sykmelding.template.TemplateMatcher
import java.util.UUID
import java.util.prefs.Preferences
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DpiSettingsTest {
    @Test
    fun `a typed resolution is a whole number, with or without spaces and DPI after it`() {
        assertEquals(75, parseDpi("75", 10..400))
        assertEquals(75, parseDpi(" 75 ", 10..400))
        assertEquals(75, parseDpi("75 dpi", 10..400))
        assertEquals(75, parseDpi("75DPI", 10..400))
        assertEquals(10, parseDpi("10", 10..400))
        assertEquals(400, parseDpi("400", 10..400))
    }

    @Test
    fun `anything else, or a resolution outside the range, is not one`() {
        for (text in listOf(null, "", " ", "abc", "dpi", "7.5", "-75", "75 px", "75 dpi dpi", "99999999999")) {
            assertNull(parseDpi(text, 1..1200), "\"$text\"")
        }
        assertNull(parseDpi("9", 10..400))
        assertNull(parseDpi("401", 10..400))
    }

    @Test
    fun `features are found at no more than the render DPI, nor the templates' 400`() {
        assertEquals(TestSettings.featureRange(75), 10..75)
        assertEquals(TestSettings.featureRange(600), 10..400)
        assertEquals(TestSettings(400, 100, 400, MatchingMethods(KeypointDetector.SIFT, MatchFilter.RATIO_TEST, HomographyMethod.MAGSAC)),
            TestSettings.PRODUCTION)
    }

    @Test
    fun `a result says which resolutions, matching methods and tolerance it was tested with`() {
        val production = PdfTestResult(PdfStatus.NO_MATCH, settings = TestSettings.PRODUCTION).summary()
        assertTrue("Matched with lib's TemplateMatcher, as in production (SIFT / Ratio test / MAGSAC++ within 4 px, PDF rendered at 400 DPI, " +
            "features found at 100 DPI)." in production, production)

        val other = PdfTestResult(PdfStatus.MATCHED, reads = emptyList(), settings = TestSettings(200, 50, 50,
            MatchingMethods(KeypointDetector.ORB, MatchFilter.CROSS_CHECK, HomographyMethod.RANSAC))).summary()
        assertTrue("with ORB / Cross-check / RANSAC within 8 px, the PDF rendered at 200 DPI and features found at 50 DPI " +
            "(production: SIFT / Ratio test / MAGSAC++ within 4 px, 400 and 100 DPI)." in other, other)
        assertTrue("Fields read with lib's OCR, at 50 DPI (production reads them at 400 DPI); Tesseract is told 70 DPI" in other, other)

        val methodsOnly = PdfTestResult(PdfStatus.NO_MATCH, settings = TestSettings.PRODUCTION.copy(methods = MatchingMethods(KeypointDetector.ORB))).summary()
        assertTrue("with ORB / Ratio test / MAGSAC++ within 4 px, the PDF rendered at 400 DPI" in methodsOnly, "another detector is not production: $methodsOnly")

        val toleranceOnly = PdfTestResult(PdfStatus.NO_MATCH, settings = TestSettings.PRODUCTION.copy(tolerance = 2.5)).summary()
        assertTrue("with SIFT / Ratio test / MAGSAC++ within 2.5 px," in toleranceOnly, "another tolerance is not production: $toleranceOnly")
        assertTrue("as in production" in PdfTestResult(PdfStatus.NO_MATCH, settings = TestSettings.PRODUCTION.copy(tolerance = 4.0)).summary(),
            "4 px typed is what Auto comes to at 100 DPI")

        val untested = PdfTestResult(PdfStatus.ERROR, "Could not download the file").summary()
        assertFalse("Matched with" in untested, "not matched, so no matching settings: $untested")
    }

    @Test
    fun `a matcher is reused only for the same resolutions, methods and tolerance`() {
        TemplateMatcher(templates = emptyList(), methods = MatchingMethods(KeypointDetector.ORB, MatchFilter.CROSS_CHECK)).use { matcher ->
            val settings = TestSettings(400, 100, 300, MatchingMethods(KeypointDetector.ORB, MatchFilter.CROSS_CHECK))
            assertTrue(settings.matchesAs(matcher), "the OCR resolution does not matter to the matcher")
            assertTrue(settings.copy(tolerance = 4.0).matchesAs(matcher), "Auto is 4 px at 100 DPI")
            assertFalse(settings.copy(tolerance = 3.0).matchesAs(matcher))
            assertFalse(settings.copy(methods = MatchingMethods(KeypointDetector.ORB)).matchesAs(matcher))
            assertFalse(settings.copy(methods = MatchingMethods(KeypointDetector.ORB, MatchFilter.CROSS_CHECK, HomographyMethod.RANSAC)).matchesAs(matcher))
            assertFalse(settings.copy(featureDpi = 75).matchesAs(matcher))
        }
    }

    @Test
    fun `a tolerance is Auto or a number of pixels, as typed`() {
        for (text in listOf(null, "", " ", "Auto", "auto", "Auto (4 px)")) assertEquals(ToleranceInput.Auto, parseTolerance(text), "\"$text\"")
        assertEquals(ToleranceInput.Pixels(3.0), parseTolerance("3"))
        assertEquals(ToleranceInput.Pixels(2.5), parseTolerance(" 2.5 px"))
        assertEquals(ToleranceInput.Pixels(2.5), parseTolerance("2,5"))
        assertEquals(ToleranceInput.Pixels(0.5), parseTolerance("0.5"))
        for (text in listOf("0.4", "0", "51", "-3", "abc", "3 mm", "1e3", "2.25")) assertNull(parseTolerance(text), "\"$text\"")
        assertEquals("4 px", pixels(4.0))
        assertEquals("2.5 px", pixels(2.5))
    }

    @Test
    fun `the tolerance field takes what is typed, keeps its value when it is not allowed, and stores Auto as nothing`() {
        SwingUtilities.invokeAndWait {
            val settings = Preferences.userRoot().node("sykmelding-ocr-test-" + UUID.randomUUID())
            try {
                val complaints = mutableListOf<String>()
                fun field() = ToleranceField(settings, "tolerance", { complaints += it }) { 4.0 }
                val first = field()
                assertTrue(first.isEditable)
                assertNull(first.value, "Auto at first")
                assertEquals("Auto", first.selectedItem)
                assertTrue("now 4 px" in first.toolTipText, first.toolTipText)

                first.editor.item = "2.5"
                assertNotNull(first.commit())
                assertEquals(2.5, first.value)
                assertEquals("2.5", settings.get("tolerance", null))
                assertEquals(2.5, field().value, "read back")

                first.editor.item = "lots"
                assertNull(first.commit())
                assertEquals(2.5, first.value)
                assertEquals("2.5", first.editor.item)
                assertEquals("Tolerance: \"lots\" is neither Auto nor a number of pixels from 0.5 to 50, with at most one decimal. It stays at 2.5 px.", complaints.single())

                first.select(null)
                assertNull(settings.get("tolerance", null))
                assertNull(field().value)
            } finally {
                settings.removeNode()
            }
        }
    }

    @Test
    fun `a method dropdown shows production's, keeps the choice in the settings, and falls back to production for an unknown one`() {
        SwingUtilities.invokeAndWait {
            val settings = Preferences.userRoot().node("sykmelding-ocr-test-" + UUID.randomUUID())
            try {
                fun field() = ChoiceField(KeypointDetector.entries, KeypointDetector.SIFT, settings, "detector", KeypointDetector::label,
                    KeypointDetector::description, "Keypoints")
                val first = field()
                assertEquals(KeypointDetector.SIFT, first.value)
                first.select(KeypointDetector.ORB)
                assertEquals("ORB", settings.get("detector", null))
                assertEquals(KeypointDetector.ORB, field().value, "read back")
                settings.put("detector", "SURF")
                assertEquals(KeypointDetector.SIFT, field().value, "not among the detectors: production's")
                val shown = first.renderer.getListCellRendererComponent(JList(), KeypointDetector.SIFT, 0, false, false) as JLabel
                assertEquals("SIFT (production)", shown.text)
            } finally {
                settings.removeNode()
            }
        }
    }

    @Test
    fun `a DPI field takes what is typed, also before Enter, and keeps its value when it is not allowed`() {
        SwingUtilities.invokeAndWait {
            val complaints = mutableListOf<String>()
            val changes = mutableListOf<Int>()
            var max = 400
            val field = DpiField("Feature DPI", listOf(50, 100), 100, { 10..max }, null, "key", { complaints += it })
            field.onChange = { changes += it }
            assertTrue(field.isEditable)
            // Laid out as in the view: the look and feel has an editor to measure.
            assertTrue(field.preferredSize.width > 0)
            assertEquals(100, field.value)
            assertEquals("100", field.selectedItem)

            field.editor.item = "35"
            assertEquals(35, field.commit()?.value)
            assertEquals(35, field.value)
            assertEquals(listOf(35), changes)

            field.editor.item = "abc"
            assertNull(field.commit())
            assertEquals(35, field.value)
            assertEquals("35", field.editor.item)
            assertEquals("Feature DPI: \"abc\" is not a whole number of DPI from 10 to 400. It stays at 35 DPI.", complaints.single())

            max = 30
            assertNull(field.commit(), "35 is no longer allowed")
            assertEquals(2, complaints.size)
            field.select(25)
            assertEquals(listOf(35, 25), changes)
        }
    }
}
