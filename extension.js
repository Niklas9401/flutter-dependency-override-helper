'use strict';
const vscode = require('vscode');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFile } = require('child_process');
const ps = require('./pubspec');

/**
 * Directory names that are never scanned when looking for local packages.
 * These are platform folders and source/test folders of Flutter projects;
 * they never contain packages.
 */
const IGNORED_DIRS = new Set(['node_modules', 'build', 'ios', 'android', 'macos', 'linux', 'windows', 'web', 'lib', 'test']);

/**
 * How long (ms) the result of scanning the local packages folders is reused.
 * CodeLens is requested after every keystroke, so the file system must not be
 * walked each time. Explicit actions always bypass the cache.
 */
const LOCAL_SCAN_TTL_MS = 15000;

/**
 * Cache of local package scans, keyed by pubspec + relevant settings.
 * @type {Map<string, {time: number, packages: LocalPackage[]}>}
 */
const localScanCache = new Map();

/**
 * @typedef {Object} LocalPackage
 * @property {string} name Package name from its pubspec.yaml.
 * @property {string} dir  Absolute path of the package directory.
 */

/**
 * @typedef {Object} GitRepo
 * @property {string} name  Package name.
 * @property {string} url   Git URL.
 * @property {string} [path] Sub-folder inside the repository (monorepo).
 */

/**
 * Extension entry point. Registers all commands declared in package.json,
 * the internal CodeLens commands and the CodeLens provider.
 * Every command handler is wrapped so that unexpected errors are shown to the
 * user as an error message instead of failing silently.
 *
 * @param {vscode.ExtensionContext} context Extension context; registrations are added to its subscriptions.
 */
function activate(context) {
  /**
   * Registers one command whose handler receives the extension context first.
   * @param {string} id Command id.
   * @param {(context: vscode.ExtensionContext, ...args: any[]) => Promise<void>} fn Async handler.
   */
  const reg = (id, fn) =>
    context.subscriptions.push(
      vscode.commands.registerCommand(id, (...args) =>
        fn(context, ...args).catch((e) => vscode.window.showErrorMessage(`Pubspec Overrides: ${e.message || e}`))
      )
    );

  // Commands visible in the Command Palette (declared in package.json).
  reg('pubspecOverrides.add', cmdAdd);
  reg('pubspecOverrides.addLocal', cmdAddLocal);
  reg('pubspecOverrides.addGit', cmdAddGit);
  reg('pubspecOverrides.remove', cmdRemove);
  reg('pubspecOverrides.removeAll', cmdRemoveAll);

  // Internal commands triggered by the CodeLens links (intentionally not in package.json).
  reg('pubspecOverrides.codeLens.addOverride', lensAddOverride);
  reg('pubspecOverrides.codeLens.removeOverride', lensRemoveOverride);
  reg('pubspecOverrides.codeLens.switchOverride', lensSwitchOverride);
  reg('pubspecOverrides.codeLens.removePackage', lensRemovePackage);

  // Inline links above the package names in every pubspec.yaml.
  const provider = new PubspecCodeLensProvider();
  context.subscriptions.push(
    vscode.languages.registerCodeLensProvider({ pattern: '**/pubspec.yaml' }, provider),
    // Changed folders/repos change which links are offered → drop the cache and redraw.
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('pubspecOverrides')) {
        localScanCache.clear();
        provider.refresh();
      }
    })
  );
}

// ---------- CodeLens ----------

/**
 * Provides clickable links ("CodeLens", like Run | Debug above tests) above
 * the package names of a pubspec.yaml:
 *
 *  - on `dependencies` / `dev_dependencies` entries:
 *      "Add override"   – only if the package is available in the configured
 *                         local folders or Git repos and not yet overridden
 *      "Remove package" – removes the dependency
 *  - on `dependency_overrides` entries:
 *      "Remove override"
 *      "Switch to Local" / "Switch to Git" – only if the package is available
 *                         as the other type
 *
 * SDK dependencies (`flutter`, `flutter_test`, …) get no links.
 */
