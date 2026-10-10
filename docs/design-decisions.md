# LiveIMAP — design decisions

This document holds the mid-level decisions that carry out
[`liveimap-project-direction.md`](liveimap-project-direction.md), and the
implementation mistakes to avoid. Each one says why, so that it can be changed
deliberately rather than by accident. What ships when is in the roadmap
([`sandbox/roadmap/README.md`](../../sandbox/roadmap/README.md)).

**Open choices are settings.** When a behaviour has more than one reasonable
answer, it is a setting with a sensible default, not a fixed choice.

## Folders

* **Fetch one level, once.** Don't transfer or store folder data the screen
  doesn't need, but never ask the server twice for the same data. If a server or
  protocol can only return the whole list, fetch it once for the view and build
  every level from it; don't refetch it per level.
* **IMAP:** after login, `NAMESPACE`, then one level, `LIST "" "<prefix>%"`.
  Expanding a row is `LIST "" "<mailbox><delim>%"`. `\HasChildren` comes back
  with each row, so the expander needs no extra call.
* **JMAP:** `Mailbox/get` can't filter. A level is one request with two calls:
  `Mailbox/query` with `filter: {parentId: <id>}` (`null` for the top level) and
  `sort: [{property: "sortOrder"}, {property: "name"}]`, then `Mailbox/get` with
  `"#ids": {resultOf: <query call>, name: "Mailbox/query", path: "/ids"}` and
  only the properties the row shows (RFC 8621 §2.3; RFC 8620 §3.7 for result
  references). The filter runs on the server, and the reply holds only that
  level.
