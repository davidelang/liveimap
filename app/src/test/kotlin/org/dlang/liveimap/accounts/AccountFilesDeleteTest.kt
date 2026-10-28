package org.dlang.liveimap.accounts

import java.io.File
import java.nio.file.Files
import org.dlang.liveimap.ui.compose.unsentAccountDir
import org.dlang.liveimap.ui.reader.attachmentPrefixFor
import org.dlang.liveimap.ui.reader.imageTrustFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFilesDeleteTest {
    @Test
    fun uuidFilesGoAndTrafficStays() {
        val files = Files.createTempDirectory("acct-files").toFile()
        val cache = Files.createTempDirectory("acct-cache").toFile()
        val id = "11111111-1111-1111-1111-111111111111"
        val other = "22222222-2222-2222-2222-222222222222"
        File(unsentAccountDir(files, id), "draft").apply { parentFile.mkdirs(); writeText("x") }
        val trust = imageTrustFile(files, id)!!
        trust.parentFile.mkdirs()
        trust.writeText("t")
        val trustTmp = File(trust.parentFile, trust.name + ".tmp")
        trustTmp.writeText("tmp")
        val part = File(cache, attachmentPrefixFor(id) + "a.part")
        part.writeText("p")
        val body = File(cache, attachmentPrefixFor(id) + "a")
        body.writeText("a")
        val traffic = File(cache, "traffic/$id").apply { mkdirs() }
        File(traffic, "x").writeText("t")
        val trafficLog = File(cache, "traffic/$id.log")
        trafficLog.writeText("log")
        val otherUnsent = File(unsentAccountDir(files, other), "keep").apply { parentFile.mkdirs(); writeText("k") }
        val otherTrust = imageTrustFile(files, other)!!
        otherTrust.parentFile.mkdirs()
        otherTrust.writeText("o")
        val otherPart = File(cache, attachmentPrefixFor(other) + "b")
        otherPart.writeText("b")
        deleteNamedAccountFiles(files, cache, id)
        assertFalse(unsentAccountDir(files, id).exists())
        assertFalse(trust.exists())
        assertFalse(trustTmp.exists())
        assertFalse(part.exists())
        assertFalse(body.exists())
        assertTrue(traffic.isDirectory)
        assertTrue(File(traffic, "x").exists())
        assertTrue(trafficLog.exists())
        assertTrue(otherUnsent.exists())
        assertTrue(otherTrust.exists())
        assertTrue(otherPart.exists())
    }

    @Test
    fun unsafeIdDeletesOnlyHashedUnsent() {
        val files = Files.createTempDirectory("acct-files").toFile()
        val cache = Files.createTempDirectory("acct-cache").toFile()
        val id = "a/b"
        val dir = unsentAccountDir(files, id)
        File(dir, "x").apply { parentFile.mkdirs(); writeText("x") }
        val digest = dir.name
        assertTrue(digest.startsWith(".id-"))
        val decoy = File(cache, "not-this-account")
        decoy.writeText("no")
        deleteNamedAccountFiles(files, cache, id)
        assertFalse(dir.exists())
        assertTrue(decoy.exists())
        assertFalse(File(files, "unsent/$id").exists())
    }
}
