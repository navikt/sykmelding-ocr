package no.nav.tsm.ocr.template.gcp

import java.awt.Desktop
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel

/**
 * Shows how much the [cache] holds, with buttons to open its folder and to delete the files. [say] reports what was
 * done in the view's status line. Call [refresh] after downloads.
 */
internal class DownloadFolderControls(private val cache: DownloadCache, private val say: (String) -> Unit) :
    JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)) {
    private val label = JLabel(" ")
    private val open = JButton("Open download folder").apply {
        toolTipText = "Show the downloaded files: ${cache.directory.absolutePath}"
        addActionListener { open(cache.directory) }
    }
    private val clear = JButton("Clear downloads").apply {
        toolTipText = "Delete the downloaded files. They are downloaded again when they are opened."
        addActionListener { confirmClear() }
    }

    init {
        add(label)
        add(open)
        add(clear)
        refresh()
    }

    fun refresh() {
        val contents = cache.contents()
        label.text = "Downloaded: ${contents.files} file${plural(contents.files)}, ${formatSize(contents.bytes)}"
        label.toolTipText = "<html>Opened files are kept in ${cache.directory.absolutePath}<br>(readable by you only), so they " +
            "are not downloaded again, also after a restart.<br>Files not opened for ${DownloadCache.DEFAULT_MAX_AGE.toDays()} days " +
            "are deleted when the app starts.</html>"
        clear.isEnabled = contents.files > 0
    }

    /** Shows [file] in the file manager, or its folder where that is not supported. */
    fun reveal(file: File) {
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            runCatching { desktop.browseFileDirectory(file) }.onFailure { say("Could not show ${file.path}: ${it.message}") }
        } else open(file.parentFile)
    }

    private fun open(folder: File) {
        runCatching { Desktop.getDesktop().open(folder) }.onFailure { say("Could not open ${folder.path}: ${it.message}") }
    }

    private fun confirmClear() {
        val contents = cache.contents()
        if (JOptionPane.showConfirmDialog(this, "Delete ${contents.files} downloaded file${plural(contents.files)} " +
                "(${formatSize(contents.bytes)}) from ${cache.directory.absolutePath}?\nThey are downloaded again when they are opened.",
                "Clear downloads", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return
        val cleared = cache.clear()
        refresh()
        say("Deleted ${cleared.deleted} downloaded file${plural(cleared.deleted)}" +
            if (cleared.kept > 0) "; ${cleared.kept} in use now were kept." else ".")
    }
}