* JMAP mailboxes have no "has children" property (RFC 8621 §2). To draw the
  expanders, the same request for a level also asks
  `Mailbox/query` with an `OR` of `parentId` conditions over the level's ids,
  then `Mailbox/get` of those `#ids` with `properties: ["parentId"]`. That
  returns the next level's ids and nothing else. When the user expands one of
  those rows, its children's ids are already known, so the expand request is a
  `Mailbox/get` of those ids plus the next "has children" query. The top level
  takes two round trips (its ids aren't known until the first reply); every
  expand after that takes one.
* Folder counts in JMAP come from `totalEmails` and `unreadEmails` in the same
  `Mailbox/get`, requested only when the count columns are shown.
* Folders nest, and folders sit beside `INBOX`; an account is not a single
  `INBOX.` tree. `INBOX` is first among personal mailboxes. Siblings stay
  siblings, and children render under their parent.
* The label is the server's leaf name. An empty namespace prefix gets a tappable
  row with the placeholder `(empty prefix)`, styled (italic, its own icon) so it
  can't be mistaken for a folder name, with a TalkBack description. It is the
  only stand-in label.
* `\HasChildren` and `\HasNoChildren` decide the expander.
* **Expanded folders: default and current state.** Which folders open expanded
  is configuration: the default view. Which folders are expanded right now is
  session state, kept in memory for the life of the process. Expanding or
  collapsing a row, or "Collapse all", never writes configuration. A new start,
  including after Android kills the process, opens at the default view, which
  starts all collapsed.
* **Saving the default view.** Two actions, both explicit:
  * "Save as default view", in the folder-list overflow menu, saves the whole
    expanded state as it is now. "Reset to default view" next to it returns the
    list to the saved default.
  * "Always expand", on a folder's long-press menu, quickly adds that one folder
    to the default (and "Don't always expand" removes it), without changing the
    rest.

Avoid:

* `LIST "" "*"`. It fetches the whole tree, which can be thousands of folders.
* Stripping `INBOX.` and promoting its children to roots.
* Labels the server didn't send, such as "Other Users" or "Shared Mailboxes".

## Index

* The index is a window onto the folder's current ordered view: a sequence
  range for Arrival order, or positions in the `SORT`, `THREAD` or `SEARCH`
  result for other orders and filters (`Email/query` positions in JMAP). The
  direction decides which end the window starts at and which way it is drawn.
  Where a folder opens is a setting (alpine's `incoming-startup-rule`: first
  unseen, first recent, first important, either of those, first, last, or
  newest), for INBOX, for other folders and per folder, worked out in the
  current view. It takes one server-side search when the rule needs one:
  Newest needs none, and without ESEARCH neither do First and Last. Arrival
  order uses a sequence-number `SEARCH` (`RETURN (MIN|MAX)` with ESEARCH);
  sorted, threaded and filtered views use one `UID SEARCH`, intersected with
  the view's order. Only the window around that row is fetched. The default is the newest message, which is also the fallback when
  nothing matches. What a sort or filter change does (run the rule again, or
  keep the top visible message), whether the `\Recent`-based rules are offered,
  an "Open at" index-menu item, and the pinerc default are settings too.
* The window holds the rows on screen plus a limited prefetch in each scroll
  direction. Rows that leave the window may be dropped.
* Rows fetch `UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE`, plus a short peek of
  the body only when the index density shows a preview. Compact density
  fetches no body.
* Sort and thread choices last for the session only. Per-folder defaults are
  set in Settings. The sort control is a Newest/Oldest direction toggle,
  then the list of criteria.
* New mail (`EXISTS`, `IDLE`) inserts rows without a reload and **never
  scrolls the list**, even when the user is at the newest end. A pill,
  "N new messages ↑" (↓ in an oldest-first view), sits at the edge where the
  new rows are; tapping it jumps to the newest message. It doesn't time out,
  and expunges or flag changes never show it.

Avoid:

* Treating the fetched page as the whole folder. Paging and filters must reach
  past it.
* Sending `SORT REVERSE` the wrong way round. Test the first row of every order
  against the server.
* Acting on sequence numbers. Commands use UIDs, because sequence numbers shift
  on every expunge.

## Index layout

* **Status columns have fixed, reserved widths.** Each mark
  (replied/forwarded, flagged/to-me, attachment) has its own slot. The slot is
  there when the mark is off, so a mark appearing or disappearing never moves
  the text sideways.
* **Selection has no mark and no column.** A long press selects a message and
  starts selection; a selected row is shown only by its background colour (and
  its TalkBack "selected" state). While selection is active, a tap on any row
  adds it or removes it, instead of opening it.
* The sequence-number column is sized for the largest number the folder can
  show (from `EXISTS`), not for the rows loaded now. Paging past 999 or 9999
  then doesn't shift every row.
* The date column has one width for the chosen date format, so it doesn't change
  as rows load.
* Marks take no vertical space: they sit on the header line. The folder list's
  expander column is a fixed width.
* A screen with its own top bar does not also pad for the status bar.

Avoid:

* Adding a mark only when it is on, so the row's text moves when it appears.
* A selection check mark. The background is the selection indicator.
* Measuring a column from the rows that happen to be loaded.

## Reading

* Opening a message fetches its `BODYSTRUCTURE`, then a `BODY.PEEK` of the
  chosen part: 4096 bytes first, and more as the user scrolls. An attachment is
  fetched only after a tap, in 65536-byte slices.
* **View modes.** A body is shown in one of three modes:
  * **Plain**: the `text/plain` part.
  * **HTML**: the `text/html` part, rendered.
  * **HTML as text**: the `text/html` part rendered the way a text-mode browser
    (lynx, links) would: paragraphs, lists, headings, quoted blocks and links
    kept as text, with no styling.

  The default is Plain, with a setting for what to show when there is no
  `text/plain` part. Any message can be switched to another mode. Headers and
  Raw source are inspection views, not body modes.
* HTML as text is also used for the index preview when a message has no
  `text/plain` part.
* HTML as text is produced by parsing with jsoup and rendering the parsed
  document as lynx-style text: numbered link references listed at the end,
  preformatted text kept, quoted blocks marked with `> `, list numbers, and
  table rows. The reader, the index preview and compose quoting use the same
  renderer.
* HTML is rendered with JavaScript off and network loads blocked. Remote images
  load only when the user asks, for that message.
* Decode in this order: transfer encoding (quoted-printable, base64), then the
  charset from `BODYSTRUCTURE`, using `java.nio.charset`. This applies to the
  reader and to previews.
* The Message-ID shown or used is the server's header.

Avoid:

* Assuming UTF-8.
* Showing transfer-encoded or raw HTML text in a preview.
* Fetching a whole body to show its first screen.

## Deleting and moving

* `\Deleted` messages stay visible until an expunge; a setting may hide them.
* Expunge is a deliberate action. With "Confirm before expunge" on, it asks
  first. With it off, the user has accepted permanent deletes, and it never asks.
* Leaving a folder never asks. With Auto-expunge off nothing happens; with it on
  the client expunges silently.
* **No expunge the user didn't ask for.** The app never issues an expunge
  except when the user explicitly asks (Expunge…, Delete permanently) or a
  setting that explicitly enables it applies (Auto-expunge on leave, the
  address-book history trim). Many users keep `\Deleted` messages for months;
  an unexpected expunge is the worst failure the client can have.
* "Permanently delete" removes just the chosen messages with `UID EXPUNGE`
  (UIDPLUS). Without UIDPLUS it is hidden or disabled.
* **Leaving a mailbox.** With Auto-expunge on, `CLOSE` (the intended
  auto-expunge). Otherwise `UNSELECT` when the server has it. Without UNSELECT,
  never `CLOSE`: selecting or examining the next mailbox is the switch, and
  leaving without opening another one sends `EXAMINE` of the same mailbox,
  which deselects it without expunging.
* **Move method** is a setting. **Copy, then mark deleted** (the default, as
  alpine does): `UID COPY` to the target, then `UID STORE +FLAGS (\Deleted)` on
  the moved UIDs; the source copies stay until an expunge the user asks for (or
  Auto-expunge on leave, when on). **IMAP MOVE**, when the server advertises it:
  `UID MOVE`, which removes only the moved UIDs. A move never expunges any
  other message: no plain `EXPUNGE` or `CLOSE` follows a copy, with or without
  UIDPLUS. Swipe actions call the same commands.
* **Delete policy.** One setting chooses what Delete does:
  * **Mark as deleted:** sets `\Deleted`; the message stays (visible unless hidden)
    until an expunge, and Undelete reverses it. IMAP only: JMAP has no
    `\Deleted` (RFC 8621 §4.1.1).
  * **Move to Trash:** a move to the Trash mailbox, which is configured or comes
    from the server's `\Trash` special-use mark, never guessed. On IMAP it
    uses the move method: copy, then `\Deleted` on the source (default), or
    `UID MOVE` when the setting chooses IMAP MOVE.
  * **Delete permanently:** `UID STORE +FLAGS (\Deleted)`, then `UID EXPUNGE` of
    exactly those UIDs (UIDPLUS); `Email/set destroy` on JMAP. It asks first
    when "Confirm before expunge" is on.

  The setting drives the trash button in the index, the selection bar and the
  reader. Their overflow menus, and the swipe choices, offer the other two
  actions where the server supports them. An action the server can't do is
  hidden in the menus and shown disabled, with the reason, in Settings. If the
  chosen policy isn't possible on this account (no UIDPLUS, no Trash mailbox, or
  Mark as deleted on JMAP), the trash button uses the nearest one that is (Mark
  as deleted on IMAP, Move to Trash on JMAP) and Settings says why.
