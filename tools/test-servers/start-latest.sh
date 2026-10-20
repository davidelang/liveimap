#!/bin/sh
# Latest lab servers: Stalwart v0.16.25, Dovecot 2.4.5,
# Cyrus 3.12.4, Courier-IMAP 6.0.6. Four base accounts. No app install.
set -eu

SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")
cport() {
  python3 "$DIR/cport.py" "$1" "$2" || exit 1
}
SEED="$DIR/seed/accounts.tsv"
GEN="$DIR/generated"
PORTS_FILE="$GEN/local-ports.tsv"
ST_ADMIN_FILE="$GEN/stalwart-01625-admin.txt"
CY_ADMIN_FILE="$GEN/cyrus-3124-admin.txt"
ACCOUNTS_OUT="$GEN/latest-accounts.txt"
FETCH_OUT="$GEN/latest-fetch.txt"
PINS_OUT="$GEN/latest-pins.txt"
CY_BUILD="$GEN/cyrus-3124-build"
CO_BUILD="$GEN/courier-606-build"

if [ ! -f "$SEED" ]; then
  echo "missing seed file" >&2
  exit 1
fi

command -v docker >/dev/null
command -v python3 >/dev/null
command -v ss >/dev/null
command -v openssl >/dev/null
command -v setfacl >/dev/null

require_seed_image() {
  server=$1
  if ! docker image inspect "liveimap-seed-$server" >/dev/null 2>&1; then
    echo "tools/test-servers/seed-snapshot.sh $server" >&2
    exit 1
  fi
}

reset_from_seed() {
  server=$1
  require_owned "$server"
  require_seed_image "$server"
  name=liveimap-$server
  if container_exists "$name"; then
    docker rm -f "$name" >/dev/null
  fi
  ports_file="$GEN/local-ports.tsv"
  port_flags=$(python3 - "$ports_file" "$name" <<'PY'
import sys
path, name = sys.argv[1], sys.argv[2]
found = []
try:
    handle = open(path, encoding="utf-8")
except OSError:
    sys.stderr.write("no saved ports for %s\n" % name)
    sys.exit(1)
with handle:
    for line in handle:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 4 or parts[3] != name:
            continue
        if not parts[1].isdigit() or not parts[2].isdigit():
            continue
        found.append("-p")
        found.append("%s:%s" % (parts[1], parts[2]))
if not found:
    sys.stderr.write("no saved ports for %s\n" % name)
    sys.exit(1)
sys.stdout.write(" ".join(found))
PY
)
  # shellcheck disable=SC2086
  docker create --name "$name" $port_flags "liveimap-seed-$server" >/dev/null
}

create_from_seed() {
  server=$1
  shift
  require_seed_image "$server"
  docker create --name "liveimap-$server" "$@" "liveimap-seed-$server"
  docker start "liveimap-$server"
}

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -qx "$1"
}

container_running() {
  docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}

# Container ports each lab container must publish. A container created before a port was added is stale:
# stop loudly with the recreate command; never reuse it silently.
assign_cports() {
  case "$1" in
    liveimap-stalwart-01625)
      cp_stalwart01625_imap=$(cport stalwart-01625 imap)
      cp_stalwart01625_imaps=$(cport stalwart-01625 imaps)
      cp_stalwart01625_managesieve=$(cport stalwart-01625 managesieve)
      cp_stalwart01625_https=$(cport stalwart-01625 https)
      cp_stalwart01625_submission=$(cport stalwart-01625 submission)
      ;;
    liveimap-dovecot-245)
      cp_dovecot245_imap=$(cport dovecot-245 imap)
      cp_dovecot245_submission=$(cport dovecot-245 submission)
      ;;
    liveimap-cyrus-3124)
      cp_cyrus3124_imap=$(cport cyrus-3124 imap)
      cp_cyrus3124_http=$(cport cyrus-3124 http)
      ;;
    liveimap-courier-606)
      cp_courier606_imap=$(cport courier-606 imap)
      ;;
    *) echo "assign_cports: unknown container $1" >&2; exit 1 ;;
  esac
}

expected_ports() {
  case "$1" in
    liveimap-stalwart-01625) echo "$cp_stalwart01625_imap $cp_stalwart01625_imaps $cp_stalwart01625_managesieve $cp_stalwart01625_https $cp_stalwart01625_submission" ;;
    liveimap-dovecot-245) echo "$cp_dovecot245_imap $cp_dovecot245_submission" ;;
    liveimap-cyrus-3124) echo "$cp_cyrus3124_imap $cp_cyrus3124_http" ;;
    liveimap-courier-606) echo "$cp_courier606_imap" ;;
    *) echo "expected_ports: unknown container $1" >&2; exit 1 ;;
  esac
}

server_owner() {
  case "$1" in
    stalwart-01625|dovecot-245|cyrus-3124|courier-606)
      printf '%s\n' "$DIR/start-latest.sh" ;;
    stalwart-dev|dovecot-dev|cyrus-3137)
      printf '%s\n' "$DIR/start-dev.sh" ;;
    cyrus-22|cyrus-24|cyrus-25|cyrus-36|dovecot-10|dovecot-12|dovecot-22|courier-50|uwimap|stalwart-0155)
      printf '%s\n' "$DIR/start-older.sh" ;;
    stalwart)
      printf '%s\n' "$DIR/start-local.sh" ;;
    zimbra-883)
      printf '%s\n' "$DIR/zimbra-883.sh" ;;
    *)
      return 1 ;;
  esac
}

require_owned() {
  server=$1
  owner=$(server_owner "$server" || true)
  if [ -z "$owner" ]; then
    echo "ERROR: $server is not a lab server" >&2
    exit 1
  fi
  if [ "$owner" != "$SCRIPT" ]; then
    echo "ERROR: $server belongs to $owner" >&2
    exit 1
  fi
}

stop_for_recreate() {
  echo "$SCRIPT --recreate $1" >&2
  exit 1
}

container_ok() {
  name=$1
  shift
  python3 - "$name" "$GEN" "$SEED" "$@" <<'PY'
import os
import subprocess
import sys

name, gen, seed = sys.argv[1], sys.argv[2], sys.argv[3]
want = set(sys.argv[4:])
gen_real = os.path.realpath(gen)
seed_real = os.path.realpath(seed)
seed_dir = seed_real if os.path.isdir(seed_real) else os.path.dirname(seed_real)
roots = (gen_real + os.sep, seed_dir + os.sep)

def under(path):
    real = os.path.realpath(path)
    if real == gen_real or real == seed_real or real == seed_dir:
        return True
    return real.startswith(roots)

fmt_ports = "{{range $p, $b := .HostConfig.PortBindings}}{{$p}}\n{{end}}"
proc = subprocess.run(
    ["docker", "inspect", "-f", fmt_ports, name],
    capture_output=True,
    text=True,
)
if proc.returncode != 0:
    sys.exit(1)
got = set()
for line in proc.stdout.splitlines():
    port = line.split("/", 1)[0]
    if port:
        got.add(port)
if got != want:
    sys.exit(1)
fmt_mounts = "{{range .Mounts}}{{.Type}}\t{{.Source}}\n{{end}}"
proc = subprocess.run(
    ["docker", "inspect", "-f", fmt_mounts, name],
    capture_output=True,
    text=True,
)
if proc.returncode != 0:
    sys.exit(1)
for line in proc.stdout.splitlines():
    if not line.strip():
        continue
    kind, sep, source = line.partition("\t")
    if kind != "bind":
        continue
    if not sep or not source or not os.path.exists(source) or not under(source):
        sys.exit(1)
sys.exit(0)
PY
}

