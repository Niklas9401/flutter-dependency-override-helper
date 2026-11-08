package com.niklasweiblen.flutterpubspecoverridehelper

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

/**
 * Settings page: Settings | Tools | Flutter Pubspec Overrides.
 *
 * Four titled groups: Local packages (folder list), Git repositories (table), General, and
 * Inline links (CodeLens actions) with one check box per link.
 */
class PubspecOverridesConfigurable(private val project: Project) : Configurable {

    // ---- local packages ----
    private val foldersModel = CollectionListModel<String>()
    private val foldersList = JBList(foldersModel)
    private val depthSpinner = JSpinner(SpinnerNumberModel(2, 1, 5, 1))

    // ---- git repositories ----
    private val reposModel = DefaultTableModel(arrayOf<Any>("Package name", "Git URL", "Sub-folder in repo (optional)"), 0)
    private val reposTable = JBTable(reposModel)
    private val defaultRefField = JBTextField()

    // ---- general ----
    private val runPubGetBox = JBCheckBox("Run 'dart pub get' / 'flutter pub get' after every change to the pubspec")
    private val autoConfirmBox = JBCheckBox("Don't ask for confirmation when an override switches type (local ↔ git)")

    // ---- inline links (CodeLens actions) ----
    private val inlineBox = JBCheckBox("Show inline links (CodeLens actions) above the package names in pubspec.yaml")

    // One check box per inline link; they only matter while the master check box above is selected.
    private val upgradeBox = JBCheckBox("\"Upgrade package\" link – runs 'pub upgrade <package>' (on dependencies / dev_dependencies)")
    private val addOverrideBox = JBCheckBox("\"Add override\" link – override a dependency with a local package or Git repo (on dependencies / dev_dependencies)")
    private val removePackageBox = JBCheckBox("\"Remove package\" link – removes the dependency (on dependencies / dev_dependencies)")
    private val removeOverrideBox = JBCheckBox("\"Remove override\" link – removes an entry (on dependency_overrides)")
    private val switchLocalBox = JBCheckBox("\"Switch to Local\" link – replaces an override by the local package (on dependency_overrides)")
    private val switchGitBox = JBCheckBox("\"Switch to Git\" link – replaces an override by the configured Git repo (on dependency_overrides)")
    private val actionBoxes = listOf(upgradeBox, addOverrideBox, removePackageBox, removeOverrideBox, switchLocalBox, switchGitBox)

    private val state get() = PubspecOverridesSettings.getInstance(project).current

    override fun getDisplayName(): String = "Flutter Pubspec Overrides"

    // ------------------------------------------------------------------ UI construction

    override fun createComponent(): JComponent {
        // Texts shown while the list/table is empty – they say what to do next.
        foldersList.emptyText.setText("No folders yet – click + and choose the folder that contains your local packages")
        reposTable.emptyText.setText("No Git repositories yet – click + to add one")

        // --- intro ---
        val intro = hint(
            "<b>Flutter Pubspec Overrides</b> (plugin version ${pluginVersion()}) helps you with <code>dependency_overrides</code> " +
                "in a pubspec.yaml"
        )

        // --- local packages: list with + (folder chooser), pencil (type a path) and − ---
        val foldersPanel = ToolbarDecorator.createDecorator(foldersList)
            .setAddAction { addFolders() }
            .setEditAction { editFolder() }
            .setRemoveAction { removeFolders() }
            .disableUpDownActions()
            .createPanel()
        foldersPanel.preferredSize = Dimension(560, 110)

        val localGroup = FormBuilder.createFormBuilder()
            .addComponent(foldersPanel)
            .addLabeledComponent("Search depth (how many sub-folder levels are searched for packages):", depthSpinner)
            .addComponent(checkFoldersButton())
            .panel
        localGroup.border = IdeBorderFactory.createTitledBorder("Local packages")

        // --- git repositories: editable table ---
        val reposPanel = ToolbarDecorator.createDecorator(reposTable)
            .setAddAction { addRepoRow() }
            .setRemoveAction { removeRepoRows() }
            .disableUpDownActions()
            .createPanel()
        reposPanel.preferredSize = Dimension(560, 140)

        val gitGroup = FormBuilder.createFormBuilder()
            .addComponent(reposPanel)
            .addLabeledComponent("Fallback branch/tag/commit (pre-filled if the current branch cannot be detected):", defaultRefField)
            .panel
        gitGroup.border = IdeBorderFactory.createTitledBorder("Git repositories")

        // --- general ---
        val generalGroup = FormBuilder.createFormBuilder()
            .addComponent(runPubGetBox)
            .addComponent(autoConfirmBox)
            .panel
        generalGroup.border = IdeBorderFactory.createTitledBorder("General")

        // --- inline links (CodeLens actions) ---
        val inlineGroup = FormBuilder.createFormBuilder()
            .addComponent(inlineBox)
            .addComponent(JBLabel("Show these links:"), 1)
            .addComponent(upgradeBox, 2)
            .addComponent(addOverrideBox, 2)
            .addComponent(removePackageBox, 2)
            .addComponent(removeOverrideBox, 2)
            .addComponent(switchLocalBox, 2)
            .addComponent(switchGitBox, 2)
            .panel
        inlineGroup.border = IdeBorderFactory.createTitledBorder("Inline links")

        val panel = FormBuilder.createFormBuilder()
            .addComponent(intro)
            .addComponent(localGroup)
            .addComponent(gitGroup)
            .addComponent(generalGroup)
            .addComponent(inlineGroup)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        inlineBox.addActionListener { updateActionBoxes() }
        reset()
        return panel
    }

