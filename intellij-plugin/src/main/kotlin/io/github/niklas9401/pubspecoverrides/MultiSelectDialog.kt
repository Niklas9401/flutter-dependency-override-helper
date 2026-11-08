package com.niklasweiblen.flutterpubspecoverridehelper

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Modal dialog with a check box per item (the IntelliJ counterpart of VS Code's multi-select quick pick).
 *
 * @param items pairs of (value, text shown in the list); the order is preserved.
 */
class MultiSelectDialog<T>(
    project: Project,
    dialogTitle: String,
    private val items: List<Pair<T, String>>
) : DialogWrapper(project) {

    private val list = CheckBoxList<T>()

    init {
        title = dialogTitle
        items.forEach { (value, text) -> list.addItem(value, text, false) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(560, 320)
        return scroll
    }

    /** The values whose check box is ticked, in list order. */
    fun selected(): List<T> = items.indices.filter { list.isItemSelected(it) }.map { items[it].first }
}
