package io.github.niklas9401.pubspecoverrides

/**
 * Pure text processing for pubspec.yaml files (Kotlin port of `pubspec.js` from the VS Code extension).
 *
 * This object intentionally has NO dependency on the IntelliJ Platform (and no YAML library): it edits
 * sections line by line so that comments, ordering and formatting in the rest of the file stay untouched.
 * Because it is dependency-free it can be unit-tested with plain JUnit.
 */
object PubspecEditor {

    /** Name of the top-level pubspec key that holds the overrides. */
    const val SECTION = "dependency_overrides"

    /** Sections that contain regular (removable) dependencies. */
    val DEPENDENCY_SECTIONS = listOf("dependencies", "dev_dependencies")

    /** Every section this object can read entries from. */
    val ALL_SECTIONS = DEPENDENCY_SECTIONS + SECTION

    /** How a package is declared in a section. */
    enum class EntryType {
        LOCAL, GIT, SDK, HOSTED;

        /** Lower-case label used in messages and for comparing with the requested override type. */
        val label: String get() = name.lowercase()
    }

    /** A rendered override: package name plus its complete YAML lines (indentation included). */
    data class OverrideEntry(val name: String, val lines: List<String>)

    /**
     * Location of a top-level section.
     * @property start  Index of the header line (e.g. `dependency_overrides:`).
     * @property end    Exclusive index after the last line belonging to the section.
     * @property inline Text after the colon on the header line, comments removed (e.g. `{}`); empty for block style.
     */
    data class Section(val start: Int, val end: Int, val inline: String)

    /**
     * Location of one package entry inside a section.
     * @property start Index of the entry's first line (equals the 0-based editor line).
     * @property end   Exclusive index after the entry's last line.
     */
    data class EntryRange(val name: String, val start: Int, var end: Int)

    /** A package found in a section, with its 0-based [line] and declaration [type]. */
    data class PackageEntry(val name: String, val line: Int, val type: EntryType?)

    /** Matches an indented `key:` line; group 2 is the key. */
    private val KEY_RE = Regex("""^(\s+)([A-Za-z0-9_]+)\s*:""")
    private val CHILD_KEY_RE = Regex("""^\s*([A-Za-z_]+)\s*:""")
    private val COMMENT_RE = Regex("#.*$")
    private val EMPTY_FLOW_MAP_RE = Regex("""^\{\s*\}$""")

    // Patterns deciding whether a scalar needs quoting (see yamlStr).
    private val QUOTE_LEADING = Regex("""^[\s\-?:,\[\]{}#&*!|>'"%@`]""")
    private val QUOTE_INNER = Regex(""":\s|\s#|\s$""")
    private val NUMERIC = Regex("""^[\d.]+$""")
    private val KEYWORD = Regex("^(true|false|null|yes|no|~)$", RegexOption.IGNORE_CASE)

    /** Text split into lines plus its line-ending style, so the file can be re-joined unchanged. */
    private class Parsed(val eol: String, val lines: MutableList<String>)

    // ------------------------------------------------------------------ rendering

    /**
     * Quotes a scalar for YAML only when necessary (leading indicator characters, `: ` or ` #` inside,
     * numbers such as `1.0`, booleans/null, empty string). Everything else stays plain for readability.
     */
    fun yamlStr(value: String): String {
        val needsQuote = QUOTE_LEADING.containsMatchIn(value) ||
            QUOTE_INNER.containsMatchIn(value) ||
            NUMERIC.containsMatchIn(value) ||
            KEYWORD.containsMatchIn(value) ||
            value.isEmpty()
        return if (needsQuote) jsonQuote(value) else value
    }

