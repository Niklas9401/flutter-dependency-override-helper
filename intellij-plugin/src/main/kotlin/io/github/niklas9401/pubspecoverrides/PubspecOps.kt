package io.github.niklas9401.pubspecoverrides

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * All user-facing operations (the Kotlin counterpart of the command handlers in `extension.js`).
 * They are written in a synchronous, dialog-driven style and must be called on the EDT.
 */
object PubspecOps {

    private const val TITLE = "Pubspec Overrides"

    // ------------------------------------------------------------------ infrastructure

    /** Runs [block] and shows any exception as an error dialog instead of failing silently. */
    fun guard(project: Project?, block: () -> Unit) {
        try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e // cancelling a progress dialog is not an error
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), TITLE)
        }
    }

    /** Runs [block] on a background thread behind a modal progress dialog (keeps the EDT free for I/O). */
    fun <T> withProgress(project: Project, title: String, block: () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<T, RuntimeException> { block() }, title, true, project
        )

    private fun notify(project: Project, message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pubspec Overrides")
            .createNotification(message, type)
            .notify(project)
    }

    private fun settings(project: Project) = PubspecOverridesSettings.getInstance(project)

    private fun document(file: VirtualFile): Document =
        FileDocumentManager.getInstance().getDocument(file) ?: throw IllegalStateException("Cannot open ${file.path}")

    /** Current text of the pubspec, including unsaved editor changes. */
    fun text(file: VirtualFile): String = document(file).text

    /**
     * Replaces the pubspec content as an undoable command (Ctrl/Cmd+Z works) and saves it.
     * Only the differing middle part is replaced, so caret, folding and scroll position stay stable.
     * Afterwards `dart/flutter pub get` is started if enabled in the settings.
     */
    fun write(project: Project, file: VirtualFile, newText: String) {
        val doc = document(file)
        val old = doc.text
        if (old == newText) return

        WriteCommandAction.writeCommandAction(project).withName(TITLE).run<RuntimeException> {
            val max = minOf(old.length, newText.length)
            var prefix = 0
            while (prefix < max && old[prefix] == newText[prefix]) prefix++
            var suffix = 0
            while (suffix < max - prefix && old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
            doc.replaceString(prefix, old.length - suffix, newText.substring(prefix, newText.length - suffix))
        }
        FileDocumentManager.getInstance().saveDocument(doc)

        if (settings(project).current.runPubGet) runPub(project, file, newText, listOf("get"))
    }

    /**
     * Runs `flutter pub <args>` (Flutter projects) or `dart pub <args>` (plain Dart packages) in the pubspec's
     * folder as a background task and reports the result in a notification.
     *
     * @param args       Everything after `pub`, e.g. `["get"]` or `["upgrade", "my_package"]`. Must be validated by the caller.
     * @param showOutput Append the tool's output to the success notification (useful for `upgrade`, which prints what changed).
     */
    private fun runPub(project: Project, pubspec: VirtualFile, text: String, args: List<String>, showOutput: Boolean = false) {
        val tool = if (PubspecEditor.usesFlutter(text)) "flutter" else "dart"
        val label = "$tool pub ${args.joinToString(" ")}"
        val dir = File(pubspec.parent.path)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, label, true) {
            override fun run(indicator: ProgressIndicator) {
                // On Windows flutter/dart are .bat files and must be started through cmd.
                val command = listOf(tool, "pub") + args
                val cmd = if (SystemInfo.isWindows) GeneralCommandLine(listOf("cmd", "/c") + command) else GeneralCommandLine(command)
                cmd.withWorkDirectory(dir)
                try {
                    val output = CapturingProcessHandler(cmd).runProcess(120_000)
                    if (output.exitCode == 0) {
                        val details = if (showOutput) "<br>" + formatOutput(output.stdout) else ""
                        notify(project, "$label finished.$details")
                    } else {
                        notify(project, "$label failed:<br>" + formatOutput(output.stderr.ifBlank { output.stdout }), NotificationType.ERROR)
                    }
                } catch (e: Exception) {
                    notify(project, "Could not start '$label': ${StringUtil.escapeXmlEntities(e.message ?: "")}", NotificationType.ERROR)
                }
                VfsUtil.markDirtyAndRefresh(true, true, true, File(dir.path)) // pick up the new pubspec.lock
            }
        })
    }

    /** Last lines of a tool output, HTML-escaped for a notification. */
    private fun formatOutput(text: String): String =
        StringUtil.escapeXmlEntities(text.trim().takeLast(800)).replace("\n", "<br>")

    // ------------------------------------------------------------------ data sources

    /** Local packages from the configured folders (cached unless [fresh]); empty if no folders are configured. */
    fun localPackages(project: Project, fresh: Boolean = false): List<LocalPackage> {
        val s = settings(project).current
        if (s.packagesFolders.isEmpty()) return emptyList() // never prompt from here – inline actions must stay silent
        return LocalPackageScanner.scanCached(s.packagesFolders.toList(), project.basePath, s.scanDepth, fresh)
    }

    private fun findLocal(project: Project, name: String): LocalPackage? =
        withProgress(project, "Scanning local packages…") { localPackages(project, fresh = true) }.find { it.name == name }

    private fun findGitRepo(project: Project, name: String) = settings(project).validGitRepos().find { it.name == name }

    /** Pre-fill for the Git reference prompt: current branch, falling back to the `defaultRef` setting. */
    private fun defaultGitRef(project: Project, pubspec: VirtualFile): String {
        val branch = withProgress(project, "Reading git branch…") { GitBranch.current(File(pubspec.parent.path)) }
        return branch.ifEmpty { settings(project).current.defaultRef }
    }

    /** Path of [pkg] relative to the pubspec's folder, with `/` separators (as pubspec.yaml expects). */
    private fun relativePath(pubspecDir: File, pkg: File): String =
        try {
            pubspecDir.toPath().relativize(pkg.toPath()).toString().replace(File.separatorChar, '/')
        } catch (e: IllegalArgumentException) {
            pkg.path.replace(File.separatorChar, '/') // e.g. different drives on Windows → absolute path
        }

    private fun askGitRef(project: Project, title: String, default: String): String? =
        Messages.showInputDialog(
            project, "Branch, Tag or Commit Hash – empty = Default Branch of the Repo", title,
            Messages.getQuestionIcon(), default, null
        )

    /**
     * Warns if an override is about to change its type (e.g. git → local). Skipped if
     * `autoConfirmTypeConflict` is enabled. Each conflicting package gets its own confirmation.
     * @return true if it is OK to continue.
     */
    private fun checkTypeConflict(project: Project, text: String, names: List<String>, target: String): Boolean {
        if (settings(project).current.autoConfirmTypeConflict) return true
        for (name in names) {
            val current = PubspecEditor.getOverrideType(text, name)?.label ?: continue // not overridden yet
            if (current == target) continue // same type → plain update
            val ok = Messages.showYesNoDialog(
                project,
                "The package \"$name\" is already overridden as \"$current\" and would now be replaced by a \"$target\" override. Continue?",
                TITLE, "Yes", "Cancel", Messages.getWarningIcon()
            ) == Messages.YES
            if (!ok) return false
        }
        return true
    }

    // ------------------------------------------------------------------ commands (Tools menu / editor popup)

    /** "Add Override…": asks for the type first, then delegates. */
    fun add(project: Project, pubspec: VirtualFile) {
        val choice = Messages.showChooseDialog(
            project, "Select override type", TITLE, Messages.getQuestionIcon(), arrayOf("Local", "Git"), "Local"
        )
        when (choice) {
            0 -> addLocal(project, pubspec)
            1 -> addGit(project, pubspec)
        }
    }

    /**
     * "Add Local Package…": pick packages from the configured folders (or choose folders in a dialog the
     * first time) and write relative `path:` overrides.
     */
    fun addLocal(project: Project, pubspec: VirtualFile) {
        val s = settings(project)
        var folders = s.current.packagesFolders.toList()

        // Nothing configured → ask the user and offer to remember the choice.
        if (folders.isEmpty()) {
            val descriptor = FileChooserDescriptorFactory.createMultipleFoldersDescriptor().withTitle("Select Packages Folders")
            val chosen = FileChooser.chooseFiles(descriptor, project, null)
            if (chosen.isEmpty()) return
            folders = chosen.map { it.path }
            val save = Messages.showYesNoDialog(
                project, "Save these folders as the default for the project?", TITLE, Messages.getQuestionIcon()
            ) == Messages.YES
            if (save) {
                s.current.packagesFolders = folders.toMutableList()
                LocalPackageScanner.clearCache()
            }
        }

        val text = text(pubspec)
        val self = PubspecEditor.packageName(text)
        // Never offer the package itself – it must not override its own dependency.
        val packages = withProgress(project, "Scanning local packages…") {
            LocalPackageScanner.scan(folders, project.basePath, s.current.scanDepth)
        }.filter { it.name != self }
        if (packages.isEmpty()) throw IllegalStateException("No packages (pubspec.yaml) found in the specified folders.")

        val existing = PubspecEditor.listOverrides(text).toSet()
        val pubspecDir = File(pubspec.parent.path)
        val items = packages.map { pkg ->
            val suffix = if (pkg.name in existing) "   – already overridden, will be replaced" else ""
            pkg to "${pkg.name}   (${relativePath(pubspecDir, pkg.dir)})$suffix"
        }
        val dialog = MultiSelectDialog(project, "Select packages", items)
        if (!dialog.showAndGet()) return
        val picked = dialog.selected()
        if (picked.isEmpty()) return

        if (!checkTypeConflict(project, text, picked.map { it.name }, "local")) return

        val entries = picked.map { PubspecEditor.renderLocal(it.name, relativePath(pubspecDir, it.dir)) }
        // Re-read the text: it may have changed while the dialogs were open.
        write(project, pubspec, PubspecEditor.applyOverrides(text(pubspec), entries))
        notify(project, "${entries.size} local override(s) set.")
    }

    /**
     * "Add Git Reference…": pick configured repos, enter a reference per repo (pre-filled with the
     * current branch) and write `git:` overrides.
     */
    fun addGit(project: Project, pubspec: VirtualFile) {
        val repos = settings(project).validGitRepos()
        if (repos.isEmpty()) {
            Messages.showWarningDialog(
                project, "No Git repos configured. Add them under Settings | Tools | Flutter Pubspec Overrides.", TITLE
            )
            return
        }

        val text = text(pubspec)
        val self = PubspecEditor.packageName(text)
        val existing = PubspecEditor.listOverrides(text).toSet()
        val items = repos.filter { it.name != self }.map { repo -> // a package must not override itself
            val suffix = if (repo.name in existing) "   – already overridden, will be replaced" else ""
            val sub = if (repo.path.isNotBlank()) " (${repo.path})" else ""
            repo to "${repo.name}   ${repo.url}$sub$suffix"
        }
        val dialog = MultiSelectDialog(project, "Select Git repos", items)
        if (!dialog.showAndGet()) return
        val picked = dialog.selected()
        if (picked.isEmpty()) return

        if (!checkTypeConflict(project, text, picked.map { it.name }, "git")) return

        val defaultRef = defaultGitRef(project, pubspec)
        val entries = mutableListOf<PubspecEditor.OverrideEntry>()
        picked.forEachIndexed { index, repo ->
            val ref = askGitRef(project, "Git reference for ${repo.name} (${index + 1}/${picked.size})", defaultRef)
                ?: return // cancelled → write nothing
            entries.add(PubspecEditor.renderGit(repo.name, repo.url, ref.trim(), repo.path))
        }

        write(project, pubspec, PubspecEditor.applyOverrides(text(pubspec), entries))
        notify(project, "${entries.size} Git override(s) set.")
    }

    /** "Remove Override…": pick which existing overrides to delete. */
    fun removeSome(project: Project, pubspec: VirtualFile) {
        val text = text(pubspec)
        val names = PubspecEditor.listOverrides(text)
        if (names.isEmpty()) {
            notify(project, "No dependency_overrides found.")
            return
        }
        val dialog = MultiSelectDialog(project, "Which overrides to remove?", names.map { it to it })
        if (!dialog.showAndGet()) return
        val picked = dialog.selected()
        if (picked.isEmpty()) return
        write(project, pubspec, PubspecEditor.removeOverrides(text, picked))
    }

    /** "Remove All Overrides": deletes every entry (and the then empty section) after a confirmation. */
    fun removeAll(project: Project, pubspec: VirtualFile) {
        val text = text(pubspec)
        val names = PubspecEditor.listOverrides(text)
        if (names.isEmpty()) {
            notify(project, "No dependency_overrides found.")
            return
        }
        val ok = Messages.showYesNoDialog(
            project, "Remove all ${names.size} overrides?", TITLE, "Remove", "Cancel", Messages.getWarningIcon()
        ) == Messages.YES
        if (ok) write(project, pubspec, PubspecEditor.removeOverrides(text, names))
    }

    // ------------------------------------------------------------------ inline actions

    /**
     * Inline "Add override" for a package from `dependencies`. If the package is available both locally
     * and via Git the user chooses; otherwise the only available type is used directly.
     */
    fun inlineAddOverride(project: Project, pubspec: VirtualFile, name: String) {
        val hasLocal = findLocal(project, name) != null
        val hasGit = findGitRepo(project, name) != null
        val type = when {
            hasLocal && hasGit -> when (Messages.showChooseDialog(
                project, "Override \"$name\" with…", TITLE, Messages.getQuestionIcon(), arrayOf("Local", "Git"), "Local"
            )) {
                0 -> "local"
                1 -> "git"
                else -> return // cancelled
            }
            hasLocal -> "local"
            hasGit -> "git"
            else -> throw IllegalStateException("\"$name\" was not found in the configured local folders or Git repos.")
        }
        writeSingleOverride(project, pubspec, name, type)
    }

    /** Inline "Switch to Local/Git": replaces the existing override by one of the requested type. */
    fun inlineSwitchOverride(project: Project, pubspec: VirtualFile, name: String, target: String) {
        // The explicit click is already the confirmation → no type-conflict dialog here.
        writeSingleOverride(project, pubspec, name, target)
    }

    /**
     * Inline "Upgrade package": runs `pub upgrade <name>` for exactly this package (`flutter pub upgrade` in
     * Flutter projects, `dart pub upgrade` otherwise). Unsaved changes are saved first, because pub reads
     * the pubspec from disk.
     */
    fun inlineUpgradePackage(project: Project, pubspec: VirtualFile, name: String) =
        upgradePackages(project, pubspec, listOf(name))

    /**
     * Command "Upgrade Packages…": pick one or more dependencies and upgrade exactly those.
     * Works without the inline links, e.g. when Code Vision is switched off.
     */
    fun upgradeSome(project: Project, pubspec: VirtualFile) {
        val entries = PubspecEditor.listPackageEntries(text(pubspec))
        val names = PubspecEditor.DEPENDENCY_SECTIONS
            .flatMap { entries.getValue(it) }
            .filter { it.type != PubspecEditor.EntryType.SDK } // flutter, flutter_test, … can't be upgraded individually
            .map { it.name }
            .distinct()
        if (names.isEmpty()) {
            notify(project, "No packages found in dependencies / dev_dependencies.")
            return
        }
        val dialog = MultiSelectDialog(project, "Which packages to upgrade?", names.map { it to it })
        if (!dialog.showAndGet()) return
        val picked = dialog.selected()
        if (picked.isNotEmpty()) upgradePackages(project, pubspec, picked)
    }

    /** Runs `pub upgrade <names…>` after saving the pubspec (pub reads it from disk). */
    private fun upgradePackages(project: Project, pubspec: VirtualFile, names: List<String>) {
        // The names end up in a command line → only accept valid Dart package names.
        names.forEach { require(Regex("[A-Za-z0-9_]+").matches(it)) { "Invalid package name: $it" } }
        FileDocumentManager.getInstance().saveDocument(document(pubspec))
        runPub(project, pubspec, text(pubspec), listOf("upgrade") + names, showOutput = true)
    }

    /** Inline "Remove override". */
    fun inlineRemoveOverride(project: Project, pubspec: VirtualFile, name: String) {
        write(project, pubspec, PubspecEditor.removeOverrides(text(pubspec), listOf(name)))
    }

    /**
     * Inline "Remove package": deletes a dependency from the section it was clicked in, after a
     * confirmation. An existing override of the same package is left untouched.
     */
    fun inlineRemovePackage(project: Project, pubspec: VirtualFile, name: String, section: String) {
        val ok = Messages.showYesNoDialog(
            project, "Remove \"$name\" from $section?", TITLE, "Remove", "Cancel", Messages.getWarningIcon()
        ) == Messages.YES
        if (ok) write(project, pubspec, PubspecEditor.removeDependencies(text(pubspec), section, listOf(name)))
    }

    /** Builds and writes an override of [type] for one package; does nothing if the user cancels the ref prompt. */
    private fun writeSingleOverride(project: Project, pubspec: VirtualFile, name: String, type: String) {
        val entry: PubspecEditor.OverrideEntry = if (type == "local") {
            val pkg = findLocal(project, name) ?: throw IllegalStateException("\"$name\" was not found in the configured local folders.")
            PubspecEditor.renderLocal(name, relativePath(File(pubspec.parent.path), pkg.dir))
        } else {
            val repo = findGitRepo(project, name) ?: throw IllegalStateException("\"$name\" is not configured as a Git repo in the settings.")
            val ref = askGitRef(project, "Git reference for $name", defaultGitRef(project, pubspec)) ?: return
            PubspecEditor.renderGit(name, repo.url, ref.trim(), repo.path)
        }
        write(project, pubspec, PubspecEditor.applyOverrides(text(pubspec), listOf(entry)))
    }
}
