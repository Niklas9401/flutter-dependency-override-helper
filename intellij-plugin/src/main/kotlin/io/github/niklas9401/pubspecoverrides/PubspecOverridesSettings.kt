package io.github.niklas9401.pubspecoverrides

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-level settings, stored in `.idea/pubspecOverrides.xml`.
 * Commit that file (or add it to a shared `.idea` folder) to share Git repos and folders with your team.
 */
@Service(Service.Level.PROJECT)
@State(name = "PubspecOverridesSettings", storages = [Storage("pubspecOverrides.xml")])
class PubspecOverridesSettings : PersistentStateComponent<PubspecOverridesSettings.State> {

    /** One configured Git repository (bean with public mutable properties, as required by the XML serializer). */
    class GitRepoConfig {
        var name: String = ""
        var url: String = ""
        /** Optional sub-folder inside the repo (monorepo). */
        var path: String = ""
    }

    /** The persisted settings. All properties need defaults and must be mutable. */
    class State {
        /** Folders containing local packages (absolute, relative to the project, `~`, `${workspaceFolder}`, `$PROJECT_DIR$`). */
        var packagesFolders: MutableList<String> = mutableListOf()
        var scanDepth: Int = 2
        var gitRepos: MutableList<GitRepoConfig> = mutableListOf()
        /** Fallback pre-fill for the Git reference if the current branch cannot be determined. */
        var defaultRef: String = ""
        var runPubGet: Boolean = false
        var autoConfirmTypeConflict: Boolean = false
        /** Master switch for all inline actions above the package names. */
        var inlineActionsEnabled: Boolean = true
        // Individual inline actions (only relevant while the master switch is on); all enabled by default.
        var inlineUpgradePackage: Boolean = true
        var inlineAddOverride: Boolean = true
        var inlineRemovePackage: Boolean = true
        var inlineRemoveOverride: Boolean = true
        var inlineSwitchToLocal: Boolean = true
        var inlineSwitchToGit: Boolean = true
    }

    /** The live state object; modify its properties directly. */
    var current: State = State()

    override fun getState(): State = current

    override fun loadState(state: State) {
        current = state
    }

    /** Git repos with the mandatory fields (name and url) filled in. */
    fun validGitRepos(): List<GitRepoConfig> = current.gitRepos.filter { it.name.isNotBlank() && it.url.isNotBlank() }

    companion object {
        fun getInstance(project: Project): PubspecOverridesSettings = project.service()
    }
}
