# TODO


# Future work

- Fake IMAP server with Cyrus 2.2 and 3.x profiles for tests, and a fixed test account (roadmap M3.3; recommendations R-P2-5)

- Client certificates, KeyChain: verify on a real device with a user-installed certificate via the chooser (lab runs use an AndroidKeyStore entry, same signing path, no chooser). RSA-PSS uses raw RSA (NoPadding decrypt); a KeyChain key whose authorizations exclude raw RSA fails TLS 1.3 with RSA keys.

- [ ] Design: subthread drill-down in the message index. Threads now have no depth limit (iterative everywhere);
  the index indents up to 6 levels and shows the depth number beyond. Needed: a way to open a subthread as its own
  view (entry point on deep rows, filter-in-place vs. new screen, back navigation, how the depth number resets).
  Owner: David to decide the UX; then implement in MessageIndexScreen/IndexModel. (2026-10-11)

- Add attachments for sending and for receiving. The reader already lists and opens received parts, and a forward can resend those parts. Also check the plain-text version of an HTML message on send: compose sends text/plain only and does not add a text/html part with a text/plain alternative.

- release shrinking stays on; the keep check is `:app:checkArm64ReleaseKeeps`; measuring startup needs a device and is not a coder step.