class PubspecCodeLensProvider {
  constructor() {
    this._emitter = new vscode.EventEmitter();
    /** Lets VS Code know that the lenses should be requested again. */
    this.onDidChangeCodeLenses = this._emitter.event;
  }

  /** Forces VS Code to re-request the lenses (e.g. after a settings change). */
  refresh() {
    this._emitter.fire();
  }

  /**
   * Builds the lenses for one pubspec document.
   *
   * @param {vscode.TextDocument} document The pubspec.yaml being displayed.
   * @returns {vscode.CodeLens[]} The links; empty if the feature is disabled.
   */
  provideCodeLenses(document) {
    const cfg = vscode.workspace.getConfiguration('pubspecOverrides', document.uri);
    if (!cfg.get('codeLens.enabled', true)) return [];

    const entries = ps.listPackageEntries(document.getText());
    const localByName = new Map(getLocalPackages(document.uri).map((p) => [p.name, p]));
    const gitByName = new Map(getGitRepos(cfg).map((r) => [r.name, r]));
    const overridden = new Set(entries.dependency_overrides.map((e) => e.name));

    /** @type {vscode.CodeLens[]} */
    const lenses = [];
    /**
     * Adds one link at the start of `line`.
     * @param {number} line 0-based line.
     * @param {string} title Text shown in the editor (supports $(codicon) syntax).
     * @param {string} command Internal command id.
     * @param {any[]} args Command arguments.
     * @param {string} tooltip Hover text.
     */
    const add = (line, title, command, args, tooltip) =>
      lenses.push(new vscode.CodeLens(new vscode.Range(line, 0, line, 0), { title, command, arguments: args, tooltip }));

    // Regular dependencies: add an override / remove the package.
    for (const section of ['dependencies', 'dev_dependencies']) {
      for (const e of entries[section]) {
        if (e.type === 'sdk') continue; // flutter, flutter_test, … – nothing sensible to do
        if (!overridden.has(e.name) && (localByName.has(e.name) || gitByName.has(e.name))) {
          add(e.line, '$(add) Add override', 'pubspecOverrides.codeLens.addOverride', [document.uri, e.name],
            `Override "${e.name}" with a local package or a Git reference`);
        }
        add(e.line, '$(trash) Remove package', 'pubspecOverrides.codeLens.removePackage', [document.uri, e.name, section],
          `Remove "${e.name}" from ${section}`);
      }
    }

    // Existing overrides: remove / switch to the other type (if available).
    for (const e of entries.dependency_overrides) {
      add(e.line, '$(trash) Remove override', 'pubspecOverrides.codeLens.removeOverride', [document.uri, e.name],
        `Remove the override of "${e.name}"`);
      if (e.type !== 'local' && localByName.has(e.name)) {
        add(e.line, '$(arrow-swap) Switch to Local', 'pubspecOverrides.codeLens.switchOverride', [document.uri, e.name, 'local'],
          `Use the local package of "${e.name}" instead`);
      }
      if (e.type !== 'git' && gitByName.has(e.name)) {
        add(e.line, '$(arrow-swap) Switch to Git', 'pubspecOverrides.codeLens.switchOverride', [document.uri, e.name, 'git'],
          `Use the configured Git repository of "${e.name}" instead`);
      }
    }
    return lenses;
  }
}

/**
 * CodeLens action "Add override" for a package from `dependencies`.
 * If the package is available both locally and via Git, the user chooses;
 * otherwise the only available type is used directly.
 *
 * @param {vscode.ExtensionContext} _context Unused.
 * @param {vscode.Uri} uri  The pubspec.
 * @param {string}     name Package name.
 * @returns {Promise<void>}
 */