check_existing_name() {
  name=$1
  if ! container_exists "$name"; then
    return 0
  fi
  server=${name#liveimap-}
  assign_cports "$name"
  # shellcheck disable=SC2046
  if ! container_ok "$name" $(expected_ports "$name"); then
    stop_for_recreate "$server"
  fi
}

replace_port_rows() {
  mkdir -p "$GEN"
  python3 - "$PORTS_FILE" "$1" "$2" "$3" <<'PY'
import os
import sys

path, containers, services, rows_path = sys.argv[1:]
containers = set(item for item in containers.split(",") if item)
services = set(item for item in services.split(",") if item)
with open(rows_path, encoding="utf-8") as handle:
    new = handle.read()
if new and not new.endswith("\n"):
    new += "\n"
old = ""
if os.path.exists(path):
    with open(path, encoding="utf-8") as handle:
        old = handle.read()
kept = []
for line in old.splitlines(keepends=True):
    parts = line.rstrip("\n").split("\t")
    if parts and parts[0] in services:
        continue
    if len(parts) >= 4 and parts[3] in containers:
        continue
    if not line.endswith("\n"):
        line += "\n"
    kept.append(line)
text = "".join(kept) + new
parent = os.path.dirname(path)
os.makedirs(parent, exist_ok=True)
tmp = path + ".new"
with open(tmp, "w", encoding="utf-8") as handle:
    handle.write(text)
PY
  mv "$PORTS_FILE.new" "$PORTS_FILE"
}

pick_ports() {
  python3 - "$PORTS_FILE" "$@" <<'PY'
import os
import random
import socket
import subprocess
import sys

ports_file = sys.argv[1]
specs = []
for item in sys.argv[2:]:
    service, _cport, _owner = item.split(":")
    specs.append(service)

rows = []
if os.path.exists(ports_file):
    with open(ports_file, encoding="utf-8") as handle:
        for line in handle:
            rows.append(line.rstrip("\n").split("\t"))

old_of = {}
for parts in rows:
    if parts and parts[0] in set(specs) and len(parts) >= 2 and parts[1].isdigit():
        old_of[parts[0]] = int(parts[1])

def host_of(parts):
    if len(parts) >= 2 and parts[1].isdigit():
        return int(parts[1])
    return None

ss = subprocess.check_output(["ss", "-lnt"], text=True, errors="replace")
listening_set = set()
for line in ss.splitlines():
    parts = line.split()
    if len(parts) < 4:
        continue
    _host, sep, got = parts[3].rpartition(":")
    if sep and got.isdigit():
        listening_set.add(int(got))

bind_host = os.environ.get("PICK_BIND_HOST", "")

def bindable(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind((bind_host, port))
        return True
    except OSError:
        return False
    finally:
        sock.close()

chosen = []

def blocked(port, service):
    for parts in rows:
        if not parts or parts[0] == service:
            continue
        if host_of(parts) == port:
            return True
    return False

def free(port, service):
    if port in chosen or blocked(port, service):
        return False
    if port in listening_set:
        return False
    return bindable(port)

def draw(service):
    seen = set()
    draws = 0
    while draws < 400:
        draws += 1
        port = random.randint(20000, 39999)
        if port in seen:
            continue
        seen.add(port)
        if free(port, service):
            return port
    sys.stderr.write("could not draw free ports\n")
    sys.exit(1)

for service in specs:
    old = old_of.get(service)
    if old is not None and free(old, service):
        host = old
    else:
        host = draw(service)
    chosen.append(host)
    print(host)
PY
}

remove_one() {
  if container_exists "$1"; then
    docker rm -f "$1" >/dev/null
  fi
}

finish_recreate() {
  name=$1
  server=$2
  assign_cports "$name"
  # shellcheck disable=SC2046
  if ! container_ok "$name" $(expected_ports "$name"); then
    echo "$SCRIPT --recreate $server" >&2
    docker stop "$name"
    exit 1
  fi
  docker stop "$name" >/dev/null
}

check_published() {
  server=${1#liveimap-}
  assign_cports "$1"
  # shellcheck disable=SC2046
  if ! container_ok "$1" $(expected_ports "$1"); then
    stop_for_recreate "$server"
  fi
}

# draw_ports N: N free host ports (20000-39999) not listening, bindable, and not already in local-ports.tsv.
draw_ports() {
python3 - "$PORTS_FILE" "$1" <<'PY'
import random
import socket
import subprocess
import sys

ports_file = sys.argv[1]
want = int(sys.argv[2])
used = set()
try:
    with open(ports_file, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2 and parts[1].isdigit():
                used.add(int(parts[1]))
except FileNotFoundError:
    pass

def listening(port):
    out = subprocess.check_output(["ss", "-lnt"], text=True, errors="replace")
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        local = parts[3]
        _host, sep, got = local.rpartition(":")
        if sep and got == str(port):
            return True
    return False

def bindable(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("", port))
        return True
    except OSError:
        return False
    finally:
        sock.close()

chosen = []
seen = set()
draws = 0
while len(chosen) < want and draws < 400:
    draws += 1
    port = random.randint(20000, 39999)
    if port in seen or port in used:
        seen.add(port)
        continue
    seen.add(port)
    if listening(port) or not bindable(port):
        continue
    chosen.append(port)

if len(chosen) < want:
    sys.exit(1)
print(" ".join(str(p) for p in chosen))
PY
}

# Port of the lab fake SMTP sink. The only writer of the fake-smtp-sink row.
sink_port() {
  count=0
  if [ -f "$PORTS_FILE" ]; then
    count=$(awk -F '\t' '$1 == "fake-smtp-sink" { n++ } END { print n + 0 }' "$PORTS_FILE")
  fi
  if [ "$count" -eq 0 ]; then
    port=$(draw_ports 1)
    case "$port" in
      ''|*[!0-9]*) echo "ERROR: no single fake-smtp-sink row in $PORTS_FILE; run $SCRIPT --recreate dovecot-245" >&2; exit 1 ;;
    esac
    mkdir -p "$GEN"
    printf 'fake-smtp-sink\t%s\t%s\thost\n' "$port" "$port" >> "$PORTS_FILE"
    echo "$port"
    return
  fi
  if [ "$count" -ne 1 ]; then
    echo "ERROR: no single fake-smtp-sink row in $PORTS_FILE; run $SCRIPT --recreate dovecot-245" >&2
    exit 1
  fi
  port=$(awk -F '\t' '$1 == "fake-smtp-sink" { print $2; exit }' "$PORTS_FILE")
  case "$port" in
    ''|*[!0-9]*) echo "ERROR: no single fake-smtp-sink row in $PORTS_FILE; run $SCRIPT --recreate dovecot-245" >&2; exit 1 ;;
  esac
  echo "$port"
}

# Dovecot 2.4.5: users, auth, submission relay to the fake SMTP sink on the docker bridge gateway, and the
# container with IMAP ($1) and submission ($2) published. The sink host port is sink_port.
create_dovecot_245() {
  dv_imap=$1
  dv_sub=$2
  relay_port=$(sink_port)
  gateway=$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}')
  if [ -z "$gateway" ]; then
    echo "ERROR: no gateway on the docker bridge network" >&2
    exit 1
  fi
  mkdir -p "$GEN/dovecot-245"
  umask 007
  cat > "$GEN/dovecot-245/auth.conf" <<'EOF'
import_environment {
  DOVEADM_PASSWORD = %{env:DOVEADM_PASSWORD | default}
  USER_PASSWORD = %{env:USER_PASSWORD | default('{CRYPT}*')}
}
passdb passwd-file {
  passwd_file_path = /etc/dovecot/passwd
}
userdb static {
  fields {
    uid = vmail
    gid = vmail
    home = /srv/vmail/%{user}
  }
}
EOF
python3 - "$SEED" "$GEN/dovecot-245/passwd" <<'PY'
import csv
import sys

seed, dest = sys.argv[1], sys.argv[2]
lines = []
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("profile") != "base":
            continue
        user = row.get("user") or ""
        password = row.get("password") or ""
        if not user or not password:
            sys.exit(1)
        lines.append("%s:{PLAIN}%s\n" % (user, password))
want = ["liveimap", "liveimap2", "liveimap-empty", "liveimap-utf8"]
got = [line.split(":", 1)[0] for line in lines]
if got != want:
    sys.stderr.write("unexpected base users\n")
    sys.exit(1)
with open(dest, "w", encoding="utf-8") as handle:
    handle.writelines(lines)
PY
umask 022
printf 'submission_relay_host = %s\nsubmission_relay_port = %s\nsubmission_relay_trusted = yes\n' "$gateway" "$relay_port" > "$GEN/dovecot-245/relay.conf"
setfacl -m u:1000:r "$GEN/dovecot-245/passwd" "$GEN/dovecot-245/auth.conf" "$GEN/dovecot-245/relay.conf"
create_from_seed dovecot-245 \
  -p "${dv_imap}:${cp_dovecot245_imap}" \
  -p "${dv_sub}:${cp_dovecot245_submission}"
docker cp "$GEN/dovecot-245/relay.conf" "liveimap-dovecot-245:/etc/dovecot/conf.d/relay.conf"
}

# --recreate-dovecot-245 is the same replace as --recreate dovecot-245.
if [ "${1:-}" = --recreate-dovecot-245 ]; then
  set -- --recreate dovecot-245
fi
if [ "${1:-}" = --reset ]; then
  if [ $# -ne 2 ] || [ -z "${2:-}" ]; then
    echo "ERROR: usage: $SCRIPT --reset <server>" >&2
    exit 1
  fi
  reset_from_seed "$2"
  if [ "$2" = dovecot-245 ]; then
    gateway=$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}')
    if [ -z "$gateway" ]; then
      echo "ERROR: no gateway on the docker bridge network" >&2
      exit 1
    fi
    relay_port=$(sink_port)
    mkdir -p "$GEN/dovecot-245"
    printf 'submission_relay_host = %s\nsubmission_relay_port = %s\nsubmission_relay_trusted = yes\n' "$gateway" "$relay_port" > "$GEN/dovecot-245/relay.conf"
    docker cp "$GEN/dovecot-245/relay.conf" "liveimap-dovecot-245:/etc/dovecot/conf.d/relay.conf"
  fi
  exit 0
fi
if [ "${1:-}" = --recreate ]; then
  if [ $# -ne 2 ] || [ -z "${2:-}" ]; then
    echo "ERROR: usage: $SCRIPT --recreate <server>" >&2
    exit 1
  fi
  require_owned "$2"
  server=$2
  require_seed_image "$server"
  name=liveimap-$server
  remove_one "$name"
  rows="$GEN/port-rows.new"
  assign_cports "$name"
  case "$server" in
    stalwart-01625)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        stalwart01625-imap:${cp_stalwart01625_imap}:liveimap-stalwart-01625 \
        stalwart01625-imaps:${cp_stalwart01625_imaps}:liveimap-stalwart-01625 \
        stalwart01625-managesieve:${cp_stalwart01625_managesieve}:liveimap-stalwart-01625 \
        stalwart01625-https:${cp_stalwart01625_https}:liveimap-stalwart-01625 \
        stalwart01625-submission:${cp_stalwart01625_submission}:liveimap-stalwart-01625)
      if [ ! -s "$ST_ADMIN_FILE" ]; then
        umask 007
        python3 -c 'import secrets; print(secrets.token_hex(16))' > "$ST_ADMIN_FILE"
        umask 022
      fi
      ST_ADMIN_PASS=$(cat "$ST_ADMIN_FILE")
      create_from_seed stalwart-01625 \
        -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
        -p "${1}:${cp_stalwart01625_imap}" \
        -p "${2}:${cp_stalwart01625_imaps}" \
        -p "${3}:${cp_stalwart01625_managesieve}" \
        -p "${4}:${cp_stalwart01625_https}" \
        -p "${5}:${cp_stalwart01625_submission}"
      unset ST_ADMIN_PASS
      {
        printf 'stalwart01625-imap\t%s\t%s\tliveimap-stalwart-01625\n' "$1" "$cp_stalwart01625_imap"
        printf 'stalwart01625-imaps\t%s\t%s\tliveimap-stalwart-01625\n' "$2" "$cp_stalwart01625_imaps"
        printf 'stalwart01625-managesieve\t%s\t%s\tliveimap-stalwart-01625\n' "$3" "$cp_stalwart01625_managesieve"
        printf 'stalwart01625-https\t%s\t%s\tliveimap-stalwart-01625\n' "$4" "$cp_stalwart01625_https"
        printf 'stalwart01625-submission\t%s\t%s\tliveimap-stalwart-01625\n' "$5" "$cp_stalwart01625_submission"
      } > "$rows"
      replace_port_rows liveimap-stalwart-01625 "" "$rows"
      ;;
    dovecot-245)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        dovecot245-imap:${cp_dovecot245_imap}:liveimap-dovecot-245 \
        dovecot245-submission:${cp_dovecot245_submission}:liveimap-dovecot-245)
      create_dovecot_245 "$1" "$2"
      {
        printf 'dovecot245-imap\t%s\t%s\tliveimap-dovecot-245\n' "$1" "$cp_dovecot245_imap"
        printf 'dovecot245-submission\t%s\t%s\tliveimap-dovecot-245\n' "$2" "$cp_dovecot245_submission"
      } > "$rows"
      replace_port_rows liveimap-dovecot-245 "" "$rows"
      ;;
    cyrus-3124)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        cyrus3124-imap:${cp_cyrus3124_imap}:liveimap-cyrus-3124 \
        cyrus3124-http:${cp_cyrus3124_http}:liveimap-cyrus-3124)
      create_from_seed cyrus-3124 \
        -p "${1}:${cp_cyrus3124_imap}" \
        -p "${2}:${cp_cyrus3124_http}"
      {
        printf 'cyrus3124-imap\t%s\t%s\tliveimap-cyrus-3124\n' "$1" "$cp_cyrus3124_imap"
        printf 'cyrus3124-http\t%s\t%s\tliveimap-cyrus-3124\n' "$2" "$cp_cyrus3124_http"
      } > "$rows"
      replace_port_rows liveimap-cyrus-3124 "" "$rows"
      ;;
    courier-606)
      # shellcheck disable=SC2046
      set -- $(pick_ports courier606-imap:${cp_courier606_imap}:liveimap-courier-606)
      create_from_seed courier-606 \
        -p "${1}:${cp_courier606_imap}"
      {
        printf 'courier606-imap\t%s\t%s\tliveimap-courier-606\n' "$1" "$cp_courier606_imap"
      } > "$rows"
      replace_port_rows liveimap-courier-606 "" "$rows"
      ;;
  esac
  rm -f "$rows"
  finish_recreate "$name" "$server"
  exit 0
