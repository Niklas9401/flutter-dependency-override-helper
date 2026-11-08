package io.github.niklas9401.pubspecoverrides

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** Finds the pubspec.yaml a command should operate on. */
object PubspecFiles {

    /** Build/tooling folders whose pubspec.yaml files are never offered. */
    private val EXCLUDED = listOf("/.dart_tool/", "/build/", "/ephemeral/", "/.symlinks/", "/node_modules/", "/.pub-cache/")

    /**
     * Resolution order: the file open in the editor (if it is a pubspec.yaml), the only pubspec.yaml in the
     * project, or – if there are several – the one picked in a chooser dialog.
     * @return the file, or null if the user cancelled.
     * @throws IllegalStateException if the project has no pubspec.yaml at all.
     */
    fun resolve(project: Project, current: VirtualFile?): VirtualFile? {
        if (current != null && current.name == "pubspec.yaml") return current

        val files = ReadAction.compute(ThrowableComputable<List<VirtualFile>, RuntimeException> {
            FilenameIndex.getVirtualFilesByName("pubspec.yaml", GlobalSearchScope.projectScope(project))
                .filter { f -> EXCLUDED.none { f.path.contains(it) } }
                .sortedBy { it.path }
        })
        if (files.isEmpty()) throw IllegalStateException("No pubspec.yaml found in the project.")
        if (files.size == 1) return files[0]

        val base = project.basePath ?: ""
        val labels = files.map { it.path.removePrefix(base).removePrefix("/") }.toTypedArray()
        val index = Messages.showChooseDialog(project, "Which pubspec.yaml?", "Pubspec Overrides", Messages.getQuestionIcon(), labels, labels[0])
        return if (index < 0) null else files[index]
    }
}

/**
 * Base class for all menu/popup actions: handles visibility, resolves the pubspec and reports errors.
 * In the editor context menu an action is only visible while a pubspec.yaml is open.
 */
abstract class PubspecAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val inEditorPopup = e.place == ActionPlaces.EDITOR_POPUP
        e.presentation.isEnabledAndVisible = e.project != null && (!inEditorPopup || file?.name == "pubspec.yaml")
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PubspecOps.guard(project) {
            val pubspec = PubspecFiles.resolve(project, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return@guard
            perform(project, pubspec)
        }
    }

    /** The actual command, called on the EDT with the resolved pubspec. */
    protected abstract fun perform(project: Project, pubspec: VirtualFile)
}

class AddOverrideAction : PubspecAction() {
    override fun perform(project: Project, pubspec: VirtualFile) = PubspecOps.add(project, pubspec)
}

class AddLocalAction : PubspecAction() {
    override fun perform(project: Project, pubspec: VirtualFile) = PubspecOps.addLocal(project, pubspec)
}

class AddGitAction : PubspecAction() {
    override fun perform(project: Project, pubspec: VirtualFile) = PubspecOps.addGit(project, pubspec)
}

class UpgradePackagesAction : PubspecAction() {
    override fun perform(project: Project, pubspec: VirtualFile) = PubspecOps.upgradeSome(project, pubspec)
}

class RemoveOverrideAction : PubspecAction() {
    override fun perform(project: Project, pubspec: VirtualFile) = PubspecOps.removeSome(project, pubspec)
}

class RemoveAllOverridesAction : PubspecAction() {
    override fun perform(project: Project, pubspec: VirtualFile) = PubspecOps.removeAll(project, pubspec)
}
