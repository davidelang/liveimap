package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F120: merging adds addresses, never removes any, and never silently overwrites name/nickname/note. */
class MergeIntoExistingTest {
    private val existing = mapOf(
        "name" to listOf(ExistingField(1, "Robert Smith")),
        "nickname" to listOf(ExistingField(2, "")),
        "email" to listOf(ExistingField(3, "bob@work.example"), ExistingField(4, "BOB@home.example")),
        "note" to listOf(ExistingField(5, "met at LISA")),
    )
    private val copied = CopyContact(
        displayName = "Bob Smith",
        nickname = "bob",
        emails = listOf("bob@home.example", "bob@new.example"),
        note = "pine import",
    )

    @Test
    fun keepsOtherAddressesAndFillsOnlyEmptyFields() {
        val plan = mergeContactPlan(copied, existing, overwrite = false)
        assertTrue(plan.changes.none { it.field == "email" && it.rowId != null })
        assertEquals(listOf(MergeChange("email", null, "bob@new.example")), plan.changes.filter { it.field == "email" })
        assertTrue(MergeChange("nickname", 2, "bob") in plan.changes)
        assertTrue(plan.changes.none { it.field == "name" || it.field == "note" })
        assertEquals(listOf("name", "note"), plan.conflicts)
    }

    @Test
    fun overwritesOnlyAfterConfirmation() {
        val plan = mergeContactPlan(copied, existing, overwrite = true)
        assertTrue(MergeChange("name", 1, "Bob Smith") in plan.changes)
        assertTrue(MergeChange("note", 5, "pine import") in plan.changes)
        assertTrue(plan.changes.none { it.field == "email" && it.rowId != null })
    }

    @Test
    fun missingFieldsAreInserted() {
        val plan = mergeContactPlan(copied, emptyMap(), overwrite = false)
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(setOf("name", "nickname", "email", "note"), plan.changes.map { it.field }.toSet())
    }

    @Test
    fun alpineMergeKeepsAddressesAndExistingValues() {
        val old = CopyContact(nickname = "bob", displayName = "Robert Smith", emails = listOf("bob@work.example", "bob@home.example"), comments = "LISA")
        val new = CopyContact(nickname = "bob2", displayName = "Bob Smith", emails = listOf("BOB@home.example", "bob@new.example"), fcc = "sent-bob")
        val (merged, kept) = fillOnlyMerge(old, new)
        assertEquals(listOf("bob@work.example", "bob@home.example", "bob@new.example"), merged.emails)
        assertEquals("Robert Smith", merged.displayName)
        assertEquals("bob", merged.nickname)
        assertEquals("LISA", merged.comments)
        assertEquals("sent-bob", merged.fcc)
        assertTrue(kept)
    }
}