async function lensAddOverride(_context, uri, name) {
  const cfg = vscode.workspace.getConfiguration('pubspecOverrides', uri);
  const hasLocal = !!findLocalPackage(uri, name);
  const hasGit = !!findGitRepo(cfg, name);

  let type;
  if (hasLocal && hasGit) {
    const choice = await vscode.window.showQuickPick(
      [
        { label: '$(folder) Local', description: 'Package from local folders', id: 'local' },
        { label: '$(git-branch) Git', description: 'Configured repo with reference', id: 'git' },
      ],
      { placeHolder: `Override "${name}" with…` }
    );
    if (!choice) return;
    type = choice.id;
  } else if (hasLocal || hasGit) {
    type = hasLocal ? 'local' : 'git';
  } else {
    throw new Error(`"${name}" was not found in the configured local folders or Git repos.`);
  }
  await writeSingleOverride(uri, name, type);
}

/**
 * CodeLens action "Switch to Local/Git" for an existing override.
 * The old override is replaced by one of the requested type.
 *
 * @param {vscode.ExtensionContext} _context Unused.
 * @param {vscode.Uri}   uri    The pubspec.
 * @param {string}       name   Package name.
 * @param {'local'|'git'} target Type to switch to.
 * @returns {Promise<void>}
 */
async function lensSwitchOverride(_context, uri, name, target) {
  // An explicit click on "Switch to …" is already the confirmation – no type-conflict dialog.
  await writeSingleOverride(uri, name, target);
}

/**
 * CodeLens action "Remove override".
 *
 * @param {vscode.ExtensionContext} _context Unused.
 * @param {vscode.Uri} uri  The pubspec.
 * @param {string}     name Package name whose override is removed.
 * @returns {Promise<void>}
 */
async function lensRemoveOverride(_context, uri, name) {
  const { doc, text } = await readPubspec(uri);
  await writePubspec(doc, ps.removeOverrides(text, [name]));
}

/**
 * CodeLens action "Remove package": deletes a dependency from the section
 * it was clicked in, after a modal confirmation. An existing override of the
 * same package is left untouched (it can be removed separately).
 *
 * @param {vscode.ExtensionContext} _context Unused.
 * @param {vscode.Uri} uri     The pubspec.
 * @param {string}     name    Package name.
 * @param {string}     section `dependencies` or `dev_dependencies`.
 * @returns {Promise<void>}
 */
async function lensRemovePackage(_context, uri, name, section) {
  const ok = await vscode.window.showWarningMessage(
    `Remove "${name}" from ${section}?`,
    { modal: true },
    'Remove'
  );
  if (!ok) return;
  const { doc, text } = await readPubspec(uri);
  await writePubspec(doc, ps.removeDependencies(text, section, [name]));
}

/**
 * Creates an override of the given type for one package and writes it,
 * replacing an existing override of that package.
 * Does nothing if the user cancels the Git reference prompt.
 *
 * @param {vscode.Uri}    uri  The pubspec.
 * @param {string}        name Package name.
 * @param {'local'|'git'} type Override type to create.
 * @returns {Promise<void>}
 */
async function writeSingleOverride(uri, name, type) {
  const entry = await buildOverrideEntry(uri, name, type);
  if (!entry) return; // cancelled
  const { doc } = await readPubspec(uri);
  await writePubspec(doc, ps.applyOverrides(doc.getText(), [entry]));
}

/**
 * Builds the YAML entry for one package: a relative `path:` for local
 * overrides, or a `git:` block for which the user is asked for the reference.
 *
 * @param {vscode.Uri}    uri  The pubspec (determines relative paths and settings scope).
 * @param {string}        name Package name.
 * @param {'local'|'git'} type Override type.
 * @returns {Promise<import('./pubspec').OverrideEntry|undefined>} The entry, or undefined if the user cancelled.
 * @throws {Error} If the package is not available as the requested type.
 */
