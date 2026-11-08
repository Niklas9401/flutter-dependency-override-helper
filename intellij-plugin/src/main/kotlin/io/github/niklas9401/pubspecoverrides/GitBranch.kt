package com.niklasweiblen.flutterpubspecoverridehelper

import java.io.File
import java.util.concurrent.TimeUnit

/** Reads the currently checked-out git branch of a directory. */
object GitBranch {

    /**
     * Runs `git rev-parse --abbrev-ref HEAD` in [dir]. Never throws.
     * @return the branch name, or "" if unavailable (no git, no repository, detached HEAD).
     */
    fun current(dir: File): String {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ""
            }
            if (process.exitValue() != 0) return ""
            val branch = process.inputStream.bufferedReader().readText().trim()
            // git prints the literal "HEAD" in detached-HEAD state, which is not a usable ref.
            if (branch == "HEAD") "" else branch
        } catch (e: Exception) {
            ""
        }
    }
}
