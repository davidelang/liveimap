#!/bin/sh
# Write generated/<server>/seed-snapshot.tar and seed-snapshot.stamp.
# GreenMail has no tar. This script is not run by the reseed commit.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GEN="$DIR/generated"
if [ $# -ne 1 ] || [ -z "${1:-}" ]; then
  echo "usage: tools/test-servers/seed-snapshot.sh <server>" >&2
  exit 1
fi
server=$1

case "$server" in
  greenmail|greenmail-16|greenmail-214|greenmail-dev)
    echo "no seed-snapshot.tar for $server" >&2
    exit 0
    ;;
esac

case "$server" in
  cyrus-22|cyrus-25|cyrus-24|cyrus-36)
    paths="/var/spool/cyrus/mail /var/lib/cyrus /var/spool/sieve" ;;
  cyrus-3124|cyrus-3137)
    paths="/var/spool/cyrus/mail /var/lib/cyrus /var/spool/cyrus/sieve" ;;
  dovecot-10|dovecot-12|dovecot-22|dovecot-245|dovecot-dev)
    paths="/srv/vmail" ;;
  courier-50)
    paths="/var/vmail" ;;
  courier-606|uwimap)
    paths="/var/mail" ;;
  stalwart-0155)
    paths="/opt/stalwart/data" ;;
  stalwart|stalwart-01625|stalwart-dev)
    paths="/var/lib/stalwart" ;;
  zimbra-883)
    paths="/opt/zimbra/store /opt/zimbra/index /opt/zimbra/db/data /opt/zimbra/data/ldap/mdb/db /opt/zimbra/redolog" ;;
  *)
    echo "unknown server $server" >&2
    exit 1
    ;;
esac
if [ "$server" = "uwimap" ]; then
  paths="/var/mail /home"
fi

name=liveimap-$server
if ! docker inspect "$name" >/dev/null 2>&1; then
  echo "missing container $name" >&2
  exit 1
fi

flag=$(mktemp)
printf 'no\n' > "$flag"
docker cp "$flag" "$name":/.liveimap-no-reseed
rm -f "$flag"
if docker inspect -f '{{.State.Running}}' "$name" | grep -qx true; then
  docker stop -t 60 "$name" >/dev/null
fi
docker start "$name" >/dev/null
up=$(awk -F '\t' -v c="$name" '$1 == c { print $2; exit }' "$DIR/up.tsv")
if [ -n "$up" ]; then
  docker exec "$name" sh -c "$up"
fi

python3 - "$DIR" "$server" "$name" <<'PY'
import csv, hashlib, os, re, socket, ssl, subprocess, sys, time
import imaplib
from datetime import datetime
from pathlib import Path

here, server, name = sys.argv[1], sys.argv[2], sys.argv[3]
gen = os.path.join(here, "generated")
corpus = os.path.join(gen, "corpus-v1")
accounts = os.path.join(here, "seed", "accounts.tsv")
folders = os.path.join(here, "seed", "folders.tsv")

def lab_host():
    host = os.environ.get("LAB_BIND", "")
    path = os.path.join(here, "host.env")
    if os.path.isfile(path):
        for line in open(path, encoding="utf-8", errors="replace"):
            if line.startswith("LAB_BIND=") and not host:
                host = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not host:
        sys.exit("LAB_BIND missing")
    return host

def imap_port():
    path = os.path.join(gen, "local-ports.tsv")
    for line in open(path, encoding="utf-8", errors="replace"):
        f = line.rstrip("\n").split("\t")
        if len(f) == 4 and f[3] == name and f[0].endswith("-imap") and f[1].isdigit():
            return int(f[1])
    sys.exit("no imap port for " + name)

host = lab_host()
port = imap_port()
deadline = time.time() + 180
while time.time() < deadline:
    try:
        c = socket.create_connection((host, port), 3)
        c.settimeout(5)
        g = c.recv(200)
        c.close()
        if g.startswith(b"* OK") or g.startswith(b"* PREAUTH"):
            break
    except Exception:
        time.sleep(2)
else:
    sys.exit("no IMAP greeting for " + server)

import base64

def mutf7(s):
    out, buf = [], ""
    def flush():
        nonlocal buf
        if buf:
            out.append("&" + base64.b64encode(buf.encode("utf-16-be")).decode().rstrip("=").replace("/", ",") + "-")
            buf = ""
    for ch in s:
        if 0x20 <= ord(ch) <= 0x7e:
            flush()
            out.append("&-" if ch == "&" else ch)
        else:
            buf += ch
    flush()
    return "".join(out)

