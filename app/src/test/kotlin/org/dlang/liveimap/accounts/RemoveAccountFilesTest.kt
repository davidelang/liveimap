package org.dlang.liveimap.accounts

import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoveAccountFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun namedCertAndTrustGoOtherAccountStays() {
        val root = tmp.root
        val trust = File(root, "image-trust")
        trust.mkdirs()
        val acct = File(trust, "acct.txt").apply { writeText("acct") }
        val other = File(trust, "other.txt").apply { writeText("other") }
        val cert = File(root, "cert.p12").apply { writeBytes(byteArrayOf(1)) }
        val sealed = File(root, "cert.pw.sealed").apply { writeBytes(byteArrayOf(2)) }
        val stored = AccountSettings(
            clientCert = cert.absolutePath,
            clientCertAlias = "system-alias",
        ).encode()
        removeAccountFiles(root, "acct", stored)
        assertFalse(acct.exists())
        assertFalse(cert.exists())
        assertFalse(sealed.exists())
        assertTrue(other.exists())
    }

    @Test
    fun unreadableSettingsDeletesTrustNotAnUnnamedCert() {
        val root = tmp.root
        val trust = File(root, "image-trust")
        trust.mkdirs()
        val acct = File(trust, "acct.txt").apply { writeText("acct") }
        val cert = File(root, "cert.p12").apply { writeBytes(byteArrayOf(1)) }
        removeAccountFiles(root, "acct", "not-settings")
        assertFalse(acct.exists())
        assertTrue(cert.exists())
    }

    @Test
    fun pathAccountIdLeavesTrustAndOutsideFile() {
        val root = tmp.root
        val trust = File(root, "image-trust")
        trust.mkdirs()
        val acct = File(trust, "acct.txt").apply { writeText("acct") }
        val secret = File(root, "secret.txt").apply { writeText("secret") }
        removeAccountFiles(root, "a/b", null)
        assertTrue(acct.exists())
        assertTrue(secret.exists())
    }

    @Test
    fun nullSettingsDeletesTrustFile() {
        val root = tmp.root
        val trust = File(root, "image-trust")
        trust.mkdirs()
        val acct = File(trust, "acct.txt").apply { writeText("acct") }
        removeAccountFiles(root, "acct", null)
        assertFalse(acct.exists())
    }
}
