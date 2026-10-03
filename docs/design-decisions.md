# LiveIMAP — design decisions

This document holds the mid-level decisions that carry out
`docs/liveimap-project-direction.md`, and the implementation mistakes to avoid.
Each one says why, so that it can be changed deliberately rather than by
accident. What ships when is in the roadmap.

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
  session state, kept in memory (and in Android's saved state, so it survives
  the process being killed). Expanding or collapsing a row, or "Collapse all",
  never writes configuration. A new session opens at the default view, which
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
  A folder opens at its newest message.
* The window holds the rows on screen plus a limited prefetch in each scroll
  direction. Rows that leave the window may be dropped.
* Rows fetch `UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE`, plus a short peek of
  the body only when the index density shows a preview. Compact density
  fetches no body.
* Sort and thread choices last for the session only. Per-folder defaults are
  set in Settings. The sort control is a Newest/Oldest direction toggle,
  then the list of criteria.
* New mail (`EXISTS`, `IDLE`) inserts rows without a reload, and the list
  doesn't jump.

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
* "Permanently delete" removes just the chosen messages with `UID EXPUNGE`
  (UIDPLUS). Without UIDPLUS it is hidden or disabled.
* Leaving a mailbox uses `UNSELECT` when the server has it; otherwise selecting
  the next mailbox is the switch.
* Move is `UID COPY` to the target, then `\Deleted` on the source. Swipe actions
  call the same commands.
* **Delete policy.** One setting chooses what Delete does:
  * **Mark as deleted:** sets `\Deleted`; the message stays (visible unless hidden)
    until an expunge, and Undelete reverses it. IMAP only: JMAP has no
    `\Deleted` (RFC 8621 §4.1.1).
  * **Move to Trash:** a move to the Trash mailbox, which is configured or comes
    from the server's `\Trash` special-use mark, never guessed. On IMAP the
    source copy is marked `\Deleted`, as for any move.
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
* IMAP `MOVE`, which expunges the source at once and bypasses the delete rules.

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
* The session's identity is the host, port, user, password and TLS mode (and
  the SMTP host, port and settings for sending). Changing any of them starts a
  new session.
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

* A filter is a list of steps: a first search, then AND (narrow) or OR (widen)
  steps, each with its own Not switch.
* The whole filter is one server search. The client never intersects result
  lists itself.
* Counts come only on demand: `UID SEARCH RETURN (COUNT)` with ESEARCH,
  otherwise the length of the `UID SEARCH` result. Never `FETCH` to count.
* The result is the folder view, it survives a sort change, and Select all acts
  on it.

## Settings

* A text field saves on Done or when it loses focus, not on every keystroke.
* Mailbox names (Sent, Postponed, the address book, Trash) are configured, or
  taken from the server's special-use marks. They are never guessed.
* Slower fallbacks are listed in their own settings section, each off by
  default.

## Compose and sending

* Bcc is an envelope recipient and is not left in the saved copy.
* After the server accepts a message, it is appended to the Sent mailbox.
  Postpone appends to the Postponed mailbox. A message the server hasn't
  accepted stays on the device as unsent.
* 8-bit bodies only when the server advertises 8BITMIME; otherwise
  quoted-printable. No line over 998 octets. Wrap at a column, or send
  format=flowed.
* `[plaintext]` in an address-book comment means "send plain text, not HTML, to
  this address".

## Address book

* The address book is Alpine's container message in the configured mailbox.
  Reading it fetches that one message in 4096-byte ranges.
* Writing it must round-trip nickname, full name, address, fcc, comment and
  distribution-list lines, including empty nicknames.
* `[plaintext]` stays in the comment, not in the address.

## Traffic log

* Commands and responses with timestamps and a connection id. No message
  bodies, no duplicate lines, a size cap. Off unless the debug option is on.
* No passwords, in the log or in the source.

## Engine and native code

* IMAP and SMTP use LibEtPan from `https://github.com/davidelang/libetpan`,
  built in `third_party` with libfastjson. JMAP is another engine behind the
  same session interface.
* Charset conversion goes through Java (`java.nio.charset`), reached from
  libetpan's `extended_charconv` hook. `minSdk` stays 26.
* Every dependency and native library is listed in
  `app/src/main/assets/licenses/NOTICES.txt`, which is regenerated whenever a
  dependency changes.
