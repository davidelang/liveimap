package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JNI FindClass names in src/main/cpp must have a -keep line. holdGlobal passes its name to FindClass. */
class ProguardKeepSyncTest {
    private val rules = text("proguard-rules.pro")
    private val gradle = text("build.gradle.kts")

    @Test
    fun findClassTargetsAreKeptAndReleaseShrinks() {
        val classes = findClassNames()
        assertTrue("no org/dlang FindClass names", classes.isNotEmpty())
        for (name in classes) {
            assertTrue(name, kept(name))
        }
        assertTrue(rules.contains("-keep class com.davidlang.liveimap.BuildConfig"))
        val types = block(gradle, "buildTypes")
        val release = block(types, "release {")
        assertTrue(release.contains("isMinifyEnabled = true"))
        assertTrue(release.contains("isShrinkResources = true"))
        val debug = block(types, "getByName(\"debug\")")
        assertFalse(debug.contains("isMinifyEnabled = true"))
        assertFalse(usageSectionFails(true, listOf("    static void <clinit>()")))
        assertTrue(usageSectionFails(true, emptyList()))
        assertTrue(usageSectionFails(true, listOf("    static void <clinit>()", "    void removed()")))
        assertTrue(usageSectionFails(true, listOf("    int field")))
        assertFalse(usageSectionFails(false, listOf("    void removed()")))
        assertTrue(gradle.contains("it.trim() != \"static void <clinit>()\""))
        assertTrue(gradle.contains("members.isEmpty() || other.isNotEmpty()"))
    }

    internal fun usageSectionFails(watched: Boolean, memberLines: List<String>): Boolean {
        if (!watched) return false
        return memberLines.isEmpty() || memberLines.any { it.trim() != "static void <clinit>()" }
    }

    private fun findClassNames(): Set<String> {
        val dir = listOf(File("src/main/cpp"), File("app/src/main/cpp")).first { it.isDirectory }
        val literal = Regex(""""(org/dlang/[^"]+)"""")
        return dir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("c", "cc", "cpp", "h", "hpp") }
            .flatMap { literal.findAll(it.readText()).map { m -> m.groupValues[1].replace('/', '.') } }
            .toSet()
    }

    private fun kept(className: String): Boolean {
        val spec = Regex("""^-keep\S*(?:,\S+)*\s+(?:class|enum|interface)\s+(\S+)""")
        return rules.lineSequence().any { raw ->
            val match = spec.find(raw.trim()) ?: return@any false
            proguardMatch(match.groupValues[1], className)
        }
    }

    private fun proguardMatch(pattern: String, className: String): Boolean {
        val regex = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            if (pattern.startsWith("**", i)) {
                regex.append(".*")
                i += 2
            } else if (pattern[i] == '*') {
                regex.append("[^.]*")
                i++
            } else if (pattern[i] == '?') {
                regex.append("[^.]")
                i++
            } else {
                regex.append(Regex.escape(pattern[i].toString()))
                i++
            }
        }
        regex.append('$')
        return Regex(regex.toString()).matches(className)
    }

    private fun block(src: String, token: String): String {
        val start = src.indexOf(token)
        assertTrue(token, start >= 0)
        val open = src.indexOf('{', start)
        assertTrue(token, open >= 0)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open + 1, i)
                }
            }
        }
        error("unclosed $token")
    }

    private fun text(path: String) =
        listOf(path, "app/$path").map(::File).first { it.exists() }.readText()
}