* **Availability** of each action = the delete-policy setting × the server
  capability (UIDPLUS for `UID EXPUNGE`; a known Trash mailbox; MOVE for the
  IMAP MOVE method) × Auto-expunge. Auto-expunge matters only for copy, then
  mark deleted: the source copy stays marked `\Deleted` until an expunge the
  user asks for or a leave with Auto-expunge on. With `UID MOVE` the server
  removes exactly the moved source UIDs at once, and Auto-expunge has no effect
  on that.
* **Inside Trash.** In the Trash mailbox itself, Move to Trash becomes Delete
  permanently when the server supports it, otherwise Mark as deleted. With
  "Confirm before expunge" on, that delete first warns that it will remove the
  messages permanently and asks to confirm.
* "Confirm before expunge" and Auto-expunge keep the meaning above; the policy
  only picks the default action.

Avoid:

* A plain `EXPUNGE` as a fallback for "Permanently delete". It removes every
  deleted message in the folder, including ones marked by other clients.
* `CLOSE`, which expunges silently, except as the leave action when
  Auto-expunge is on.
* A plain `EXPUNGE` (or `CLOSE`) as a side effect of a move, a delete, a
  folder switch or any other action. It removes every `\Deleted` message in
  the folder.
* `MOVE` used without the IMAP MOVE method chosen in Settings.

## Session and connections

* One main connection per account, plus one watch connection that uses `EXAMINE`
  and `IDLE`. `SELECT` only when the mailbox changes; `NAMESPACE` once per
  connection.
* `EXISTS`, `EXPUNGE`, a UIDVALIDITY change, and `IDLE` news invalidate the
  window on screen.
* When the app goes to the background, `IDLE` and the connections are closed. On
  return the client reconnects and resyncs.
