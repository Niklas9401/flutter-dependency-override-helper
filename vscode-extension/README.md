# Pubspec Overrides for VS Code

Quickly toggle local path and Git `dependency_overrides` in your Flutter/Dart `pubspec.yaml` without manually copying paths or Git URLs.

Access all commands via the Context Menu inside any `pubspec.yaml` or through the Command Palette (`Cmd/Ctrl + Shift + P` -> **Pubspec Overrides**).

---

## Usage

### Local Package Overrides
1. Configure your local package directories in settings (`pubspecOverrides.packagesFolders`). If empty, VS Code will prompt you to pick folders on first run.
2. Run **Pubspec Overrides: Add Local Packages...**.
3. Pick one or multiple packages from the multi-select list. 
4. Relative `path:` entries are automatically calculated and inserted into your `pubspec.yaml`.

### Git Overrides
1. Define your common Git repositories in your workspace settings (ideally in `.vscode/settings.json` so the whole team shares them):

```json
{
  "pubspecOverrides.gitRepos": [
    { 
      "name": "my_core", 
      "url": "git@github.com:acme/my_core.git" 
    },
    { 
      "name": "my_ui", 
      "url": "[https://github.com/acme/mono.git](https://github.com/acme/mono.git)", 
      "path": "packages/my_ui" 
    }
  ],
  "pubspecOverrides.runPubGet": true
}