async function buildOverrideEntry(uri, name, type) {
  const cfg = vscode.workspace.getConfiguration('pubspecOverrides', uri);

  if (type === 'local') {
    const pkg = findLocalPackage(uri, name);
    if (!pkg) throw new Error(`"${name}" was not found in the configured local folders.`);
    return ps.renderLocal(name, toPosix(path.relative(path.dirname(uri.fsPath), pkg.dir)));
  }

  const repo = findGitRepo(cfg, name);
  if (!repo) throw new Error(`"${name}" is not configured in pubspecOverrides.gitRepos.`);
  const ref = await askGitRef(`Git reference for ${name}`, await defaultGitRef(uri));
  if (ref === undefined) return undefined;
  return ps.renderGit(name, repo.url, ref.trim(), repo.path);
}

// ---------- Locating & writing the pubspec ----------

/**
 * Determines which pubspec.yaml a command should operate on.
 *
 * Resolution order:
 *  1. The active editor, if it shows a pubspec.yaml.
 *  2. The only pubspec.yaml in the workspace.
 *  3. A quick-pick if several exist (build/tooling folders are excluded).
 *
 * @returns {Promise<vscode.Uri|undefined>} The chosen file, or undefined if the user cancelled.
 * @throws {Error} If the workspace contains no pubspec.yaml at all.
 */
async function resolvePubspec() {
  const active = vscode.window.activeTextEditor?.document;
  if (active && path.basename(active.fileName) === 'pubspec.yaml') return active.uri;

  const files = await vscode.workspace.findFiles(
    '**/pubspec.yaml',
    '**/{.dart_tool,node_modules,build,.symlinks,ephemeral,.pub-cache}/**',
    50
  );
  if (!files.length) throw new Error('No pubspec.yaml found in the workspace.');
  if (files.length === 1) return files[0];

  const picked = await vscode.window.showQuickPick(
    files.map((u) => ({ label: vscode.workspace.asRelativePath(u), uri: u })),
    { placeHolder: 'Which pubspec.yaml?' }
  );
  return picked?.uri;
}

/**
 * Opens a pubspec as a VS Code document and reads its current text.
 * Going through the document (not the file system) means unsaved editor
 * changes are taken into acount.
 *
 * @param {vscode.Uri} uri Location of the pubspec.yaml.
 * @returns {Promise<{doc: vscode.TextDocument, text: string}>}
 */
async function readPubspec(uri) {
  const doc = await vscode.workspace.openTextDocument(uri);
  return { doc, text: doc.getText() };
}

/**
 * Replaces the complete content of a pubspec and saves it.
 *
 * The change is applied as a regular workspace edit, so the user can undo it
 * with Ctrl/Cmd+Z. If the setting `pubspecOverrides.runPubGet` is enabled,
 * `dart pub get` or `flutter pub get` is started in a termnal afterwards.
 *
 * @param {vscode.TextDocument} doc     The pubspec document.
 * @param {string}              newText The new complete file content.
 * @returns {Promise<void>}
 */
async function writePubspec(doc, newText) {
  const edit = new vscode.WorkspaceEdit();
  const full = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
  edit.replace(doc.uri, full, newText);
  await vscode.workspace.applyEdit(edit);
  await doc.save();

  if (vscode.workspace.getConfiguration('pubspecOverrides', doc.uri).get('runPubGet')) {
    // Flutter projects need `flutter pub get`, plain Dart packages `dart pub get`.
    const cmd = ps.usesFlutter(newText) ? 'flutter pub get' : 'dart pub get';
    const term = vscode.window.createTerminal({ name: 'Pubspec Overrides', cwd: path.dirname(doc.uri.fsPath) });
    term.show(true); // true = don't steal keyboard focus from the editor
    term.sendText(cmd);
  }
}

// ---------- Type check & warning on type change ----------

/**
 * Warns the user if an override is about to be switched to a different type
 * (e.g. an existing `git` override would be replaced by a `local` one).
 *
 * Each conflicting package triggers its own modal confirmation. The check is
 * skipped entirely if `pubspecOverrides.autoConfirmTypeConflict` is true.
 *
 * @param {string}   text                 Current pubspec.yaml content.
 * @param {string[]} selectedPackageNames Packages the user is about to add/replace.
 * @param {'local'|'git'} targetType      Type of the override that will be written.
 * @param {vscode.Uri} uri                The pubspec (used to read resource-scoped settings).
 * @returns {Promise<boolean>} True if it is OK to continue, false if the user declined.
 */
