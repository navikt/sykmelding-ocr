package org.example.no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.ocr.Ocr
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ExecutionException
import javax.imageio.ImageIO
import javax.swing.*

fun main(args: Array<String>) {
    val images = listOf("/img.png", "/img_2.png").map { resource ->
        File("template/src/test/resources/$resource").inputStream()
            .use { ImageIO.read(it) }
            ?: error("Missing or unreadable $resource")
    }
    val overlay = createDarkOverlay(images[1])

    SwingUtilities.invokeLater {
        val leftPanel = ImagePanel(images[0], overlay)
        val rightPanel = ImagePanel(images[1])
        val status = JLabel("Reference overlay")
        val processButton = JButton("Process images")
        val ocrButton = JButton("OCR")
        processButton.addActionListener {
            onProcessImages(leftPanel, rightPanel, images[1], images[0], processButton, ocrButton, status)
        }
        ocrButton.addActionListener {
            onSelectOcrArea(leftPanel, rightPanel, processButton, ocrButton, status)
        }
        JFrame("Ocr Text:").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            setSize(800, 600)
            setLocationRelativeTo(null)
            add(JPanel(GridLayout(1, 2, 8, 0)).apply {
                add(leftPanel)
                add(rightPanel)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(JPanel().apply {
                    add(processButton)
                    add(ocrButton)
                }, BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
            isVisible = true
        }
    }
}

private fun onProcessImages(
    leftPanel: ImagePanel,
    rightPanel: ImagePanel,
    reference: BufferedImage,
    target: BufferedImage,
    button: JButton,
    ocrButton: JButton,
    status: JLabel
) {
    button.isEnabled = false
    ocrButton.isEnabled = false
    status.text = "Aligning..."
    object : SwingWorker<AlignedImages, Void>() {
        override fun doInBackground(): AlignedImages = alignImages(reference, target)

        override fun done() {
            try {
                val aligned = get()
                leftPanel.overlay = aligned.overlay
                rightPanel.image = aligned.image
                leftPanel.repaint()
                rightPanel.repaint()
                status.text = "Image and overlay aligned with rotation/scale only"
            } catch (exception: ExecutionException) {
                status.text = "Alignment failed: ${exception.cause?.message ?: exception.message}"
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                status.text = "Alignment interrupted"
            } finally {
                button.isEnabled = true
                ocrButton.isEnabled = true
            }
        }
    }.execute()
}

private fun onSelectOcrArea(
    leftPanel: ImagePanel,
    rightPanel: ImagePanel,
    processButton: JButton,
    ocrButton: JButton,
    status: JLabel
) {
    if (leftPanel.isSelecting) {
        leftPanel.cancelSelection()
        rightPanel.cancelSelection()
        processButton.isEnabled = true
        status.text = "OCR selection cancelled"
        return
    }

    processButton.isEnabled = false
    status.text = "Drag an area on either picture. Click OCR again to cancel."
    val onSelected: (BufferedImage) -> Unit = { crop ->
        leftPanel.cancelSelection()
        rightPanel.cancelSelection()
        ocrButton.isEnabled = false
        status.text = "Recognizing selected area..."
        object : SwingWorker<String, Void>() {
            override fun doInBackground(): String =
                checkNotNull(Ocr().parse(crop)) { "OCR returned no result" }

            override fun done() {
                try {
                    val text = get()
                    status.text = if (text.isBlank()) "No text found in the selected area" else "OCR complete"
                    val result = JTextArea(
                        if (text.isBlank()) "No text found in the selected area." else text,
                        12, 50
                    ).apply {
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                        caretPosition = 0
                    }
                    JOptionPane.showMessageDialog(leftPanel, JScrollPane(result), "OCR result", JOptionPane.INFORMATION_MESSAGE)
                } catch (exception: ExecutionException) {
                    status.text = "OCR failed: ${exception.cause?.message ?: exception.message}"
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    status.text = "OCR interrupted"
                } finally {
                    processButton.isEnabled = true
                    ocrButton.isEnabled = true
                }
            }
        }.execute()
    }
    val onInvalidSelection = { status.text = "Drag a non-empty area inside either picture." }
    leftPanel.selectArea(onSelected, onInvalidSelection)
    rightPanel.selectArea(onSelected, onInvalidSelection)
}

internal fun createDarkOverlay(image: BufferedImage): BufferedImage {
    val overlay = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            val red = (pixel ushr 16) and 0xff
            val green = (pixel ushr 8) and 0xff
            val blue = pixel and 0xff
            val brightness = (299 * red + 587 * green + 114 * blue) / 1000
            val sourceAlpha = pixel ushr 24
            val opacity = (sourceAlpha * (255 - brightness) + 127) / 255
            if (opacity > 0) {
                overlay.setRGB(x, y, (opacity shl 24) or 0x00ff0000)
            }
        }
    }
    return overlay
}
