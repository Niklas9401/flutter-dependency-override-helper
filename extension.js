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
 * Extension entry point. Registers all commands declared in package.json.
 * Every command handler is wrapped so that unexpected errors are shown to the
 * user as an error message instead of failing silently.
 *
 * @param {vscode.ExtensionContext} context Extension context; registrations are added to its subscriptions.
 */
function activate(context) {
  /**
   * Registers one command whose handler receives the extension context first.
   * @param {string} id Command id from package.json.
   * @param {(context: vscode.ExtensionContext, ...args: any[]) => Promise<void>} fn Async handler.
   */
  const reg = (id, fn) =>
    context.subscriptions.push(
      vscode.commands.registerCommand(id, (...args) =>
        fn(context, ...args).catch((e) => vscode.window.showErrorMessage(`Pubspec Overrides: ${e.message || e}`))
      )
    );
  reg('pubspecOverrides.add', cmdAdd);
  reg('pubspecOverrides.addLocal', cmdAddLocal);
  reg('pubspecOverrides.addGit', cmdAddGit);
  reg('pubspecOverrides.remove', cmdRemove);
  reg('pubspecOverrides.removeAll', cmdRemoveAll);
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

  // Scan every configured folder; folders that don't exist are skipped silently.
  const self = ps.packageName(text);
  const scanDepth = cfg.get('scanDepth') ?? 2;
  let allPkgs = [];

  for (const rawFolder of folders) {
    const resolved = resolveFolder(rawFolder, uri);
    if (resolved && fs.existsSync(resolved)) {
      const found = findPackages(resolved, scanDepth);
      allPkgs.push(...found);
    }
  }

  // De-duplicate by package name (first folder in the list wins) and never
  // offer the package itself – it must not override its own dependency.
  const uniquePkgsMap = new Map();
  for (const p of allPkgs) {
    if (p.name !== self && !uniquePkgsMap.has(p.name)) {
      uniquePkgsMap.set(p.name, p);
    }
  }
  const pkgs = Array.from(uniquePkgsMap.values()).sort((a, b) => a.name.localeCompare(b.name));

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

  // Ignore incomplete setting entries (name and url are mandatory).
  const repos = (cfg.get('gitRepos') || []).filter((r) => r && r.name && r.url);
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
  const defaultRef = (await currentBranch(path.dirname(uri.fsPath))) || cfg.get('defaultRef') || '';
  const entries = [];
  for (const item of picked) {
    const ref = await vscode.window.showInputBox({
      title: `Git reference for ${item.repo.name} (${picked.indexOf(item) + 1}/${picked.length})`,
      prompt: 'Branch, Tag or Commit Hash – empty = Default Branch of the Repo',
      value: defaultRef,
      ignoreFocusOut: true,
    });
    if (ref === undefined) return;
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
 * Turns a folder from the settings into an absolute path.
 *
 * Supported forms: absolute paths, `${workspaceFolder}/...`, `~/...` (home
 * directory) and paths relative to the workspace folder.
 *
 * @param {string}     value       Raw folder value from the settings.
 * @param {vscode.Uri} pubspecUri  The pubspec being edited; determines which workspace folder is meant.
 * @returns {string|null} Absollute path, or null for an empty value.
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
 * @returns {{name: string, dir: string}[]} Packages sorted by name; `dir` is the absolute package directory.
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

/** Called by VS Code when the extension is deactivated; */
function deactivate() {}
module.exports = { activate, deactivate };