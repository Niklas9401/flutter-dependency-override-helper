# Flutter Dependency Override Helper

Quickly insert `dependency_overrides` into a Flutter/Dart `pubspec.yaml` – as a local path or a Git reference –
with clickable inline actions above the package names.

| Plugin | Folder | Published on |
|---|---|---|
| VS Code extension | [`vscode-extension/`](vscode-extension) | VS Code Marketplace, Open VSX |
| Android Studio / IntelliJ plugin | [`intellij-plugin/`](intellij-plugin) | JetBrains Marketplace |

Both plugins show inline actions above the package names – **Upgrade package** (`pub upgrade <name>`), Add override,
Remove package, Remove override and Switch to Local/Git – which can be switched off individually in the settings.

Both plugins share the same behaviour and the same line-based pubspec editing logic
(`pubspec.js` ↔ `PubspecEditor.kt`), so a change to one usually has to be mirrored in the other.

## Development

```sh
# VS Code extension (Node 20+)
cd vscode-extension
npm test                  # unit tests of the pubspec logic
# open the folder in VS Code and press F5 to start an Extension Development Host

# IntelliJ / Android Studio plugin (JDK 21, Gradle 8.10)
cd intellij-plugin
gradle test               # or ./gradlew test once you generated and committed the wrapper
gradle runIde             # sandbox IDE
gradle buildPlugin        # → build/distributions/*.zip
```

## CI and releases

* Every pull request / push to `main` is built and tested by [`ci.yml`](.github/workflows/ci.yml).
* Pushing a version tag publishes the plugins via [`release.yml`](.github/workflows/release.yml):

| Tag | What is published |
|---|---|
| `v1.2.3` | both plugins, version 1.2.3 |
| `vscode-v1.2.3` | only the VS Code extension |
| `intellij-v1.2.3` | only the IntelliJ / Android Studio plugin |

```sh
git tag v1.2.3
git push origin v1.2.3
```

One-time setup (accounts, tokens, secrets): see [RELEASING.md](RELEASING.md).

## License

[MIT](LICENSE)