fi

# Check every existing container before starting any, so a stale one stops the script with nothing started.
for name in liveimap-stalwart-01625 liveimap-dovecot-245 \
  liveimap-cyrus-3124 liveimap-courier-606; do
  if container_exists "$name"; then
    check_published "$name"
  fi
done
present=0
for name in liveimap-stalwart-01625 liveimap-dovecot-245 \
  liveimap-cyrus-3124 liveimap-courier-606; do
  if container_exists "$name"; then
    present=1
    if ! container_running "$name"; then
      docker start "$name"
    fi
  fi
done
if [ "$present" -eq 0 ]; then

require_seed_image stalwart-01625
require_seed_image dovecot-245
require_seed_image cyrus-3124
require_seed_image courier-606
docker pull debian:bookworm

mkdir -p "$GEN" "$CY_BUILD" "$CO_BUILD"

echo "resolving courier tarball"
python3 - "$CO_BUILD/courier.url" <<'PY'
import re
import sys
import urllib.request

out_path = sys.argv[1]
want = "courier-imap-6.0.6.tar.bz2"
ua = {"User-Agent": "liveimap-lab"}

def fetch(url):
    req = urllib.request.Request(url, headers=ua)
    with urllib.request.urlopen(req, timeout=90) as resp:
        return resp.read().decode("utf-8", "replace"), resp.geturl()

