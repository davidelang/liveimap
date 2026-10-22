#!/usr/bin/env python3
"""LiveIMAP lab helper: record CAPABILITY and seed test folders on a LAB server.

Usage:
  lab_imap.py caps HOST PORT [USER PASSWORD] [--allow HOST:PORT ...]
  lab_imap.py seed HOST PORT [USER PASSWORD] [--count N] [--big N] [--allow HOST:PORT ...]
  lab_imap.py check
  Add --record FILE [--server-name N] [--commit C] [--suite S] to caps or seed
  to write the shared JSON-lines recording (tools/test-servers/rec.py).

Safe to re-run: seed only adds messages until each folder holds the target count.
"""
import argparse, imaplib, re, socket, sys
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from email.utils import format_datetime, make_msgid

TEST_ROOT = "LiveIMAP-Test"
_DOTTED_IPV4 = re.compile(r"(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])")


def require_allowed(host, port, allow):
    pair = f"{host}:{port}"
    if pair in allow:
        return
    sys.exit(f"refusing {host}:{port}: not on the allowlist")


RECORDER = None
TLS_MODE = ""  # "", "Implicit" or "StartTls" (from --tls); lab servers only, certificate not verified


class _Rec:
    """Mixin: record every byte imaplib sends and receives."""

    def send(self, data):
        if RECORDER:
            RECORDER.sent(data)
        return super().send(data)

    def readline(self):
        line = super().readline()
        if RECORDER and line:
            n = RECORDER.received_line(line)
            if n is not None:
                self._lit_left = n
        return line

    def read(self, size):
        data = super().read(size)
        if RECORDER and data:
            RECORDER.literal("S", data)
        return data


class RecIMAP4(_Rec, imaplib.IMAP4):
    pass


class RecIMAP4_SSL(_Rec, imaplib.IMAP4_SSL):
    pass


def connect(host, port, user, password, allow):
    require_allowed(host, port, allow)
    tls = TLS_MODE == "Implicit" or (not TLS_MODE and str(port).endswith("993"))
    if RECORDER:
        RECORDER.event("connect", f"{host}:{port} {'Implicit' if tls else TLS_MODE or 'None'}")
    import lab
    if tls:
        m = RecIMAP4_SSL(host, port, ssl_context=lab._lab_ssl())
    else:
        m = RecIMAP4(host, port)
        if TLS_MODE == "StartTls":
            if "STARTTLS" not in m.capabilities:
                raise SystemExit("server does not offer STARTTLS")
            m.starttls(lab._lab_ssl())
    pre = m.capabilities
    try:
        m.login(user, password)
    except Exception as exc:
        arg = exc.args[0] if exc.args else exc
        text = arg.decode("utf-8", "replace") if isinstance(arg, bytes) else str(arg)
        if password:
            text = text.replace(password, "***")
        sys.exit(text)
    typ, data = m.capability()
    post = data[0].decode() if data and data[0] else ""
    return m, " ".join(pre), post


def caps(a, allow):
    m, pre, post = connect(a.host, a.port, a.user, a.password, allow)
    print(f"server {a.host}:{a.port}")
    print(f"greeting {m.welcome.decode(errors='replace')}")
    print(f"pre-login  {pre}")
    print(f"post-login {post}")
    m.logout()


def delimiter(m):
    typ, data = m.list('""', '""')
    line = data[0].decode() if data and data[0] else ''
    parts = line.split('"')
    return parts[1] if len(parts) > 2 else "/"


def count(m, box):
    typ, data = m.select(f'"{box}"', readonly=True)
    return int(data[0]) if typ == "OK" else -1


UTF8_LEAF = "T\u00ebst-\u00dcn\u00ef"  # "Tëst-Ünï": non-ASCII folder for the UTF8=ACCEPT / modified UTF-7 step


def mutf7(text):
    """RFC 3501 modified UTF-7 mailbox name."""
    import base64
    out, run = [], []
    def flush():
        if run:
            b = base64.b64encode("".join(run).encode("utf-16-be")).decode().rstrip("=").replace("/", ",")
            out.append("&" + b + "-"); run.clear()
    for ch in text:
        if 0x20 <= ord(ch) <= 0x7e:
            flush(); out.append("&-" if ch == "&" else ch)
        else:
            run.append(ch)
    flush()
    return "".join(out)


def ensure(m, box):
    if count(m, box) < 0:
        m.create(f'"{box}"')


