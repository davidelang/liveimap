# Contacts: project plan (2026-10-12)

Plan only; no code yet. Goal (David, 2026-10-12): write support for the Alpine
address book; read Android contacts across all accounts/sets with the Alpine
book as just another source; copy a contact between any two sources, mapping
matching fields and keeping the rest in a structured block so a reverse copy
restores them.

## Where it stands (verified in source)

Paths under `app/src/main/kotlin/org/dlang/liveimap/ui/contacts/`.

| Piece | Status | Evidence |
|---|---|---|
| Read Alpine book (container message, ranged fetch) | done | `AlpineBook.kt` `readPineBook`, `parseAlpineBook`; `AlpineBookTest` |
| Write Alpine book (append whole book, history trim by UID EXPUNGE) | done | `writePineBook`, `revisionsToExpunge`, `formatAlpineBook`; `AlpineBookWriteTest`; design-decisions.md "Address book" |
| Add sender from the reader | done | `ui/reader/MessageReaderScreen.kt` ~2310-2330 |
| Edit / delete / rename an entry, edit lists | missing | no editor; `AddressBookPicker.kt` only picks |
| Android contact sets (per account, owning-app label) | done for completion | `Completion.kt` `androidContactSetsOnce`, `androidEntriesOnce` (RawContacts by ACCOUNT_TYPE/NAME) |
| Alpine book as a completion source beside Android sets | done | `completionSourcesInOrder`, `withSourceEnabled`, `moveCompletionSource` |
| A contacts browser listing all sources | missing | only the picker (Alpine) and the copy screen |
| Copy Alpine <-> Android | done | `CopyMap.kt` `copyContacts(intoPine=…)`, `ContactCopyScreen.kt` (route in `ui/NavHost.kt`); `CopyMapTest` |
| Copy Android set -> other Android set | partial | `copyIntoAndroid` exists; check source/destination choice in `enabledCopySources` |
| Copy Alpine book of account A -> account B | missing | copy screen is bound to one account |
| Extras kept on copy | partial | Android->Alpine: dropped, or flattened into comments as "Label: value" (`extraLines`) — not parseable back. Alpine->Android: `pine-fcc:` / `pine-plaintext:` lines in the note (`buildNote`/`parseNote`) — these do round-trip |

## Model

One interface over every source:

```
ContactSource { id, label, kind (Alpine(account) | Android(accountType, accountName)),
                capabilities (nickname, fcc, groups, lists, notes, photo, multiEmail),
                list(), write(changes) }
Contact = CopyContact (existing, CopyMap.kt) + sourceId + sourceKey (nickname / rawContactId)
```

The Alpine book is a source per account with an address book configured; each
Android account (Google, Exchange, phone-local, ...) is a source. Sources show
in the same order/enabled list already used for completion.

## Field mapping

| Field | Alpine | Android |
|---|---|---|
| nickname | nickname | Nickname row |
| full name | fullname ("Last, First" split on comma) | StructuredName display/given/family |
| email(s) | address (single) or list members | Email rows (all) |
| distribution list | `(a,b,c)` address | Group membership or skip (asked in preview) |
| fcc | fcc | extras block |
| comment | comment | Note (outside the block) |
| [plaintext] | in comment | extras block |
| phone, postal, org, birthday, website, IM, custom | — | native rows |
| photo, ringtone, starred, linked | — | native; not copied to Alpine (photo noted as "present") |

## Structured extras block (the reverse-copy guarantee)

Whatever the destination can't hold goes into its free-text field (Alpine:
comment; Android: Note) as one fenced block, appended after the user's own
text:

```
[liveimap-extra v1]
phone.mobile=+1 555 0100
postal.home=1 Main St%0AAnytown
org=Example Inc
fcc=sent-friends
plaintext=yes
[/liveimap-extra]
```

* Keys `kind.label`, values percent-encoded (`%0A`, `%09`, `%25`, `%5D`) so the
  block survives Alpine's one-line, tab-separated comment field (written there
  with spaces between the bracket tags instead of newlines).
* On copy, the block is parsed first and each key is restored to a native
  field the destination supports; only what still doesn't fit is re-written
  into a new block. The user's own comment/note text is kept verbatim.
* Unknown keys and versions are kept as is, never dropped.
* Replaces today's flattened "Label: value" text and the `pine-fcc:` lines;
  both are still read for old copies.
* Alpine's comment limit: the current 1000-char cap (`pineFields`) applies to
  the user text; if the block would be cut, the preview says which fields are
  dropped instead of truncating mid-block.

## Phases

1. **Source layer.** `ContactSource` for Alpine (per account) and Android
   (per account set), reusing `readPineBook`/`writePineBook` and the RawContacts
   queries. Unit tests with fakes. No UI change.
2. **Extras block.** Encoder/decoder plus round-trip tests: Android -> Alpine
   -> Android and Alpine -> Android -> Alpine return identical contacts
   (except photo/ringtone/starred/linked, which the preview names).
3. **Copy between any two sources.** Copy screen picks source and destination
   from the full list (including another account's Alpine book, Android set to
   Android set). Keeps the existing preview, merge, one-entry-per-email and
   one confirm. WRITE_CONTACTS asked only when an Android set is written.
4. **Alpine book editing.** Add / edit / delete / rename entry and edit list
   members; each save is one book write with history trim. Conflict check:
   re-read the latest copy before writing; if it changed (another client),
   show the difference and ask.
5. **Contacts browser.** One list across enabled sources, source shown per
   row, search, open entry, "Copy to…", "Edit" (Alpine; Android opens the
   system editor via ACTION_EDIT).
6. **Lab test.** Alpine-book write and copy steps in LabSuiteTest on a lab
   server; emulator check of copy both ways with a test Google-less local set.

## Rules

* Never write a source the user didn't pick as destination; one confirm per copy.
* Never drop data silently: anything not copied is listed in the preview.
* Alpine writes follow the existing rules (whole-book append, UIDPLUS trim only).
* Lab servers and emulator only for tests; no real address books.
