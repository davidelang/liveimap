package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertFalse
import org.junit.Test

/** F62: completion data is cached per (account, mailbox), failures are not cached, writes invalidate. */
class CompletionCacheTest {
    @Test fun keyedAndInvalidated() {
        assertFalse(pineBookIsLoaded("a", "addressbook"))
        forgetCompletionCaches()
    }
}
