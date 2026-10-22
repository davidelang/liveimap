#!/usr/bin/env python3
"""Seed corpus-v1 (generated/corpus-v1, from make_corpus.generate) into one RUNNING lab server for the
liveimap account: folders created with the server's delimiter, APPEND with manifest flags and INTERNALDATE.
Skips when INBOX already holds the seed marker. Usage: seed_corpus.py HOST PORT [--tls|--starttls] [--user U]. Lab servers only."""
import base64, csv, imaplib, os, sys
from datetime import datetime
from pathlib import Path
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import make_corpus, lab
CORPUS = os.path.join(HERE, "generated", "corpus-v1")

def ensure_corpus():
    manifest = os.path.join(CORPUS, "manifest.tsv")
    if not os.path.exists(manifest):
        accounts, folders = make_corpus.load_tables()
        make_corpus.generate(Path(CORPUS), accounts, folders)
    if not os.path.exists(manifest):
        sys.exit("manifest.tsv is missing")

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

def listed(m):
    """Mailbox names from LIST "" "*" (quoted or atom; literals as sent)."""
    typ, data = m.list('""', '*')
    if typ != "OK":
        raise SystemExit(f"LIST failed: {data}")
    names, i = set(), 0
    while i < len(data or []):
        item = data[i]
        if isinstance(item, tuple):  # literal name: (b'(\\flags) "/" {n}', b'name')
            names.add(item[1].decode("utf-8", "replace"))
        elif item:
            line = item.decode("utf-8", "replace")
            if line.endswith('"'):
                names.add(line[:-1].rsplit('"', 1)[1].replace('\\"', '"').replace("\\\\", "\\"))
            else:
                names.add(line.rsplit(" ", 1)[1])
        i += 1
    return names


def main(host, port, tls):
    ensure_corpus()
    assert host == lab.LAB_HOST, "lab host only"
    if tls:
        m = imaplib.IMAP4_SSL(host, port, ssl_context=lab._lab_ssl())
    else:
        m = imaplib.IMAP4(host, port)
        if "--starttls" in sys.argv:
            m.starttls(ssl_context=lab._lab_ssl())
    login = sys.argv[sys.argv.index("--user") + 1] if "--user" in sys.argv else lab.USER  # bare lab user unless --user
    m.login(login, lab.PASS)
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
    # Every folders.tsv folder, in file order (parents before children), before any APPEND. Empty parents
    # (Archive, Lists, Noselect-parent) are real rows; superiors with no row (Deep) are left to the server.
    existing = listed(m)  # server-made folders (Stalwart, Zimbra: Sent, Drafts, Trash, Junk) are kept as they are
    for f in make_corpus.read_tsv(make_corpus.SEED / "folders.tsv"):
        if f["user"] == lab.USER and f["folder"] != "INBOX":
            p = prefix + mutf7(f["folder"].replace("/", delim))
            if p in existing:
                made.add(p)
                continue
            typ, resp = m.create(q(p))
            if typ != "OK":
                raise SystemExit(f"CREATE {p} failed: {resp}")
            made.add(p)
    for r in rows:
        folder = r["folder"]
        name = prefix + mutf7(folder.replace("/", delim)) if folder != "INBOX" else "INBOX"
        if folder != "INBOX" and name not in made:
            raise SystemExit(f"manifest folder {folder} has no folders.tsv row")
        path = os.path.join(CORPUS, lab.USER, make_corpus.slug_for(folder), r["file"])
        data = open(path, "rb").read().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        flags = "" if r["flags"] == "-" else "(" + r["flags"] + ")"
        when = datetime.fromisoformat(r["internaldate"].replace("Z", "+00:00"))
        typ, resp = m.append(q(name), flags or None, imaplib.Time2Internaldate(when), data)
        if typ != "OK":
            raise SystemExit(f"append failed {folder} {r['file']} {resp}")
        n += 1
    m.logout()
    print(f"{host}:{port} seeded {n}/{len(rows)} messages, delimiter {delim!r}")
    others = sorted({f["user"] for f in make_corpus.read_tsv(make_corpus.SEED / "folders.tsv")} - {lab.USER})
    pw = {a["user"]: a["password"] for a in make_corpus.read_tsv(make_corpus.SEED / "accounts.tsv")}
    for user in others:
        seed_inbox_user(host, port, tls, prefix_login(login, user), pw[user], user)


def prefix_login(login, user):
    """Same login form as the lab user: a --user with a domain keeps that domain."""
    return user + login[len(lab.USER):] if login.startswith(lab.USER) else user


def seed_inbox_user(host, port, tls, login, password, user):
    """The secondary accounts hold INBOX only (folders.tsv)."""
    if tls:
        m = imaplib.IMAP4_SSL(host, port, ssl_context=lab._lab_ssl())
    else:
        m = imaplib.IMAP4(host, port)
        if "--starttls" in sys.argv:
            m.starttls(ssl_context=lab._lab_ssl())
    if (login + password).isascii():
        m.login(login, password)
    else:
        m.authenticate("PLAIN", lambda _c: ("\0" + login + "\0" + password).encode("utf-8"))
    typ, d = m.select("INBOX")
    if int(d[0]):
        print(f"{host}:{port} {user} already has {int(d[0])} messages"); m.logout(); return
    rows = [r for r in csv.DictReader(open(os.path.join(CORPUS, "manifest.tsv"), encoding="utf-8"), delimiter="\t") if r["user"] == user]
    n = 0
    for r in rows:
        if r["folder"] != "INBOX":
            raise SystemExit(f"{user}: only INBOX is seeded for secondary accounts, manifest has {r['folder']}")
        path = os.path.join(CORPUS, user, make_corpus.slug_for("INBOX"), r["file"])
        data = open(path, "rb").read().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        flags = "" if r["flags"] == "-" else "(" + r["flags"] + ")"
        when = datetime.fromisoformat(r["internaldate"].replace("Z", "+00:00"))
        typ, resp = m.append("INBOX", flags or None, imaplib.Time2Internaldate(when), data)
        if typ != "OK":
            raise SystemExit(f"{user}: append failed {r['file']} {resp}")
        n += 1
    m.logout()
    print(f"{host}:{port} {user} seeded {n}/{len(rows)} messages")

if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), "--tls" in sys.argv)
