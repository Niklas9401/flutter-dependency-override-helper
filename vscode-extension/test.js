// Plain Node tests for pubspec.js – run with: node test.js
const p = require('./pubspec');
const assert = require('assert');

// --- add: creates the section at the end of the file ---
let t = `name: app
dependencies:
  flutter:
    sdk: flutter
  foo: ^1.0.0
`;
t = p.applyOverrides(t, [
  p.renderLocal('foo', '../foo'),
  p.renderGit('bar', 'git@github.com:acme/bar.git', 'feature/x', 'packages/bar'),
]);
console.log(t);
assert.deepStrictEqual(p.listOverrides(t), ['foo', 'bar']);

// --- replace an existing entry while another top-level key follows the section ---
t += `\nflutter:\n  uses-material-design: true\n`;
t = p.applyOverrides(t, [p.renderGit('foo', 'https://github.com/acme/foo.git', '1.0', null)]);
console.log(t);
assert.deepStrictEqual(p.listOverrides(t), ['bar', 'foo']);
assert(t.indexOf('foo:\n    git') > t.indexOf('dependency_overrides'));
assert(t.indexOf('flutter:\n  uses') > t.indexOf('ref: "1.0"')); // numeric-looking ref gets quoted

// --- override type detection ---
assert.strictEqual(p.getOverrideType(t, 'foo'), 'git');
assert.strictEqual(p.getOverrideType(t, 'bar'), 'git'); // nested `path:` must not be mistaken for local
assert.strictEqual(p.getOverrideType(t, 'missing'), null);
const typed = `name: a
dependency_overrides:
  loc:
    path: ../loc
  inline_loc: {path: ../x}
  hosted_pkg: ^2.0.0
  sdk_pkg:
    sdk: flutter
`;
assert.strictEqual(p.getOverrideType(typed, 'loc'), 'local');
assert.strictEqual(p.getOverrideType(typed, 'inline_loc'), 'local');
assert.strictEqual(p.getOverrideType(typed, 'hosted_pkg'), 'hosted');
assert.strictEqual(p.getOverrideType(typed, 'sdk_pkg'), 'sdk');
assert.strictEqual(p.getOverrideType('name: a\n', 'x'), null); // no section at all

// --- remove: single entries, then the whole (now empty) section ---
let r = p.removeOverrides(t, ['bar']);
assert.deepStrictEqual(p.listOverrides(r), ['foo']);
r = p.removeOverrides(r, ['foo']);
console.log(r);
assert(!r.includes('dependency_overrides'));
assert(r.includes('uses-material-design'));

// --- CRLF line endings are preserved + empty inline map is normalised ---
let c = 'name: a\r\ndependency_overrides: {}\r\n';
c = p.applyOverrides(c, [p.renderLocal('x', '../x')]);
assert(c.includes('\r\n') && !/[^\r]\n/.test(c));
console.log(JSON.stringify(c));
console.log('OK');

// ---------------------------------------------------------------------------
// CodeLens support: listing all sections + removing regular dependencies
// ---------------------------------------------------------------------------
const full = `name: app

dependencies:
  flutter:
    sdk: flutter
  foo: ^1.0.0
  bar:
    git:
      url: git@github.com:acme/bar.git
      path: packages/bar
  baz:
    path: ../baz

dev_dependencies:
  flutter_test:
    sdk: flutter
  mocktail: ^1.0.0

dependency_overrides:
  foo:
    path: ../foo
  bar:
    git:
      url: git@github.com:acme/bar.git
      ref: main

flutter:
  uses-material-design: true
`;
const all = p.listPackageEntries(full);
assert.deepStrictEqual(all.dependencies.map((e) => [e.name, e.type]),
  [['flutter', 'sdk'], ['foo', 'hosted'], ['bar', 'git'], ['baz', 'local']]);
assert.deepStrictEqual(all.dev_dependencies.map((e) => [e.name, e.type]),
  [['flutter_test', 'sdk'], ['mocktail', 'hosted']]);
assert.deepStrictEqual(all.dependency_overrides.map((e) => [e.name, e.type]),
  [['foo', 'local'], ['bar', 'git']]);
// "line" must be the 0-based editor line of the entry
const fileLines = full.split('\n');
for (const sec of Object.values(all)) {
  for (const e of sec) assert(fileLines[e.line].trim().startsWith(e.name + ':'), `line of ${e.name}`);
}
// `dependencies` must not swallow `dev_dependencies` entries (and vice versa)
assert(!all.dependencies.some((e) => e.name === 'mocktail'));
assert.deepStrictEqual(p.listPackageEntries('name: a\n'),
  { dependencies: [], dev_dependencies: [], dependency_overrides: [] });

// remove a regular dependency, including its nested lines
let d = p.removeDependencies(full, 'dependencies', ['bar']);
assert.deepStrictEqual(p.listPackageEntries(d).dependencies.map((e) => e.name), ['flutter', 'foo', 'baz']);
assert(!d.includes('packages/bar\n\ndev_dependencies') && d.includes('ref: main')); // override of bar untouched
d = p.removeDependencies(d, 'dev_dependencies', ['mocktail']);
assert.deepStrictEqual(p.listPackageEntries(d).dev_dependencies.map((e) => e.name), ['flutter_test']);
assert.throws(() => p.removeDependencies(full, 'dependency_overrides', ['foo']));
console.log('CodeLens helpers OK');
