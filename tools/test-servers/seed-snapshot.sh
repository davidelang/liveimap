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
docker start "$name" >/dev/null
up=$(awk -F '\t' -v c="$name" '$1 == c { print $2; exit }' "$DIR/up.tsv")
if [ -n "$up" ]; then
  docker exec "$name" sh -c "$up"
fi

python3 - "$DIR" "$server" "$name" <<'PY'
import csv, os, socket, ssl, subprocess, sys, time
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

for user, password in users:
    m = login(user, password)
    try:
        m.select("INBOX")
        typ, data = m.search(None, "SUBJECT", '"LiveIMAP seed corpus-v1"')
        if data and data[0].split():
            continue
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
# shellcheck disable=SC2046
tar -C "$stage" -cf "$out" $(find "$stage" -mindepth 1 -maxdepth 1 -printf '%P\n')
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
