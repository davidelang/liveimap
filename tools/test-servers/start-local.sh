#!/bin/sh
# Start GreenMail and Stalwart on nine free high ports. No install, no app.
set -eu

SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")
SEED="$DIR/seed/accounts.tsv"
GEN="$DIR/generated"
PORTS_FILE="$GEN/local-ports.tsv"
ADMIN_FILE="$GEN/stalwart-admin.txt"

if [ ! -f "$SEED" ]; then
  echo "missing seed file" >&2
  exit 1
fi

command -v docker >/dev/null
command -v python3 >/dev/null
command -v ss >/dev/null
command -v openssl >/dev/null

reseed_prepare() {
  server=$1
  image=$2
  mkdir -p "$GEN/$server"
  if [ ! -f "$GEN/corpus-v1/manifest.tsv" ] || [ ! -f "$DIR/seed/accounts.tsv" ] \
    || [ ! -f "$DIR/seed/folders.tsv" ] || [ ! -f "$DIR/seed/corpus-v1.sums" ]; then
    echo "missing seed inputs for $server" >&2
    exit 1
  fi
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker pull "$image"
  fi
  LIVEIMAP_IMAGE_ID=$(docker image inspect -f '{{.Id}}' "$image")
  chmod a+r "$GEN/corpus-v1/manifest.tsv" "$DIR/seed/accounts.tsv" \
    "$DIR/seed/folders.tsv" "$DIR/seed/corpus-v1.sums" 2>/dev/null || true
  chmod a+rx "$GEN/$server" "$GEN/corpus-v1" "$DIR/seed" 2>/dev/null || true
}

reseed_mount_words() {
  server=$1
  printf '%s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s' \
    -v "$GEN/$server:/liveimap-seed:ro" \
    -v "$GEN/corpus-v1/manifest.tsv:/liveimap-check/manifest.tsv:ro" \
    -v "$DIR/seed/accounts.tsv:/liveimap-check/accounts.tsv:ro" \
    -v "$DIR/seed/folders.tsv:/liveimap-check/folders.tsv:ro" \
    -v "$DIR/seed/corpus-v1.sums:/liveimap-check/corpus-v1.sums:ro" \
    -v "$GEN/corpus-v1:/liveimap-corpus:ro" \
    -e "LIVEIMAP_SERVER=$server" \
    -e "LIVEIMAP_IMAGE_ID=$LIVEIMAP_IMAGE_ID"
}

reseed_exec_words() {
  image=$1
  mode=$2
  python3 - "$image" "$mode" <<'PY'
import json, shlex, subprocess, sys
image, mode = sys.argv[1], sys.argv[2]
cfg = json.loads(subprocess.check_output(["docker", "image", "inspect", image]))[0]["Config"]
args = list(cfg.get("Entrypoint") or []) + list(cfg.get("Cmd") or [])
sys.stdout.write(mode + " -- " + " ".join(shlex.quote(a) for a in args))
PY
}

install_reseed() {
  name=$1
  flag=$(mktemp)
  printf 'no\n' > "$flag"
  docker cp "$DIR/reseed.sh" "$name":/liveimap-reseed
  docker cp "$flag" "$name":/.liveimap-no-reseed
  rm -f "$flag"
}

patch_saved_entrypoint() {
  name=$1
  kind=$2
  tmp=$(mktemp)
  if ! docker cp "$name":/entrypoint.sh "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    return 0
  fi
  python3 - "$tmp" "$kind" <<'PY'
import sys
path, kind = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8", errors="replace").read()
orig = text
if kind == "cyrus":
    needle = 'su -s /bin/sh cyrus -c "$CTL -r"'
    if "/liveimap-reseed" not in text and needle in text:
        text = text.replace(needle, "/liveimap-reseed\n" + needle, 1)
    mark = "if [ ! -f /tmp/liveimap-restored ]; then\n"
    start = "awk -F '\\t' 'NR > 1 && $3 == \"base\" { print $1 }' /run/liveimap/accounts.tsv > /tmp/base-names\n"
    if "/tmp/liveimap-restored" not in text and start in text:
        text = text.replace(start, mark + start, 1)
        end = "rm -f /tmp/base-names /tmp/imtest.out /tmp/create-cmd\n"
        if end in text:
            text = text.replace(end, end + "fi\n", 1)
elif kind == "courier":
    needle = '"$MAKEUSERDB"\n'
    if "/liveimap-reseed" not in text and needle in text:
        text = text.replace(needle, "/liveimap-reseed\n" + needle, 1)
elif kind == "dovecot":
    needle = "exec /usr/local/sbin/dovecot -F\n"
    if "/liveimap-reseed" not in text and needle in text:
        text = text.replace(needle, "/liveimap-reseed\n" + needle, 1)
if text != orig:
    open(path, "w", encoding="utf-8").write(text)
PY
  chmod 755 "$tmp"
  docker cp "$tmp" "$name":/entrypoint.sh
  rm -f "$tmp"
}

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -qx "$1"
}

