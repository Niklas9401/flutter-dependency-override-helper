# Releasing

## How a release works

1. Make sure `main` is green (CI) and the changelog/README are up to date.
2. Push a tag (see the table in the [README](README.md#ci-and-releases)), e.g. `git tag v1.2.3 && git push origin v1.2.3`.
3. [`release.yml`](.github/workflows/release.yml) then
   * takes the version **from the tag** (the version in `package.json` / `build.gradle.kts` is overridden during the build, nothing is committed back),
   * runs the tests,
   * publishes to the marketplaces,
   * creates a GitHub release with the `.vsix` and the plugin `.zip` attached.

Tags must end in `MAJOR.MINOR.PATCH` (e.g. `1.2.3`); anything else fails the first job.
A marketplace version can never be overwritten – if a release goes wrong, publish a new, higher version.

## One-time setup

Add the secrets under **GitHub ▸ Settings ▸ Secrets and variables ▸ Actions**.
Optionally create an environment named **`release`** (Settings ▸ Environments) and add required reviewers –
the publish jobs then wait for a manual approval before they run.

### VS Code Marketplace — secret `VSCE_PAT` (required for VS Code releases)
1. Create a publisher at <https://marketplace.visualstudio.com/manage> – its ID must equal `"publisher"` in `vscode-extension/package.json`.
2. At <https://dev.azure.com> create a **Personal Access Token**: organization *All accessible organizations*, scope *Marketplace ▸ Manage*.
3. Save it as secret `VSCE_PAT`.

### Open VSX — secret `OVSX_PAT` (optional; used by VSCodium, Cursor, Gitpod, …)
1. Log in at <https://open-vsx.org> with GitHub, link an Eclipse account and accept the publisher agreement.
2. Create an access token and save it as secret `OVSX_PAT`.
3. Create the namespace once: `npx ovsx create-namespace <publisher-id> -p <token>`.

Without this secret the Open VSX step is skipped with a warning.

### JetBrains Marketplace — secret `JETBRAINS_PUBLISH_TOKEN` (for Android Studio / IntelliJ)
1. **The first version must be uploaded manually** at <https://plugins.jetbrains.com/plugin/add>
   (build it with `gradle buildPlugin` or download the artifact from a CI run). It goes through a one-time review.
   Make sure the plugin ID in `plugin.xml` is final before that – it can't be changed later.
2. Afterwards create a token under your JetBrains Hub profile (*Authentication ▸ New token*, scope *Plugin Repository*)
   and save it as `JETBRAINS_PUBLISH_TOKEN`.
3. Without the token the upload step is skipped with a warning; the zip is still attached to the GitHub release.

**Plugin signing (optional, recommended).** Create a certificate as described in the
[JetBrains docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) and add the secrets
`JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY` and `JETBRAINS_PRIVATE_KEY_PASSWORD`.
`build.gradle.kts` reads them from the environment.

## Gradle wrapper (optional)

CI installs Gradle 8.10.2 itself, so the repository works without a committed wrapper.
For convenient local builds generate one once and commit it:

```sh
cd intellij-plugin
gradle wrapper --gradle-version 8.10.2
git add gradlew gradlew.bat gradle/wrapper
```

`.gitattributes` keeps `gradlew` on LF line endings (important when committing from Windows).
