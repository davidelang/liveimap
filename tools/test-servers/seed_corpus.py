#!/usr/bin/env python3
"""Seed corpus-v1 (beside this script, from make_corpus.generate) into one RUNNING lab server for the
liveimap account: folders created with the server's delimiter, APPEND with manifest flags and INTERNALDATE.
Skips when INBOX already holds the seed marker. Usage: seed_corpus.py HOST PORT [--tls|--starttls] [--user U]. Lab servers only."""
import base64, csv, imaplib, os, ssl, sys
from datetime import datetime
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import make_corpus, lab
CORPUS = os.path.join(HERE, "corpus-v1")

def mutf7(s):
    out, buf = [], ""
    def flush():
        nonlocal buf
        if buf:
            out.append("&" + base64.b64encode(buf.encode("utf-16-be")).decode().rstrip("=").replace("/", ",") + "-")
            buf = ""
    for ch in s:
        if 0x20 <= ord(ch) <= 0x7e:
            flush(); out.append("&-" if ch == "&" else ch)
        else:
            buf += ch
    flush()
    return "".join(out)

def q(name):
    return '"' + name.replace("\\", "\\\\").replace('"', '\\"') + '"'

def main(host, port, tls):
    assert host == lab.LAB_HOST, "lab host only"
    ctx = ssl.create_default_context(cadata=open(os.path.join(lab.GEN, "ca", "ca.der"), "rb").read())
    ctx.check_hostname = False
    if tls:
        ctx = ssl.create_default_context(cadata=open(os.path.join(lab.GEN, "ca", "ca.der"), "rb").read())
        ctx.check_hostname = False
        m = imaplib.IMAP4_SSL(host, port, ssl_context=ctx)
    else:
        m = imaplib.IMAP4(host, port)
        if "--starttls" in sys.argv:
            m.starttls(ssl_context=ctx)
    m.login(sys.argv[sys.argv.index("--user") + 1] if "--user" in sys.argv else lab.USER, lab.PASS)  # zimbra: lab.user_for
    # The acceptance append/flag folder: top level, or under the personal prefix where top level is refused
    # (courier, older Cyrus: "INBOX."). An error just means it exists or that name is not allowed here.
    prefix = ""
    try:
        typ, ns = m.namespace() if "NAMESPACE" in m.capabilities else ("NO", [b""])  # GreenMail has no NAMESPACE
        prefix = ns[0].decode().split('(("', 1)[1].split('"', 1)[0]
    except (IndexError, AttributeError):
        pass
    for name in ["LiveIMAP-Test"] + ([prefix + "LiveIMAP-Test"] if prefix else []):
        try:
            m.create(name)
        except imaplib.IMAP4.error:
            pass
    m.select("INBOX")
    typ, d = m.search(None, "SUBJECT", '"LiveIMAP seed corpus-v1"')
    if d and d[0].split():
        print(f"{host}:{port} already seeded"); m.logout(); return
    typ, d = m.list('""', '"INBOX"')
    delim = "/"
    if d and d[0]:
        parts = d[0].decode().split('"')
        if len(parts) >= 3 and len(parts[1]) == 1: delim = parts[1]
    rows = [r for r in csv.DictReader(open(os.path.join(CORPUS, "manifest.tsv"), encoding="utf-8"), delimiter="\t") if r["user"] == lab.USER]
    made, n = set(), 0
    for r in rows:
        folder = r["folder"]
        name = mutf7(folder.replace("/", delim)) if folder != "INBOX" else "INBOX"
        if folder != "INBOX" and name not in made:
            parts = folder.split("/")
            for i in range(1, len(parts) + 1):
                p = mutf7(delim.join(parts[:i]))
                if p not in made:
                    m.create(q(p)); made.add(p)
        path = os.path.join(CORPUS, lab.USER, make_corpus.slug_for(folder), r["file"])
        data = open(path, "rb").read().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        flags = "" if r["flags"] == "-" else "(" + r["flags"] + ")"
        when = datetime.fromisoformat(r["internaldate"].replace("Z", "+00:00"))
        typ, resp = m.append(q(name), flags or None, imaplib.Time2Internaldate(when), data)
        if typ != "OK": print("append failed", folder, r["file"], resp); continue
        n += 1
    m.logout()
    print(f"{host}:{port} seeded {n}/{len(rows)} messages, delimiter {delim!r}")

if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), "--tls" in sys.argv)
