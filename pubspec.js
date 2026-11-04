'use strict';

/** Name of the top-level pubspec key this module manages. */
const SECTION = 'dependency_overrides';

/** Matches the section header line; capture group 1 is whatever follows the colon. */
const SECTION_RE = /^dependency_overrides\s*:(.*)$/;

/** Matches an indented `key:` line; capture group 1 = indentation, group 2 = key. */
const KEY_RE = /^(\s+)([A-Za-z0-9_]+)\s*:/;

/**
 * @typedef {Object} OverrideEntry
 * @property {string}   name  Package name (the key under `dependency_overrides`).
 * @property {string[]} lines Fully rendered YAML lines of the entry, indentation included.
 */

/**
 * @typedef {Object} Section
 * @property {number} start  Index of the `dependency_overrides:` header line.
 * @property {number} end    Exclusive index after the last line that belongs to the section.
 * @property {string} inline Anything written after the colon on the header line (comments removed),
 *                           e.g. `{}` for an empty inline map; empty string for a normal block.
 */

/**
 * @typedef {Object} EntryRange
 * @property {string} name  Package name of the entry.
 * @property {number} start Index of the entry's first line (`name:`).
 * @property {number} end   Exclusive index after the entry's last line.
 */

/**
 * Quotes a scalar for YAML only when necessary.
 *
 * Plain scalars are kept as they are for readability (`../foo`, `feature/x`).
 * Values that YAML could misinterpret – leading special characters, `: ` or ` #`
 * inside, pure numbers like `1.0` (would become a float), booleans/null, or the
 * empty string – are emitted as a JSON string, which is valid YAML.
 *
 * @param {string|number} value Raw value to be written.
 * @returns {string} The value, quoted if required.
 */
function yamlStr(value) {
  const v = String(value);
  const needsQuote =
    /^[\s\-?:,\[\]{}#&*!|>'"%@`]/.test(v) || // starts with a YAML indicator character
    /:\s|\s#|\s$/.test(v) ||                 // contains ": " / " #" or trailing whitespace
    /^[\d.]+$/.test(v) ||                    // would be parsed as a number (e.g. ref 1.0)
    /^(true|false|null|yes|no|~)$/i.test(v) || // would be parsed as bool/null
    v === '';
  return needsQuote ? JSON.stringify(v) : v;
}

/**
 * Renders a local path override.
 *
 * @example
 * renderLocal('foo', '../foo')
 * // → ['  foo:', '    path: ../foo']
 *
 * @param {string} name    Package name.
 * @param {string} relPath Path to the package, relative to the pubspec (POSIX separators).
 * @returns {OverrideEntry}
 */
function renderLocal(name, relPath) {
  return { name, lines: [`  ${name}:`, `    path: ${yamlStr(relPath)}`] };
}

/**
 * Renders a git override. `ref` and `subPath` are optional and omitted when empty.
 *
 * @example
 * renderGit('bar', 'git@github.com:acme/bar.git', 'main', 'packages/bar')
 * // → ['  bar:', '    git:', '      url: git@github.com:acme/bar.git',
 * //    '      ref: main', '      path: packages/bar']
 *
 * @param {string}  name      Package name.
 * @param {string}  url       Git URL of the repository.
 * @param {string}  [ref]     Branch, tag or commit hash. Empty = default branch of the repo.
 * @param {string}  [subPath] Sub-folder of the package inside a monorepo.
 * @returns {OverrideEntry}
 */
function renderGit(name, url, ref, subPath) {
  const lines = [`  ${name}:`, '    git:', `      url: ${yamlStr(url)}`];
  if (ref) lines.push(`      ref: ${yamlStr(ref)}`);
  if (subPath) lines.push(`      path: ${yamlStr(subPath)}`);
  return { name, lines };
}

/**
 * Splits a file into lines and remembers its line-ending style, so the file
 * can be re-joined without converting CRLF to LF (or vice versa).
 *
 * @param {string} text Complete file content.
 * @returns {{eol: string, lines: string[]}}
 */
function parse(text) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  return { eol, lines: text.split(/\r?\n/) };
}

/**
 * @param {string} line
 * @returns {number} Number of leading whitespace characters.
 */
function indentOf(line) {
  return line.length - line.trimStart().length;
}

/**
 * @param {string} line
 * @returns {boolean} True for empty/whitespace-only lines and `#` comment lines.
 */
function isBlankOrComment(line) {
  const t = line.trim();
  return t === '' || t.startsWith('#');
}