async function checkTypeConflict(text, selectedPackageNames, targetType, uri) {
  const cfg = vscode.workspace.getConfiguration('pubspecOverrides', uri);

  // Auto-confirm enabled in the settings → never ask.
  if (cfg.get('autoConfirmTypeConflict', false)) {
    return true;
  }

  for (const name of selectedPackageNames) {
    const currentType = ps.getOverrideType(text, name); // 'local', 'git', 'sdk', 'hosted' or null
    // null = not overridden yet → nothing to warn about; same type → plain update.
    if (currentType && currentType !== targetType) {
      const answer = await vscode.window.showWarningMessage(
        `The package "${name}" is already overridden as "${currentType}" and would now be replaced by a "${targetType}" override. Continue?`,
        { modal: true },
        'Yes'
      );
      if (answer !== 'Yes') {
        return false;
      }
    }
  }
  return true;
}

// ---------- Commands ----------

/**
 * Command "Add override…": asks for the override type first and then delegates
 * to {@link cmdAddLocal} or {@link cmdAddGit}.
 *
 * @param {vscode.ExtensionContext} context Extension context, passed through to the sub-commands.
 * @returns {Promise<void>}
 */
async function cmdAdd(context) {
  const choice = await vscode.window.showQuickPick(
    [
      { label: '$(folder) Local', description: 'Package from local folders', id: 'local' },
      { label: '$(git-branch) Git', description: 'Configured repos with reference', id: 'git' },
    ],
    { placeHolder: 'Select override type' }
  );
  if (!choice) return;
  return choice.id === 'local' ? cmdAddLocal(context) : cmdAddGit(context);
}

/**
 * Command "Add local packages…".
 *
 * Flow:
 *  1. Determine the folders to scan: `packagesFolders` setting, or a
 *     folder dialog (optionally saved to the workspace settings).
 *  2. Scan all folders for packages (identified by their own pubspec.yaml).
 *  3. Let the user multi-select packages and confirm type changes.
 *  4. Write relative `path:` overrides into the pubspec.
 *
 * @returns {Promise<void>}
 */
async function cmdAddLocal() {
  const uri = await resolvePubspec();
  if (!uri) return;
  const { doc, text } = await readPubspec(uri);
  const cfg = vscode.workspace.getConfiguration('pubspecOverrides', uri);

  let folders = cfg.get('packagesFolders') || [];

  // Nothing configured → ask the user and offer to remember the choice.
  if (!folders.length) {
    const sel = await vscode.window.showOpenDialog({
      canSelectFiles: false,
      canSelectFolders: true,
      canSelectMany: true,
      openLabel: 'Select Packages Folders',
    });
    if (!sel?.length) return;
    folders = sel.map(s => s.fsPath);
    const save = await vscode.window.showInformationMessage(
      'Save these folders as the default for the workspace?',
      'Yes',
      'No'
    );
    if (save === 'Yes') {
      await cfg.update('packagesFolders', folders, vscode.ConfigurationTarget.Workspace);
    }
  }

  // Scan every configured folder (missing folders are skipped silently) and
  // never offer the package itself – it must not override its own dependency.
  const self = ps.packageName(text);
  const scanDepth = cfg.get('scanDepth') ?? 2;
  const pkgs = scanLocalPackages(uri, folders, scanDepth).filter((p) => p.name !== self);

  if (!pkgs.length) throw new Error(`No packages (pubspec.yaml) found in the specified folders.`);

  const existing = new Set(ps.listOverrides(text));
  const pubspecDir = path.dirname(uri.fsPath);
  const picked = await vscode.window.showQuickPick(
    pkgs.map((p) => ({
      label: p.name,
      description: toPosix(path.relative(pubspecDir, p.dir)), // the path that will be written
      detail: existing.has(p.name) ? 'already set as override – will be replaced' : undefined,
      pkg: p,
    })),
    { canPickMany: true, placeHolder: 'Select packages', matchOnDescription: true }
  );
  if (!picked?.length) return;

  // Warn if an existing git override would be turned into a local one.
  const packageNames = picked.map(i => i.label);
  if (!(await checkTypeConflict(text, packageNames, 'local', uri))) {
    return;
  }

  const entries = picked.map((i) => ps.renderLocal(i.label, i.description));
  // Re-read the text from the document: it may have changed while the dialogs were open.
  await writePubspec(doc, ps.applyOverrides(doc.getText(), entries));
  vscode.window.showInformationMessage(`${entries.length} local override(s) set.`);
}