def filename(url):
    path = url.split("?", 1)[0].rstrip("/")
    if path.endswith("/download"):
        path = path[: -len("/download")]
    return path.rsplit("/", 1)[-1]

page, _ = fetch("https://www.courier-mta.org/download.html")
hrefs = re.findall(r'href="([^"]+)"', page)
chosen = None
for href in hrefs:
    if filename(href) == want:
        chosen = href
        break
if chosen is None:
    folder, _ = fetch("https://sourceforge.net/projects/courier/files/imap/6.0.6/")
    if want not in folder:
        sys.stderr.write("download page has no %s\n" % want)
        sys.exit(1)
    match = re.search(r'href="([^"]*%s/download[^"]*)"' % re.escape(want), folder)
    if not match:
        chosen = "https://sourceforge.net/projects/courier/files/imap/6.0.6/%s/download" % want
    else:
        chosen = match.group(1)
        if chosen.startswith("/"):
            chosen = "https://sourceforge.net" + chosen
if filename(chosen) != want:
    sys.stderr.write("refusing courier file %s\n" % filename(chosen))
    sys.exit(1)
with open(out_path, "w", encoding="utf-8") as handle:
    handle.write(chosen + "\n")
print(chosen)
PY

echo "downloading tarballs"
python3 - "$CY_BUILD" "$CO_BUILD" <<'PY'
import hashlib
import os
import sys
import urllib.request

cy_dir, co_dir = sys.argv[1], sys.argv[2]
ua = {"User-Agent": "liveimap-lab"}

def download(url, dest):
    req = urllib.request.Request(url, headers=ua)
    with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as out:
        while True:
            chunk = resp.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    digest = hashlib.sha256()
    with open(dest, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

cy_url = "https://github.com/cyrusimap/cyrus-imapd/releases/download/cyrus-imapd-3.12.4/cyrus-imapd-3.12.4.tar.gz"
cy_dest = os.path.join(cy_dir, "cyrus-imapd-3.12.4.tar.gz")
cy_sum = download(cy_url, cy_dest)
with open(os.path.join(cy_dir, "sha256"), "w", encoding="utf-8") as handle:
    handle.write(cy_sum + "\n")
co_url = open(os.path.join(co_dir, "courier.url"), encoding="utf-8").read().strip()
co_dest = os.path.join(co_dir, "courier-imap-6.0.6.tar.bz2")
co_sum = download(co_url, co_dest)
with open(os.path.join(co_dir, "sha256"), "w", encoding="utf-8") as handle:
    handle.write(co_sum + "\n")
print("cyrus", cy_sum)
print("courier", co_sum)
PY

echo "preparing cyrus image"
cat > "$CY_BUILD/xxd" <<'EOF'
#!/bin/sh
set -eu
if [ "${1:-}" != "-i" ] || [ -z "${2:-}" ]; then
  echo "xxd: only -i FILE is implemented" >&2
  exit 1
fi
file=$2
sym=$(printf '%s' "$file" | sed 's/[^A-Za-z0-9]/_/g')
size=$(wc -c < "$file" | tr -d ' ')
echo "unsigned char ${sym}[] = {"
od -An -tx1 -v "$file" | awk '
  {
    for (i = 1; i <= NF; i++) {
      printf "  0x%s,", $i
      n++
      if (n % 12 == 0) printf "\n"
    }
  }
  END { if (n % 12 != 0) printf "\n" }
'
echo "};"
echo "unsigned int ${sym}_len = ${size};"
EOF
cat > "$CY_BUILD/rsync" <<'EOF'
#!/bin/sh
# Present for configure. Cyrus calls rsync only to copy search indexes.
exit 0
EOF
chmod 755 "$CY_BUILD/xxd" "$CY_BUILD/rsync"
cat > "$CY_BUILD/imapd.conf" <<'EOF'
configdirectory: /var/lib/cyrus
partition-default: /var/spool/cyrus/mail
sievedir: /var/spool/cyrus/sieve
admins: cyrus
allowplaintext: yes
sasl_pwcheck_method: auxprop
sasl_mech_list: PLAIN LOGIN
httpmodules: jmap
conversations: 1
conversations_db: twoskip
unixhierarchysep: yes
virtdomains: off
sasl_minimum_layer: 0
EOF
cat > "$CY_BUILD/cyrus.conf" <<'EOF'
START {
  recover       cmd="ctl_cyrusdb -r"
}

SERVICES {
  imap          cmd="imapd" listen="143" prefork=0
  http          cmd="httpd" listen="8080" prefork=0
}

EVENTS {
  checkpoint    cmd="ctl_cyrusdb -c" period=30
}
EOF
cat > "$CY_BUILD/entrypoint.sh" <<'EOF'
#!/bin/sh
set -eu
find_bin() {
  find /usr/local/cyrus /usr/lib/cyrus /usr/sbin /usr/bin -type f -name "$1" 2>/dev/null | head -n 1
}
MASTER=$(find_bin master)
CTL=$(find_bin ctl_cyrusdb)
IMTEST=$(find_bin imtest)
SASL=$(command -v saslpasswd2)
if [ -z "$MASTER" ] || [ -z "$CTL" ] || [ -z "$IMTEST" ] || [ -z "$SASL" ]; then
  echo "cyrus binaries missing" >&2
  exit 1
fi
if ! id cyrus >/dev/null 2>&1; then
  useradd -r -d /var/lib/cyrus -s /usr/sbin/nologin cyrus
fi
mkdir -p /var/lib/cyrus /var/spool/cyrus/mail /var/spool/cyrus/sieve /run/cyrus /etc/sasl2
chown -R cyrus:cyrus /var/lib/cyrus /var/spool/cyrus /run/cyrus
cat > /etc/sasl2/Cyrus.conf <<'SASL'
pwcheck_method: auxprop
auxprop_plugin: sasldb
mech_list: plain login
sasldb_path: /etc/sasldb2
SASL
if [ -d /usr/lib/x86_64-linux-gnu/sasl2 ]; then
  cp /etc/sasl2/Cyrus.conf /usr/lib/x86_64-linux-gnu/sasl2/Cyrus.conf
fi
cp /etc/sasl2/Cyrus.conf /usr/lib/sasl2/Cyrus.conf 2>/dev/null || true
mkdir -p /usr/lib/sasl2
cp /etc/sasl2/Cyrus.conf /usr/lib/sasl2/Cyrus.conf
admin=$(tr -d '\r\n' < /run/liveimap/admin)
printf '%s\n' "$admin" | "$SASL" -p -c cyrus
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 "\t" $2 }' /run/liveimap/accounts.tsv > /tmp/base-users
while IFS=$(printf '\t') read -r user pass; do
  [ -n "$user" ] || continue
  printf '%s\n' "$pass" | "$SASL" -p -c "$user"
