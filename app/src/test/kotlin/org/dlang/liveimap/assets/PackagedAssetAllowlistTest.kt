package org.dlang.liveimap.assets

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PackagedAssetAllowlistTest {
    @Test
    fun packagedNamesAreTheAllowlist() {
        val tmp = Files.createTempDirectory("packaged-assets")
        try {
            val root = tmp.toFile()
            assertFalse(root.absolutePath.contains("src${File.separator}main${File.separator}assets"))
            listOf(
                "help/getting-started.txt",
                "help/reporting-a-bug.txt",
                "licenses/NOTICES.txt",
                "settings-tree.json",
                "settings-tree.md",
                "scratch.md",
                "x.swp",
            ).forEach { rel ->
                val file = File(root, rel)
                file.parentFile?.mkdirs()
                file.writeText("x")
            }
            val (code, out) = runScript("select", root.absolutePath)
            assertEquals(0, code)
            assertEquals(
                listOf(
                    "help/getting-started.txt",
                    "help/reporting-a-bug.txt",
                    "licenses/NOTICES.txt",
                ),
                out.lines().filter { it.isNotEmpty() },
            )
            assertFalse(out.contains("scratch.md"))
            assertFalse(out.contains("x.swp"))
            assertFalse(out.contains("settings-tree"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun todayDebugApkList() {
        val tmp = Files.createTempDirectory("packaged-assets-apk")
        try {
            val apk = File(tmp.toFile(), "today.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                fun put(name: String, dir: Boolean = false) {
                    val entry = ZipEntry(if (dir) name.trimEnd('/') + "/" else name)
                    zip.putNextEntry(entry)
                    if (!dir) zip.write("x".toByteArray())
                    zip.closeEntry()
                }
                put("assets/help/", dir = true)
                put("assets/help/getting-started.txt")
                put("assets/help/reporting-a-bug.txt")
                put("assets/licenses/NOTICES.txt")
                put("assets/settings-tree.md")
                put("res/values/strings.xml")
            }
            val (code, out) = runScript("check-apk", "app", apk.absolutePath)
            assertEquals(1, code)
            assertEquals(listOf("extra: settings-tree.md"), lines(out, "extra:"))
            assertEquals(listOf("missing:"), lines(out, "missing:"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun androidTestApkHasNoAssets() {
        val tmp = Files.createTempDirectory("packaged-assets-androidtest")
        try {
            val empty = File(tmp.toFile(), "empty.apk")
            ZipOutputStream(empty.outputStream()).use { }
            val (emptyCode, emptyOut) = runScript("check-apk", "instrumentation", empty.absolutePath)
            assertEquals(0, emptyCode)
            assertEquals("", emptyOut)

            val withAsset = File(tmp.toFile(), "with.apk")
            ZipOutputStream(withAsset.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/scratch.md"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
            val (code, out) = runScript("check-apk", "instrumentation", withAsset.absolutePath)
            assertEquals(1, code)
            assertEquals(listOf("extra: scratch.md"), lines(out, "extra:"))
            assertEquals(listOf("missing:"), lines(out, "missing:"))
            assertFalse(out.contains("getting-started.txt"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun lines(out: String, prefix: String): List<String> =
        out.lines().filter { it.startsWith(prefix) }

    private fun runScript(vararg args: String, stdin: String? = null): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("python3", scriptFile().absolutePath) + args)
        pb.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
        pb.redirectErrorStream(true)
        val proc = pb.start()
        if (stdin != null) {
            proc.outputStream.bufferedWriter().use { it.write(stdin) }
        } else {
            proc.outputStream.close()
        }
        val text = proc.inputStream.bufferedReader().readText().trim()
        return proc.waitFor() to text
    }

    private fun scriptFile(): File {
        val candidates = listOf(File("packaged-assets.py"), File("app/packaged-assets.py"))
        return candidates.firstOrNull { it.isFile }
            ?: error("packaged-assets.py not found from ${File(".").absolutePath}")
    }
}