* A dead connection is detected (keep-alive, connect and read timeouts) and
  reconnected. The failure is reported in words; a server's "Completed" is never
  shown as an error.
* The session identity is the protocol, host, port, user, password, TLS mode, SASL mechanism and SMTP settings. Changing any of
  them starts a new session (for that account only).
* **Later: more watched folders.** Folders beyond the open one can be watched
  for new mail, each with its own `IDLE` connection (IMAP `IDLE` watches only
  the selected mailbox). Servers limit connections per user (Dovecot's default
  is 10 per user and address, `mail_max_userip_connections`), so the client
  keeps a connection budget: the main connection, the open folder's watch, and
  a configurable number of extra watches. Folders beyond the budget are checked
  with `STATUS` on a timer. Where the server has `NOTIFY` (RFC 5465), one
  connection can watch many mailboxes instead. JMAP needs no extra connections:
  one push channel covers every mailbox. All watches close when the app goes to
  the background.

## Searching and filters

* **No always-open search bar.** A search icon opens a simple bar with a text
  field, a visible field selector (Subject by default; From, To, Cc,
  Participating) and an Advanced button. Phone screens are narrow, so the bar
  has nothing else.
* The simple bar always searches the **current folder**. Scope (subfolders,
  subscribed folders, all folders) is only on the Advanced page.
* **Never full text by default.** `TEXT` and `BODY` searches are offered only on
  the Advanced page, with a cost note. Folders hold hundreds of thousands of
  messages; a full-text search there is expensive.
* Participating is `OR OR FROM x TO x CC x`. Non-ASCII text uses
  `CHARSET UTF-8`.
* The Advanced page holds multi-field searches: field, value and Not per row,
  AND/OR steps, dates, size, flags, keywords, Body and Full text, and scope.
  Multi-folder searches use `ESEARCH IN (…)` when advertised, else one search
  per folder in turn, cancellable.
* A filter is a list of steps: a first search, then AND (narrow) or OR (widen)
  steps, each with its own Not switch.
* The whole filter is one server search. The client never intersects result
  lists itself.
* Counts come only on demand: `UID SEARCH RETURN (COUNT)` with ESEARCH,
  otherwise the length of the `UID SEARCH` result. Never `FETCH` to count.
* The result is the folder view, it survives a sort change, and Select all acts
  on it.
* These are **search filters** (index view). They are not inbound delivery
  rules.

## Inbound rules (Sieve)

* **Sieve is the output format.** Rules the user edits in the UI are compiled
  to Sieve (RFC 5228 plus only the extensions the server advertises).
* **The engine is server-side.** Delivery filtering runs on the mail server
  (ManageSieve on Cyrus 3.x / port 4190, or JMAP Sieve per RFC 9661). LiveIMAP
  never applies inbound rules itself on fetch, IDLE, or display.
* **Gmail-style entry:** "Filter messages like this" from a message seeds
  criteria; the drawer also has Add filter and Edit filters.
* **Actions:** fileinto, set flags (including mark read), discard, redirect.
  No vacation.
* **Client:** a minimal Kotlin ManageSieve client. Plaintext is acceptable;
  TLS is used when the account has it.
* **Do not clobber hand-written scripts.** LiveIMAP owns only `liveimap`. With
  consent, the active script `include`s it (RFC 6609). If `include` is missing,
  ask before SETACTIVE. Never edit his existing script without explicit consent.
* Roadmap: [`inbound-rules-sieve.md`](../../sandbox/roadmap/inbound-rules-sieve.md).
  Research:
  [`managesieve-inbound-rules-20261005.md`](../../sandbox/research/managesieve-inbound-rules-20261005.md).

## Settings

* Settings are many, so they are organized in three levels: a list of groups;
  one screen per group, with its settings in collapsible sections (the first
  open, the others collapsed with a summary of their values); and sub-screens
  for lists and editors. Rarely used settings go in a collapsed Advanced
  section at the end of their group. Every setting has one home; no screen is
  a long flat list.
* A text field saves on Done or when it loses focus, not on every keystroke.
* Mailbox names (Sent, Postponed, the address book, Trash) are configured, or
  taken from the server's special-use marks. They are never guessed.
* Slower fallbacks are listed in their own settings section, each off by
  default.
