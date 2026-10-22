package no.nav.tsm.ocr.template.view

import no.nav.tsm.ocr.template.gcp.BucketTemplateTestView
import no.nav.tsm.ocr.template.gcp.GcpBucketView
import no.nav.tsm.ocr.template.gcp.SaveFolders
import org.example.no.nav.tsm.ocr.template.template.TemplateLibrary
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitPanesTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `every divider in the bucket views and the comparison view can be dragged both ways`() {
        SwingUtilities.invokeAndWait {
            val views = listOf(
                "bucket test" to BucketTemplateTestView(settings = null, library = TemplateLibrary(File("../lib/src/main/resources/templates")), testFolder = null),
                "bucket" to GcpBucketView(folders = SaveFolders(directory), settings = null),
                "compare" to ImageComparisonView(),
            )
            for (size in listOf(Dimension(1500, 900), Dimension(1100, 700))) for ((name, view) in views) {
                view.size = size
                layOut(view)
                val splits = components(view).filterIsInstance<JSplitPane>()
                assertTrue(splits.isNotEmpty(), name)
                var checked = 0
                for (split in splits) {
                    val horizontal = split.orientation == JSplitPane.HORIZONTAL_SPLIT
                    val length = if (horizontal) split.width else split.height
                    val label = "$name ${size.width}×${size.height}, ${if (horizontal) "left/right" else "top/bottom"} divider of $length px"
                    if (length < 3 * MIN_SPLIT_SIDE) continue
                    checked++
                    assertTrue(split.isContinuousLayout, label)
                    // The bug: a side's own minimum filled the pane, so the divider started out of reach and could only go one way.
                    assertTrue(split.dividerLocation in split.minimumDividerLocation..split.maximumDividerLocation,
                        "$label starts at ${split.dividerLocation}, outside ${split.minimumDividerLocation}..${split.maximumDividerLocation}")
                    assertEquals(MIN_SPLIT_SIDE, split.minimumDividerLocation, label)
                    assertEquals(length - MIN_SPLIT_SIDE - split.dividerSize, split.maximumDividerLocation, label)
                    for (target in listOf(MIN_SPLIT_SIDE + 20, length - MIN_SPLIT_SIDE - split.dividerSize - 20, MIN_SPLIT_SIDE + 40)) {
                        split.dividerLocation = target
                        layOut(split)
                        assertEquals(target, split.dividerLocation, label)
                        val first = split.leftComponent
                        assertEquals(target, if (horizontal) first.width else first.height, "$label: first side after dragging to $target")
                    }
                }
                // The bucket test view has six dividers; the others one or two.
                assertTrue(checked >= minOf(splits.size, 2), "$name ${size.width}×${size.height}: checked $checked of ${splits.size} dividers")
            }
        }
    }

    @Test
    fun `a split pane starts at its share once it has a size, then keeps where it was dragged`() {
        SwingUtilities.invokeAndWait {
            val split = splitPane(JSplitPane.HORIZONTAL_SPLIT, JPanel(), JPanel(), location = 0.25, weight = 0.5)
            split.size = Dimension(1010, 400)
            layOut(split)
            assertEquals(250, split.dividerLocation)
            split.dividerLocation = 700
            layOut(split)
            layOut(split)
            assertEquals(700, split.dividerLocation)
            assertEquals(Dimension(MIN_SPLIT_SIDE, MIN_SPLIT_SIDE), split.leftComponent.minimumSize)
            assertEquals(SPLIT_DIVIDER_SIZE, split.dividerSize)
        }
    }

    @Test
    fun `a wrapping toolbar puts what does not fit on more rows instead of hiding it`() {
        SwingUtilities.invokeAndWait {
            val bar = JPanel(WrapLayout())
            repeat(6) { bar.add(JButton("Button $it")) }
            val oneRow = bar.preferredSize.height
            val holder = JPanel(java.awt.BorderLayout()).apply { add(bar, java.awt.BorderLayout.NORTH) }
            holder.size = Dimension(bar.preferredSize.width / 2, 400)
            layOut(holder)
            assertTrue(bar.preferredSize.height > oneRow, "wraps: ${bar.preferredSize.height} > $oneRow")
            layOut(holder)
            bar.components.map { it as JButton }.forEach { button ->
                assertTrue(button.x >= 0 && button.x + button.width <= bar.width, "${button.text} at ${button.bounds} in ${bar.width}")
                assertTrue(button.y + button.height <= bar.height, "${button.text} at ${button.bounds}, bar ${bar.height} high")
            }
        }
    }
}

/** Lays out [component] and everything in it, as showing it would: validate() does nothing until it is on screen. */
internal fun layOut(component: Component) {
    if (component !is Container) return
    component.doLayout()
    component.components.forEach(::layOut)
}

internal fun components(component: Component): List<Component> =
    listOf(component) + ((component as? Container)?.components?.flatMap(::components) ?: emptyList())