    /** Double-quoted string with the escapes YAML shares with JSON. */
    private fun jsonQuote(v: String): String {
        val sb = StringBuilder("\"")
        for (c in v) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * Renders a local path override, e.g. `foo:` / `path: ../foo`.
     * @param relPath Path to the package relative to the pubspec, with `/` separators.
     */
    fun renderLocal(name: String, relPath: String): OverrideEntry =
        OverrideEntry(name, listOf("  $name:", "    path: ${yamlStr(relPath)}"))

    /**
     * Renders a git override. [ref] and [subPath] are omitted when empty.
     * @param ref     Branch, tag or commit hash; empty = default branch of the repo.
     * @param subPath Sub-folder of the package inside a monorepo.
     */
    fun renderGit(name: String, url: String, ref: String?, subPath: String?): OverrideEntry {
        val lines = mutableListOf("  $name:", "    git:", "      url: ${yamlStr(url)}")
        if (!ref.isNullOrEmpty()) lines.add("      ref: ${yamlStr(ref)}")
        if (!subPath.isNullOrEmpty()) lines.add("      path: ${yamlStr(subPath)}")
        return OverrideEntry(name, lines)
    }

    // ------------------------------------------------------------------ line helpers

    private fun parse(text: String): Parsed {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        return Parsed(eol, text.split(Regex("\r?\n")).toMutableList())
    }

    /** Re-joins the lines; the result ends with exactly one line break. */
    private fun join(p: Parsed): String = p.lines.joinToString(p.eol).trimEnd() + p.eol

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    /** True for empty/whitespace-only lines and `#` comment lines. */
    private fun isBlankOrComment(line: String): Boolean {
        val t = line.trim()
        return t.isEmpty() || t.startsWith("#")
    }

    // ------------------------------------------------------------------ section / entry parsing

    /**
     * Locates a top-level section such as `dependencies` or `dependency_overrides`.
     *
     * The section ends before the next top-level key. Trailing blank lines and top-level comments are
     * deliberately NOT part of it, so new entries are inserted right after the last indented line.
     *
     * @return the section, or null if the file has none.
     */
    private fun findSectionNamed(lines: List<String>, name: String): Section? {
        // The anchor (^) guarantees `dependencies` never matches `dev_dependencies`.
        val re = Regex("^$name\\s*:(.*)$")
        val start = lines.indexOfFirst { re.containsMatchIn(it) }
        if (start == -1) return null

        val rest = re.find(lines[start])!!.groupValues[1].replace(COMMENT_RE, "").trim()

        var end = start + 1
        for (i in start + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank()) continue
            if (indentOf(l) == 0) {
                if (l.trim().startsWith("#")) continue // top-level comment: neither ends nor extends the section
                break // next top-level key → section is over
            }
            end = i + 1 // indented line → belongs to the section
        }
        return Section(start, end, rest)
    }

    private fun findSection(lines: List<String>): Section? = findSectionNamed(lines, SECTION)

    /**
     * Finds the package entries inside a section. The entry indentation is derived from the shallowest
     * non-comment line, so any consistent indentation works; deeper lines belong to the preceding entry.
     */
    private fun parseEntries(lines: List<String>, section: Section): List<EntryRange> {
        val body = ((section.start + 1) until section.end).filter { !isBlankOrComment(lines[it]) }
        if (body.isEmpty()) return emptyList()

        val entryIndent = body.minOf { indentOf(lines[it]) }
        val entries = mutableListOf<EntryRange>()
        for (i in body) {
            if (indentOf(lines[i]) != entryIndent) continue
            val m = KEY_RE.find(lines[i]) ?: continue
            entries.add(EntryRange(m.groupValues[2], i, section.end))
        }
        // Each entry ends where the next one starts (the last one at the section end).
        for (idx in 0 until entries.size - 1) entries[idx].end = entries[idx + 1].start
        // Don't swallow trailing blank/comment lines when an entry is removed later.
        for (e in entries) {
            while (e.end > e.start + 1 && isBlankOrComment(lines[e.end - 1])) e.end--
        }
        return entries
    }

    /**
     * Determines how an entry is declared. Only the first level below the package name is inspected, so a
     * nested `path:` inside a `git:` block is correctly reported as GIT. Inline forms such as
     * `foo: ^1.0.0` or `foo: {path: ../foo}` are recognised too.
     */
    private fun entryType(lines: List<String>, entry: EntryRange): EntryType? {
        val header = lines[entry.start]
        val rest = header.substring(header.indexOf(':') + 1).replace(COMMENT_RE, "").trim()
        if (rest.isNotEmpty()) {
            return when {
                Regex("""\bgit\s*:""").containsMatchIn(rest) -> EntryType.GIT
                Regex("""\bpath\s*:""").containsMatchIn(rest) -> EntryType.LOCAL
                Regex("""\bsdk\s*:""").containsMatchIn(rest) -> EntryType.SDK
                else -> EntryType.HOSTED // plain version constraint
            }
        }

        val children = ((entry.start + 1) until entry.end).filter { !isBlankOrComment(lines[it]) }
        if (children.isEmpty()) return null
        val childIndent = children.minOf { indentOf(lines[it]) }
        for (i in children) {
            if (indentOf(lines[i]) != childIndent) continue
            when (CHILD_KEY_RE.find(lines[i])?.groupValues?.get(1)) {
                "git" -> return EntryType.GIT
                "path" -> return EntryType.LOCAL
                "sdk" -> return EntryType.SDK
                "hosted", "version" -> return EntryType.HOSTED
            }
        }
        return null
    }

    // ------------------------------------------------------------------ queries