done < /tmp/base-users
rm -f /tmp/base-users
chown cyrus:cyrus /etc/sasldb2
chmod 640 /etc/sasldb2
su -s /bin/sh cyrus -c "$CTL -r" || su -s /bin/sh cyrus -c "$CTL -r"
"$MASTER" -d -p /run/cyrus/master.pid
ready=0
i=0
while [ "$i" -lt 60 ]; do
  if bash -c 'echo >/dev/tcp/127.0.0.1/143' >/dev/null 2>&1; then
    ready=1
    break
  fi
  i=$((i + 1))
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "cyrus imap did not open" >&2
  exit 1
fi
if [ ! -f /tmp/liveimap-restored ]; then
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 }' /run/liveimap/accounts.tsv > /tmp/base-names
while read -r user; do
  [ -n "$user" ] || continue
  printf 'A1 CREATE user/%s\n' "$user" > /tmp/create-cmd
  timeout 20 "$IMTEST" -m login -a cyrus -w "$admin" -p 143 -f /tmp/create-cmd 127.0.0.1 >/tmp/imtest.out 2>&1 || true
done < /tmp/base-names
rm -f /tmp/base-names /tmp/imtest.out /tmp/create-cmd
fi
unset admin
exec sleep infinity
EOF
chmod 755 "$CY_BUILD/entrypoint.sh"
cat > "$CY_BUILD/Dockerfile" <<'EOF'
FROM debian:bookworm
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential autoconf automake libtool pkg-config bison flex gperf \
    libssl-dev libjansson-dev libxml2-dev libsqlite3-dev libical-dev \
    libsasl2-dev libsasl2-modules sasl2-bin libpcre2-dev uuid-dev \
    libicu-dev libxapian-dev zlib1g-dev libnghttp2-dev \
    && rm -rf /var/lib/apt/lists/*
COPY xxd /usr/local/bin/xxd
COPY rsync /usr/local/bin/rsync
RUN chmod 755 /usr/local/bin/xxd /usr/local/bin/rsync
COPY cyrus-imapd-3.12.4.tar.gz /tmp/cyrus-imapd-3.12.4.tar.gz
RUN tar -xzf /tmp/cyrus-imapd-3.12.4.tar.gz -C /tmp \
    && cd /tmp/cyrus-imapd-3.12.4 \
    && ./configure --prefix=/usr/local/cyrus --enable-http --enable-jmap --enable-xapian \
    && make -j4 \
    && make install \
    && rm -rf /tmp/cyrus-imapd-3.12.4 /tmp/cyrus-imapd-3.12.4.tar.gz
COPY imapd.conf /etc/imapd.conf
COPY cyrus.conf /etc/cyrus.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
EOF

echo "preparing courier image"
cat > "$CO_BUILD/build-inside.sh" <<'EOF'
#!/bin/sh
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update
# The 6.0.6 tarball is bzip2. bookworm tar does not include that decompressor.
apt-get install -y --no-install-recommends bzip2
tar -xjf /src/courier-imap-6.0.6.tar.bz2 -C /src
cd /src
# courier-debuild reads mailuser from courierauthconfig before debuild names
# packages. This stub only exists so the first run can reach that list.
cat > /usr/local/bin/courierauthconfig <<'STUB'
#!/bin/sh
echo mailuser=courier
echo mailgroup=courier
STUB
chmod 755 /usr/local/bin/courierauthconfig
cd /src/courier-imap-6.0.6
# Bookworm ships these names. Patch the control file courier-debuild copies.
sed -i \
  -e 's/libcourier-auth-dev/courier-authlib-dev/' \
  -e 's/gnutls-dev/libgnutls28-dev/' \
  -e 's/libgcrypt-dev/libgcrypt20-dev/' \
  -e '/^Pre-Depends:/d' \
  packaging/debian/control
# Unicode 2.6 is installed from the tarball, so it has no dpkg shlibs.
printf '\noverride_dh_shlibdeps:\n\tdh_shlibdeps -- --ignore-missing-info\n' >> packaging/debian/rules
# courier-debuild unpacks the tarball again and ignores this tree.
cd /src
tar -cjf courier-imap-6.0.6.tar.bz2 courier-imap-6.0.6
cd /src/courier-imap-6.0.6
# configure.ac rejects a bare "ps" name. procps provides /bin/ps.
apt-get install -y procps
if [ ! -x /bin/ps ] && [ ! -x /usr/bin/ps ]; then
  echo "procps did not install ps" >&2
  exit 1
fi
set +e
DEBGCC=default ./courier-debuild -us -uc > /tmp/debuild1.log 2>&1
code=$?
set -e
if grep -q 'Cannot find pathname to ps' /tmp/debuild1.log; then
  echo "Cannot find pathname to ps" >&2
  exit 1
fi
if [ "$code" -ne 0 ]; then
  if [ -f /src/courier-imap-6.0.6/deb/courier-imap-6.0.6/debian/control ]; then
    ctl=/src/courier-imap-6.0.6/deb/courier-imap-6.0.6/debian/control
  else
    ctl=/src/courier-imap-6.0.6/packaging/debian/control
  fi
  awk '
    BEGIN { p = 0 }
    /^Build-Depends:/ { p = 1; sub(/^Build-Depends:[[:space:]]*/, ""); printf "%s", $0; next }
    p && /^[^[:space:]]/ { p = 0 }
    p { sub(/^[[:space:]]*/, ""); printf " %s", $0 }
    END { printf "\n" }
  ' "$ctl" > /tmp/build-depends.txt
  if grep -Eq 'debuild: (command )?not found|Cannot determine gcc version' /tmp/debuild1.log \
      || ! grep -q 'Unmet build dependencies' /tmp/debuild1.log; then
    printf '%s\n' devscripts >> /tmp/build-depends.txt
  fi
  python3 - <<'PY' || apt-get install -y python3
import sys
print("python-ok")
PY
  python3 - <<'PY'
import os
import re
import subprocess
import textwrap

lines = [
    line.strip()
    for line in open("/tmp/build-depends.txt", encoding="utf-8", errors="replace")
    if line.strip()
]
pkgs = []
if lines:
    for part in lines[0].split(","):
        part = re.sub(r"\s+", " ", part).strip()
        if part and not part.startswith("%"):
            pkgs.append(part)
    for line in lines[1:]:
        part = re.sub(r"\s+", " ", line).strip().strip(",")
        if part and not part.startswith("%"):
            pkgs.append(part)
# debuild itself
if "devscripts" not in " ".join(pkgs):
    if os.path.exists("/tmp/debuild1.log"):
        log = open("/tmp/debuild1.log", encoding="utf-8", errors="replace").read()
        if "debuild" in log and "not found" in log:
            pkgs.append("devscripts")
wanted = []
seen = set()
for pkg in pkgs:
    if pkg not in seen:
        seen.add(pkg)
        wanted.append(pkg)
if not wanted:
    raise SystemExit("courier-debuild failed without naming packages")
print("installing", " ".join(wanted))
subprocess.check_call(["apt-get", "satisfy", "-y"] + wanted)
PY
  # Bookworm's libcourier-unicode 2.1 copies output iterators. 6.0.6's
  # rfc822 iterators are not copyable. Unicode 2.6 takes them by reference.
  apt-get install -y build-essential locales
  printf '%s\n' 'en_US.UTF-8 UTF-8' 'en_US ISO-8859-1' > /etc/locale.gen
  locale-gen
  tar -xjf /src/courier-unicode-2.6.0.tar.bz2 -C /src
  cd /src/courier-unicode-2.6.0
  # Multiarch searches this directory before /usr/lib. Replace Bookworm's .so.
  ./configure --prefix=/usr --libdir=/usr/lib/x86_64-linux-gnu
  make -j4
  make install
  ldconfig
  rm -f /usr/local/bin/courierauthconfig
  hash -r || true
  cd /src/courier-imap-6.0.6
  export MAKEFLAGS=-j4
  # Upstream make check needs both en_US locales and still mismatches Bookworm.
  export DEB_BUILD_OPTIONS=parallel=4,nocheck
  DEBGCC=default ./courier-debuild -us -uc
