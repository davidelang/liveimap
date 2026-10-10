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

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -qx "$1"
}

container_running() {
  docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}

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

docker run -d --name liveimap-greenmail \
  -p "${GM_IMAP}:3143" \
  -p "${GM_IMAPS}:3993" \
  -p "${GM_SMTP}:3025" \
  -p "${GM_API}:8080" \
  greenmail/standalone:2.1.13

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
docker run -d --name liveimap-stalwart \
  -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
  -p "${ST_IMAP}:143" \
  -p "${ST_IMAPS}:993" \
  -p "${ST_SIEVE}:4190" \
  -p "${ST_HTTPS}:443" \
  -p "${ST_SUB}:587" \
  -v liveimap-stalwart-etc:/etc/stalwart \
  -v liveimap-stalwart-data:/var/lib/stalwart \
  stalwartlabs/stalwart:v0.16
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
  printf 'service\thost_port\tcontainer_port\tcontainer\n'
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
} >"$PORTS_FILE"

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