* **Thresholds for asking are settings.** "Ask before sorting / threading /
  a body or full-text search / a client-side fallback in folders larger than N
  messages" (default 5000 each). 0 means always ask. The question offers "Just
  this once" and "Always for this folder".

## Toolbars and menus

* Each screen's overflow menu ends, after a divider, with **"Customize
  toolbar…"**. It is last so it is hard to hit by accident, and it can't be
  hidden.
* It opens a per-screen editor with three sections in order: **Toolbar**,
  **Overflow**, **Hidden**. Items are dragged within and between sections; each
  item also has Move up/down and move-to-section buttons and TalkBack custom
  actions. "Reset to default" restores the screen's defaults. Dragging uses the
  `Reorderable` library (Calvin-LL, Apache-2.0), listed in NOTICES.
* The toolbar may wrap to more than one row (capped by a setting, default 2);
  actions never move into the overflow silently.
* Defaults follow Material 3: one or two essential actions visible, the rest in
  the overflow. Up/Back, the drawer, Send and "Customize toolbar…" are pinned.

## Rotation and configuration changes

* Every screen works in portrait and landscape. Rotation, dark-mode, font-size
  and window-size changes keep the state: the screen, scroll position (first
  visible UID and offset), selection, open message and its scroll, compose
  drafts, open dialogs, and the IMAP connection.
* Screen models live in `ViewModel`s scoped to the navigation entry, so a
  configuration change reuses them. Saved positions are UIDs, never sequence
  numbers or list indexes.
* A new start is a new start. After Android kills the process, the app opens
  at its default view: nothing of the session (screen, scroll position,
  selection, expanded folders, cached data) is restored, and the session
  cache is never persisted.
* No `android:configChanges` override and no orientation lock.
* Multi-pane is Off or On, and Off is the default. On shows the drawer, the
  index, and an open message as panes. A bar between panes resizes them, and
  dragging a pane to the edge closes it. Window width does not choose the
  panes.

## Compose and sending

* Bcc is an envelope recipient and is not left in the saved copy.
* **Sending is SMTP submission:** port 587 with STARTTLS, or implicit TLS on
  port 465, with SMTP AUTH (same SASL rules as IMAP). Port 25 plaintext only
  when the user chooses it, with a warning. A JMAP account also sends through
  SMTP by default; JMAP `EmailSubmission` is an option for that account.
* After the server accepts a message, it is appended to the Sent mailbox.
  Postpone appends to the Postponed mailbox. A message the server hasn't
  accepted stays on the device as unsent.
