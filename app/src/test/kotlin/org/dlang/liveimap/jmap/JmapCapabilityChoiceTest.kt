package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapCapabilityChoiceTest {
    @Test
    fun emptySetSwitchOff() {
        val choice = jmapCapabilityChoice(emptySet(), false, "liveimap")
        assertFalse(choice.mail)
        assertFalse(choice.submission)
        assertEquals(JmapCapabilityFolders.Imap, choice.folders)
        assertEquals(JmapCapabilitySend.Smtp, choice.send)
    }

    @Test
    fun emptySetSwitchOnFails() {
        val choice = jmapCapabilityChoice(emptySet(), true, "liveimap")
        assertEquals(JmapCapabilitySend.Fail("jmap submission is not offered"), choice.send)
    }

    @Test
    fun mailOnlySwitchOff() {
        val choice = jmapCapabilityChoice(setOf(JMAP_MAIL), false, "liveimap")
        assertTrue(choice.mail)
        assertEquals(JmapCapabilityFolders.Mail, choice.folders)
        assertEquals(JmapCapabilitySend.Smtp, choice.send)
    }

    @Test
    fun mailOnlySwitchOnFails() {
        val choice = jmapCapabilityChoice(setOf(JMAP_MAIL), true, "liveimap")
        assertEquals(JmapCapabilitySend.Fail("jmap submission is not offered"), choice.send)
    }

    @Test
    fun submissionOnlySwitchOnFails() {
        val choice = jmapCapabilityChoice(setOf(JMAP_SUBMISSION), true, "liveimap")
        assertFalse(choice.mail)
        assertEquals(JmapCapabilityFolders.Imap, choice.folders)
        assertEquals(JmapCapabilitySend.Fail("jmap submission is not offered"), choice.send)
    }

    @Test
    fun mailAndSubmissionSwitchOffStaysSmtp() {
        val choice = jmapCapabilityChoice(setOf(JMAP_MAIL, JMAP_SUBMISSION), false, "liveimap")
        assertEquals(JmapCapabilityFolders.Mail, choice.folders)
        assertEquals(JmapCapabilitySend.Smtp, choice.send)
    }

    @Test
    fun mailAndSubmissionSwitchOn() {
        val choice = jmapCapabilityChoice(setOf(JMAP_MAIL, JMAP_SUBMISSION), true, "liveimap")
        assertEquals(JmapCapabilitySend.Submission, choice.send)
    }

    @Test
    fun mailAndSubmissionSwitchOnBlankUsernameKeepsImapFolders() {
        for (username in listOf("", " ", "\t", "\n")) {
            val choice = jmapCapabilityChoice(setOf(JMAP_MAIL, JMAP_SUBMISSION), true, username)
            assertEquals(JmapCapabilityFolders.Imap, choice.folders)
            assertEquals(JmapCapabilitySend.Submission, choice.send)
        }
    }

    @Test
    fun coreAloneIsNeitherCapability() {
        val choice = jmapCapabilityChoice(setOf("urn:ietf:params:jmap:core"), true, "liveimap")
        assertFalse(choice.mail)
        assertFalse(choice.submission)
        assertEquals(JmapCapabilitySend.Fail("jmap submission is not offered"), choice.send)
    }

    @Test
    fun submissionxWithMailIsNotSubmission() {
        val choice = jmapCapabilityChoice(
            setOf(JMAP_MAIL, "urn:ietf:params:jmap:submissionx"),
            true,
            "liveimap",
        )
        assertFalse(choice.submission)
        assertEquals(JmapCapabilitySend.Fail("jmap submission is not offered"), choice.send)
    }
}
