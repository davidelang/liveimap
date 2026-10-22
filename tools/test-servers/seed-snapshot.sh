#!/bin/sh
# Stop the lab container and docker commit it to liveimap-seed-<server>.
# A volume or bind mount exits 1. No tar, no stamp, and the container stays stopped.
# This script is not run by the reseed commit.
set -eu

if [ $# -ne 1 ] || [ -z "${1:-}" ]; then
  echo "usage: tools/test-servers/seed-snapshot.sh <server>" >&2
  exit 1
fi
server=$1

case "$server" in
  cyrus-22|cyrus-25|cyrus-24|cyrus-36)
    ;;
  cyrus-3124|cyrus-3137)
    ;;
  dovecot-10|dovecot-12|dovecot-22|dovecot-245|dovecot-dev)
    ;;
  courier-50)
    ;;
  courier-606|uwimap)
    ;;
  stalwart-0155)
    ;;
  stalwart|stalwart-01625|stalwart-dev)
    ;;
  zimbra-883)
    ;;
  *)
    echo "unknown server $server" >&2
    exit 1
    ;;
esac

name=liveimap-$server
if ! docker inspect "$name" >/dev/null 2>&1; then
  echo "missing container $name" >&2
  exit 1
fi

running=$(docker inspect -f '{{.State.Running}}' "$name")
if [ "$running" = "true" ]; then
  if ! docker stop -t 60 "$name" >/dev/null; then
    exit 1
  fi
  running=$(docker inspect -f '{{.State.Running}}' "$name")
  if [ "$running" = "true" ]; then
    echo "stop failed $name" >&2
    exit 1
  fi
fi

bad=$(docker inspect -f '{{range .Mounts}}{{if or (eq .Type "volume") (eq .Type "bind")}}{{println .Destination}}{{end}}{{end}}' "$name")
if [ -n "$bad" ]; then
  printf '%s\n' "$bad" >&2
  exit 1
fi

entry=$(docker inspect -f '{{json .Config.Entrypoint}}' "$name")
cmd=$(docker inspect -f '{{json .Config.Cmd}}' "$name")
env=$(docker inspect -f '{{json .Config.Env}}' "$name")
case "$entry$cmd$env" in
  *"/liveimap-reseed"*|*"LIVEIMAP_"*)
    echo "Config.Entrypoint, Config.Cmd, or Config.Env contains /liveimap-reseed or LIVEIMAP_ in $name" >&2
    exit 1
    ;;
esac

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
mkdir -p "$here/generated"
tmp=$(mktemp -d "$here/generated/seed-snapshot.XXXXXX")
reseed_missing=0
err=$(docker cp "$name:/liveimap-reseed" "$tmp/reseed" 2>&1) || {
  case "$err" in
    *"Could not find the file"*)
      reseed_missing=1
      ;;
    *)
      printf '%s\n' "$err" >&2
      rm -rf "$tmp"
      exit 1
      ;;
  esac
}
if [ "$reseed_missing" -eq 0 ]; then
  rm -rf "$tmp"
  echo "/liveimap-reseed is in $name" >&2
  exit 1
fi
entry_missing=0
err=$(docker cp "$name:/entrypoint.sh" "$tmp/entrypoint.sh" 2>&1) || {
  case "$err" in
    *"Could not find the file"*)
      entry_missing=1
      ;;
    *)
      printf '%s\n' "$err" >&2
      rm -rf "$tmp"
      exit 1
      ;;
  esac
}
if [ "$entry_missing" -eq 0 ]; then
  grep_status=0
  grep -F -q '/liveimap-reseed' "$tmp/entrypoint.sh" || grep_status=$?
  if [ "$grep_status" -eq 0 ]; then
    rm -rf "$tmp"
    echo "/entrypoint.sh contains /liveimap-reseed in $name" >&2
    exit 1
  fi
  if [ "$grep_status" -ne 1 ]; then
    rm -rf "$tmp"
    exit 1
  fi
fi
rm -rf "$tmp"

docker start "$name"
imap_port=$(python3 "$here/cport.py" "$server" imap)
published=$(docker port "$name" "$imap_port")
host_port=${published##*:}
lab_host=$(awk -F= '$1 == "LAB_BIND" { gsub(/["[:space:]]/, "", $2); print $2; exit }' "$here/host.env")
case "$lab_host" in
  "")
    echo "LAB_BIND missing" >&2
    exit 1
    ;;
esac
# Bare USER login and the recorded domain login, including zimbra-883.
python3 - "$lab_host" "$host_port" "$here" "$server" <<'PY'
import imaplib
import ssl
import sys
import time

host, port_s, tools, server = sys.argv[1:]
sys.path.insert(0, tools)
import lab
user = lab.USER
secret = lab.PASS
port = int(port_s)
deadline = time.time() + 180
imap = None
while time.time() < deadline:
    try:
        imap = imaplib.IMAP4(host, port, timeout=20)
        break
    except OSError:
        time.sleep(2)
if imap is None:
    sys.stderr.write("bare login no greeting\n")
    sys.exit(1)
try:
    parts = []
    for item in imap.capabilities or []:
        if isinstance(item, bytes):
            parts.append(item.decode())
        else:
            parts.append(item)
    caps = " ".join(parts)
    if "LOGINDISABLED" in caps:
        ctx = ssl._create_unverified_context()
        imap.starttls(ctx)
    typ, data = imap.login(user, secret)
except Exception as exc:
    text = str(exc).replace(secret, "***")
    sys.stderr.write("bare login %s\n" % text)
    sys.exit(1)
if typ != "OK":
    text = str(data).replace(secret, "***")
    sys.stderr.write("bare login %s\n" % text)
    sys.exit(1)
try:
    imap.logout()
except Exception:
    pass
login_domain, expect = lab._imap_domain_columns(server)
if login_domain == "LAB_MAIL_DOMAIN":
    login_domain = lab.mail_domain()
if not login_domain or not expect:
    problem = lab.domain_login_problem({"name": server}, "")
else:
    account = user.split("@")[0] + "@" + login_domain
    caps = lab.plain_caps(port)
    if caps and "LOGINDISABLED" in caps:
        err = lab._starttls_login(port, account)
        reply = "OK" if not err else err
    else:
        reply = lab._plain_login_reply(port, account)
    problem = lab.domain_login_problem({"name": server}, reply)
if problem:
    sys.stderr.write(problem + "\n")
    sys.exit(1)
PY
if ! docker stop -t 60 "$name"; then
  exit 1
fi
running=$(docker inspect -f '{{.State.Running}}' "$name")
if [ "$running" = "true" ]; then
  echo "stop failed $name" >&2
  exit 1
fi

docker commit "$name" "liveimap-seed-$server" >/dev/null