container_running() {
  docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}

expected_ports() {
  case "$1" in
    liveimap-greenmail) echo "3143 3993 3025 8080" ;;
    liveimap-stalwart) echo "143 993 4190 443 587" ;;
    *) echo "expected_ports: unknown container $1" >&2; exit 1 ;;
  esac
}

server_owner() {
  case "$1" in
    greenmail-214|stalwart-01625|dovecot-245|cyrus-3124|courier-606)
      printf '%s\n' "$DIR/start-latest.sh" ;;
    greenmail-dev|stalwart-dev|dovecot-dev|cyrus-3137)
      printf '%s\n' "$DIR/start-dev.sh" ;;
    cyrus-22|cyrus-24|cyrus-25|cyrus-36|dovecot-10|dovecot-12|dovecot-22|courier-50|uwimap|greenmail-16|stalwart-0155)
      printf '%s\n' "$DIR/start-older.sh" ;;
    greenmail|stalwart)
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
  # shellcheck disable=SC2046
  if ! container_ok "$name" $(expected_ports "$name"); then
    echo "$SCRIPT --recreate $server" >&2
    docker stop "$name"
    exit 1
  fi
  docker stop "$name" >/dev/null
}

if [ "${1:-}" = --recreate ]; then
  if [ $# -ne 2 ] || [ -z "${2:-}" ]; then
    echo "ERROR: usage: $SCRIPT --recreate <server>" >&2
    exit 1
  fi
  require_owned "$2"
  server=$2
  name=liveimap-$server
  remove_one "$name"
  rows="$GEN/port-rows.new"
  case "$server" in
    greenmail)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        greenmail-imap:3143:liveimap-greenmail \
        greenmail-imaps:3993:liveimap-greenmail \
        greenmail-smtp:3025:liveimap-greenmail \
        greenmail-api:8080:liveimap-greenmail)
      docker pull greenmail/standalone:2.1.13
      reseed_prepare greenmail greenmail/standalone:2.1.13
      # shellcheck disable=SC2046
      docker create --name liveimap-greenmail \
        $(reseed_mount_words greenmail) \
        --entrypoint /liveimap-reseed \
        -p "${1}:3143" \
        -p "${2}:3993" \
        -p "${3}:3025" \
        -p "${4}:8080" \
        greenmail/standalone:2.1.13 \
        $(reseed_exec_words greenmail/standalone:2.1.13 --greenmail)
      install_reseed liveimap-greenmail
      docker start liveimap-greenmail
      {
        printf 'greenmail-imap\t%s\t3143\tliveimap-greenmail\n' "$1"
        printf 'greenmail-imaps\t%s\t3993\tliveimap-greenmail\n' "$2"
        printf 'greenmail-smtp\t%s\t3025\tliveimap-greenmail\n' "$3"
        printf 'greenmail-api\t%s\t8080\tliveimap-greenmail\n' "$4"
      } > "$rows"
      replace_port_rows liveimap-greenmail "" "$rows"
      ;;
    stalwart)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        stalwart-imap:143:liveimap-stalwart \
        stalwart-imaps:993:liveimap-stalwart \
        stalwart-managesieve:4190:liveimap-stalwart \
        stalwart-https:443:liveimap-stalwart \
        stalwart-submission:587:liveimap-stalwart)
      docker pull stalwartlabs/stalwart:v0.16
      if ! docker volume inspect liveimap-stalwart-etc >/dev/null 2>&1; then
        docker volume create liveimap-stalwart-etc >/dev/null
      fi
      if ! docker volume inspect liveimap-stalwart-data >/dev/null 2>&1; then
        docker volume create liveimap-stalwart-data >/dev/null
      fi
      if ! docker run --rm --user 0 --entrypoint /bin/sh \
        -v liveimap-stalwart-etc:/etc/stalwart \
        stalwartlabs/stalwart:v0.16 \
        -c 'test -s /etc/stalwart/config.json'; then
        docker run --rm --user 0 --entrypoint /bin/sh \
          -v liveimap-stalwart-etc:/etc/stalwart \
          stalwartlabs/stalwart:v0.16 \
          -c 'umask 077; printf %s "{\"@type\":\"RocksDb\",\"path\":\"/var/lib/stalwart/\"}" > /etc/stalwart/config.json; chown 2000:2000 /etc/stalwart/config.json'
      fi
      ST_ADMIN_PASS=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
      reseed_prepare stalwart stalwartlabs/stalwart:v0.16
      # shellcheck disable=SC2046
      docker create --name liveimap-stalwart \
        $(reseed_mount_words stalwart) \
        --entrypoint /liveimap-reseed \
        -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
        -p "${1}:143" \
        -p "${2}:993" \
        -p "${3}:4190" \
        -p "${4}:443" \
        -p "${5}:587" \
        -v liveimap-stalwart-etc:/etc/stalwart \
        -v liveimap-stalwart-data:/var/lib/stalwart \
        stalwartlabs/stalwart:v0.16 \
        $(reseed_exec_words stalwartlabs/stalwart:v0.16 --before)
      install_reseed liveimap-stalwart
      docker start liveimap-stalwart
      unset ST_ADMIN_PASS
      {
        printf 'stalwart-imap\t%s\t143\tliveimap-stalwart\n' "$1"
        printf 'stalwart-imaps\t%s\t993\tliveimap-stalwart\n' "$2"
        printf 'stalwart-managesieve\t%s\t4190\tliveimap-stalwart\n' "$3"
        printf 'stalwart-https\t%s\t443\tliveimap-stalwart\n' "$4"
        printf 'stalwart-submission\t%s\t587\tliveimap-stalwart\n' "$5"
      } > "$rows"
      replace_port_rows liveimap-stalwart "" "$rows"
      ;;
  esac
  rm -f "$rows"
  finish_recreate "$name" "$server"
  exit 0