    /** Version of the installed plugin, so you can see whether a new build is really in use. */
    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId("com.niklasweiblen.flutterpubspecoverridehelper"))?.version ?: "dev"

    /** Button that scans the folders currently shown in the list (also unsaved ones) and reports what it finds. */
    private fun checkFoldersButton(): JComponent {
        val button = JButton("Check folders")
        button.toolTipText = "Shows which packages the plugin finds in the folders above"
        button.addActionListener { checkFolders() }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.add(button)
        return row
    }

    /** Scans the listed folders and tells the user which packages were found and which folders don't exist. */
    private fun checkFolders() {
        val folders = foldersModel.items.toList()
        if (folders.isEmpty()) {
            Messages.showInfoMessage(foldersList, "No folders added yet. Click + to add one.", "Local Packages")
            return
        }
        val base = project.basePath
        val depth = depthSpinner.value as Int
        val packages = PubspecOps.withProgress(project, "Scanning local packages…") { LocalPackageScanner.scan(folders, base, depth) }
        val missing = folders.filter { LocalPackageScanner.resolveFolder(it, base)?.isDirectory != true }

        val message = buildString {
            if (packages.isEmpty()) {
                append("No packages found.")
            } else {
                append("Found ${packages.size} package(s):\n")
                append(packages.take(30).joinToString(", ") { it.name })
                if (packages.size > 30) append(", … and ${packages.size - 30} more")
            }
            if (missing.isNotEmpty()) append("\n\nThese folders do not exist:\n").append(missing.joinToString("\n"))
        }
        Messages.showInfoMessage(foldersList, message, "Local Packages")
    }

    /** Small grey explanation text (HTML allowed). */
    private fun hint(html: String): JBLabel {
        val label = JBLabel("<html>$html</html>", UIUtil.ComponentStyle.SMALL)
        label.foreground = UIUtil.getContextHelpForeground()
        return label
    }

    // ------------------------------------------------------------------ folder list actions

    /** "+": choose folders in the file dialog. Folders inside the project are stored as `$PROJECT_DIR$/…`. */
    private fun addFolders() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFoldersDescriptor().withTitle("Select Packages Folders")
        FileChooser.chooseFiles(descriptor, project, null)
            .map { toPortablePath(it.path) }
            .filter { it !in foldersModel.items }
            .forEach { foldersModel.add(it) }
    }

    /** Pencil: edit the selected entry as text, e.g. to use `$PROJECT_DIR$/../packages`. */
    private fun editFolder() {
        val index = foldersList.selectedIndex
        if (index < 0) return
        val edited = Messages.showInputDialog(
            foldersList,
            "Folder (absolute, \$PROJECT_DIR\$/…, or ~/…):",
            "Edit Packages Folder",
            null,
            foldersModel.items[index],
            null
        )?.trim()
        if (edited.isNullOrEmpty()) return
        foldersModel.replaceAll(foldersModel.items.toMutableList().also { it[index] = edited })
        foldersList.selectedIndex = index
    }

    /** "−": remove the selected entries. */
    private fun removeFolders() {
        foldersList.selectedValuesList.forEach { foldersModel.remove(it) }
    }

    /** Paths inside the project become `$PROJECT_DIR$/…`, which keeps the shared settings valid on other machines. */
    private fun toPortablePath(path: String): String {
        val base = project.basePath?.trimEnd('/', '\\') ?: return path
        val normalized = path.replace('\\', '/')
        val normalizedBase = base.replace('\\', '/')
        return when {
            normalized == normalizedBase -> "\$PROJECT_DIR\$"
            normalized.startsWith("$normalizedBase/") -> "\$PROJECT_DIR\$" + normalized.removePrefix(normalizedBase)
            else -> path
        }
    }

    // ------------------------------------------------------------------ repo table actions

    /** "+": add an empty row and start editing its first cell. */
    private fun addRepoRow() {
        reposTable.cellEditor?.stopCellEditing()
        reposModel.addRow(arrayOf<Any>("", "", ""))
        val row = reposModel.rowCount - 1
        reposTable.setRowSelectionInterval(row, row)
        reposTable.editCellAt(row, 0)
        reposTable.editorComponent?.requestFocusInWindow()
    }

    /** "−": remove the selected rows. */
    private fun removeRepoRows() {
        reposTable.cellEditor?.stopCellEditing()
        reposTable.selectedRows.sortedDescending().forEach { reposModel.removeRow(it) }
    }

    /** The table content as settings objects; rows without name or URL are ignored. */
    private fun currentRepos(): List<PubspecOverridesSettings.GitRepoConfig> {
        reposTable.cellEditor?.stopCellEditing() // commit a cell that is still being edited
        fun cell(row: Int, col: Int) = (reposModel.getValueAt(row, col) as? String ?: "").trim()
        return (0 until reposModel.rowCount).mapNotNull { row ->
            val name = cell(row, 0)
            val url = cell(row, 1)
            if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
            PubspecOverridesSettings.GitRepoConfig().also {
                it.name = name
                it.url = url
                it.path = cell(row, 2)
            }
        }
    }

    /** Comparable representation of a repo list (used to detect modifications). */
    private fun repoKeys(repos: List<PubspecOverridesSettings.GitRepoConfig>): List<String> =
        repos.map { "${it.name}\u0000${it.url}\u0000${it.path}" }

    /** The individual links can only be changed while the master switch is on. */
    private fun updateActionBoxes() = actionBoxes.forEach { it.isEnabled = inlineBox.isSelected }

    // ------------------------------------------------------------------ Configurable

    override fun isModified(): Boolean {
        val s = state
        return foldersModel.items != s.packagesFolders ||
            (depthSpinner.value as Int) != s.scanDepth ||
            repoKeys(currentRepos()) != repoKeys(s.gitRepos) ||
            defaultRefField.text != s.defaultRef ||
            runPubGetBox.isSelected != s.runPubGet ||
            autoConfirmBox.isSelected != s.autoConfirmTypeConflict ||
            inlineBox.isSelected != s.inlineActionsEnabled ||
            upgradeBox.isSelected != s.inlineUpgradePackage ||
            addOverrideBox.isSelected != s.inlineAddOverride ||
            removePackageBox.isSelected != s.inlineRemovePackage ||
            removeOverrideBox.isSelected != s.inlineRemoveOverride ||
            switchLocalBox.isSelected != s.inlineSwitchToLocal ||
            switchGitBox.isSelected != s.inlineSwitchToGit
    }

    override fun apply() {
        val s = state
        s.packagesFolders = foldersModel.items.toMutableList()
        s.scanDepth = depthSpinner.value as Int
        s.gitRepos = currentRepos().toMutableList()
        s.defaultRef = defaultRefField.text.trim()
        s.runPubGet = runPubGetBox.isSelected
        s.autoConfirmTypeConflict = autoConfirmBox.isSelected
        s.inlineActionsEnabled = inlineBox.isSelected
        s.inlineUpgradePackage = upgradeBox.isSelected
        s.inlineAddOverride = addOverrideBox.isSelected
        s.inlineRemovePackage = removePackageBox.isSelected
        s.inlineRemoveOverride = removeOverrideBox.isSelected
        s.inlineSwitchToLocal = switchLocalBox.isSelected
        s.inlineSwitchToGit = switchGitBox.isSelected

        // Folders/repos/switches decide which inline links are offered → drop the scan cache and redraw the editors.
        LocalPackageScanner.clearCache()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    override fun reset() {
        val s = state
        foldersModel.replaceAll(s.packagesFolders.toList())
        depthSpinner.value = s.scanDepth
        reposModel.rowCount = 0
        s.gitRepos.forEach { reposModel.addRow(arrayOf<Any>(it.name, it.url, it.path)) }
        defaultRefField.text = s.defaultRef
        runPubGetBox.isSelected = s.runPubGet
        autoConfirmBox.isSelected = s.autoConfirmTypeConflict
        inlineBox.isSelected = s.inlineActionsEnabled
        upgradeBox.isSelected = s.inlineUpgradePackage
        addOverrideBox.isSelected = s.inlineAddOverride
        removePackageBox.isSelected = s.inlineRemovePackage
        removeOverrideBox.isSelected = s.inlineRemoveOverride
        switchLocalBox.isSelected = s.inlineSwitchToLocal
        switchGitBox.isSelected = s.inlineSwitchToGit
        updateActionBoxes()
    }
}