def q(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'

def login(user, password):
    if server == "zimbra-883":
        user = user + "@zimbra.zcs-foss.test"
    ctx = ssl.create_default_context()
    ca = os.path.join(gen, "ca", "ca.der")
    if os.path.isfile(ca):
        ctx.load_verify_locations(cadata=open(ca, "rb").read())
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    m = imaplib.IMAP4(host, port)
    try:
        m.login(user, password)
        return m
    except imaplib.IMAP4.error:
        try:
            m.starttls(ssl_context=ctx)
            m.login(user, password)
            return m
        except Exception:
            try:
                m.logout()
            except Exception:
                pass
            raise

users = []
with open(accounts, newline="", encoding="utf-8") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("profile") == "base" and row.get("user") and row.get("password"):
            users.append((row["user"], row["password"]))

manifest = list(csv.DictReader(open(os.path.join(corpus, "manifest.tsv"), encoding="utf-8"), delimiter="\t"))
folder_rows = list(csv.DictReader(open(folders, encoding="utf-8"), delimiter="\t"))

def slug(folder):
    return folder.replace("/", "_")

def append_corpus():
    for user, password in users:
        m = login(user, password)
        try:
            m.select("INBOX")
            delim = "/"
            typ, listed = m.list('""', '"INBOX"')
            if listed and listed[0]:
                parts = listed[0].decode(errors="replace").split('"')
                if len(parts) >= 3 and len(parts[1]) == 1:
                    delim = parts[1]
            made = set()
            for row in folder_rows:
                if row["user"] != user or row["folder"] == "INBOX":
                    continue
                segs = row["folder"].split("/")
                for i in range(1, len(segs) + 1):
                    p = mutf7(delim.join(segs[:i]))
                    if p in made:
                        continue
                    try:
                        m.create(q(p))
                    except imaplib.IMAP4.error:
                        pass
                    made.add(p)
            try:
                m.create("LiveIMAP-Test")
            except imaplib.IMAP4.error:
                pass
            for row in manifest:
                if row["user"] != user:
                    continue
                folder = row["folder"]
                name_box = "INBOX" if folder == "INBOX" else mutf7(folder.replace("/", delim))
                path = os.path.join(corpus, user, slug(folder), row["file"])
                data = open(path, "rb").read().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
                flags = None if row["flags"] == "-" else "(" + row["flags"] + ")"
                when = datetime.fromisoformat(row["internaldate"].replace("Z", "+00:00"))
                typ, resp = m.append(q(name_box), flags, imaplib.Time2Internaldate(when), data)
                if typ != "OK":
                    sys.exit("append failed " + user + " " + folder)
        finally:
            try:
                m.logout()
            except Exception:
                pass

def listed_names(mail):
    typ, data = mail.list()
    found = set()
    delim = "/"
    if typ != "OK":
        return found, delim
    for row in data or []:
        if not row:
            continue
        line = row.decode("utf-8", "replace") if isinstance(row, bytes) else str(row)
        parts = line.split('"')
        if len(parts) >= 3 and len(parts[1]) == 1:
            delim = parts[1]
            if len(parts) > 3:
                found.add(parts[-2])
            else:
                name = parts[-1].strip()
                if name:
                    found.add(name)
        else:
            bits = line.split()
            if bits:
                found.add(bits[-1])
    return found, delim

def mailbox_for(folder, user, delim, found):
    if folder == "INBOX":
        cands = ["INBOX"]
    else:
        enc = mutf7(folder.replace("/", delim))
        cands = [
            enc,
            "INBOX" + delim + enc,
            "user" + delim + user + delim + enc,
        ]
    for cand in cands:
        if cand in found:
            return cand
    return ""

def flag_key(words):
    kept = [w for w in words if w.lower() != "\\recent"]
    return tuple(sorted(kept, key=str.lower))

def fetched_messages(mail):
    typ, data = mail.uid("fetch", "1:*", "(UID FLAGS BODY.PEEK[])")
    if typ != "OK" or not data or data == [None]:
        return []
    msgs = []
    for item in data:
        if item is None or item == b")":
            continue
        if isinstance(item, tuple):
            meta, body = item[0], item[1] if len(item) > 1 else b""
        else:
            meta, body = item, b""
        meta_s = meta.decode("utf-8", "replace") if isinstance(meta, bytes) else str(meta)
        uid_m = re.search(r"\bUID (\d+)", meta_s)
        flags_m = re.search(r"FLAGS \(([^)]*)\)", meta_s)
        uid = int(uid_m.group(1)) if uid_m else 0
        flags = flags_m.group(1).split() if flags_m else []
        if not isinstance(body, (bytes, bytearray)):
            body = str(body).encode()
        body = bytes(body).replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        msgs.append((uid, flags, body))
    msgs.sort(key=lambda row: row[0])
    return msgs

def verify_seed():
    passwords = {}
    with open(accounts, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            if row.get("user"):
                passwords[row["user"]] = row.get("password") or ""
    sums = {}
    for line in open(os.path.join(here, "seed", "corpus-v1.sums"), encoding="utf-8"):
        fields = line.rstrip("\n").split("\t")
        if len(fields) >= 4:
            sums[(fields[0], fields[1])] = fields[3]
    flags_by = {}
    for row in manifest:
        words = [] if row.get("flags") == "-" else (row.get("flags") or "").split()
        flags_by.setdefault((row["user"], row["folder"]), []).append(flag_key(words))
    problems = []
    seen = []
    for row in folder_rows:
        if row["user"] not in seen:
            seen.append(row["user"])
    for user in seen:
        password = passwords.get(user, "")
        try:
            m = login(user, password)
        except Exception:
            problems.append(server + " INBOX: login failed")
            continue
        try:
            found, delim = listed_names(m)
            for row in folder_rows:
                if row["user"] != user:
                    continue
                folder = row["folder"]
                label = server + " " + folder
                box = mailbox_for(folder, user, delim, found)
                if not box:
                    problems.append(label + ": missing")
                    continue
                typ, data = m.select(box, readonly=True)
                if typ != "OK":
                    problems.append(label + ": missing")
                    continue
                try:
                    got_count = int(data[0])
                except (TypeError, ValueError, IndexError):
                    got_count = -1
                want_count = int(row["count"])
                if got_count != want_count:
                    problems.append(label + ": count " + str(got_count) + " != " + str(want_count))
                msgs = fetched_messages(m) if got_count else []
                want_flags = flags_by.get((user, folder), [])
                got_flags = [flag_key(flags) for _uid, flags, _body in msgs]
                if sorted(got_flags) != sorted(want_flags):
                    problems.append(label + ": flags")
                joined = "\n".join(hashlib.sha256(body).hexdigest() for _uid, _flags, body in msgs)
                digest = hashlib.sha256(joined.encode()).hexdigest()
                want_digest = sums.get((user, folder), "")
                if digest != want_digest:
                    problems.append(label + ": corpus-v1.sums")
        finally:
            try:
                m.logout()
            except Exception:
                pass
    return problems

recreate_scripts = {
    "greenmail-214": "start-latest.sh",
    "stalwart-01625": "start-latest.sh",
    "dovecot-245": "start-latest.sh",
    "cyrus-3124": "start-latest.sh",
    "courier-606": "start-latest.sh",
    "greenmail-dev": "start-dev.sh",
    "stalwart-dev": "start-dev.sh",
    "dovecot-dev": "start-dev.sh",
    "cyrus-3137": "start-dev.sh",
    "cyrus-22": "start-older.sh",
    "cyrus-24": "start-older.sh",
    "cyrus-25": "start-older.sh",
    "cyrus-36": "start-older.sh",
    "dovecot-10": "start-older.sh",
    "dovecot-12": "start-older.sh",
    "dovecot-22": "start-older.sh",
    "courier-50": "start-older.sh",
    "uwimap": "start-older.sh",
    "greenmail-16": "start-older.sh",
    "stalwart-0155": "start-older.sh",
    "greenmail": "start-local.sh",
    "stalwart": "start-local.sh",
    "zimbra-883": "zimbra-883.sh",
}

def recreate_line():
    script = recreate_scripts[server]
    return "tools/test-servers/" + script + " --recreate " + server

def fail_seed(problems):
    for problem in problems:
        sys.stderr.write(problem + "\n")
    sys.stderr.write(recreate_line() + "\n")
    sys.exit(1)

problems = verify_seed()
if problems:
    if any("login failed" in item for item in problems):
        fail_seed(problems)
    append_corpus()
    problems = verify_seed()
    if problems:
        fail_seed(problems)
PY

docker stop -t 60 "$name" >/dev/null
stage=$(mktemp -d)
# shellcheck disable=SC2086
for p in $paths; do
  rel=${p#/}
  mkdir -p "$stage/$(dirname "$rel")"
  rm -rf "$stage/$rel"
  docker cp "$name:$p" "$stage/$rel"
done
mkdir -p "$GEN/$server"
out="$GEN/$server/seed-snapshot.tar"
rels=
# shellcheck disable=SC2086
for p in $paths; do
  rels="$rels ${p#/}"
done
# shellcheck disable=SC2086
tar -C "$stage" -cf "$out" $rels
rm -rf "$stage"
if [ "$server" = "zimbra-883" ]; then
  if tar -tf "$out" | grep -E '(^|/)opt/zimbra/bin(/|$)' >/dev/null 2>&1; then
    rm -f "$out"
    echo "zimbra tar includes /opt/zimbra/bin" >&2
    exit 1
  fi
fi
image=$(docker inspect -f '{{.Image}}' "$name")
stamp="$GEN/$server/seed-snapshot.stamp"
{
  printf 'image %s\n' "$image"
  for spec in \
    "tools/test-servers/generated/corpus-v1/manifest.tsv|$GEN/corpus-v1/manifest.tsv" \
    "tools/test-servers/seed/accounts.tsv|$DIR/seed/accounts.tsv" \
    "tools/test-servers/seed/folders.tsv|$DIR/seed/folders.tsv" \
    "tools/test-servers/seed/corpus-v1.sums|$DIR/seed/corpus-v1.sums"
  do
    key=${spec%%|*}
    path=${spec#*|}
    sum=$(sha256sum "$path" | awk 'NR==1 { print $1; exit }')
    printf '%s %s\n' "$key" "$sum"
  done
} > "$stamp"
chmod a+r "$out" "$stamp"
chmod a+rx "$GEN/$server"
flag=$(mktemp)
printf 'yes\n' > "$flag"
docker cp "$flag" "$name":/.liveimap-no-reseed
rm -f "$flag"
