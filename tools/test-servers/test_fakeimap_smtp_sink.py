#!/usr/bin/env python3
"""Pinned fakeimap as a no-auth SMTP sink (replaces ai-fake-smtp.py)."""
import os, smtplib, socket, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fakeimap_pin

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
WORK = os.path.join(REPO, "sandbox", "tmp-rescue", "smtp-sink-test")


def fail(text):
    sys.exit(text)


def main():
    fakeimap_pin.require_ready()
    os.makedirs(WORK, exist_ok=True)
    srv = fakeimap_pin.start_smtp_sink(
        WORK,
        [
            {"addr": "127.0.0.1:0", "dsn": True},
            {"addr": "127.0.0.1:0", "dsn": False},
        ],
        os.path.join(WORK, "err.txt"),
        log_path=os.path.join(WORK, "log.jsonl"),
    )
    try:
        listeners = srv.bound.get("smtp_listeners") or []
        if len(listeners) != 2:
            # fall back to status
            st = srv.control({"op": "status"})
            listeners = st.get("smtp_listeners") or listeners
        if len(listeners) != 2:
            fail(f"expected two smtp listeners, bound={srv.bound}")
        dsn_addr, plain_addr = listeners[0], listeners[1]
        def port(addr):
            return int(addr.rsplit(":", 1)[1])

        # DSN listener advertises DSN and accepts mail with no AUTH.
        with smtplib.SMTP("127.0.0.1", port(dsn_addr), timeout=5) as s:
            code, resp = s.ehlo("test.example")
            feats = s.esmtp_features
            if "dsn" not in feats:
                fail(f"DSN listener EHLO missing DSN: {feats}")
            if "auth" in feats:
                fail(f"sink must not advertise AUTH: {feats}")
            # docmd keeps this runnable on Python builds whose smtplib has no mail(..., dsn=).
            code, _ = s.docmd("MAIL FROM:<a@b.example> RET=HDRS ENVID=abc123")
            if code != 250:
                fail(f"MAIL FROM {code}")
            code, _ = s.docmd("RCPT TO:<user@example.test> NOTIFY=SUCCESS,FAILURE")
            if code != 250:
                fail(f"RCPT TO {code}")
            s.data(b"From: a@b.example\r\nSubject: sink test\r\n\r\nhello\r\n")

        # Non-DSN listener omits DSN.
        with smtplib.SMTP("127.0.0.1", port(plain_addr), timeout=5) as s:
            s.ehlo("test.example")
            if "dsn" in s.esmtp_features:
                fail("plain listener advertised DSN")
            s.mail("c@d.example")
            s.rcpt("nobody@example.test")
            s.data(b"From: c@d.example\r\nSubject: no dsn\r\n\r\nbye\r\n")

        # 220 banner on the first bind.
        first = srv.bound.get("smtp") or listeners[0]
        sock = socket.create_connection(("127.0.0.1", port(first)), 5)
        banner = sock.recv(200)
        sock.close()
        if not banner.startswith(b"220"):
            fail(f"bad banner {banner!r}")

        msgs = srv.control({"op": "list-smtp"}).get("messages") or []
        if len(msgs) != 2:
            fail(f"list-smtp {msgs}")
        if msgs[0].get("from") not in ("a@b.example", "<a@b.example>"):
            # accept either form
            frm = (msgs[0].get("from") or "")
            if "a@b.example" not in frm:
                fail(f"first from {msgs[0]}")
    finally:
        srv.close()
    print("ok")


if __name__ == "__main__":
    main()
