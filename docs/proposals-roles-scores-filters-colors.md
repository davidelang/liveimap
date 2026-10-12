# Proposals: roles, scores, display filters, index colors (2026-10-12)

Short proposals only, no code. Each maps an Alpine feature (pinerc
`patterns-roles`, `patterns-scores2`, `patterns-filters2`,
`patterns-indexcolors`) onto this UI. All four share one pattern editor: the
same criteria as Advanced search (from, to, cc, subject, header, body,
folder, flags, size, age, keyword), so a saved search can seed a pattern.
Patterns are per account, stored in AccountSettings, importable from pinerc
(today PinercImport.kt only looks for `/START=` in `patterns-other`; a real pattern parser is new work).

## Roles (identities)

* **What:** a named identity: From name/address, Reply-To, Fcc, signature,
  SMTP server, and an optional match pattern.
* **UI:** Settings > Roles: list, add, reorder. In compose, the From line is a
  dropdown of roles (replacing the fixed From); the chosen role sets Fcc and
  signature. On reply/forward the first role whose pattern matches the
  original (e.g. To: my alt address) is preselected, with a small "Role: Work"
  chip; Alpine's "ask which role" becomes the dropdown being open on send if
  more than one matched and no default is set.
* **Depends on:** signature (missing), alt-addresses (exists).

## Scores

* **What:** each pattern adds a number to a message's score; the index can
  sort by score and filters can use "score ≥ N".
* **UI:** Settings > Scores: rules "If <pattern> then +/- N". The index row
  shows nothing by default; with "Show score" on, a small number at the row
  end. Sort menu gains "Score". "Score ≥ N" becomes a filter chip.
* **Cost:** scoring needs the matched fields per message; computed only for
  the visible window (headers already fetched), with server SEARCH for
  body/header criteria in one batched request per window. Sort by score is
  client-side over the window plus a server pre-filter, and the UI says so.

## Display filters

* **What:** Alpine's display filters run a command on message text; here, a
  safe built-in set instead of commands: hide quoted text past N lines, fold
  long signatures, de-URL-defang, show PGP-armored text as a box, strip
  tracking pixels.
* **UI:** Settings > Reading > Display filters: toggles, each with an
  optional pattern ("only for mail from lists"). The reader shows a thin
  "Filtered: quotes folded · Show original" bar when one applied.
* **Not:** running external programs (no shell on Android; security).

## Index colors

* **What:** a pattern sets the row's color (text and/or background), like
  Alpine `patterns-indexcolors`.
* **UI:** Settings > Index colors: "If <pattern> then color <swatch>" with a
  preview row. Colors come from a fixed palette that has light/dark variants
  and passes contrast checks against both themes; a left color stripe is the
  default style (keeps unread bold and look-alike highlighting readable), with
  full background as an option. First matching rule wins; drag to reorder.
* **Cost:** same window-only matching as scores; shares that code.

## Order

Roles first (most mail impact, needs signature), then index colors and scores
together (shared matcher), display filters last.