/**
 * Command "Add Git references…".
 *
 * Repos come from the `pubspecOverrides.gitRepos` setting. After selecting
 * repos the user is asked for a reference (branch, tag or commit) per repo;
 * the input is pre-filled with the current branch of the project.
 *
 * @returns {Promise<void>}
 */
async function cmdAddGit() {
  const uri = await resolvePubspec();
  if (!uri) return;
  const { doc, text } = await readPubspec(uri);
  const cfg = vscode.workspace.getConfiguration('pubspecOverrides', uri);

  const repos = getGitRepos(cfg);
  if (!repos.length) {
    const a = await vscode.window.showWarningMessage(
      'No Git repos configured (pubspecOverrides.gitRepos).',
      'Open Settings'
    );
    if (a) vscode.commands.executeCommand('workbench.action.openSettings', 'pubspecOverrides.gitRepos');
    return;
  }

  const self = ps.packageName(text);
  const existing = new Set(ps.listOverrides(text));
  const picked = await vscode.window.showQuickPick(
    repos
      .filter((r) => r.name !== self) // a package must not override itself
      .map((r) => ({
        label: r.name,
        description: r.url + (r.path ? ` (${r.path})` : ''),
        detail: existing.has(r.name) ? 'already set as override – will be replaced' : undefined,
        repo: r,
      })),
    { canPickMany: true, placeHolder: 'Select Git repos', matchOnDescription: true }
  );
  if (!picked?.length) return;

  // Warn if an existing local override would be turned into a git one.
  const packageNames = picked.map(i => i.label);
  if (!(await checkTypeConflict(text, packageNames, 'git', uri))) {
    return;
  }

  // Pre-fill with the current branch; fall back to the configured default ref.
  const defaultRef = await defaultGitRef(uri);
  const entries = [];
  for (const item of picked) {
    const ref = await askGitRef(
      `Git reference for ${item.repo.name} (${picked.indexOf(item) + 1}/${picked.length})`,
      defaultRef
    );
    if (ref === undefined) return; // Escape pressed → cancel the whole command, write nothing
    entries.push(ps.renderGit(item.repo.name, item.repo.url, ref.trim(), item.repo.path));
  }

  await writePubspec(doc, ps.applyOverrides(doc.getText(), entries));
  vscode.window.showInformationMessage(`${entries.length} Git-Override(s) set.`);
}

/**
 * Command "Remove overrides": lets the user pick which existing overrides to delete.
 *
 * @returns {Promise<void>}
 */
async function cmdRemove() {
  const uri = await resolvePubspec();
  if (!uri) return;
  const { doc, text } = await readPubspec(uri);
  const names = ps.listOverrides(text);
  if (!names.length) return void vscode.window.showInformationMessage('No dependency_overrides found.');

  const picked = await vscode.window.showQuickPick(names, {
    canPickMany: true,
    placeHolder: 'Which overrides to remove?',
  });
  if (!picked?.length) return;
  await writePubspec(doc, ps.removeOverrides(text, picked));
}

/**
 * Command "Remove all overrides": deletes every entry (and the then empty
 * `dependency_overrides` section) after a modal confirmation.
 *
 * @returns {Promise<void>}
 */