/**
 * Locates the `dependency_overrides` section.
 *
 * The section ends right before the next top-level key (a non-blank,
 * non-comment line with indentation 0). Trailing blank lines and top-level
 * comments are deliberately NOT part of the section, so new entries are
 * inserted directly after the last indented line.
 *
 * @param {string[]} lines File content split into lines.
 * @returns {Section|null} The section, or null if the file has none.
 */
function findSection(lines) {
  const start = lines.findIndex((l) => SECTION_RE.test(l));
  if (start === -1) return null;

  // Text after "dependency_overrides:" without a trailing comment, e.g. "{}".
  const rest = SECTION_RE.exec(lines[start])[1].replace(/#.*$/, '').trim();

  let end = start + 1;
  for (let i = start + 1; i < lines.length; i++) {
    const l = lines[i];
    if (l.trim() === '') continue;
    if (indentOf(l) === 0) {
      if (l.trim().startsWith('#')) continue; // top-level comment: neither ends nor extends the section
      break; // next top-level key → section is over
    }
    end = i + 1; // indented line → belongs to the section
  }
  return { start, end, inline: rest };
}

/**
 * Finds the individual package entries inside a section.
 *
 * The entry indentation is derived from the shallowest non-comment line in the
 * section, so 2-space, 4-space and tab-indented files are all handled. Lines
 * indented deeper than that belong to the preceding entry (e.g. `path:`,
 * `git:` and its children).
 *
 * @param {string[]} lines   File content split into lines.
 * @param {Section}  section Section returned by {@link findSection}.
 * @returns {EntryRange[]} Entries in file order.
 */
function parseEntries(lines, section) {
  // Indices of all meaningful (non-blank, non-comment) lines in the section body.
  const body = [];
  for (let i = section.start + 1; i < section.end; i++) {
    if (!isBlankOrComment(lines[i])) body.push(i);
  }
  if (!body.length) return [];

  // Package names live on the shallowest indentation level.
  const entryIndent = Math.min(...body.map((i) => indentOf(lines[i])));
  const entries = [];
  for (const i of body) {
    if (indentOf(lines[i]) !== entryIndent) continue;
    const m = KEY_RE.exec(lines[i]);
    if (m) entries.push({ name: m[2], start: i, end: section.end });
  }

  // Each entry ends where the next one starts (the last one at the section end).
  entries.forEach((e, idx) => {
    if (idx < entries.length - 1) e.end = entries[idx + 1].start;
  });

  // Don't swallow trailing blank/comment lines when an entry is removed later.
  for (const e of entries) {
    while (e.end > e.start + 1 && isBlankOrComment(lines[e.end - 1])) e.end--;
  }
  return entries;
}

/**
 * Lists the names of all packages currently overridden in the pubspec.
 *
 * @param {string} text Complete pubspec.yaml content.
 * @returns {string[]} Package names in file order (empty if there is no section).
 */
function listOverrides(text) {
  const { lines } = parse(text);
  const section = findSection(lines);
  if (!section) return [];
  return parseEntries(lines, section).map((e) => e.name);
}

/**
 * Determines HOW a package is currently overridden.
 *
 * Only the keys on the first level below the package name are inspected, so a
 * nested `path:` inside a `git:` block is correctly reported as `'git'`.
 * Inline forms such as `foo: ^1.0.0` or `foo: {path: ../foo}` are recognised too.
 *
 * @example
 * getOverrideType(text, 'foo') // → 'local' | 'git' | 'sdk' | 'hosted' | null
 *
 * @param {string} text        Complete pubspec.yaml content.
 * @param {string} packageName Package to look up.
 * @returns {'local'|'git'|'sdk'|'hosted'|null} The override type, or null if the package is not overridden
 *          (or the form is not recognised).
 */
function getOverrideType(text, packageName) {
  const { lines } = parse(text);
  const section = findSection(lines);
  if (!section) return null;

  const entry = parseEntries(lines, section).find((e) => e.name === packageName);
  if (!entry) return null;

  // Inline value on the same line, e.g. `foo: ^1.0.0` or `foo: {path: ../foo}`.
  const rest = lines[entry.start].slice(lines[entry.start].indexOf(':') + 1).replace(/#.*$/, '').trim();
  if (rest) {
    if (/\bgit\s*:/.test(rest)) return 'git';
    if (/\bpath\s*:/.test(rest)) return 'local';
    return 'hosted'; // plain version constraint
  }

  // Block form: look at the first key below the package name.
  const children = [];
  for (let i = entry.start + 1; i < entry.end; i++) {
    if (!isBlankOrComment(lines[i])) children.push(i);
  }
  if (!children.length) return null;
  const childIndent = Math.min(...children.map((i) => indentOf(lines[i])));
  for (const i of children) {
    if (indentOf(lines[i]) !== childIndent) continue;
    const key = /^\s*([A-Za-z_]+)\s*:/.exec(lines[i])?.[1];
    if (key === 'git') return 'git';
    if (key === 'path') return 'local';
    if (key === 'sdk') return 'sdk';
    if (key === 'hosted' || key === 'version') return 'hosted';
  }
  return null;
}

/**
 * Normalises an empty inline map (`dependency_overrides: {}`) to block style
 * so entries can be appended below it. Non-empty inline maps are not supported.
 *
 * Mutates `lines` in place.
 *
 * @param {string[]} lines   File content split into lines.
 * @param {Section}  section Section returned by {@link findSection}.
 * @throws {Error} If the section is a non-empty inline map.
 */
function normalizeInline(lines, section) {
  if (!section.inline) return; // already block style
  if (/^\{\s*\}$/.test(section.inline)) {
    lines[section.start] = `${SECTION}:`;
  } else {
    throw new Error(
      'dependency_overrides is written as an inline map – please convert it to block style once.'
    );
  }
}

/**
 * Removes the given packages from the section. Mutates `lines` in place and
 * leaves the (possibly now empty) section header untouched.
 *
 * @param {string[]} lines File content split into lines.
 * @param {string[]} names Package names to remove.
 * @returns {boolean} True if at least one entry was removed.
 */
function removeEntries(lines, names) {
  const section = findSection(lines);
  if (!section) return false;
  normalizeInline(lines, section);

  const entries = parseEntries(lines, section).filter((e) => names.includes(e.name));
  // Delete bottom-up so the indices of earlier entries stay valid while splicing.
  entries.sort((a, b) => b.start - a.start).forEach((e) => lines.splice(e.start, e.end - e.start));
  return entries.length > 0;
}

/**
 * Adds overrides to the pubspec, replacing existing entries of the same name.
 * Creates the `dependency_overrides:` section at the end of the file if needed.
 * Line endings (LF/CRLF) are preserved and the file ends with exactly one newline.
 *
 * @param {string}          text       Complete pubspec.yaml content.
 * @param {OverrideEntry[]} newEntries Entries created via {@link renderLocal} / {@link renderGit}.
 * @returns {string} The updated file content.
 * @throws {Error} If an existing section is a non-empty inline map.
 */
function applyOverrides(text, newEntries) {
  const { eol, lines } = parse(text);
  const names = newEntries.map((e) => e.name);

  // Drop old versions of the same packages first, so "add" doubles as "replace".
  const hadSection = !!findSection(lines);
  if (hadSection) removeEntries(lines, names);

  // Re-locate the section: removal may have shifted the indices.
  const section = findSection(lines);
  const toInsert = newEntries.flatMap((e) => e.lines);

  if (!section) {
    // No section yet: append one at the end, separated by a blank line.
    while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
    lines.push('', `${SECTION}:`, ...toInsert, '');
  } else {
    normalizeInline(lines, section);
    lines.splice(section.end, 0, ...toInsert); // right after the last existing entry
  }
  return lines.join(eol).replace(/\s*$/, '') + eol;
}

/**
 * Removes the given packages from `dependency_overrides`. If this leaves the
 * section empty, the header (and one preceding blank line) is removed as well.
 *
 * @param {string}   text  Complete pubspec.yaml content.
 * @param {string[]} names Package names to remove.
 * @returns {string} The updated file content.
 */
function removeOverrides(text, names) {
  const { eol, lines } = parse(text);
  removeEntries(lines, names);

  // Clean up an empty section so no dangling `dependency_overrides:` remains.
  const section = findSection(lines);
  if (section && parseEntries(lines, section).length === 0) {
    let from = section.start;
    if (from > 0 && lines[from - 1].trim() === '') from--; // also drop the separating blank line
    lines.splice(from, section.end - from);
  }
  return lines.join(eol).replace(/\s*$/, '') + eol;
}

/**
 * Reads the package's own name from the top-level `name:` key.
 *
 * @param {string} text Complete pubspec.yaml content.
 * @returns {string|null} The package name, or null if none was found.
 */
function packageName(text) {
  const m = /^name\s*:\s*([A-Za-z0-9_]+)/m.exec(text);
  return m ? m[1] : null;
}

/**
 * Heuristic: does this pubspec depend on the Flutter SDK? Used to choose
 * between `flutter pub get` and `dart pub get`.
 *
 * @param {string} text Complete pubspec.yaml content.
 * @returns {boolean}
 */
function usesFlutter(text) {
  return /sdk\s*:\s*flutter/.test(text);
}

module.exports = {
  renderLocal,
  renderGit,
  listOverrides,
  getOverrideType,
  applyOverrides,
  removeOverrides,
  packageName,
  usesFlutter,
};