# LiveIMAP — project direction

LiveIMAP is a window onto the mail server. Mail stays on the server. Alpine is
the model for what the client does. The screen is a modern Android UI, including
swipe actions.

This document holds the goals and rules that stay the same across versions,
servers and accounts. Mid-level design decisions, and the implementation
mistakes to avoid, are in [`design-decisions.md`](design-decisions.md). What
ships when, and the facts about particular servers, are in the roadmap
([`sandbox/roadmap/`](../../sandbox/roadmap/README.md)).

## Goals

* **The screen can be trusted.** What it shows is the server's current state. A
  folder opens where its start-position setting says (by default at its newest mail). A failure is reported, not hidden behind
  stale data.
* **Large accounts work.** About 1000 folders, about 500,000 messages, and more
  than 200,000 in one folder. The user will not read them all here, but the
  client must move through that tree without fetching it.
* **Any standards-based server works.** The client adapts to what the server
  advertises. It doesn't require one server's feature set.
* **Alpine's functions, without its presentation.** The things an Alpine user
  relies on are here, in a form that suits a touch screen.

## Rules

1. **Fetch only what is needed.** A folder list is fetched one level at a time.
   An index fetches the rows that fit on the screen, plus enough to scroll
   smoothly. A message fetches its structure, then the part being read, in
   slices, as the user scrolls. Decoding (signatures, HTML, charsets) may need
   more of a message; fetch that, and no more. Never ask the server twice for
   the same data: if a server can only return a whole list, fetch it once for
   the view and use it.
2. **The server is the store.** Stored on the device: configuration, secrets (in
   the Android keystore), and outgoing mail the server has not yet accepted.
   Outgoing mail is the user's work: it stays, is retried when the link returns,
   and the screen says it was not sent. Headers, bodies, search results, thread
   trees and folder lists are a short-term in-memory cache only, purged on exit
   or process restart — not a persistent local datastore. The app cannot know
   whether a disk cache is valid when it connects (or fails to); a clear "can't
   connect right now" is better than working from stale data. This holds whatever
   the server supports (including CONDSTORE/QRESYNC).
3. **Degrade by capability.** The client uses what the server advertises,
   feature by feature. When something is missing, that feature is off and the
   screen says why, or a slower fallback is offered as an opt-in setting (off
   by default). One missing extension never refuses the whole server.
4. **Fallbacks do the minimum.** A fallback does only the work its feature
   needs. It doesn't fetch data that only "may" be needed, and scroll prefetch
   stays limited. Some fallbacks inherently need everything (for example,
   client-side threading when the server can't thread). They are allowed, run
   only when the user asks, and say what they cost.
5. **Alpine functions, Android presentation.** Any Alpine function (selecting,
   narrowing and broadening, expunge rules, the address book, roles,
   next-unread) is a candidate feature. Alpine's key-driven, character-cell
   presentation is not. Alpine's settings file can be imported: names and
   settings only, never passwords.
6. *(Optional.)* **The mail on screen is the server's.** Folder lists, indexes
   and messages show only what the server returned.

## Transports

LiveIMAP is designed for two mail transports, behind one session interface:

* **IMAP**, with SMTP submission for sending.
* **JMAP** (RFC 8620 and RFC 8621), for mail. A JMAP account sends through
  SMTP submission by default; JMAP `EmailSubmission` is an option.

An account uses one transport, chosen by what its server offers. Design
decisions are written so they hold for both. For example, the index is a window
of positions in the server's ordered result, which is a SORT, THREAD or SEARCH
result in IMAP and `Email/query` with `position` and `limit` in JMAP. The rules
above apply to both: a JMAP capability that is missing is handled exactly like
a missing IMAP extension. The roadmap says which transport ships first.

An account is identified by its transport, host, port and user; several
accounts, on one server or several, can be open at once.