def message(i, kind):
    msg = EmailMessage()
    msg["From"] = f"Lab Sender {i % 7} <sender{i % 7}@example.test>"
    msg["To"] = "liveimap@example.test"
    if i % 5 == 0:
        msg["Cc"] = "cc-person@example.test"
    # Later messages are newer, so replies are dated after the message they answer.
    msg["Date"] = format_datetime(datetime.now(timezone.utc) - timedelta(minutes=10 * max(0, 5000 - i)))
    root = i - (i % 4)
    # A thread's root carries the Message-ID its replies reference, so THREAD REFERENCES links them (no placeholder).
    msg["Message-ID"] = f"<thread-{root}@lab.liveimap.test>" if i % 4 == 0 else make_msgid(domain="lab.liveimap.test")
    if i % 4:
        msg["In-Reply-To"] = f"<thread-{root}@lab.liveimap.test>"
        msg["References"] = f"<thread-{root}@lab.liveimap.test>"
        msg["Subject"] = f"Re: Lab thread {root}"
    else:
        msg["Subject"] = f"Lab thread {root}"
    if kind == "latin1":
        del msg["Subject"]; msg["Subject"] = f"Caf\u00e9 {i}"
        msg.set_content(f"Gr\u00fc\u00dfe aus dem Labor, Nachricht {i}.\n", charset="iso-8859-1")
    elif kind == "sjis":
        del msg["Subject"]; msg["Subject"] = f"\u65e5\u672c\u8a9e {i}"
        msg.set_content(f"\u3053\u3093\u306b\u3061\u306f {i}\n", charset="shift_jis")
    elif kind == "html":
        msg["List-Id"] = "Lab list <lab-list.liveimap.test>"  # "always show images from this list"
        msg.set_content("<html><body><p>HTML only <b>message</b> %d.</p><ul><li>one</li><li>two</li></ul>"
                        "<blockquote>quoted</blockquote><a href=\"https://example.test/%d\">link</a>"
                        "<img src=\"https://example.test/pixel-%d.png\" alt=\"remote\"></body></html>" % (i, i, i),
                        subtype="html")
    elif kind == "attach":
        msg.set_content(f"Message {i} with an attachment.\n")
        msg.add_attachment(b"%PDF-1.4\n% lab\n", maintype="application", subtype="pdf", filename=f"lab-{i}.pdf")
    else:
        msg.set_content(f"Plain lab message {i}.\n" + ("A long line " * 20 + "\n") * (i % 3))
    return msg.as_bytes()


def deep_message(levels):
    """A message with `levels` nested multipart/mixed parts around one text part, built iteratively (the email
    package recurses per level). Exercises the nesting limits: app 64-level window, libetpan 128 (LAB_DEEP=1)."""
    out = [f"From: Lab Sender <sender0@example.test>\r\nTo: liveimap@example.test\r\n"
           f"Subject: Deep nesting {levels}\r\nMessage-ID: <deep-{levels}@lab.liveimap.test>\r\n"
           f"Date: {format_datetime(datetime.now(timezone.utc))}\r\nMIME-Version: 1.0\r\n"]
    for k in range(levels):
        out.append(f'Content-Type: multipart/mixed; boundary="deep{k}"\r\n\r\n--deep{k}\r\n')
    out.append(f"Content-Type: text/plain; charset=us-ascii\r\n\r\nInnermost text at level {levels}.\r\n")
    for k in reversed(range(levels)):
        out.append(f"--deep{k}--\r\n")
    return "".join(out).encode()


def fake_apk():
    import io, zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00lab fake manifest")
        z.writestr("classes.dex", b"dex\n035\x00lab")
    return buf.getvalue()


# (subject, filename, declared maintype/subtype, content, should the app warn)
SPOOFS = [
    ("Spoof honest pdf", "report.pdf", ("application", "pdf"), b"%PDF-1.4\n% lab honest\n", False),
    ("Spoof apk as pdf", "invoice.pdf", ("application", "pdf"), None, True),
    ("Spoof html as jpeg", "cat.jpg", ("image", "jpeg"), b"<!DOCTYPE html><html><body>lab</body></html>", True),
    ("Spoof svg as png", "logo.png", ("image", "png"), b'<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"><script>1</script></svg>', True),
]


def spoof_message(subject, filename, mime, content):
    msg = EmailMessage()
    msg["From"] = "Lab Sender <sender0@example.test>"
    msg["To"] = "liveimap@example.test"
    msg["Subject"] = subject
    msg["Message-ID"] = make_msgid(domain="lab.liveimap.test")
    msg["Date"] = format_datetime(datetime.now(timezone.utc))
    msg.set_content(f"{subject}: the attachment {filename} claims {mime[0]}/{mime[1]}.\n")
    msg.add_attachment(content if content is not None else fake_apk(), maintype=mime[0], subtype=mime[1], filename=filename)
    return msg.as_bytes()


def _listed(m):
    typ, ns = ("NO", None)
    if "NAMESPACE" in m.capabilities:
        typ, ns = m.namespace()
    field = ns[0] if typ == "OK" and ns and ns[0] else b""
    _typ, data = m.list('""', '""')
    line = data[0] if data and data[0] else b""
    return field, line


