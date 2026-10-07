package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinercImportTest {
    @Test
    fun parseQuotedNameUnsetAndLastWins() {
        assertEquals("David Lang", parsePinerc("personal-name=\"David Lang\"\n")["personal-name"])
        val unset = parsePinerc("personal-name=\n")
        assertTrue(unset.containsKey("personal-name"))
        assertEquals(null, unset["personal-name"])
        assertEquals("b", parsePinerc("user-id=a\nuser-id=b\n")["user-id"])
        assertEquals(
            "expunge-without-confirm,expunge-only-manually",
            parsePinerc("# c\n\nfeature-list=expunge-without-confirm,\n expunge-only-manually\n")["feature-list"],
        )
    }

    @Test
    fun parseQuotesEscapesCaseAndOneSpace() {
        assertEquals("a\"b\\c", parsePinerc("personal-name=\"a\\\"b\\\\c\"\n")["personal-name"])
        assertEquals("", parsePinerc("personal-name=\"\"\n")["personal-name"])
        assertEquals("Ada", parsePinerc("Personal-Name=Ada\n")["personal-name"])
        assertEquals("Ada", parsePinerc("personal-name= Ada \n")["personal-name"])
        assertEquals(" Ada", parsePinerc("personal-name=  Ada \n")["personal-name"])
        assertEquals("ab", parsePinerc("user-id=a\n\tb\n")["user-id"])
    }

    @Test
    fun previewAppliesUsableFieldsAndHidesPassword() {
        val text = """
            inbox-path={imap.example.com:143/user=ada}INBOX
            smtp-server=smtp.example.com:2525
            user-domain=lang.hm
            personal-name=Ada
            default-fcc=INBOX.sent-mail
            postponed-folder=INBOX.postponed
            address-book={imap.example.com}ab, ~/abook
            sort-key=Thread/reverse
            feature-list=expunge-without-confirm
            inbox-password=secret
            """.trimIndent() + "\n"
        val preview = previewPinerc(text, AccountSettings())
        val next = preview.next
        assertEquals("imap.example.com", next.imapHost)
        assertEquals(143, next.imapPort)
        assertEquals("smtp.example.com", next.smtpHost)
        assertEquals(2525, next.smtpPort)
        assertEquals("ada", next.username)
        assertEquals("ada@lang.hm", next.email)
        assertEquals("Ada", next.displayName)
        assertEquals("INBOX.sent-mail", next.sentMailbox)
        assertEquals("INBOX.postponed", next.postponedMailbox)
        assertEquals("ab", next.addressBookMailbox)
        assertEquals(SortKey.ThreadReferences, next.defaultView.key)
        assertTrue(next.defaultView.newestFirst)
        assertFalse(next.askBeforeExpunge)
        assertEquals("", next.spamMailbox)
        assertEquals(
            AccountSettings(),
            next.copy(
                imapHost = "",
                imapPort = 143,
                smtpHost = "",
                smtpPort = 25,
                username = "",
                displayName = "",
                email = "",
                sentMailbox = "",
                postponedMailbox = "",
                addressBookMailbox = "",
                defaultView = FolderView(SortKey.Arrival, newestFirst = true),
                askBeforeExpunge = true,
            ),
        )
        assertTrue(preview.rows.none { "secret" in it })
        assertTrue(preview.skipped.none { "secret" in it })
        assertTrue(preview.skipped.contains("Local path is not a mailbox"))
        assertTrue(preview.skipped.contains("Passwords are not imported"))
    }

    @Test
    fun tlsSortAndLocalPathAreNotApplied() {
        val tls = previewPinerc(
            "inbox-path={imap.example.com/ssl/user=ada}INBOX\n",
            AccountSettings(),
        )
        assertEquals("", tls.next.imapHost)
        assertEquals("", tls.next.username)
        assertEquals(AccountSettings(), tls.next)
        assertTrue(tls.skipped.contains("imap.example.com: TLS is not supported"))

        val sort = previewPinerc("sort-key=Score\n", AccountSettings())
        assertEquals(AccountSettings(), sort.next)
        assertTrue(sort.skipped.contains("Sort key is not supported"))

        val sent = previewPinerc("default-fcc=~/mail/sent\n", AccountSettings())
        assertEquals("", sent.next.sentMailbox)
        assertTrue(sent.skipped.contains("Local path is not a mailbox"))
    }

    @Test
    fun unmentionedFieldsStay() {
        val current = AccountSettings(
            spamMailbox = "Junk",
            theme = ThemeMode.Dark,
            swipeTrailing = SwipeBinding(SwipeAction.Move, moveMailbox = "Archive"),
            swipeLeading = SwipeBinding(SwipeAction.Reply),
            favorites = listOf(FolderFavorite(node = false, mailbox = "INBOX", delimiter = '.')),
            friendlyName = "Work",
        )
        val preview = previewPinerc("personal-name=Ada\n", current)
        assertEquals(current.copy(displayName = "Ada"), preview.next)
        assertEquals(listOf("Display name:  → Ada"), preview.rows)
    }

    @Test
    fun smtpPortsUsersAndInboxWithoutPort() {
        val submit = previewPinerc("smtp-server=smtp.example.com/submit\n", AccountSettings())
        assertEquals("smtp.example.com", submit.next.smtpHost)
        assertEquals(587, submit.next.smtpPort)

        val explicit = previewPinerc("smtp-server=smtp.example.com:2525/submit\n", AccountSettings())
        assertEquals(2525, explicit.next.smtpPort)

        val plain = previewPinerc("smtp-server=smtp.example.com\n", AccountSettings())
        assertEquals(25, plain.next.smtpPort)

        val tls = previewPinerc(
            "smtp-server={smtp.example.com/tls}\n",
            AccountSettings(smtpHost = "old"),
        )
        assertEquals("old", tls.next.smtpHost)
        assertEquals(25, tls.next.smtpPort)
        assertTrue(tls.skipped.contains("smtp.example.com: TLS is not supported"))

        val secure = previewPinerc(
            "inbox-path={imap.example.com/Secure/user=ada}INBOX\n",
            AccountSettings(),
        )
        assertEquals("", secure.next.imapHost)
        assertTrue(secure.skipped.contains("imap.example.com: TLS is not supported"))

        val inbox = previewPinerc("inbox-path={imap.example.com/user=ada}INBOX\n", AccountSettings())
        assertEquals("imap.example.com", inbox.next.imapHost)
        assertEquals(143, inbox.next.imapPort)
        assertEquals("ada", inbox.next.username)

        val differ = previewPinerc(
            "inbox-path={imap.example.com/user=ada}INBOX\nsmtp-server={smtp.example.com/user=bob}\n",
            AccountSettings(),
        )
        assertEquals("ada", differ.next.username)
        assertEquals("smtp.example.com", differ.next.smtpHost)
        assertEquals(25, differ.next.smtpPort)
        assertTrue(differ.skipped.contains("SMTP username is not a separate setting"))

        val same = previewPinerc(
            "inbox-path={imap.example.com/user=ada}INBOX\nsmtp-server={smtp.example.com/user=ada}\n",
            AccountSettings(),
        )
        assertTrue(same.skipped.none { it == "SMTP username is not a separate setting" })
    }

    @Test
    fun userIdEmailAndFolders() {
        val kept = previewPinerc(
            "inbox-path={imap.example.com/user=ada}INBOX\nuser-id=bob\nuser-domain=lang.hm\n",
            AccountSettings(),
        )
        assertEquals("ada", kept.next.username)
        assertEquals("bob@lang.hm", kept.next.email)

        val fromId = previewPinerc("user-id=bob\nuser-domain=lang.hm\n", AccountSettings())
        assertEquals("bob", fromId.next.username)
        assertEquals("bob@lang.hm", fromId.next.email)

        val at = previewPinerc("user-id=bob@other.hm\nuser-domain=lang.hm\n", AccountSettings())
        assertEquals("bob@other.hm", at.next.username)
        assertEquals("bob@other.hm", at.next.email)

        val domainOnly = previewPinerc(
            "user-domain=lang.hm\n",
            AccountSettings(username = "ada", email = "keep@lang.hm"),
        )
        assertEquals("ada", domainOnly.next.username)
        assertEquals("keep@lang.hm", domainOnly.next.email)

        val braced = previewPinerc("default-fcc={imap.example.com}INBOX.sent\n", AccountSettings())
        assertEquals("INBOX.sent", braced.next.sentMailbox)

        val slash = previewPinerc("postponed-folder=INBOX/postponed\n", AccountSettings())
        assertEquals("", slash.next.postponedMailbox)
        assertTrue(slash.skipped.contains("Local path is not a mailbox"))

        val book = previewPinerc(
            "address-book=~/abook, {imap.example.com}ab\n",
            AccountSettings(addressBookMailbox = "keep"),
        )
        assertEquals("ab", book.next.addressBookMailbox)
        assertTrue(book.skipped.contains("Local path is not a mailbox"))

        val localBook = previewPinerc(
            "address-book=~/abook\n",
            AccountSettings(addressBookMailbox = "keep"),
        )
        assertEquals("keep", localBook.next.addressBookMailbox)

        val quoted = previewPinerc(
            "address-book=\"{imap.example.com}ab\", \"~/abook\"\n",
            AccountSettings(),
        )
        assertEquals("ab", quoted.next.addressBookMailbox)
        assertTrue(quoted.skipped.contains("Local path is not a mailbox"))
    }

    @Test
    fun sortFeaturesAndOmittedLines() {
        val date = previewPinerc("sort-key=Date\n", AccountSettings())
        assertEquals(FolderView(SortKey.Date, newestFirst = false), date.next.defaultView)

        val ordered = previewPinerc("sort-key=orderedsubj/reverse\n", AccountSettings())
        assertEquals(
            FolderView(SortKey.ThreadOrderedSubject, newestFirst = true),
            ordered.next.defaultView,
        )

        val manual = previewPinerc("feature-list=expunge-only-manually\n", AccountSettings())
        assertTrue(manual.next.askBeforeExpunge)
        assertEquals(AccountSettings(), manual.next)
        assertTrue(manual.skipped.contains("Expunge already happens only when asked"))

        val last = previewPinerc(
            "feature-list=no-expunge-without-confirm,expunge-without-confirm-everywhere\n",
            AccountSettings(),
        )
        assertFalse(last.next.askBeforeExpunge)

        val back = previewPinerc(
            "feature-list=expunge-without-confirm,no-expunge-without-confirm-everywhere\n",
            AccountSettings(),
        )
        assertTrue(back.next.askBeforeExpunge)
        assertEquals(AccountSettings(), back.next)

        val mixed = previewPinerc(
            "feature-list=expunge-without-confirm,enable-foo,expunge-only-manually\n",
            AccountSettings(),
        )
        assertFalse(mixed.next.askBeforeExpunge)
        assertEquals(1, mixed.omittedCount)
        assertTrue(mixed.skipped.contains("Expunge already happens only when asked"))

        val omitted = previewPinerc(
            "normal-foreground-color=red\nkeymap=a\nkeybinding-style=b\nfoo=bar\ninbox-password=secret\n",
            AccountSettings(),
        )
        assertEquals(4, omitted.omittedCount)
        assertEquals(listOf("Passwords are not imported"), omitted.skipped)
        assertTrue(omitted.rows.isEmpty())
        assertEquals(AccountSettings(), omitted.next)
        assertTrue(omitted.skipped.none { "secret" in it })

        val notes = previewPinerc(
            "signature-file=/tmp/sig\nliteral-signature=hi\nincoming-folders=a\nstay-open-folders=b\nfolder-collections=c\n",
            AccountSettings(),
        )
        assertEquals(
            listOf(
                "Signature is not a setting",
                "Signature is not a setting",
                "Folder lists are not imported",
                "Folder lists are not imported",
                "Folder lists are not imported",
            ),
            notes.skipped,
        )
        assertEquals(0, notes.omittedCount)
        assertEquals(AccountSettings(), notes.next)
        assertEquals(listOf("Display name: Old → Ada"), previewPinerc(
            "personal-name=Ada\n",
            AccountSettings(displayName = "Old"),
        ).rows)
    }

    @Test
    fun startupRuleMapsCaseDefaultAndUnknown() {
        val cases = listOf(
            "first-unseen" to StartRule.FirstUnseen,
            "first-recent" to StartRule.FirstRecent,
            "first-important" to StartRule.FirstImportant,
            "first-important-or-unseen" to StartRule.FirstImportantOrUnseen,
            "first-important-or-recent" to StartRule.FirstImportantOrRecent,
            "first" to StartRule.First,
            "last" to StartRule.Last,
        )
        for ((text, rule) in cases) {
            val preview = previewPinerc("incoming-startup-rule=$text\n", AccountSettings())
            assertEquals(rule, preview.next.inboxStart)
            assertTrue(preview.rows.any { it.startsWith("INBOX opens at:") })
        }
        val upper = previewPinerc(
            "incoming-startup-rule=FIRST-UNSEEN\n",
            AccountSettings(inboxStart = StartRule.Last),
        )
        assertEquals(StartRule.FirstUnseen, upper.next.inboxStart)
        val unknown = previewPinerc(
            "incoming-startup-rule=first-new\n",
            AccountSettings(inboxStart = StartRule.Last),
        )
        assertEquals(StartRule.Last, unknown.next.inboxStart)
        assertTrue(unknown.skipped.contains("Startup rule not recognized"))
        val leave = previewPinerc(
            "personal-name=Ada\n",
            AccountSettings(
                inboxStart = StartRule.Last,
                pinercStartDefault = PinercStartDefault.LeaveUnchanged,
            ),
        )
        assertEquals(StartRule.Last, leave.next.inboxStart)
        val alpine = previewPinerc(
            "personal-name=Ada\n",
            AccountSettings(
                inboxStart = StartRule.Last,
                pinercStartDefault = PinercStartDefault.AlpineDefault,
            ),
        )
        assertEquals(StartRule.FirstUnseen, alpine.next.inboxStart)
        assertTrue(alpine.rows.contains("INBOX opens at: First unread (alpine's default)"))
        val patterns = previewPinerc(
            "patterns-other=/START=first-unseen\n",
            AccountSettings(),
        )
        assertTrue(patterns.skipped.contains("Per-folder startup rules are not imported"))
        assertEquals(StartRule.Newest, patterns.next.inboxStart)
    }

    @Test
    fun autoExpungeIsOfferedOnlyWhenConfirmIsTurnedOff() {
        val without = previewPinerc("feature-list=expunge-without-confirm\n", AccountSettings())
        assertFalse(without.next.askBeforeExpunge)
        assertFalse(without.next.autoExpunge)
        assertTrue(without.offerAutoExpunge)
        val unticked = pinercApplied(without, turnOnAutoExpunge = false)
        assertEquals(without.next, unticked)
        assertFalse(unticked.autoExpunge)
        assertFalse(unticked.askBeforeExpunge)
        val ticked = pinercApplied(without, turnOnAutoExpunge = true)
        assertTrue(ticked.autoExpunge)
        assertFalse(ticked.askBeforeExpunge)

        val everywhere = previewPinerc(
            "feature-list=expunge-without-confirm-everywhere\n",
            AccountSettings(),
        )
        assertFalse(everywhere.next.askBeforeExpunge)
        assertFalse(everywhere.next.autoExpunge)
        assertTrue(everywhere.offerAutoExpunge)
        assertFalse(pinercApplied(everywhere, turnOnAutoExpunge = false).autoExpunge)
        val everywhereOn = pinercApplied(everywhere, turnOnAutoExpunge = true)
        assertTrue(everywhereOn.autoExpunge)
        assertFalse(everywhereOn.askBeforeExpunge)

        val lastOff = previewPinerc(
            "feature-list=no-expunge-without-confirm,expunge-without-confirm-everywhere\n",
            AccountSettings(),
        )
        assertFalse(lastOff.next.askBeforeExpunge)
        assertFalse(lastOff.next.autoExpunge)
        assertTrue(lastOff.offerAutoExpunge)

        val confirm = previewPinerc(
            "feature-list=no-expunge-without-confirm\n",
            AccountSettings(askBeforeExpunge = false),
        )
        assertTrue(confirm.next.askBeforeExpunge)
        assertFalse(confirm.next.autoExpunge)
        assertFalse(confirm.offerAutoExpunge)
        val confirmTicked = pinercApplied(confirm, turnOnAutoExpunge = true)
        assertFalse(confirmTicked.autoExpunge)
        assertTrue(confirmTicked.askBeforeExpunge)
        assertEquals(confirm.next, confirmTicked)

        val confirmEverywhere = previewPinerc(
            "feature-list=no-expunge-without-confirm-everywhere\n",
            AccountSettings(askBeforeExpunge = false),
        )
        assertTrue(confirmEverywhere.next.askBeforeExpunge)
        assertFalse(confirmEverywhere.offerAutoExpunge)
        assertFalse(pinercApplied(confirmEverywhere, turnOnAutoExpunge = true).autoExpunge)

        val lastOn = previewPinerc(
            "feature-list=expunge-without-confirm,no-expunge-without-confirm-everywhere\n",
            AccountSettings(),
        )
        assertTrue(lastOn.next.askBeforeExpunge)
        assertFalse(lastOn.offerAutoExpunge)
        assertFalse(pinercApplied(lastOn, turnOnAutoExpunge = true).autoExpunge)

        val manual = previewPinerc("feature-list=expunge-only-manually\n", AccountSettings())
        assertTrue(manual.next.askBeforeExpunge)
        assertFalse(manual.offerAutoExpunge)
        assertFalse(pinercApplied(manual, turnOnAutoExpunge = true).autoExpunge)

        val noList = previewPinerc("personal-name=Ada\n", AccountSettings())
        assertFalse(noList.offerAutoExpunge)
        assertFalse(pinercApplied(noList, turnOnAutoExpunge = true).autoExpunge)
        assertFalse(previewPinerc("\n", AccountSettings()).offerAutoExpunge)

        val already = previewPinerc(
            "feature-list=expunge-without-confirm\n",
            AccountSettings(autoExpunge = true),
        )
        assertFalse(already.next.askBeforeExpunge)
        assertTrue(already.next.autoExpunge)
        assertTrue(already.offerAutoExpunge)
        val alreadyUnticked = pinercApplied(already, turnOnAutoExpunge = false)
        assertTrue(alreadyUnticked.autoExpunge)
        assertFalse(alreadyUnticked.askBeforeExpunge)
        assertEquals(already.next, alreadyUnticked)

        val alreadyOff = previewPinerc(
            "feature-list=expunge-without-confirm\n",
            AccountSettings(askBeforeExpunge = false),
        )
        assertFalse(alreadyOff.next.askBeforeExpunge)
        assertFalse(alreadyOff.next.autoExpunge)
        assertTrue(alreadyOff.offerAutoExpunge)
        assertEquals(alreadyOff.next, pinercApplied(alreadyOff, turnOnAutoExpunge = false))
        val alreadyOffTicked = pinercApplied(alreadyOff, turnOnAutoExpunge = true)
        assertTrue(alreadyOffTicked.autoExpunge)
        assertFalse(alreadyOffTicked.askBeforeExpunge)
    }

    @Test
    fun folderNamesUseSameServerPrefixAndSkipOtherHosts() {
        val bareInbox = previewPinerc("inbox-path=inbox\n", AccountSettings())
        assertEquals("", bareInbox.next.imapHost)
        assertEquals(AccountSettings(), bareInbox.next)
        assertTrue(bareInbox.skipped.contains("inbox-path needs a server in braces"))

        val keptHost = previewPinerc(
            "inbox-path=inbox\n",
            AccountSettings(imapHost = "imap.example.com", imapPort = 993, username = "ada"),
        )
        assertEquals("imap.example.com", keptHost.next.imapHost)
        assertEquals(993, keptHost.next.imapPort)
        assertEquals("ada", keptHost.next.username)
        assertTrue(keptHost.skipped.contains("inbox-path needs a server in braces"))

        val openBrace = previewPinerc("inbox-path={imap.example.com\n", AccountSettings())
        assertEquals(AccountSettings(), openBrace.next)
        assertTrue(openBrace.skipped.none { it == "inbox-path needs a server in braces" })

        val tls = previewPinerc(
            "inbox-path={imap.example.com/ssl/user=ada}INBOX\n",
            AccountSettings(),
        )
        assertEquals("", tls.next.imapHost)
        assertEquals("", tls.next.username)
        assertEquals(AccountSettings(), tls.next)
        assertTrue(tls.skipped.contains("imap.example.com: TLS is not supported"))
        assertTrue(tls.skipped.none { it == "inbox-path needs a server in braces" })

        val host = AccountSettings(imapHost = "imap.example.com")
        val other = previewPinerc("default-fcc={other.example.com}Sent\n", host)
        assertEquals("", other.next.sentMailbox)
        assertEquals("imap.example.com", other.next.imapHost)
        assertTrue(other.skipped.contains("other.example.com is a different server"))

        val ignoredCase = previewPinerc(
            "default-fcc={IMAP.Example.Com:993/user=ada}INBOX.sent\n",
            host,
        )
        assertEquals("INBOX.sent", ignoredCase.next.sentMailbox)
        assertEquals("imap.example.com", ignoredCase.next.imapHost)

        val slash = previewPinerc("default-fcc={imap.example.com}mail/sent\n", host)
        assertEquals("mail/sent", slash.next.sentMailbox)

        val fromInbox = previewPinerc(
            "inbox-path={imap.example.com/user=ada}INBOX\ndefault-fcc={other.example.com}Sent\n",
            AccountSettings(),
        )
        assertEquals("imap.example.com", fromInbox.next.imapHost)
        assertEquals(143, fromInbox.next.imapPort)
        assertEquals("ada", fromInbox.next.username)
        assertEquals("", fromInbox.next.sentMailbox)
        assertTrue(fromInbox.skipped.contains("other.example.com is a different server"))

        val prefixed = previewPinerc(
            "folder-collections=\"Mail\" {imap.example.com}INBOX.[]\n" +
                "default-fcc=sent-mail\npostponed-folder=drafts\n",
            host,
        )
        assertEquals("INBOX.sent-mail", prefixed.next.sentMailbox)
        assertEquals("INBOX.drafts", prefixed.next.postponedMailbox)
        assertTrue(prefixed.skipped.contains("Folder lists are not imported"))

        val already = previewPinerc(
            "folder-collections=\"Mail\" {imap.example.com}INBOX.[]\ndefault-fcc=INBOX.sent-mail\n",
            host,
        )
        assertEquals("INBOX.sent-mail", already.next.sentMailbox)

        val differentCase = previewPinerc(
            "folder-collections=\"Mail\" {imap.example.com}INBOX.[]\ndefault-fcc=inbox.sent-mail\n",
            host,
        )
        assertEquals("INBOX.inbox.sent-mail", differentCase.next.sentMailbox)

        val noBrackets = previewPinerc(
            "folder-collections=\"Mail\" {imap.example.com}INBOX.\ndefault-fcc=sent-mail\n",
            host,
        )
        assertEquals("sent-mail", noBrackets.next.sentMailbox)

        val otherCollection = previewPinerc(
            "folder-collections={other.example.com}INBOX.[]\ndefault-fcc=sent-mail\n",
            host,
        )
        assertEquals("", otherCollection.next.sentMailbox)
        assertEquals("imap.example.com", otherCollection.next.imapHost)
        assertTrue(otherCollection.skipped.contains("other.example.com is a different server"))
        assertTrue(otherCollection.skipped.contains("Folder lists are not imported"))

        val emptyHostBraced = previewPinerc(
            "default-fcc={imap.example.com}INBOX.sent\n",
            AccountSettings(),
        )
        assertEquals("INBOX.sent", emptyHostBraced.next.sentMailbox)
        assertEquals("", emptyHostBraced.next.imapHost)

        val emptyHostCollection = previewPinerc(
            "folder-collections=\"Mail\" {imap.example.com}INBOX.[]\ndefault-fcc=sent-mail\n",
            AccountSettings(),
        )
        assertEquals("sent-mail", emptyHostCollection.next.sentMailbox)
        assertEquals("", emptyHostCollection.next.imapHost)

        val noCollection = previewPinerc("default-fcc=INBOX.sent-mail\n", AccountSettings())
        assertEquals("INBOX.sent-mail", noCollection.next.sentMailbox)

        val localPost = previewPinerc("postponed-folder=INBOX/postponed\n", AccountSettings())
        assertEquals("", localPost.next.postponedMailbox)
        assertTrue(localPost.skipped.contains("Local path is not a mailbox"))

        val lists = previewPinerc("folder-collections=c\n", AccountSettings())
        assertEquals(listOf("Folder lists are not imported"), lists.skipped)
        assertEquals(AccountSettings(), lists.next)

        val submit = previewPinerc("smtp-server=smtp.example.com/submit\n", AccountSettings())
        assertEquals("smtp.example.com", submit.next.smtpHost)
        assertEquals(587, submit.next.smtpPort)

        val book = previewPinerc(
            "address-book={other.example.com}ab, {imap.example.com}book\n",
            host,
        )
        assertEquals("book", book.next.addressBookMailbox)
        assertTrue(book.skipped.contains("other.example.com is a different server"))

        val plainBook = previewPinerc(
            "folder-collections=\"Mail\" {imap.example.com}INBOX.[]\naddress-book=ab\n",
            host.copy(addressBookMailbox = "keep"),
        )
        assertEquals("keep", plainBook.next.addressBookMailbox)
        assertTrue(plainBook.skipped.contains("Local path is not a mailbox"))
    }
}