    /** Names of all packages currently overridden, in file order. */
    fun listOverrides(text: String): List<String> {
        val lines = parse(text).lines
        val section = findSection(lines) ?: return emptyList()
        return parseEntries(lines, section).map { it.name }
    }

    /**
     * Entries of `dependencies`, `dev_dependencies` and `dependency_overrides` with line numbers and types.
     * Sections that don't exist map to an empty list.
     */
    fun listPackageEntries(text: String): Map<String, List<PackageEntry>> {
        val lines = parse(text).lines
        return ALL_SECTIONS.associateWith { name ->
            val section = findSectionNamed(lines, name)
            if (section == null) emptyList()
            else parseEntries(lines, section).map { PackageEntry(it.name, it.start, entryType(lines, it)) }
        }
    }

    /** How [packageName] is currently overridden, or null if it isn't (or the form is not recognised). */
    fun getOverrideType(text: String, packageName: String): EntryType? {
        val lines = parse(text).lines
        val section = findSection(lines) ?: return null
        val entry = parseEntries(lines, section).find { it.name == packageName } ?: return null
        return entryType(lines, entry)
    }

    /** The package's own name from the top-level `name:` key. */
    fun packageName(text: String): String? =
        Regex("^name\\s*:\\s*([A-Za-z0-9_]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    /** Heuristic: does this pubspec depend on the Flutter SDK? (`flutter pub get` vs `dart pub get`) */
    fun usesFlutter(text: String): Boolean = Regex("""sdk\s*:\s*flutter""").containsMatchIn(text)

    // ------------------------------------------------------------------ modification

    /**
     * Normalises an empty inline map (`dependency_overrides: {}`) to block style so entries can be added
     * below it. Mutates [lines].
     * @throws IllegalStateException for a non-empty inline map.
     */
    private fun normalizeInline(lines: MutableList<String>, section: Section, name: String) {
        if (section.inline.isEmpty()) return // already block style
        if (EMPTY_FLOW_MAP_RE.matches(section.inline)) {
            lines[section.start] = "$name:"
        } else {
            throw IllegalStateException("$name is written as an inline map – please convert it to block style once.")
        }
    }

    /** Removes the given packages from a section (header stays). Mutates [lines]; true if anything was removed. */
    private fun removeEntries(lines: MutableList<String>, names: Collection<String>, sectionName: String): Boolean {
        val section = findSectionNamed(lines, sectionName) ?: return false
        normalizeInline(lines, section, sectionName)

        val entries = parseEntries(lines, section).filter { it.name in names }
        // Delete bottom-up so the indices of earlier entries stay valid.
        entries.sortedByDescending { it.start }.forEach { e -> lines.subList(e.start, e.end).clear() }
        return entries.isNotEmpty()
    }

    /**
     * Adds overrides, replacing existing entries of the same name. Creates the `dependency_overrides:`
     * section at the end of the file if needed. The result ends with exactly one line break.
     */
    fun applyOverrides(text: String, newEntries: List<OverrideEntry>): String {
        val parsed = parse(text)
        val lines = parsed.lines

        // Drop old versions of the same packages first, so "add" doubles as "replace".
        if (findSection(lines) != null) removeEntries(lines, newEntries.map { it.name }, SECTION)

        // Re-locate the section: removal may have shifted the indices.
        val section = findSection(lines)
        val toInsert = newEntries.flatMap { it.lines }

        if (section == null) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            lines.add("")
            lines.add("$SECTION:")
            lines.addAll(toInsert)
            lines.add("")
        } else {
            normalizeInline(lines, section, SECTION)
            lines.addAll(section.end, toInsert) // right after the last existing entry
        }
        return join(parsed)
    }

    /** Removes packages from `dependency_overrides`; an emptied section is removed completely. */
    fun removeOverrides(text: String, names: Collection<String>): String {
        val parsed = parse(text)
        val lines = parsed.lines
        removeEntries(lines, names, SECTION)

        val section = findSection(lines)
        if (section != null && parseEntries(lines, section).isEmpty()) {
            var from = section.start
            if (from > 0 && lines[from - 1].isBlank()) from-- // also drop the separating blank line
            lines.subList(from, section.end).clear()
        }
        return join(parsed)
    }

    /**
     * Removes packages from `dependencies` or `dev_dependencies`. The section header is always kept.
     * @throws IllegalArgumentException if [sectionName] is not a dependency section.
     */
    fun removeDependencies(text: String, sectionName: String, names: Collection<String>): String {
        require(sectionName in DEPENDENCY_SECTIONS) { "Unsupported section: $sectionName" }
        val parsed = parse(text)
        removeEntries(parsed.lines, names, sectionName)
        return join(parsed)
    }
}