def seed_spoof(a, allow):
    import lab
    m, _, _ = connect(a.host, a.port, a.user, a.password, allow)
    field, line = _listed(m)
    box, target = lab.suite_mailboxes(field, line)[5]
    rows = lab.fixture_fields("spoof")
    ensure(m, box)
    for i in range(count(m, box), target):
        subject, filename = rows[i][0], rows[i][1]
        mime, content = SPOOFS[i][2], SPOOFS[i][3]
        typ, _ = m.append(f'"{box}"', "(\\Seen)", None, spoof_message(subject, filename, mime, content))
        print(f"append {subject}: {typ}")
    print(f"{box}: {count(m, box)} messages (target {target})")
    m.logout()


def seed_deep(a, allow):
    import lab
    m, _, _ = connect(a.host, a.port, a.user, a.password, allow)
    field, line = _listed(m)
    box, target = lab.suite_mailboxes(field, line)[4]
    levels = [int(fields[0]) for fields in lab.fixture_fields("deep")]
    ensure(m, box)
    for i in range(count(m, box), target):
        typ, data = m.append(f'"{box}"', "(\\Seen)", None, deep_message(levels[i]))
        print(f"append {levels[i]} levels: {typ} {data[0][:80] if data and data[0] else ''}")
    print(f"{box}: {count(m, box)} messages (target {target})")
    m.logout()


def seed(a, allow):
    import lab
    bad = lab.require_suite_count(a.count)
    if bad:
        sys.exit(bad)
    m, _, _ = connect(a.host, a.port, a.user, a.password, allow)
    field, line = _listed(m)
    boxes = list(lab.suite_mailboxes(field, line)[:4])
    kinds = [fields[0] for fields in lab.fixture_fields("kind")]
    if a.big:
        root = boxes[0][0]
        sub = boxes[1][0]
        delim = sub[len(root):-len("Sub")]
        boxes.append((root + delim + "Big", a.big))
    for box, target in boxes:
        ensure(m, box)
        have = count(m, box)
        for i in range(have, target):
            flags = "(\\Seen)" if i % 3 else "()"
            if i % 11 == 0:
                flags = "(\\Flagged)"
            m.append(f'"{box}"', flags, None, message(i, kinds[i % len(kinds)]))
        print(f"{box}: {count(m, box)} messages (target {target})")
    m.logout()


def _network_forbidden(*_args, **_kwargs):
    raise AssertionError("network call")


def check():
    with open(__file__, encoding="utf-8") as fh:
        if _DOTTED_IPV4.search(fh.read()):
            sys.exit("dotted IPv4 address present")
    socket.socket = _network_forbidden
    socket.getaddrinfo = _network_forbidden
    socket.create_connection = _network_forbidden
    imaplib.IMAP4 = _network_forbidden
    imaplib.IMAP4_SSL = _network_forbidden
    allow = {"lab.example:10143"}
    require_allowed("lab.example", 10143, allow)
    for host, port in (("other.example", 10143), ("lab.example", 10993)):
        expect = f"refusing {host}:{port}: not on the allowlist"
        try:
            require_allowed(host, port, allow)
        except SystemExit as exc:
            if exc.code != expect:
                sys.exit(f"bad refusal: {exc.code}")
        else:
            sys.exit(f"missing refusal for {host}:{port}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["caps", "seed", "seed-deep", "seed-spoof", "check"])
    p.add_argument("host", nargs="?")
    p.add_argument("port", nargs="?", type=int)
    p.add_argument("user", nargs="?")
    p.add_argument("password", nargs="?")
    p.add_argument("--allow", action="append", default=[])
    p.add_argument("--count", type=int, default=60)
    p.add_argument("--big", type=int, default=0)
    p.add_argument("--record")
    p.add_argument("--server-name", default="")
    p.add_argument("--commit", default="")
    p.add_argument("--suite", default="")
    p.add_argument("--tls", default="")
    global TLS_MODE
    a = p.parse_args()
    TLS_MODE = a.tls if a.tls in ("Implicit", "StartTls") else ""
    if a.action == "check":
        if a.host is not None or a.port is not None or a.user is not None or a.password is not None:
            p.error("check takes no host, port, user, or password")
        check()
        return
    if a.host is None or a.port is None:
        p.error("host and port are required")
    if a.user is None or a.password is None:
        import lab
        if a.user is None:
            a.user = lab.USER
        if a.password is None:
            a.password = lab.PASS
    allow = set(a.allow)
    global RECORDER
    if a.record:
        from rec import Recorder
        tls = a.tls or ("Implicit" if str(a.port).endswith("993") else "None")
        RECORDER = Recorder(a.record, {"app": "lab_imap", "commit": a.commit, "tool": "lab_imap",
            "server": {"name": a.server_name, "host": a.host, "port": a.port, "tls": tls},
            "protocol": "imap", "suite": a.suite or a.action})
    try:
        if a.action == "caps":
            caps(a, allow)
        elif a.action == "seed-deep":
            seed_deep(a, allow)
        elif a.action == "seed-spoof":
            seed_spoof(a, allow)
        else:
            seed(a, allow)
    finally:
        if RECORDER:
            RECORDER.close()


if __name__ == "__main__":
    main()