fi
rm -f /usr/local/bin/courierauthconfig
debs=$(find /src -name '*.deb' -type f | grep -v -- '-dbgsym' || true)
if [ -z "$debs" ]; then
  echo "courier deb missing" >&2
  exit 1
fi
for deb in $debs; do
  apt-get install -y "$deb" || dpkg -i "$deb"
done
apt-get install -y -f
if ! find /usr -name 'authuserdb*' | grep -q .; then
  apt-get install -y courier-authlib-userdb
fi
EOF
chmod 755 "$CO_BUILD/build-inside.sh"
cat > "$CO_BUILD/entrypoint.sh" <<'EOF'
#!/bin/sh
set -eu
if [ ! -f /etc/courier/authdaemonrc ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y courier-authdaemon
fi
conf=$(find /etc -name authdaemonrc 2>/dev/null | head -n 1)
if [ -z "$conf" ]; then
  echo "authdaemonrc missing" >&2
  exit 1
fi
if grep -q '^authmodulelist' "$conf"; then
  sed -i 's/^authmodulelist=.*/authmodulelist="authuserdb"/' "$conf"
else
  echo 'authmodulelist="authuserdb"' >> "$conf"
fi
# ADDRESS=0 is Courier's any-interface setting. Do not write a dotted wildcard.
for imapd in /etc/courier/imapd /usr/lib/courier-imap/etc/imapd; do
  if [ -f "$imapd" ]; then
    sed -i 's/^ADDRESS=0\.0\.0\.0$/ADDRESS=0/' "$imapd"
    if ! grep -q '^PORT=' "$imapd"; then
      echo 'PORT=143' >> "$imapd"
    fi
  fi
done
USERDB=$(command -v userdb || true)
USERDBPW=$(command -v userdbpw || true)
MAKEUSERDB=$(command -v makeuserdb || true)
MAILDIRMAKE=$(command -v maildirmake || command -v maildirmake.courier || true)
if [ -z "$MAILDIRMAKE" ] && [ -x /usr/lib/courier-imap/bin/maildirmake ]; then
  MAILDIRMAKE=/usr/lib/courier-imap/bin/maildirmake
fi
if [ -z "$USERDB" ] || [ -z "$USERDBPW" ] || [ -z "$MAKEUSERDB" ] || [ -z "$MAILDIRMAKE" ]; then
  echo "courier userdb tools missing" >&2
  exit 1
fi
uid=2500
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 "\t" $2 }' /run/liveimap/accounts.tsv > /tmp/base-users
while IFS=$(printf '\t') read -r user pass; do
  [ -n "$user" ] || continue
  home=/var/mail/$user
  mkdir -p "$home"
  if ! id "$user" >/dev/null 2>&1; then
    useradd -M -u "$uid" -d "$home" -s /usr/sbin/nologin "$user"
  fi
  gid=$(id -g "$user")
  uid_now=$(id -u "$user")
  "$USERDB" "$user" set "uid=$uid_now" "gid=$gid" "home=$home" "mail=$home/Maildir" >/dev/null
  printf '%s\n' "$pass" | "$USERDBPW" -md5 | "$USERDB" "$user" set systempw >/dev/null
  if [ ! -d "$home/Maildir" ]; then
    "$MAILDIRMAKE" "$home/Maildir"
  fi
  chown -R "$user:$user" "$home"
  uid=$((uid + 1))
done < /tmp/base-users
rm -f /tmp/base-users
"$MAKEUSERDB"
if [ -x /etc/init.d/courier-authdaemon ]; then
  /etc/init.d/courier-authdaemon start
elif [ -x /etc/init.d/courier-auth ]; then
  /etc/init.d/courier-auth start
fi
if [ -x /etc/init.d/courier-imap ]; then
  /etc/init.d/courier-imap start
elif [ -x /usr/lib/courier-imap/libexec/imapd.rc ]; then
  /usr/lib/courier-imap/libexec/imapd.rc start
elif [ -x /etc/init.d/courier-imapd ]; then
  /etc/init.d/courier-imapd start
fi
exec sleep infinity
EOF
chmod 755 "$CO_BUILD/entrypoint.sh"
cat > "$CO_BUILD/Dockerfile" <<'EOF'
FROM debian:bookworm
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*
COPY courier-imap-6.0.6.tar.bz2 /src/courier-imap-6.0.6.tar.bz2
COPY courier-unicode-2.6.0.tar.bz2 /src/courier-unicode-2.6.0.tar.bz2
COPY build-inside.sh /src/build-inside.sh
RUN chmod 755 /src/build-inside.sh && /src/build-inside.sh
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
EOF

if docker image inspect liveimap-cyrus-3124:phase1 >/dev/null 2>&1; then
  echo "reusing cyrus image"
else
  echo "building cyrus image"
  docker build -t liveimap-cyrus-3124:phase1 "$CY_BUILD"
fi
echo "building courier image"
docker build -t liveimap-courier-606:phase1 "$CO_BUILD"

sink_port >/dev/null
echo "drawing ports"
PORTS=$(draw_ports 10)

# shellcheck disable=SC2086
set -- $PORTS
ST_IMAP=$1
ST_IMAPS=$2
ST_SIEVE=$3
ST_HTTPS=$4
ST_SUB=$5
shift 5
DV_IMAP=$1
CY_IMAP=$2
CY_HTTP=$3
CO_IMAP=$4
DV_SUB=$5

echo "starting dovecot stalwart"
assign_cports liveimap-dovecot-245
assign_cports liveimap-stalwart-01625
assign_cports liveimap-cyrus-3124
assign_cports liveimap-courier-606
create_dovecot_245 "$DV_IMAP" "$DV_SUB"

umask 007
ST_ADMIN_PASS=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
printf '%s\n' "$ST_ADMIN_PASS" > "$ST_ADMIN_FILE"
umask 022
create_from_seed stalwart-01625 \
  -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
  -p "${ST_IMAP}:${cp_stalwart01625_imap}" \
  -p "${ST_IMAPS}:${cp_stalwart01625_imaps}" \
  -p "${ST_SIEVE}:${cp_stalwart01625_managesieve}" \
  -p "${ST_HTTPS}:${cp_stalwart01625_https}" \
  -p "${ST_SUB}:${cp_stalwart01625_submission}"
unset ST_ADMIN_PASS

ready=0
i=0
while [ "$i" -lt 120 ]; do
  if docker exec liveimap-stalwart-01625 /bin/sh -c 'curl -fsS -o /dev/null http://127.0.0.1:8080/healthz/live'; then
    ready=1
    break
  fi
  i=$((i + 1))
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "stalwart management listener did not open" >&2
  exit 1
fi

listener_json=$(docker exec -i liveimap-stalwart-01625 /bin/sh -c 'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap' <<'JSON'
{"using":["urn:ietf:params:jmap:core","urn:stalwart:jmap"],"methodCalls":[["x:NetworkListener/set",{"create":{"imap143":{"name":"imap","protocol":"imap","bind":{"[::]:143":true},"tlsImplicit":false,"useTls":true}}},"c1"]]}
JSON
)
case "$listener_json" in
  *'"created"'*) ;;
  *)
    echo "stalwart imap listener was not created" >&2
    exit 1
    ;;