fi

for name in liveimap-greenmail liveimap-stalwart; do
  check_existing_name "$name"
done
present=0
for name in liveimap-greenmail liveimap-stalwart; do
  if container_exists "$name"; then
    present=1
    if ! container_running "$name"; then
      docker start "$name"
    fi
  fi
done
if [ "$present" -eq 0 ]; then

PORTS=$(python3 - <<'PY'
import random
import socket
import subprocess
import sys

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
while len(chosen) < 9 and draws < 200:
    draws += 1
    port = random.randint(20000, 39999)
    if port in seen:
        continue
    seen.add(port)
    if listening(port) or not bindable(port):
        continue
    chosen.append(port)

if len(chosen) < 9:
    sys.exit(1)
print(" ".join(str(p) for p in chosen))
PY
)

# shellcheck disable=SC2086
set -- $PORTS
GM_IMAP=$1
GM_IMAPS=$2
GM_SMTP=$3
GM_API=$4
ST_IMAP=$5
ST_IMAPS=$6
ST_SIEVE=$7
ST_HTTPS=$8
ST_SUB=$9

docker pull greenmail/standalone:2.1.13
docker pull stalwartlabs/stalwart:v0.16

reseed_prepare greenmail greenmail/standalone:2.1.13
# shellcheck disable=SC2046
docker create --name liveimap-greenmail \
  $(reseed_mount_words greenmail) \
  --entrypoint /liveimap-reseed \
  -p "${GM_IMAP}:3143" \
  -p "${GM_IMAPS}:3993" \
  -p "${GM_SMTP}:3025" \
  -p "${GM_API}:8080" \
  greenmail/standalone:2.1.13 \
  $(reseed_exec_words greenmail/standalone:2.1.13 --greenmail)