* **Drafts survive the background.** When the app goes to the background with
  a message being composed, the draft is appended to the server's Drafts
  folder (the account's Postponed mailbox), so a process kill doesn't lose it.
  Nothing is stored on the device. The previous saved copy of the same draft
  is marked `\Deleted` (never expunged by this). The draft is resumed from
  that folder like any postponed message. If the append fails, compose says
  the draft is not saved.
* 8-bit bodies only when the server advertises 8BITMIME; otherwise
  quoted-printable. No line over 998 octets. Wrap at a column, or send
  format=flowed.
* `[plaintext]` in an address-book comment means "send plain text, not HTML, to
  this address".

## Security: TLS and authentication

* TLS mode and authentication are per-account settings, for IMAP, SMTP and
  ManageSieve. TLS modes: Implicit TLS (993 / 465), STARTTLS required,
  STARTTLS if offered (labelled as open to downgrade), or None (plaintext, with
  a warning). New accounts default to Implicit TLS, IMAP port 993 and SMTP port
  465. An account saved without a TLS mode loads as None and keeps
  working until the user picks another mode.
* After STARTTLS or AUTH, capabilities are read again. No STARTTLS after
  PREAUTH.
* SASL through libetpan and cyrus-sasl, strongest first among what the server
  advertises: SCRAM-SHA-256(-PLUS), SCRAM-SHA-1(-PLUS), CRAM-MD5, then the
  clear-text password mechanisms PLAIN (with SASL-IR), LOGIN and the IMAP
  `LOGIN` command. On a plaintext connection only challenge-response
  mechanisms (SCRAM, CRAM-MD5) are used without asking; clear-text password
  mechanisms only after the user accepts the plaintext warning. DIGEST-MD5 is not used (tests record it as an expected skip). NTLM only when chosen in Advanced. An Advanced
  setting can force a mechanism and set an authorization user.
* OpenSSL 3.x, TLS 1.2 minimum, SNI always. The certificate chain is checked
  in Kotlin with the platform trust manager plus a hostname check. A failure
  is fatal unless the user pins that exact certificate (subject, issuer,
  validity, SHA-256 fingerprint shown; stored per account and host:port). A
  changed pinned certificate asks again. There is no global "accept all".
* Test servers use certificates from a lab test CA. The app trusts that CA
  only for acceptance-test connections to marked test servers (a test-only
  trust anchor), never for normal account connections.
* JMAP uses the platform HTTPS stack, with the same certificate check and
  pinning prompt.
* Later options, each with its own roadmap doc and not scheduled: OAuth
  (XOAUTH2 / OAUTHBEARER; waits on the client-ID registration decision),
  client certificates (EXTERNAL), and GSSAPI (MIT krb5 through cyrus-sasl,
  low priority, under Advanced).

## Accounts

* An account has an id and its own settings, secrets and toolbar layouts.
  Several accounts, on one server or several, can be open.
* The session identity is per account (see "Session and connections").
* Each account has one main and one watch connection in the foreground; all
  close in the background.

## Testing

* Tests run only against test servers: lab servers, and test servers the
  user configures at test time. A configured test server may be a real mail
  server used only through its test accounts: no test, tool or agent changes a
  real account, and test tools log in only as an allowlisted test account.
  Each configured server has a role, read-write or read-only (for example a
  replica); only a read-write one takes writes. The docs name no particular
  host. Sends go only to the test recipient set at test time.
* Every lab server starts from one versioned seed (accounts, folder tree,
  message corpus) with a profile: base by default, and an optional scale
  profile (a 100,000-message folder and 1,000 folders) chosen per server
  because it takes a lot of space. Each lab server is reset to its seed with
  one command, and the lab servers are reset before a full regression run.
  Lab servers run as pinned containers on one lab host, each on its own ports, reachable from the LAN only. Site values come from host settings, and the repo holds only the setup scripts, pins and seed generator.
* Automated acceptance tests run on the device from a drawer entry, only
  against entries marked as test servers in Debug settings. Tests that change
  mail stay inside a `LiveIMAP-Test` folder tree. Scale tests are skipped,
  with "scale content not seeded", where the scale profile is absent.
* Every test is tagged with the feature and the plan that added it; suites are
  filters: full regression, this plan, the last N plans, or a feature. Each
  plan adds the tests for its own work.
* Tests cover functional paths (including every advertised auth mechanism and
  TLS mode; DIGEST-MD5 is an expected skip) and degraded behaviour: capabilities hidden by a debug override,
  real older servers, and slower fallbacks forced on.
* Each run writes a JSON report and a text summary to
  `/sdcard/Android/data/org.dlang.liveimap/files/acceptance/` for `adb pull`,
  with build, device, servers, seed profiles, capabilities and per-test
  results, so runs and builds can be compared.

## Address book

* The address book is Alpine's container message in the configured mailbox.
  Reading it fetches that one message in 4096-byte ranges.
* Writing it must round-trip nickname, full name, address, fcc, comment and
  distribution-list lines, including empty nicknames.
* `[plaintext]` stays in the comment, not in the address.
* Each write appends the whole book as a new last message, as alpine does.
* **History trim.** "Address book history" is the number of old copies kept:
  default 3 (alpine's `remote-abook-history` default), the imported pinerc
  value as is, or LiveIMAP's "Never trim". Right after each write, the copies
  beyond that number are marked `\Deleted` and removed with `UID EXPUNGE` of
  exactly those UIDs. Never a plain `EXPUNGE`, never on exit, and no trim
  without UIDPLUS.

## Traffic log

* Commands and responses with timestamps and a connection id. No message
  bodies, no duplicate lines, a size cap. Off unless the debug option is on.
* No passwords, in the log or in the source.

## Engine and native code

* IMAP and SMTP use LibEtPan from `https://github.com/davidelang/libetpan`,
  built in `third_party` with libfastjson. JMAP is another engine behind the
  same session interface, using LiveIMAP's own Kotlin JMAP client (no
  maintained Kotlin or Java JMAP library exists to depend on).
* Charset conversion goes through Java (`java.nio.charset`), reached from
  libetpan's `extended_charconv` hook. `minSdk` stays 26.
* Every dependency and native library is listed in
  `app/src/main/assets/licenses/NOTICES.txt`, which is regenerated whenever a
  dependency changes.