esac
unset listener_json
docker restart liveimap-stalwart-01625 >/dev/null
ready=0
i=0
while [ "$i" -lt 120 ]; do
  if docker exec liveimap-stalwart-01625 /bin/sh -c 'curl -fsS -o /dev/null http://127.0.0.1:8080/healthz/live'; then
    ready=1
    break
  fi
  i=$((i + 1))
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "stalwart did not return after restart" >&2
  exit 1
fi

# v0.16.25 has no /api/principal route. The same JMAP URL used for the
# listener creates x:Account records. Unqualified LOGIN uses localhost.local.
python3 - "$SEED" <<'PY' | docker exec -i liveimap-stalwart-01625 /bin/sh -c 'curl -sS -o /tmp/acct-body -w "%{http_code}" -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap > /tmp/acct-code'
import csv
import json
import sys

seed = sys.argv[1]
users = []
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("profile") != "base":
            continue
        users.append((row["user"], row["password"]))
creates = {
    "d1": {"name": "localhost.local"},
}
account_creates = {}
for index, (user, password) in enumerate(users):
    account_creates["a%d" % index] = {
        "name": user,
        "@type": "User",
        "domainId": "#d1",
        "credentials": {"0": {"@type": "Password", "secret": password}},
    }
body = {
    "using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"],
    "methodCalls": [
        ["x:Domain/set", {"create": creates}, "c1"],
        ["x:Account/set", {"create": account_creates}, "c2"],
    ],
}
sys.stdout.buffer.write(json.dumps(body).encode("utf-8"))
PY
st_code=$(docker exec liveimap-stalwart-01625 cat /tmp/acct-code)
st_body=$(docker exec liveimap-stalwart-01625 cat /tmp/acct-body)
case "$st_body" in
  *notCreated*)
    python3 - "$SEED" "$ACCOUNTS_OUT" "$st_code" "$st_body" <<'PY'
import csv
import sys

seed, dest, code, body = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("password"):
            body = body.replace(row["password"], "[redacted]")
excerpt = body.replace("\n", " ")[:400]
with open(dest, "a", encoding="utf-8") as handle:
    handle.write("stalwart principal %s %s\n" % (code, excerpt))
sys.exit(1)
PY
    ;;
  *'"created"'*) ;;
  *)
    python3 - "$SEED" "$ACCOUNTS_OUT" "$st_code" "$st_body" <<'PY'
import csv
import sys

seed, dest, code, body = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
passwords = []
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("password"):
            passwords.append(row["password"])
for password in passwords:
    body = body.replace(password, "[redacted]")
excerpt = body.replace("\n", " ")[:400]
with open(dest, "a", encoding="utf-8") as handle:
    handle.write("stalwart principal %s %s\n" % (code, excerpt))
sys.exit(1)
PY
    ;;
esac
unset st_code st_body

echo "starting cyrus and courier"
create_from_seed cyrus-3124 \
  -p "${CY_IMAP}:${cp_cyrus3124_imap}" \
  -p "${CY_HTTP}:${cp_cyrus3124_http}"
create_from_seed courier-606 \
  -p "${CO_IMAP}:${cp_courier606_imap}"

echo "login append jmap"
python3 - "$SEED" "$ACCOUNTS_OUT" "$FETCH_OUT" \
  "$ST_IMAP" "$ST_HTTPS" "$DV_IMAP" "$CY_IMAP" "$CY_HTTP" "$CO_IMAP" <<'PY'
import base64
import csv
import json
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request

(
    seed,
    accounts_out,
    fetch_out,
    st_imap,
    st_https,
    dv_imap,
    cy_imap,
    cy_http,
    co_imap,
) = sys.argv[1:]
ports = {
    "stalwart01625": int(st_imap),
    "dovecot245": int(dv_imap),
    "cyrus3124": int(cy_imap),
    "courier606": int(co_imap),
}
order = ["stalwart01625", "dovecot245", "cyrus3124", "courier606"]
host = "127.0.0.1"
users = []
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("profile") == "base":
            users.append((row["user"], row["password"]))
password_of = dict(users)
ascii_users = ["liveimap", "liveimap2", "liveimap-empty"]

message = (
    "From: liveimap@test\r\n"
    "To: liveimap@test\r\n"
    "Subject: Lab note one\r\n"
    "Date: Fri, 9 Oct 2026 12:30:00 -0700\r\n"
    "MIME-Version: 1.0\r\n"
    "Content-Type: text/plain; charset=utf-8\r\n"
    "\r\n"
    "Lab server note.\r\n"
).encode("utf-8")