async function cmdRemoveAll() {
  const uri = await resolvePubspec();
  if (!uri) return;
  const { doc, text } = await readPubspec(uri);
  const names = ps.listOverrides(text);
  if (!names.length) return void vscode.window.showInformationMessage('No dependency_overrides found.');
  const ok = await vscode.window.showWarningMessage(
    `Remove all ${names.length} overrides?`,
    { modal: true },
    'Remove'
  );
  if (ok) await writePubspec(doc, ps.removeOverrides(text, names));
}

// ---------- Helpers ----------

/**
 * Reads the valid Git repos from the settings. Incomplete entries
 * (name and url are mandatory) are ignored.
 *
 * @param {vscode.WorkspaceConfiguration} cfg Configuration of the `pubspecOverrides` section.
 * @returns {GitRepo[]}
 */
function getGitRepos(cfg) {
  return (cfg.get('gitRepos') || []).filter((r) => r && r.name && r.url);
}

/**
 * @param {vscode.WorkspaceConfiguration} cfg  Configuration of the `pubspecOverrides` section.
 * @param {string}                        name Package name.
 * @returns {GitRepo|undefined} The configured repo for that package, if any.
 */
function findGitRepo(cfg, name) {
  return getGitRepos(cfg).find((r) => r.name === name);
}

/**
 * Scans the given folders for local packages. If the same package name occurs
 * in several folders, the first folder in the list wins.
 *
 * @param {vscode.Uri} uri       The pubspec (needed to resolve relative folders).
 * @param {string[]}   folders   Raw folder values from the settings.
 * @param {number}     scanDepth How many levels below each folder are searched.
 * @returns {LocalPackage[]} Unique packages sorted by name.
 */