internal fun previewPinerc(text: String, current: AccountSettings): PinercPreview =
    pinercPreview(
        text,
        current,
        PinercPhrases(
            tls = "%1\$s: TLS is not supported",
            smtpUser = "SMTP username is not a separate setting",
            local = "Local path is not a mailbox",
            history = "Address book history is not a number.",
            sort = "Sort key is not supported",
            rule = "Startup rule not recognized",
            expunge = "Expunge already happens only when asked",
            passwords = "Passwords are not imported",
            folders = "Folder lists are not imported",
            signature = "Signature is not a setting",
            perFolder = "Per-folder startup rules are not imported",
            inboxDefault = "INBOX opens at: First unread (alpine's default)",
            change = "%1\$s: %2\$s → %3\$s",
            imapHost = "IMAP host",
            imapPort = "IMAP port",
            smtpHost = "SMTP host",
            smtpPort = "SMTP port",
            username = "Username",
            displayName = "Display name",
            altAddresses = "Alternate addresses",
            email = "Email",
            sentMailbox = "Sent mailbox",
            postponedMailbox = "Postponed mailbox",
            addressBookMailbox = "Address book mailbox",
            historyLabel = "Address book history",
            defaultView = "Default view",
            newest = "Newest first",
            askExpunge = "Ask before expunge",
            inboxOpens = "INBOX opens at",
            otherHost = "%1\$s is a different server",
            inboxBraces = "inbox-path needs a server in braces",
        ),
    )
