---
title: Setup
---

# Setup

## Guided

With no account yet, Add account opens the wizard. Guided setup is the email screen, the incoming server, and the sending server.

![Email](images/phone/setup-email.jpg)

![Incoming](images/phone/setup-incoming.jpg)

![Sending](images/phone/setup-sending.jpg)

The example is the test account `liveimap@10.2.3.133`. The password is `liveimap-test`. There is no hostname and no personal name. Full name stays blank. The username is `liveimap`. IMAP is `10.2.3.133` port 20000. SMTP is `10.0.0.1` port 25. TLS is off, a clear-text password is allowed, and there is no SMTP login. The folders and the inbox are the corpus in the `cyrus-3137` image.

Finish on the wizard would leave TLS implicit, so the account is saved from Advanced after those rows are set. The walk does not tap Test or Finish. The shots do not require a message to be delivered.

## Advanced

When an account is already on the phone, Add account opens the advanced editor and skips the wizard. With no account yet, Advanced on the wizard opens the same editor and does not create the account until Save.

![Advanced](images/phone/setup-advanced.jpg)

On that editor, before Save: IMAP host `10.2.3.133`, IMAP port 20000, TLS None, Allow a clear-text password on, SMTP host `10.0.0.1`, SMTP port 25, SMTP TLS None, and No SMTP login. Save is what stores the account.
