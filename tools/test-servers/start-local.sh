#!/bin/sh
# Start Stalwart on five free high ports. No install, no app.
set -eu

SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")
cport() {
  python3 "$DIR/cport.py" "$1" "$2" || exit 1
}
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

expected_ports() {
  case "$1" in
    liveimap-stalwart) echo "$(cport stalwart imap) $(cport stalwart imaps) $(cport stalwart managesieve) $(cport stalwart https) $(cport stalwart submission)" ;;
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

if [ "${1:-}" = --reset ]; then
  if [ $# -ne 2 ] || [ -z "${2:-}" ]; then
    echo "ERROR: usage: $SCRIPT --reset <server>" >&2
    exit 1
  fi
  reset_from_seed "$2"
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
  case "$server" in
    stalwart)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        stalwart-imap:$(cport stalwart imap):liveimap-stalwart \
        stalwart-imaps:$(cport stalwart imaps):liveimap-stalwart \
        stalwart-managesieve:$(cport stalwart managesieve):liveimap-stalwart \
        stalwart-https:$(cport stalwart https):liveimap-stalwart \
        stalwart-submission:$(cport stalwart submission):liveimap-stalwart)
      ST_ADMIN_PASS=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
      create_from_seed stalwart \
        -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
        -p "${1}:$(cport stalwart imap)" \
        -p "${2}:$(cport stalwart imaps)" \
        -p "${3}:$(cport stalwart managesieve)" \
        -p "${4}:$(cport stalwart https)" \
        -p "${5}:$(cport stalwart submission)"
      unset ST_ADMIN_PASS
      {
        printf 'stalwart-imap\t%s\t%s\tliveimap-stalwart\n' "$1" "$(cport stalwart imap)"
        printf 'stalwart-imaps\t%s\t%s\tliveimap-stalwart\n' "$2" "$(cport stalwart imaps)"
        printf 'stalwart-managesieve\t%s\t%s\tliveimap-stalwart\n' "$3" "$(cport stalwart managesieve)"
        printf 'stalwart-https\t%s\t%s\tliveimap-stalwart\n' "$4" "$(cport stalwart https)"
        printf 'stalwart-submission\t%s\t%s\tliveimap-stalwart\n' "$5" "$(cport stalwart submission)"
      } > "$rows"
      replace_port_rows liveimap-stalwart "" "$rows"
      ;;
  esac
  rm -f "$rows"
  finish_recreate "$name" "$server"
  exit 0
fi

for name in liveimap-stalwart; do
  check_existing_name "$name"
done
present=0
for name in liveimap-stalwart; do
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
while len(chosen) < 5 and draws < 200:
    draws += 1
    port = random.randint(20000, 39999)
    if port in seen:
        continue
    seen.add(port)
    if listening(port) or not bindable(port):
        continue
    chosen.append(port)

if len(chosen) < 5:
    sys.exit(1)
print(" ".join(str(p) for p in chosen))
PY
)

# shellcheck disable=SC2086
set -- $PORTS
ST_IMAP=$1
ST_IMAPS=$2
ST_SIEVE=$3
ST_HTTPS=$4
ST_SUB=$5

require_seed_image stalwart
ST_ADMIN_PASS=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
create_from_seed stalwart \
  -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
  -p "${ST_IMAP}:$(cport stalwart imap)" \
  -p "${ST_IMAPS}:$(cport stalwart imaps)" \
  -p "${ST_SIEVE}:$(cport stalwart managesieve)" \
  -p "${ST_HTTPS}:$(cport stalwart https)" \
  -p "${ST_SUB}:$(cport stalwart submission)"
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

python3 - "$ST_IMAP" "$ST_SIEVE" "$ST_HTTPS" <<'PY'
import socket
import subprocess
import sys
import time

st_imap, st_sieve, st_https = (int(x) for x in sys.argv[1:4])
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
print("IMAP_OK stalwart")
print("SIEVE_OK")
print("HTTPS_CERT_OK")
PY

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
  printf 'stalwart-imap\t%s\t%s\tliveimap-stalwart\n' "$ST_IMAP" "$(cport stalwart imap)"
  printf 'stalwart-imaps\t%s\t%s\tliveimap-stalwart\n' "$ST_IMAPS" "$(cport stalwart imaps)"
  printf 'stalwart-managesieve\t%s\t%s\tliveimap-stalwart\n' "$ST_SIEVE" "$(cport stalwart managesieve)"
  printf 'stalwart-https\t%s\t%s\tliveimap-stalwart\n' "$ST_HTTPS" "$(cport stalwart https)"
  printf 'stalwart-submission\t%s\t%s\tliveimap-stalwart\n' "$ST_SUB" "$(cport stalwart submission)"
  printf 'lan_ipv4\t%s\n' "$lan_ipv4"
} > "$GEN/port-rows.new"
replace_port_rows "liveimap-stalwart" lan_ipv4 "$GEN/port-rows.new"
rm -f "$GEN/port-rows.new"

docker logs liveimap-stalwart 2>&1 | head -n 80 >"$ADMIN_FILE"

echo "stalwart-imap $ST_IMAP"
echo "stalwart-imaps $ST_IMAPS"
echo "stalwart-managesieve $ST_SIEVE"
echo "stalwart-https $ST_HTTPS"
echo "stalwart-submission $ST_SUB"
docker inspect -f '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}}' \
  liveimap-stalwart
fi
for name in liveimap-stalwart; do
  if container_exists "$name"; then
    docker stop "$name"
  fi
done
