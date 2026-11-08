package com.niklasweiblen.flutterpubspecoverridehelper

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Clickable links above the package names of a pubspec.yaml ("Code Vision", the mechanism behind the
 * "N usages" hints above functions):
 *
 *  - on `dependencies` / `dev_dependencies` entries:
 *      "Upgrade package" – runs `pub upgrade <name>` for this package
 *      "Add override"    – only if the package is available in the configured local folders or Git repos
 *                          and is not overridden yet
 *      "Remove package"  – removes the dependency (after a confirmation)
 *  - on `dependency_overrides` entries:
 *      "Remove override"
 *      "Switch to Local" / "Switch to Git" – only if the package is available as the other type
 *
 * SDK dependencies (`flutter`, `flutter_test`, …) get no links.
 * The whole feature and every single link can be switched off in the plugin settings (all on by default).
 *
 * Users can toggle all Code Vision hints under Settings | Editor | Inlay Hints | Code vision.
 */
class PubspecCodeVisionProvider : DaemonBoundCodeVisionProvider {

    override val id: String = "pubspec.overrides.inline.actions"
    override val name: String = "Pubspec override actions"
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top

    /**
     * Computes the links for one editor. Runs on a background thread inside the daemon's read action.
     * @return pairs of (line range the link belongs to, link).
     */
    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        val pubspec = file.virtualFile ?: return emptyList()
        if (pubspec.name != "pubspec.yaml") return emptyList()

        val project = file.project
        val settings = PubspecOverridesSettings.getInstance(project)
        val config = settings.current
        if (!config.inlineActionsEnabled) return emptyList()

        val document = editor.document
        val entries = PubspecEditor.listPackageEntries(document.text)
        val localNames = PubspecOps.localPackages(project).map { it.name }.toSet() // cached, see LocalPackageScanner
        val gitNames = settings.validGitRepos().map { it.name }.toSet()
        val overridden = entries.getValue(PubspecEditor.SECTION).map { it.name }.toSet()

        val result = mutableListOf<Pair<TextRange, CodeVisionEntry>>()

        /** Adds one link to [line]; clicking it runs [action] (errors are shown in a dialog). */
        fun link(line: Int, title: String, tooltip: String, action: () -> Unit) {
            if (line >= document.lineCount) return
            val range = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
            val entry = ClickableTextCodeVisionEntry(
                title, id,
                onClick = { _, _ -> PubspecOps.guard(project) { action() } },
                tooltip = tooltip
            )
            result.add(range to entry)
        }

        // Regular dependencies: upgrade / add an override / remove the package.
        for (section in PubspecEditor.DEPENDENCY_SECTIONS) {
            for (e in entries.getValue(section)) {
                if (e.type == PubspecEditor.EntryType.SDK) continue // flutter, flutter_test, … – nothing sensible to do
                if (config.inlineUpgradePackage) {
                    link(e.line, "Upgrade package", "Run \"pub upgrade ${e.name}\"") {
                        PubspecOps.inlineUpgradePackage(project, pubspec, e.name)
                    }
                }
                if (config.inlineAddOverride && e.name !in overridden && (e.name in localNames || e.name in gitNames)) {
                    link(e.line, "Add override", "Override \"${e.name}\" with a local package or a Git reference") {
                        PubspecOps.inlineAddOverride(project, pubspec, e.name)
                    }
                }
                if (config.inlineRemovePackage) {
                    link(e.line, "Remove package", "Remove \"${e.name}\" from $section") {
                        PubspecOps.inlineRemovePackage(project, pubspec, e.name, section)
                    }
                }
            }
        }

        // Existing overrides: remove / switch to the other type (if available).
        for (e in entries.getValue(PubspecEditor.SECTION)) {
            if (config.inlineRemoveOverride) {
                link(e.line, "Remove override", "Remove the override of \"${e.name}\"") {
                    PubspecOps.inlineRemoveOverride(project, pubspec, e.name)
                }
            }
            if (config.inlineSwitchToLocal && e.type != PubspecEditor.EntryType.LOCAL && e.name in localNames) {
                link(e.line, "Switch to Local", "Use the local package of \"${e.name}\" instead") {
                    PubspecOps.inlineSwitchOverride(project, pubspec, e.name, "local")
                }
            }
            if (config.inlineSwitchToGit && e.type != PubspecEditor.EntryType.GIT && e.name in gitNames) {
                link(e.line, "Switch to Git", "Use the configured Git repository of \"${e.name}\" instead") {
                    PubspecOps.inlineSwitchOverride(project, pubspec, e.name, "git")
                }
            }
        }
        return result
    }
}
