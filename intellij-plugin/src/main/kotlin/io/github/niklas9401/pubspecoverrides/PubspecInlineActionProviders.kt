package io.github.niklas9401.pubspecoverrides

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/*
 * Clickable inline links ("Code Vision", the mechanism behind the "N usages" hints above functions;
 * called "CodeLens" in VS Code) above the package names of a pubspec.yaml.
 *
 * IMPORTANT: every link is its own Code Vision provider. A provider can show only ONE link per line –
 * several entries of the same provider on the same line collapse into one, which is why a single provider
 * for all actions only showed the last action. One provider per action is also how JetBrains does it.
 *
 * Links on `dependencies` / `dev_dependencies` entries (SDK packages like `flutter` get none):
 *   Upgrade package, Add override, Remove package
 * Links on `dependency_overrides` entries:
 *   Remove override, Switch to Local, Switch to Git
 *
 * Each link can be switched off in the plugin settings (all on by default); the master switch there
 * hides all of them.
 */

/** A package line in the editor and what happens when its link is clicked. */
class InlineLink(
    /** 0-based line the link belongs to. */
    val line: Int,
    /** Hover text. */
    val tooltip: String,
    /** Runs on the EDT when the link is clicked. */
    val action: () -> Unit
)

/** What the providers need to know about a pubspec; built once per provider run. */
class PubspecContext(
    val project: Project,
    val pubspec: VirtualFile,
    /** Entries per section, see [PubspecEditor.listPackageEntries]. */
    private val entries: Map<String, List<PubspecEditor.PackageEntry>>,
    /** Names of packages found in the configured local folders. */
    val localNames: Set<String>,
    /** Names of packages configured as Git repos. */
    val gitNames: Set<String>
) {
    /** Names of the packages that currently have an override. */
    val overridden: Set<String> = entries.getValue(PubspecEditor.SECTION).map { it.name }.toSet()

    /** Entries of `dependency_overrides`. */
    fun overrides(): List<PubspecEditor.PackageEntry> = entries.getValue(PubspecEditor.SECTION)

    /** Entries of `dependencies` / `dev_dependencies` except SDK packages, paired with their section name. */
    fun regularDependencies(): List<Pair<String, PubspecEditor.PackageEntry>> =
        PubspecEditor.DEPENDENCY_SECTIONS.flatMap { section ->
            entries.getValue(section)
                .filter { it.type != PubspecEditor.EntryType.SDK } // flutter, flutter_test, … – nothing sensible to do
                .map { section to it }
        }

    companion object {
        /** Parses the editor text and looks up the packages that are available locally / via Git. */
        fun create(project: Project, pubspec: VirtualFile, document: Document): PubspecContext {
            val settings = PubspecOverridesSettings.getInstance(project)
            return PubspecContext(
                project,
                pubspec,
                PubspecEditor.listPackageEntries(document.text),
                PubspecOps.localPackages(project).map { it.name }.toSet(), // cached, see LocalPackageScanner
                settings.validGitRepos().map { it.name }.toSet()
            )
        }
    }
}

/**
 * Base class of all inline links: one subclass = one link type.
 *
 * @param id    Unique provider id.
 * @param name  Name shown in Settings | Editor | Inlay Hints | Code vision.
 * @param title Text of the link in the editor.
 */
abstract class PubspecInlineActionProvider(
    override val id: String,
    override val name: String,
    private val title: String
) : DaemonBoundCodeVisionProvider {

    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top

    /** Whether this link is switched on in the plugin settings (the master switch is checked separately). */
    protected abstract fun isEnabled(config: PubspecOverridesSettings.State): Boolean

    /** The links of this type for the given pubspec. */
    protected abstract fun links(ctx: PubspecContext): List<InlineLink>

    /**
     * Computes the links for one editor. Runs on a background thread inside the daemon's read action.
     * @return pairs of (line range the link belongs to, link).
     */
    final override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        val pubspec = file.virtualFile ?: return emptyList()
        if (pubspec.name != "pubspec.yaml") return emptyList()

        val project = file.project
        val config = PubspecOverridesSettings.getInstance(project).current
        if (!config.inlineActionsEnabled || !isEnabled(config)) return emptyList()

        val document = editor.document
        return links(PubspecContext.create(project, pubspec, document))
            .filter { it.line < document.lineCount }
            .map { link ->
                val range = TextRange(document.getLineStartOffset(link.line), document.getLineEndOffset(link.line))
                val entry = ClickableTextCodeVisionEntry(
                    title, id,
                    onClick = { _, _ -> PubspecOps.guard(project) { link.action() } }, // errors are shown in a dialog
                    tooltip = link.tooltip
                )
                range to entry
            }
    }
}