install_reseed liveimap-greenmail
docker start liveimap-greenmail

if ! docker volume inspect liveimap-stalwart-etc >/dev/null 2>&1; then
  docker volume create liveimap-stalwart-etc >/dev/null
fi
if ! docker volume inspect liveimap-stalwart-data >/dev/null 2>&1; then
  docker volume create liveimap-stalwart-data >/dev/null
fi

# v0.16 stays on the bootstrap listener until config.json exists, and the
# image defaults still omit cleartext IMAP. Seed the datastore, add that
# listener, and restart the same container so port 143 accepts a greeting.
if ! docker run --rm --user 0 --entrypoint /bin/sh \
  -v liveimap-stalwart-etc:/etc/stalwart \
  stalwartlabs/stalwart:v0.16 \
  -c 'test -s /etc/stalwart/config.json'; then
  docker run --rm --user 0 --entrypoint /bin/sh \
    -v liveimap-stalwart-etc:/etc/stalwart \
    stalwartlabs/stalwart:v0.16 \
    -c 'umask 077; printf %s "{\"@type\":\"RocksDb\",\"path\":\"/var/lib/stalwart/\"}" > /etc/stalwart/config.json; chown 2000:2000 /etc/stalwart/config.json'
fi

ST_ADMIN_PASS=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
reseed_prepare stalwart stalwartlabs/stalwart:v0.16
# shellcheck disable=SC2046
docker create --name liveimap-stalwart \
  $(reseed_mount_words stalwart) \
  --entrypoint /liveimap-reseed \
  -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
  -p "${ST_IMAP}:143" \
  -p "${ST_IMAPS}:993" \
  -p "${ST_SIEVE}:4190" \
  -p "${ST_HTTPS}:443" \
  -p "${ST_SUB}:587" \
  -v liveimap-stalwart-etc:/etc/stalwart \
  -v liveimap-stalwart-data:/var/lib/stalwart \
  stalwartlabs/stalwart:v0.16 \
  $(reseed_exec_words stalwartlabs/stalwart:v0.16 --before)
install_reseed liveimap-stalwart
docker start liveimap-stalwart
unset ST_ADMIN_PASS

ready=0
i=0
while [ "$i" -lt 120 ]; do
  if docker exec liveimap-stalwart /bin/sh -c 'curl -fsS -o /dev/null http://127.0.0.1:8080/healthz/live'; then
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

