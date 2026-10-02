# LiveIMAP — project direction

The phone is a window onto the server. Mail stays on the server. The client fetches one folder level at a time, and a short slice of one message when that message is opened. Alpine is the model for that behavior. The screen is a modern Android UI, including swipe actions. `pine.rc` is a picture of the settings, not a format the first version has to import.

## v1

One account, one server. Asgard answered this before login on 2026-09-29:

```text
* OK asgard Cyrus IMAP4 v2.2.13-Debian-2.2.13-19ubuntu4 server ready
* CAPABILITY IMAP4 IMAP4rev1 ACL QUOTA LITERAL+ NAMESPACE UIDPLUS ID NO_ATOMIC_RENAME UNSELECT CHILDREN MULTIAPPEND BINARY SORT THREAD=ORDEREDSUBJECT THREAD=REFERENCES ANNOTATEMORE IDLE STARTTLS
```

IMAP `10.0.0.100:143` and SMTP port 25, both plaintext. The settings screen holds host, username, password, and the mailbox names for sent mail, postponed mail, and the address book. Those mailbox names start empty. The example `~/.pinerc` uses a top-level `Drafts` and `inbox.sent-mail`. The client does not guess them. That file has no password. Do not put one in the source.

After `LOGIN`, the session requires `NAMESPACE`, `UIDPLUS`, `LITERAL+`, `CHILDREN`, `SORT`, `THREAD=REFERENCES`, and `IDLE`. `UNSELECT` is not required. A missing `UNSELECT` does not stop the session. The client never sends `CLOSE`. If any required capability is missing, show the capability list and stop. There is one implementation. There is no second command path for a poorer server.

This server does not advertise `CONDSTORE`, `QRESYNC`, `ESEARCH`, `MOVE`, `SPECIAL-USE`, `LIST-EXTENDED`, `LIST-STATUS`, `METADATA`, or `UTF8=ACCEPT`. v1 does not call them and does not invent substitutes. A later client may talk to other servers. If a command is absent there, the feature is absent. The phone does not compute it.

Scale that has to work: about 1000 folders, about 500,000 messages, and more than 200,000 in one folder. The user will not read all of them here. The client must still move through that tree.

## What is stored

Configuration, including which folders are expanded, the plain-or-HTML default, whether `\Deleted` mail stays visible, and swipe actions. Secrets go in the keystore.

An outbound message the server has not accepted. Compose is the user's work. If SMTP or `APPEND` fails because the link is down, that message stays on the device and is sent when the link is back. The screen says it has not been sent.

Headers, bodies, search hits, thread trees, and folder listings are memory for the session. They are not written as a mailbox. Cyrus 2.2.13 has no `CONDSTORE` or `QRESYNC`, and other clients change the server while this app is closed. A header file cannot be proved fresh, so v1 keeps no disk cache of mail.

While the session is open, `EXISTS`, `EXPUNGE`, a UIDVALIDITY change, and `IDLE` on a second connection invalidate the window on screen.

## Folders

This account is not a single `INBOX.` tree. Folders nest, and folders sit beside `INBOX`. After login, `NAMESPACE`, then one level: `LIST "" "<prefix>%"`. Expanding a row is `LIST "" "<mailbox><delim>%"`. Never `LIST "" "*"`. `INBOX` is first among personal mailboxes. Siblings stay siblings. Children render under the parent. The label is the server's leaf name. Do not strip `INBOX.` and promote those names to roots. Do not label a namespace "Other Users" or "Shared Mailboxes". `\HasChildren` and `\HasNoChildren` decide the expander. The list starts collapsed. Expansion is config.

The expander column is a fixed width. Flag marks sit on the header line and take no vertical space when they are off. A screen with its own top bar does not also pad for the status bar.

## Messages

The index window is 60 sequence numbers at the high end of `EXISTS`, plus the next older page, and pages that leave the screen are dropped. The fetch is `UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE` only. No body and no snippet on the index.

Arrival order uses that sequence range. Other orders are `UID SORT`. Thread view is `UID THREAD REFERENCES`. Search is `UID SEARCH`. The client may hold the UID list or the thread tree. It fetches envelopes for the visible rows only.

Opening a message fetches `BODYSTRUCTURE` for that UID, then `BODY.PEEK` of the preferred part for the first 4096 bytes, then more only as the user scrolls. An attachment is fetched only after a tap, 65536 bytes at a time. Plain versus HTML is a setting, default plain, overridable on that message. HTML is a WebView with JavaScript off and network loads blocked. The Message-ID is the header from the server.

`\Deleted` stays visible until an explicit expunge. That is the default. A setting may hide them. When `UNSELECT` is advertised, leaving a mailbox sends `UNSELECT` before `SELECT` of the next. When it is not, `SELECT` of the next mailbox is the switch. Move is `UID COPY` to the configured mailbox, then `\Deleted` on the source. Swipe calls those same commands.

## Compose and the address book

SMTP has no authentication on this server. Bcc is an envelope recipient and is not left in the saved message. After SMTP accepts, `APPEND` to the configured sent mailbox. Postpone is `APPEND` to the configured postponed mailbox.

The address book is the Alpine container in the configured mailbox. A read for the compose picker fetches that one message in 4096-byte ranges. A later write must round-trip nickname, full name, address, fcc, comment, and distribution-list lines, including empty nicknames. `[plaintext]` stays in the comment. It is not pasted into the address. v1 may ship the reader before the writer.

No sample mail, no sample folders, no stand-in Message-ID, no second IMAP parser.

## Library

The IMAP and SMTP engine is LibEtPan from `https://github.com/davidelang/libetpan` already setup in third_party this should be compiled with libfastjson.

JMAP is not v1. The JSON backend, when JMAP is built, is libfastjson. `mailjson.c` is written against json-c 0.14 and is not a drop-in. v1 does not link either JSON library.

The failed tree's Android build is not the procedure for the new repository. Do not copy `third_party/libetpan` from it. In particular, do not ship a file named `libetpan.so` whose contents are the text `LIBETPAN_STUB`. On this Linux host the NDK is `/usr/lib/android-ndk` and its prebuilt is `toolchains/llvm/prebuilt/linux-x86_64`, owned by root. A build must not create symlinks there. OpenSSL 1.1.1w treats that prebuilt as a standalone toolchain because it contains `AndroidVersion.txt`. `ANDROID_NDK_HOME` has to be the NDK root, which has `source.properties` and no `AndroidVersion.txt`, so Configure uses the `*-clang` and `llvm-ar` names the NDK already ships.

## Out of v1

Roles, scores, display filters, index colors, NNTP, a `pine.rc` importer, TLS, authenticated SMTP, and a second server. The settings can grow into the pinerc shape. The first screen is this one server: a folder level, and an arrival-order window of real headers.
