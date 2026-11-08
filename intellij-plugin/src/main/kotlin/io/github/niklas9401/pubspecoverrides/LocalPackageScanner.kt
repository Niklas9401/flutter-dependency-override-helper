package com.niklasweiblen.flutterpubspecoverridehelper

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A package found in one of the configured folders. */
data class LocalPackage(val name: String, val dir: File)

/** Finds local Dart/Flutter packages (directories with a pubspec.yaml) below the configured folders. */
object LocalPackageScanner {

    /** Directory names that are never scanned: platform folders and source/test folders never contain packages. */
    private val IGNORED_DIRS = setOf("node_modules", "build", "ios", "android", "macos", "linux", "windows", "web", "lib", "test")

    /** How long a scan result is reused by the inline-action provider, which runs after every edit. */
    private val TTL_MS = TimeUnit.SECONDS.toMillis(15)

    private data class CacheEntry(val time: Long, val packages: List<LocalPackage>)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Drops all cached scan results (call after the settings changed). */
    fun clearCache() = cache.clear()

    /**
     * Turns a folder from the settings into an absolute directory.
     * Supports absolute paths, `${workspaceFolder}` / `$PROJECT_DIR$`, `~/…` and paths relative to the project.
     */
    fun resolveFolder(raw: String, projectBase: String?): File? {
        if (raw.isBlank()) return null
        val base = projectBase ?: ""
        var v = raw.trim().replace("\${workspaceFolder}", base).replace("\$PROJECT_DIR\$", base)
        if (v == "~" || v.startsWith("~/") || v.startsWith("~\\")) {
            v = System.getProperty("user.home") + v.substring(1)
        }
        val f = File(v)
        return if (f.isAbsolute) f else File(base, v)
    }

    /**
     * Recursively searches [root] for packages. Hidden and ignored directories are skipped.
     * @param maxDepth how many levels below [root] are searched (0 = only [root] itself).
     */
    fun findPackages(root: File, maxDepth: Int): List<LocalPackage> {
        val result = mutableListOf<LocalPackage>()
        fun walk(dir: File, depth: Int) {
            val pubspec = File(dir, "pubspec.yaml")
            if (pubspec.isFile) {
                val name = PubspecEditor.packageName(pubspec.readText())
                if (name != null) result.add(LocalPackage(name, dir))
            }
            if (depth >= maxDepth) return
            val children = dir.listFiles() ?: return // unreadable directory → skip
            for (child in children) {
                if (!child.isDirectory || child.name.startsWith(".") || child.name in IGNORED_DIRS) continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return result.sortedBy { it.name }
    }

    /**
     * Scans all [folders]. Missing folders are skipped silently; if a package name occurs in several
     * folders the first folder in the list wins.
     * @return unique packages sorted by name.
     */
    fun scan(folders: List<String>, projectBase: String?, depth: Int): List<LocalPackage> {
        val unique = LinkedHashMap<String, LocalPackage>()
        for (raw in folders) {
            val dir = resolveFolder(raw, projectBase)?.takeIf { it.isDirectory } ?: continue
            for (p in findPackages(dir, depth)) unique.putIfAbsent(p.name, p)
        }
        return unique.values.sortedBy { it.name }
    }

    /** Like [scan], but reuses a recent result unless [fresh] is set. */
    fun scanCached(folders: List<String>, projectBase: String?, depth: Int, fresh: Boolean): List<LocalPackage> {
        val key = "$projectBase|$folders|$depth"
        val hit = cache[key]
        if (!fresh && hit != null && System.currentTimeMillis() - hit.time < TTL_MS) return hit.packages
        val packages = scan(folders, projectBase, depth)
        cache[key] = CacheEntry(System.currentTimeMillis(), packages)
        return packages
    }
}
