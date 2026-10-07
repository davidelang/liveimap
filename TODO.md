# TODO


# Future work

- Support more than one mail account

- Keep the account and password in the Android secure store

- Index opens at the newest message in every sort and thread mode, pages past 120, and inserts new mail without a reload (roadmap M1.1; sandbox/plans/critical-ui-fixes-20261003-1505-plan.md; sandbox/research/recommendations-2026-10-03.md B2 B9 B14-B16)

- Up navigation on Settings, About and Unsent (roadmap M1.2; sandbox/plans/settings-screens-and-up-navigation-20261003-1320-plan.md; recommendations B1)

- Traffic log with timestamps and connection ids, no bodies, no duplicate lines, size cap, debug option only (roadmap M1.3; recommendations R-P1-6, D6)

- Detect a dead IMAP connection, reconnect once, add keep-alive and timeouts, and never show "Completed" as an error (roadmap M1.4-M1.5; recommendations R-P1-2, R-P1-4)

- SELECT only when the mailbox changes, one EXAMINE-based IDLE connection, NAMESPACE once per connection (roadmap M1.6; recommendations R-P1-3)

- Close IDLE and connections in the background, then reconnect and resync on return (roadmap M1.7; recommendations R-P1-9, D7)

- Outgoing mail: EHLO with 8BITMIME negotiation, line wrapping or format=flowed, no line over 998 octets (roadmap M1.10; recommendations section 11.3)

- Settings group screens with Up bars and save on Done (roadmap M2.1; sandbox/plans/settings-screens-and-up-navigation-20261003-1320-plan.md; recommendations W9, D12, R-P1-5)

- Open-source licenses screen on About and a plaintext-connection notice (roadmap M2.2-M2.3; sandbox/plans/settings-screens-and-up-navigation-20261003-1320-plan.md; recommendations section 12, D11)

- Session-only sort with a direction toggle and one criteria list (roadmap M2.4; recommendations R-P1-8, D2)

- Index overflow menu with Expunge, Confirm before expunge and Auto-expunge settings, and per-message Permanently delete on UIDPLUS servers (roadmap M2.5-M2.6; recommendations R-P2-1, R-P2-8, D10)

- Folder list level cache and counts that survive expand and collapse (roadmap M2.7; recommendations B4 B5)

- Help and getting started page with account setup, Alpine settings import and the plaintext note (roadmap M2.11; recommendations D15)

- Index selection shown by row background only, and fixed-width status, sequence and date columns so marks and paging never shift the text (roadmap M2.12; recommendations B18 B19, D16, D21)

- Expanded folders: the current state is session state, and the default view is saved only by Save as default view or a folder's Always expand (roadmap M2.13; recommendations B21, D19)

- Delete policy setting for the trash button: mark as deleted, move to Trash, or delete permanently (roadmap M2.14; recommendations R-P2-8, D20)

- Per-feature capability flags instead of the all-or-nothing session gate, plus opt-in slower fallbacks that do only the work they need (roadmap M3; recommendations W1 W2, D13)

- Fake IMAP server with Cyrus 2.2 and 3.x profiles for tests, and a fixed test account (roadmap M3.3; recommendations R-P2-5)

- Replace GNU libiconv with Java charsets through the libetpan extended_charconv hook and keep minSdk 26 (roadmap M4.1; recommendations section 12)

- Reader decodes bodies with the charset from BODYSTRUCTURE (roadmap M4.2; recommendations B17)

- Move OpenSSL from 1.1.1w to 3.x before TLS or any public release (roadmap M4.3; recommendations section 12)

- Filters with a Not switch, AND and OR steps evaluated on the server, on-demand counts, and an advanced filter window (roadmap M5; recommendations R-P1-10, D9)

- Reader header, link confirm, attachments, thread layout, compose top bar and configurable action bars (roadmap M6.1-M6.5; recommendations R-P2-2, R-P2-6, W6-W8)

- Alpine functions: next unread, jump to number, Save without delete, alt-addresses, Reply-To question (roadmap M6.6-M6.7; recommendations R-P2-4, section 11.6)

- pinerc importer fixes for folder names, hosts, inbox-path, submission and the Auto-expunge opt-in (roadmap M6.8; recommendations section 11.5)

- Body view modes Plain, HTML and HTML as text, a jsoup-based lynx-style HTML renderer, and previews from HTML when there is no text/plain part (roadmap M6.12; recommendations B20, D17, D23)

- Alpine-scale tests on a 244k-message folder and 1000 folders, and the index frame drops (roadmap M7.1-M7.2; recommendations R-P2-3, B13)

- Move UI strings into strings.xml (roadmap M7.5; recommendations W10)

- TLS and SMTP AUTH, then other IMAP servers by capability (roadmap M8.1-M8.2; sandbox/research/liveimap-roadmap-20261003.md)

- Watch more folders for new mail, each with its own IDLE connection within a connection budget (roadmap M8.6; recommendations D22)

- JMAP accounts on Cyrus 3.x, chosen by the server's JMAP capability (roadmap M9; sandbox/research/liveimap-roadmap-20261003.md)

- A long press on an icon or menu action shows a short explanation of that control. Do this later with the expanded manual, once the UI is stable, so the text does not go stale.

- Move OpenSSL from 1.1.1w to 3.x before a public release. IMAP TLS may ship on 1.1.1w (roadmap M4.3; recommendations section 12).