listener_json=$(docker exec -i liveimap-stalwart /bin/sh -c 'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap' <<'JSON'
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

docker restart liveimap-stalwart >/dev/null

python3 - "$GM_IMAP" "$ST_IMAP" "$ST_SIEVE" "$ST_HTTPS" <<'PY'
import socket
import subprocess
import sys
import time

gm_imap, st_imap, st_sieve, st_https = (int(x) for x in sys.argv[1:5])
HOST = "127.0.0.1"

def read_line(port, timeout):
    sock = socket.create_connection((HOST, port), timeout=timeout)
    try:
        sock.settimeout(timeout)
        buf = b""
        while b"\n" not in buf and len(buf) < 8192:
            chunk = sock.recv(1024)
            if not chunk:
                break
            buf += chunk
        return buf.decode("utf-8", "replace").strip("\r\n")
    finally:
        sock.close()

def wait_line(port, seconds, predicate):
    deadline = time.time() + seconds
    last = ""
    while time.time() < deadline:
        try:
            last = read_line(port, 3)
        except OSError:
            time.sleep(0.5)
            continue
        if predicate(last):
            return last
        time.sleep(0.5)
    sys.stderr.write("timed out waiting for port %s\n" % port)
    sys.exit(1)

def has_ok(line):
    return "OK" in line

def nonempty(line):
    return line != ""

wait_line(gm_imap, 60, has_ok)
wait_line(st_imap, 60, has_ok)
wait_line(st_sieve, 60, nonempty)

deadline = time.time() + 60
cert_ok = False
while time.time() < deadline:
    try:
        proc = subprocess.run(
            [
                "timeout",
                "15",
                "openssl",
                "s_client",
                "-connect",
                "%s:%s" % (HOST, st_https),
                "-servername",
                "localhost",
            ],
            input=b"",
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
    except OSError:
        time.sleep(0.5)
        continue
    text = proc.stdout.decode("utf-8", "replace")
    if "BEGIN CERTIFICATE" in text:
        cert_ok = True
        break
    time.sleep(0.5)
if not cert_ok:
    sys.stderr.write("https certificate missing\n")
    sys.exit(1)
print("IMAP_OK greenmail")
print("IMAP_OK stalwart")
print("SIEVE_OK")
print("HTTPS_CERT_OK")
PY

HTTP_CODE=$(python3 - "$SEED" "$GM_API" <<'PY'
import csv
import json
import sys
import urllib.error
import urllib.request

seed, port = sys.argv[1], sys.argv[2]
password = None
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("user") == "liveimap":
            password = row.get("password")
            break
if not password:
    sys.stderr.write("liveimap user missing\n")
    sys.exit(1)
body = json.dumps(
    {
        "email": "liveimap@localhost",
        "login": "liveimap",
        "password": password,
    }
).encode("utf-8")
req = urllib.request.Request(
    "http://127.0.0.1:%s/api/user" % port,
    data=body,
    headers={"Content-Type": "application/json"},
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=30) as resp:
        code = resp.status
        resp.read()
except urllib.error.HTTPError as err:
    code = err.code
    err.read()
print(code)
if code not in (200, 201):
    sys.exit(1)
PY
)

mkdir -p "$GEN"

lan_ipv4=
if command -v ip >/dev/null 2>&1; then
  iface=$(ip -4 route show default 2>/dev/null | awk '
    {
      for (i = 1; i <= NF; i++) if ($i == "dev") { print $(i + 1); exit }
    }
  ' || true)
  if [ -n "${iface:-}" ]; then
    lan_ipv4=$(ip -4 -o addr show dev "$iface" 2>/dev/null | awk '{ print $4; exit }' | cut -d/ -f1 || true)
  fi
fi

{
  if [ ! -s "$PORTS_FILE" ]; then
    printf 'service\thost_port\tcontainer_port\tcontainer\n'
  fi
  printf 'greenmail-imap\t%s\t3143\tliveimap-greenmail\n' "$GM_IMAP"
  printf 'greenmail-imaps\t%s\t3993\tliveimap-greenmail\n' "$GM_IMAPS"
  printf 'greenmail-smtp\t%s\t3025\tliveimap-greenmail\n' "$GM_SMTP"
  printf 'greenmail-api\t%s\t8080\tliveimap-greenmail\n' "$GM_API"
  printf 'stalwart-imap\t%s\t143\tliveimap-stalwart\n' "$ST_IMAP"
  printf 'stalwart-imaps\t%s\t993\tliveimap-stalwart\n' "$ST_IMAPS"
  printf 'stalwart-managesieve\t%s\t4190\tliveimap-stalwart\n' "$ST_SIEVE"
  printf 'stalwart-https\t%s\t443\tliveimap-stalwart\n' "$ST_HTTPS"
  printf 'stalwart-submission\t%s\t587\tliveimap-stalwart\n' "$ST_SUB"
  printf 'lan_ipv4\t%s\n' "$lan_ipv4"
} > "$GEN/port-rows.new"
replace_port_rows "liveimap-greenmail,liveimap-stalwart" lan_ipv4 "$GEN/port-rows.new"
rm -f "$GEN/port-rows.new"

docker logs liveimap-stalwart 2>&1 | head -n 80 >"$ADMIN_FILE"

echo "POST $HTTP_CODE"
echo "greenmail-imap $GM_IMAP"
echo "greenmail-imaps $GM_IMAPS"
echo "greenmail-smtp $GM_SMTP"
echo "greenmail-api $GM_API"
echo "stalwart-imap $ST_IMAP"
echo "stalwart-imaps $ST_IMAPS"
echo "stalwart-managesieve $ST_SIEVE"
echo "stalwart-https $ST_HTTPS"
echo "stalwart-submission $ST_SUB"
docker inspect -f '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}}' \
  liveimap-greenmail liveimap-stalwart
fi
for name in liveimap-greenmail liveimap-stalwart; do
  if container_exists "$name"; then
    docker stop "$name"
  fi
done