function scanLocalPackages(uri, folders, scanDepth) {
  const unique = new Map();
  for (const rawFolder of folders) {
    const resolved = resolveFolder(rawFolder, uri);
    if (!resolved || !fs.existsSync(resolved)) continue; // missing folders are skipped silently
    for (const p of findPackages(resolved, scanDepth)) {
      if (!unique.has(p.name)) unique.set(p.name, p);
    }
  }
  return Array.from(unique.values()).sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Local packages for the folders configured in the settings, with a short-lived
 * cache so the CodeLens provider doesn't walk the file system on every keystroke.
 *
 * @param {vscode.Uri} uri          The pubspec.
 * @param {boolean}    [fresh=false] Bypass the cache (used by explicit user actions).
 * @returns {LocalPackage[]} Empty if no folders are configured.
 */
function getLocalPackages(uri, fresh = false) {
  const cfg = vscode.workspace.getConfiguration('pubspecOverrides', uri);
  const folders = cfg.get('packagesFolders') || [];
  if (!folders.length) return []; // no dialog here – lenses must never prompt on their own
  const scanDepth = cfg.get('scanDepth') ?? 2;

  const key = `${uri.toString()}|${JSON.stringify(folders)}|${scanDepth}`;
  const hit = localScanCache.get(key);
  if (!fresh && hit && Date.now() - hit.time < LOCAL_SCAN_TTL_MS) return hit.packages;

  const packages = scanLocalPackages(uri, folders, scanDepth);
  localScanCache.set(key, { time: Date.now(), packages });
  return packages;
}

/**
 * Looks up a single local package by name (always with a fresh scan).
 *
 * @param {vscode.Uri} uri  The pubspec.
 * @param {string}     name Package name.
 * @returns {LocalPackage|undefined}
 */
function findLocalPackage(uri, name) {
  return getLocalPackages(uri, true).find((p) => p.name === name);
}

/**
 * Default value for the Git reference prompt: the currently checked-out branch
 * of the project, falling back to the `pubspecOverrides.defaultRef` setting.
 *
 * @param {vscode.Uri} uri The pubspec.
 * @returns {Promise<string>} May be empty.
 */
async function defaultGitRef(uri) {
  const cfg = vscode.workspace.getConfiguration('pubspecOverrides', uri);
  return (await currentBranch(path.dirname(uri.fsPath))) || cfg.get('defaultRef') || '';
}

/**
 * Asks the user for a Git reference.
 *
 * @param {string} title      Title of the input box.
 * @param {string} defaultRef Pre-filled value.
 * @returns {Promise<string|undefined>} The entered text (may be empty = default branch),
 *          or undefined if the user cancelled with Escape.
 */
function askGitRef(title, defaultRef) {
  return vscode.window.showInputBox({
    title,
    prompt: 'Branch, Tag or Commit Hash – empty = Default Branch of the Repo',
    value: defaultRef,
    ignoreFocusOut: true, // don't dismiss the input when the user clicks elsewhere
  });
}

/**
 * Turns a folder from the settings into an absolute path.
 *
 * Supported forms: absolute paths, `${workspaceFolder}/...`, `~/...` (home
 * directory) and paths relative to the workspace folder.
 *
 * @param {string}     value       Raw folder value from the settings.
 * @param {vscode.Uri} pubspecUri  The pubspec being edited; determines which workspace folder is meant.
 * @returns {string|null} Absolute path, or null for an empty value.
 */
function resolveFolder(value, pubspecUri) {
  if (!value) return null;
  // Fall back to the pubspec's own directory if it lies outside every workspace folder.
  const ws = vscode.workspace.getWorkspaceFolder(pubspecUri)?.uri.fsPath ?? path.dirname(pubspecUri.fsPath);
  let v = value.replace(/\$\{workspaceFolder\}/g, ws);
  if (v === '~' || v.startsWith('~/') || v.startsWith('~\\')) v = path.join(os.homedir(), v.slice(1));
  return path.isAbsolute(v) ? v : path.resolve(ws, v);
}

/**
 * Recursively searches a folder for Dart/Flutter packages.
 * A directory counts as a package if it contains a pubspec.yaml with a `name:`.
 * Hidden directories and those in {@link IGNORED_DIRS} are skipped.
 *
 * @param {string} root     Absolute folder to start in (depth 0 = the folder itself).
 * @param {number} maxDepth How many levels below `root` are searched.
 * @returns {LocalPackage[]} Packages sorted by name.
 */
function findPackages(root, maxDepth) {
  const result = [];
  const walk = (dir, depth) => {
    const pub = path.join(dir, 'pubspec.yaml');
    if (fs.existsSync(pub)) {
      const name = ps.packageName(fs.readFileSync(pub, 'utf8'));
      if (name) result.push({ name, dir });
    }
    if (depth >= maxDepth) return;
    let items = [];
    try {
      items = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return; // unreadable directory (permissions etc.) → skip
    }
    for (const it of items) {
      if (!it.isDirectory() || it.name.startsWith('.') || IGNORED_DIRS.has(it.name)) continue;
      walk(path.join(dir, it.name), depth + 1);
    }
  };
  walk(root, 0);
  return result.sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Determines the currently checked-out git branch via `git rev-parse`.
 * Never rejects: any failure is reported as an empty string.
 *
 * @param {string} cwd Directory inside the git repository (the pubspec's folder).
 * @returns {Promise<string>} Branch name, or '' if unavailable (no git, no repository, detached HEAD).
 */
function currentBranch(cwd) {
  return new Promise((resolve) => {
    execFile('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd, timeout: 5000 }, (err, stdout) => {
      const b = err ? '' : stdout.trim();
      resolve(b && b !== 'HEAD' ? b : '');
    });
  });
}

/**
 * Converts OS-specific path separators to `/`, as expected in pubspec.yaml
 * (relevant on Windows, where `path.relative` yields backslashes).
 *
 * @param {string} p Path with native separators.
 * @returns {string} Path with forward slashes.
 */
const toPosix = (p) => p.split(path.sep).join('/');

/** Called by VS Code when the extension is deactivated; nothing to clean up. */
function deactivate() {}
module.exports = { activate, deactivate };