/** "Upgrade package" on every regular dependency: runs `pub upgrade <name>`. */
class UpgradePackageInlineProvider : PubspecInlineActionProvider(
    "pubspec.overrides.inline.upgradePackage", "Pubspec Overrides: Upgrade package", "Upgrade package"
) {
    override fun isEnabled(config: PubspecOverridesSettings.State) = config.inlineUpgradePackage

    override fun links(ctx: PubspecContext): List<InlineLink> =
        ctx.regularDependencies().map { (_, e) ->
            InlineLink(e.line, "Run \"pub upgrade ${e.name}\"") { PubspecOps.inlineUpgradePackage(ctx.project, ctx.pubspec, e.name) }
        }
}

/** "Add override" on dependencies that exist locally or as configured Git repo and are not overridden yet. */
class AddOverrideInlineProvider : PubspecInlineActionProvider(
    "pubspec.overrides.inline.addOverride", "Pubspec Overrides: Add override", "Add override"
) {
    override fun isEnabled(config: PubspecOverridesSettings.State) = config.inlineAddOverride

    override fun links(ctx: PubspecContext): List<InlineLink> =
        ctx.regularDependencies()
            .filter { (_, e) -> e.name !in ctx.overridden && (e.name in ctx.localNames || e.name in ctx.gitNames) }
            .map { (_, e) ->
                InlineLink(e.line, "Override \"${e.name}\" with a local package or a Git reference") {
                    PubspecOps.inlineAddOverride(ctx.project, ctx.pubspec, e.name)
                }
            }
}

/** "Remove package" on every regular dependency. */
class RemovePackageInlineProvider : PubspecInlineActionProvider(
    "pubspec.overrides.inline.removePackage", "Pubspec Overrides: Remove package", "Remove package"
) {
    override fun isEnabled(config: PubspecOverridesSettings.State) = config.inlineRemovePackage

    override fun links(ctx: PubspecContext): List<InlineLink> =
        ctx.regularDependencies().map { (section, e) ->
            InlineLink(e.line, "Remove \"${e.name}\" from $section") {
                PubspecOps.inlineRemovePackage(ctx.project, ctx.pubspec, e.name, section)
            }
        }
}

/** "Remove override" on every entry of `dependency_overrides`. */
class RemoveOverrideInlineProvider : PubspecInlineActionProvider(
    "pubspec.overrides.inline.removeOverride", "Pubspec Overrides: Remove override", "Remove override"
) {
    override fun isEnabled(config: PubspecOverridesSettings.State) = config.inlineRemoveOverride

    override fun links(ctx: PubspecContext): List<InlineLink> =
        ctx.overrides().map { e ->
            InlineLink(e.line, "Remove the override of \"${e.name}\"") {
                PubspecOps.inlineRemoveOverride(ctx.project, ctx.pubspec, e.name)
            }
        }
}

/** "Switch to Local" on overrides that are not local yet but whose package exists in a local folder. */
class SwitchToLocalInlineProvider : PubspecInlineActionProvider(
    "pubspec.overrides.inline.switchToLocal", "Pubspec Overrides: Switch to Local", "Switch to Local"
) {
    override fun isEnabled(config: PubspecOverridesSettings.State) = config.inlineSwitchToLocal

    override fun links(ctx: PubspecContext): List<InlineLink> =
        ctx.overrides()
            .filter { it.type != PubspecEditor.EntryType.LOCAL && it.name in ctx.localNames }
            .map { e ->
                InlineLink(e.line, "Use the local package of \"${e.name}\" instead") {
                    PubspecOps.inlineSwitchOverride(ctx.project, ctx.pubspec, e.name, "local")
                }
            }
}

/** "Switch to Git" on overrides that are not git yet but whose package is configured as Git repo. */
class SwitchToGitInlineProvider : PubspecInlineActionProvider(
    "pubspec.overrides.inline.switchToGit", "Pubspec Overrides: Switch to Git", "Switch to Git"
) {
    override fun isEnabled(config: PubspecOverridesSettings.State) = config.inlineSwitchToGit

    override fun links(ctx: PubspecContext): List<InlineLink> =
        ctx.overrides()
            .filter { it.type != PubspecEditor.EntryType.GIT && it.name in ctx.gitNames }
            .map { e ->
                InlineLink(e.line, "Use the configured Git repository of \"${e.name}\" instead") {
                    PubspecOps.inlineSwitchOverride(ctx.project, ctx.pubspec, e.name, "git")
                }
            }
}