def wait_tcp(port, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            sock = socket.create_connection((host, port), 2)
        except OSError:
            time.sleep(0.5)
            continue
        sock.close()
        return True
    return False

class Imap:
    def __init__(self, port):
        self.s = socket.create_connection((host, port), 10)
        self.s.settimeout(30)
        self.buf = b""
        self.n = 0

    def readline(self):
        while b"\n" not in self.buf:
            chunk = self.s.recv(8192)
            if not chunk:
                break
            self.buf += chunk
        line, sep, rest = self.buf.partition(b"\n")
        if not sep and not line:
            raise OSError("imap closed")
        self.buf = rest
        return line.decode("utf-8", "replace").rstrip("\r")

    def consume_literal(self, line):
        match = re.search(r"\{(\d+)\}\s*$", line)
        if not match:
            return
        need = int(match.group(1))
        while len(self.buf) < need:
            chunk = self.s.recv(8192)
            if not chunk:
                break
            self.buf += chunk
        self.buf = self.buf[need:]

    def read_tag(self, tag):
        lines = []
        while True:
            line = self.readline()
            lines.append(line)
            self.consume_literal(line)
            if line.startswith(tag + " "):
                return lines

    def command(self, text):
        self.n += 1
        tag = "a%d" % self.n
        self.s.sendall(("%s %s\r\n" % (tag, text)).encode("utf-8"))
        return self.read_tag(tag)

    def starttls(self):
        lines = self.command("STARTTLS")
        if not any(" OK " in line or line.endswith(" OK") or " OK" in line for line in lines):
            raise OSError("starttls failed")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.s = ctx.wrap_socket(self.s, server_hostname="localhost")
        self.buf = b""

    def login(self, user, password):
        if user.isascii() and password.isascii() and " " not in password and '"' not in password:
            return self.command('LOGIN %s "%s"' % (user, password))
        literal = password.encode("utf-8")
        self.n += 1
        tag = "a%d" % self.n
        self.s.sendall(("%s LOGIN %s {%d}\r\n" % (tag, user, len(literal))).encode("ascii"))
        cont = self.readline()
        if not cont.startswith("+"):
            return [cont]
        self.s.sendall(literal + b"\r\n")
        return self.read_tag(tag)

def ok_lines(lines):
    for line in lines:
        parts = line.split(" ", 2)
        if len(parts) >= 2 and parts[1] == "OK":
            return True
    return False

account_lines = ["uw-imap 2007f no release after 2007f and no dev branch\n"]
fetch_lines = []
failed = False

for server in order:
    port = ports[server]
    if not wait_tcp(port, 180):
        sys.stderr.write("imap port did not open for %s\n" % server)
        sys.exit(1)
    deadline = time.time() + 180
    greeting = ""
    imap = None
    while time.time() < deadline:
        try:
            imap = Imap(port)
            greeting = imap.readline()
        except OSError:
            time.sleep(0.5)
            continue
        if "OK" in greeting:
            break
        time.sleep(0.5)
    if imap is None or "OK" not in greeting:
        sys.stderr.write("greeting missing OK for %s\n" % server)
        sys.exit(1)
    if "LOGINDISABLED" in greeting:
        imap.starttls()
    live_ok = False
    for user, password in users:
        lines = imap.login(user, password)
        good = ok_lines(lines)
        if user == "liveimap-utf8" and not good:
            account_lines.append("%s %s utf8-login failed\n" % (user, server))
        else:
            account_lines.append("%s %s %s\n" % (user, server, "ok" if good else "failed"))
        if user in ascii_users and not good:
            failed = True
        if user == "liveimap" and good:
            live_ok = True
        if good:
            imap.command("LOGOUT")
            imap = Imap(port)
            greeting = imap.readline()
            if "LOGINDISABLED" in greeting:
                imap.starttls()
    if live_ok:
        imap.login("liveimap", password_of["liveimap"])
        appended = None
        for _attempt in range(30):
            imap.n += 1
            tag = "a%d" % imap.n
            imap.s.sendall(("%s APPEND INBOX {%d}\r\n" % (tag, len(message))).encode("ascii"))
            cont = imap.readline()
            if cont.startswith("+"):
                imap.s.sendall(message + b"\r\n")
                appended = imap.read_tag(tag)
                if ok_lines(appended):
                    break
                sys.stderr.write("append failed for %s\n" % server)
                sys.exit(1)
            if not cont.startswith(tag + " "):
                imap.read_tag(tag)
            time.sleep(1)
        if not appended:
            sys.stderr.write("append failed for %s\n" % server)
            sys.exit(1)
        uid = None
        for line in appended:
            match = re.search(r"APPENDUID \d+ (\d+)", line)
            if match:
                uid = match.group(1)
        imap.command("SELECT INBOX")
        if uid:
            fetched = imap.command("UID FETCH %s BODY.PEEK[1]<0.32>" % uid)
        else:
            fetched = imap.command("FETCH 1 BODY.PEEK[1]<0.32>")
        prefix = ""
        count = ""
        for line in fetched:
            start = line.find("BODY")
            if start < 0:
                continue
            brace = line.find("{", start)
            end = line.find("}", brace if brace >= 0 else start)
            if brace >= 0 and end > brace:
                prefix = line[start : end + 1]
                count = line[brace + 1 : end]
                break
        if not prefix:
            sys.stderr.write("fetch prefix missing for %s\n" % server)
            sys.exit(1)
        fetch_lines.append("%s %s %s\n" % (server, prefix, count))
        imap.command("LOGOUT")
    else:
        sys.stderr.write("liveimap login failed for %s\n" % server)
        sys.exit(1)

def http_get(url, user, password, tls=False):
    req = urllib.request.Request(url)
    if user:
        token = base64.b64encode(("%s:%s" % (user, password)).encode("utf-8")).decode("ascii")
        req.add_header("Authorization", "Basic " + token)
    ctx = ssl._create_unverified_context() if tls else None
    try:
        with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode("utf-8", "replace")

for server in ("dovecot245", "courier606"):
    account_lines.append("jmap %s no JMAP listener\n" % server)

cy_status, cy_body = http_get(
    "http://%s:%s/.well-known/jmap" % (host, cy_http),
    "liveimap",
    password_of["liveimap"],
)
cy_urn = "urn:ietf:params:jmap:mail" in cy_body
account_lines.append(
    "jmap cyrus3124 %s %s\n" % (cy_status, "urn:ietf:params:jmap:mail" if cy_urn else "missing-urn")
)
if cy_status != 200 or not cy_urn:
    failed = True

st_status, st_body = http_get(
    "https://%s:%s/.well-known/jmap" % (host, st_https),
    "liveimap",
    password_of["liveimap"],
    tls=True,
)
st_urn = "urn:ietf:params:jmap:mail" in st_body
st_count = 0
try:
    parsed = json.loads(st_body)
    accounts = parsed.get("accounts")
    if isinstance(accounts, dict):
        st_count = len(accounts)
except json.JSONDecodeError:
    st_count = 0
account_lines.append(
    "jmap stalwart01625 %s %s accounts=%s\n"
    % (st_status, "urn:ietf:params:jmap:mail" if st_urn else "missing-urn", st_count)
)
if not st_urn:
    failed = True

with open(accounts_out, "w", encoding="utf-8") as handle:
    handle.writelines(account_lines)
with open(fetch_out, "w", encoding="utf-8") as handle:
    handle.writelines(fetch_lines)
if failed:
    sys.exit(1)
PY

{
  printf 'stalwart01625-imap\t%s\t%s\tliveimap-stalwart-01625\n' "$ST_IMAP" "$cp_stalwart01625_imap"
  printf 'stalwart01625-imaps\t%s\t%s\tliveimap-stalwart-01625\n' "$ST_IMAPS" "$cp_stalwart01625_imaps"
  printf 'stalwart01625-managesieve\t%s\t%s\tliveimap-stalwart-01625\n' "$ST_SIEVE" "$cp_stalwart01625_managesieve"
  printf 'stalwart01625-https\t%s\t%s\tliveimap-stalwart-01625\n' "$ST_HTTPS" "$cp_stalwart01625_https"
  printf 'stalwart01625-submission\t%s\t%s\tliveimap-stalwart-01625\n' "$ST_SUB" "$cp_stalwart01625_submission"
  printf 'dovecot245-imap\t%s\t%s\tliveimap-dovecot-245\n' "$DV_IMAP" "$cp_dovecot245_imap"
  printf 'dovecot245-submission\t%s\t%s\tliveimap-dovecot-245\n' "$DV_SUB" "$cp_dovecot245_submission"
  printf 'cyrus3124-imap\t%s\t%s\tliveimap-cyrus-3124\n' "$CY_IMAP" "$cp_cyrus3124_imap"
  printf 'cyrus3124-http\t%s\t%s\tliveimap-cyrus-3124\n' "$CY_HTTP" "$cp_cyrus3124_http"
  printf 'courier606-imap\t%s\t%s\tliveimap-courier-606\n' "$CO_IMAP" "$cp_courier606_imap"
} > "$GEN/port-rows.new"
replace_port_rows \
  "liveimap-stalwart-01625,liveimap-dovecot-245,liveimap-cyrus-3124,liveimap-courier-606" \
  "" \
  "$GEN/port-rows.new"
rm -f "$GEN/port-rows.new"

{
  printf 'image\tstalwartlabs/stalwart:v0.16.25\n'
  printf 'image\tdovecot/dovecot:2.4.5\n'
  printf 'tarball\tcyrus-imapd-3.12.4.tar.gz\t%s\n' "$(cat "$CY_BUILD/sha256")"
  printf 'tarball\tcourier-imap-6.0.6.tar.bz2\t%s\t%s\n' "$(cat "$CO_BUILD/sha256")" "$(cat "$CO_BUILD/courier.url")"
} > "$PINS_OUT"

echo "containers"
docker inspect -f '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}}' \
  liveimap-stalwart-01625 liveimap-dovecot-245 \
  liveimap-cyrus-3124 liveimap-courier-606
echo "ports"
echo "stalwart01625-imap $ST_IMAP"
echo "stalwart01625-imaps $ST_IMAPS"
echo "stalwart01625-managesieve $ST_SIEVE"
echo "stalwart01625-https $ST_HTTPS"
echo "stalwart01625-submission $ST_SUB"
echo "dovecot245-imap $DV_IMAP"
echo "dovecot245-submission $DV_SUB"
echo "cyrus3124-imap $CY_IMAP"
echo "cyrus3124-http $CY_HTTP"
echo "courier606-imap $CO_IMAP"
echo "accounts"
cat "$ACCOUNTS_OUT"
echo "fetch"
cat "$FETCH_OUT"
fi
for name in liveimap-stalwart-01625 liveimap-dovecot-245 \
  liveimap-cyrus-3124 liveimap-courier-606; do
  if container_exists "$name"; then
    docker stop "$name"
  fi
done
