package com.niklasweiblen.flutterpubspecoverridehelper

import com.niklasweiblen.flutterpubspecoverridehelper.PubspecEditor.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Plain unit tests (no IDE needed) – the same scenarios as `test.js` of the VS Code extension. */
class PubspecEditorTest {

    private val base = """
        name: app
        dependencies:
          flutter:
            sdk: flutter
          foo: ^1.0.0
    """.trimIndent() + "\n"

    private val full = """
        name: app

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
    """.trimIndent() + "\n"

    @Test
    fun addCreatesSectionAndReplacesExistingEntries() {
        var t = PubspecEditor.applyOverrides(
            base,
            listOf(
                PubspecEditor.renderLocal("foo", "../foo"),
                PubspecEditor.renderGit("bar", "git@github.com:acme/bar.git", "feature/x", "packages/bar")
            )
        )
        assertEquals(listOf("foo", "bar"), PubspecEditor.listOverrides(t))

        t += "\nflutter:\n  uses-material-design: true\n"
        t = PubspecEditor.applyOverrides(t, listOf(PubspecEditor.renderGit("foo", "https://github.com/acme/foo.git", "1.0", null)))
        assertEquals(listOf("bar", "foo"), PubspecEditor.listOverrides(t))
        assertTrue(t.indexOf("flutter:\n  uses") > t.indexOf("ref: \"1.0\"")) // numeric-looking ref is quoted
    }

    @Test
    fun overrideTypeDetection() {
        val typed = """
            name: a
            dependency_overrides:
              loc:
                path: ../loc
              inline_loc: {path: ../x}
              hosted_pkg: ^2.0.0
              sdk_pkg:
                sdk: flutter
              nested:
                git:
                  url: u
                  path: sub
        """.trimIndent() + "\n"
        assertEquals(EntryType.LOCAL, PubspecEditor.getOverrideType(typed, "loc"))
        assertEquals(EntryType.LOCAL, PubspecEditor.getOverrideType(typed, "inline_loc"))
        assertEquals(EntryType.HOSTED, PubspecEditor.getOverrideType(typed, "hosted_pkg"))
        assertEquals(EntryType.SDK, PubspecEditor.getOverrideType(typed, "sdk_pkg"))
        assertEquals(EntryType.GIT, PubspecEditor.getOverrideType(typed, "nested")) // nested path: is not "local"
        assertNull(PubspecEditor.getOverrideType(typed, "missing"))
        assertNull(PubspecEditor.getOverrideType("name: a\n", "x"))
    }

    @Test
    fun removeEntriesAndEmptySection() {
        var t = PubspecEditor.applyOverrides(base, listOf(PubspecEditor.renderLocal("foo", "../foo"), PubspecEditor.renderLocal("bar", "../bar")))
        t += "\nflutter:\n  uses-material-design: true\n"
        t = PubspecEditor.removeOverrides(t, listOf("bar"))
        assertEquals(listOf("foo"), PubspecEditor.listOverrides(t))
        t = PubspecEditor.removeOverrides(t, listOf("foo"))
        assertFalse(t.contains("dependency_overrides"))
        assertTrue(t.contains("uses-material-design"))
    }

    @Test
    fun crlfIsPreservedAndEmptyInlineMapIsNormalised() {
        val c = PubspecEditor.applyOverrides("name: a\r\ndependency_overrides: {}\r\n", listOf(PubspecEditor.renderLocal("x", "../x")))
        assertEquals("name: a\r\ndependency_overrides:\r\n  x:\r\n    path: ../x\r\n", c)
    }

    @Test
    fun nonEmptyInlineMapIsRejected() {
        try {
            PubspecEditor.applyOverrides("name: a\ndependency_overrides: {foo: 1}\n", listOf(PubspecEditor.renderLocal("x", "../x")))
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // ok
        }
    }

    @Test
    fun listPackageEntriesCoversAllSections() {
        val all = PubspecEditor.listPackageEntries(full)
        assertEquals(
            listOf("flutter" to EntryType.SDK, "foo" to EntryType.HOSTED, "bar" to EntryType.GIT, "baz" to EntryType.LOCAL),
            all.getValue("dependencies").map { it.name to it.type }
        )
        assertEquals(
            listOf("flutter_test" to EntryType.SDK, "mocktail" to EntryType.HOSTED),
            all.getValue("dev_dependencies").map { it.name to it.type }
        )
        assertEquals(
            listOf("foo" to EntryType.LOCAL, "bar" to EntryType.GIT),
            all.getValue("dependency_overrides").map { it.name to it.type }
        )
        // "line" must be the 0-based editor line of the entry
        val lines = full.split("\n")
        all.values.flatten().forEach { e -> assertTrue("line of ${e.name}", lines[e.line].trim().startsWith("${e.name}:")) }

        val empty = PubspecEditor.listPackageEntries("name: a\n")
        assertTrue(empty.values.all { it.isEmpty() })
    }

    @Test
    fun removeDependenciesKeepsOverridesAndOtherSections() {
        var d = PubspecEditor.removeDependencies(full, "dependencies", listOf("bar"))
        assertEquals(listOf("flutter", "foo", "baz"), PubspecEditor.listPackageEntries(d).getValue("dependencies").map { it.name })
        assertTrue(d.contains("ref: main")) // the override of bar is untouched
        d = PubspecEditor.removeDependencies(d, "dev_dependencies", listOf("mocktail"))
        assertEquals(listOf("flutter_test"), PubspecEditor.listPackageEntries(d).getValue("dev_dependencies").map { it.name })
        try {
            PubspecEditor.removeDependencies(full, "dependency_overrides", listOf("foo"))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
