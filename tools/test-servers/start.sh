#!/bin/sh
# Recreate or reset one lab server, or every lab server, from its seed image.
set -eu
SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")
GEN="$DIR/generated"
PORTS_FILE="$GEN/local-ports.tsv"
SEED="$DIR/seed/accounts.tsv"
ST_ADMIN_FILE="$GEN/stalwart-01625-admin.txt"
DV_CONF="$GEN/dovecot-dev"

cport() {
  python3 "$DIR/cport.py" "$1" "$2" || exit 1
}

if [ ! -f "$SEED" ]; then
  echo "missing seed file" >&2
  exit 1
fi
command -v docker >/dev/null
command -v python3 >/dev/null
command -v ss >/dev/null
command -v setfacl >/dev/null

require_seed_image() {
  server=$1
  if ! docker image inspect "liveimap-seed-$server" >/dev/null 2>&1; then
    echo "tools/test-servers/build-seed-image $server" >&2
    exit 1
  fi
}

reset_from_seed() {
  server=$1
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
    liveimap-stalwart-dev)
      cp_stalwartdev_imap=$(cport stalwart-dev imap)
      cp_stalwartdev_imaps=$(cport stalwart-dev imaps)
      cp_stalwartdev_managesieve=$(cport stalwart-dev managesieve)
      cp_stalwartdev_https=$(cport stalwart-dev https)
      cp_stalwartdev_submission=$(cport stalwart-dev submission)
      ;;
    liveimap-dovecot-dev)
      cp_dovecotdev_imap=$(cport dovecot-dev imap)
      ;;
    liveimap-cyrus-3137)
      cp_cyrus3137_imap=$(cport cyrus-3137 imap)
      cp_cyrus3137_http=$(cport cyrus-3137 http)
      ;;
    liveimap-stalwart)
      cp_stalwart_imap=$(cport stalwart imap)
      cp_stalwart_imaps=$(cport stalwart imaps)
      cp_stalwart_managesieve=$(cport stalwart managesieve)
      cp_stalwart_https=$(cport stalwart https)
      cp_stalwart_submission=$(cport stalwart submission)
      ;;
    liveimap-cyrus-22)
      cp_cyrus22_imap=$(cport cyrus-22 imap)
      ;;
    liveimap-cyrus-24)
      cp_cyrus24_imap=$(cport cyrus-24 imap)
      ;;
    liveimap-cyrus-25)
      cp_cyrus25_imap=$(cport cyrus-25 imap)
      ;;
    liveimap-dovecot-10)
      cp_dovecot10_imap=$(cport dovecot-10 imap)
      ;;
    liveimap-dovecot-12)
      cp_dovecot12_imap=$(cport dovecot-12 imap)
      ;;
    liveimap-dovecot-22)
      cp_dovecot22_imap=$(cport dovecot-22 imap)
      ;;
    liveimap-courier-50)
      cp_courier50_imap=$(cport courier-50 imap)
      ;;
    liveimap-uwimap)
      cp_uwimap_imap=$(cport uwimap imap)
      ;;
    liveimap-cyrus-36)
      cp_cyrus36_imap=$(cport cyrus-36 imap)
      cp_cyrus36_http=$(cport cyrus-36 http)
      ;;
    liveimap-stalwart-0155)
      cp_stalwart0155_imap=$(cport stalwart-0155 imap)
      cp_stalwart0155_imaps=$(cport stalwart-0155 imaps)
      cp_stalwart0155_managesieve=$(cport stalwart-0155 managesieve)
      cp_stalwart0155_https=$(cport stalwart-0155 https)
      cp_stalwart0155_submission=$(cport stalwart-0155 submission)
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
    liveimap-stalwart-dev) echo "$cp_stalwartdev_imap $cp_stalwartdev_imaps $cp_stalwartdev_managesieve $cp_stalwartdev_https $cp_stalwartdev_submission" ;;
    liveimap-dovecot-dev) echo "$cp_dovecotdev_imap" ;;
    liveimap-cyrus-3137) echo "$cp_cyrus3137_imap $cp_cyrus3137_http" ;;
    liveimap-stalwart) echo "$cp_stalwart_imap $cp_stalwart_imaps $cp_stalwart_managesieve $cp_stalwart_https $cp_stalwart_submission" ;;
    liveimap-cyrus-22) echo "$cp_cyrus22_imap" ;;
    liveimap-cyrus-24) echo "$cp_cyrus24_imap" ;;
    liveimap-cyrus-25) echo "$cp_cyrus25_imap" ;;
    liveimap-dovecot-10) echo "$cp_dovecot10_imap" ;;
    liveimap-dovecot-12) echo "$cp_dovecot12_imap" ;;
    liveimap-dovecot-22) echo "$cp_dovecot22_imap" ;;
    liveimap-courier-50) echo "$cp_courier50_imap" ;;
    liveimap-uwimap) echo "$cp_uwimap_imap" ;;
    liveimap-cyrus-36) echo "$cp_cyrus36_imap $cp_cyrus36_http" ;;
    liveimap-stalwart-0155) echo "$cp_stalwart0155_imap $cp_stalwart0155_imaps $cp_stalwart0155_managesieve $cp_stalwart0155_https $cp_stalwart0155_submission" ;;
    *) echo "expected_ports: unknown container $1" >&2; exit 1 ;;
  esac
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

remove_one() {
  if container_exists "$1"; then
    docker rm -f "$1" >/dev/null
  fi
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

sink_port() {
  count=0
  if [ -f "$PORTS_FILE" ]; then
    count=$(awk -F '\t' '$1 == "fake-smtp-sink" { n++ } END { print n + 0 }' "$PORTS_FILE")
  fi
  if [ "$count" -eq 0 ]; then
    port=$(draw_ports 1)
    case "$port" in
      ''|*[!0-9]*) echo "ERROR: no single fake-smtp-sink row in $PORTS_FILE; run tools/test-servers/start.sh --recreate dovecot-245" >&2; exit 1 ;;
    esac
    mkdir -p "$GEN"
    printf 'fake-smtp-sink\t%s\t%s\thost\n' "$port" "$port" >> "$PORTS_FILE"
    echo "$port"
    return
  fi
  if [ "$count" -ne 1 ]; then
    echo "ERROR: no single fake-smtp-sink row in $PORTS_FILE; run tools/test-servers/start.sh --recreate dovecot-245" >&2
    exit 1
  fi
  port=$(awk -F '\t' '$1 == "fake-smtp-sink" { print $2; exit }' "$PORTS_FILE")
  case "$port" in
    ''|*[!0-9]*) echo "ERROR: no single fake-smtp-sink row in $PORTS_FILE; run tools/test-servers/start.sh --recreate dovecot-245" >&2; exit 1 ;;
  esac
  echo "$port"
}

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
umask 022
printf 'submission_relay_host = %s\nsubmission_relay_port = %s\nsubmission_relay_trusted = yes\n' "$gateway" "$relay_port" > "$GEN/dovecot-245/relay.conf"
setfacl -m u:1000:r "$GEN/dovecot-245/auth.conf" "$GEN/dovecot-245/relay.conf"
create_from_seed dovecot-245 \
  -p "${dv_imap}:${cp_dovecot245_imap}" \
  -p "${dv_sub}:${cp_dovecot245_submission}"
}

write_dovecot_dev_files() {
  mkdir -p "$DV_CONF"
  umask 007
  cat > "$DV_CONF/auth.conf" <<'EOF'
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
  umask 022
  setfacl -m u:1000:r "$DV_CONF/auth.conf"
}


if [ $# -eq 1 ] && [ "$1" = --recreate-all ]; then
  servers=$(python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import lab; sys.stdout.write("\n".join(lab.SEED_SERVERS))' "$DIR") || exit 1
  for server in $servers; do
    "$SCRIPT" --recreate "$server" || exit 1
  done
  exit 0
fi
if [ "${1:-}" = --recreate-all ]; then
  echo "ERROR: usage: tools/test-servers/start.sh --recreate-all" >&2
  exit 1
fi
if [ "${1:-}" = --recreate-dovecot-245 ]; then
  set -- --recreate dovecot-245
fi
if [ "${1:-}" != --recreate ] && [ "${1:-}" != --reset ]; then
  echo "ERROR: usage: tools/test-servers/start.sh --recreate|--reset <server>" >&2
  exit 1
fi
if [ $# -ne 2 ] || [ -z "${2:-}" ]; then
  echo "ERROR: usage: tools/test-servers/start.sh --recreate|--reset <server>" >&2
  exit 1
fi
mode=$1
server=$2
if ! python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import lab; raise SystemExit(0 if sys.argv[2] in lab.SEED_SERVERS else 1)' "$DIR" "$server"; then
  echo "ERROR: $server is not a lab server" >&2
  exit 1
fi
if [ "$server" = zimbra-883 ]; then
python3 - "$DIR" "$mode" <<'PY'
import os
import re
import socket
import subprocess
import sys

tools = sys.argv[1]
mode = sys.argv[2]
sys.path.insert(0, tools)
import ports as listen_ports
name = "liveimap-zimbra-883"
image = "liveimap-seed-zimbra-883"
host = "127.0.0.1"
gen = os.path.join(tools, "generated")
ports_path = os.path.join(gen, "local-ports.tsv")
script = "tools/test-servers/start.sh"
mount_gen = gen
mount_seed = os.path.join(tools, "seed")
want_ports = {"143", "443", "7071"}
admin_pw = ""
ldap_pw = ""
redact_secrets = []

def run(args, timeout=120, env=None, input_text=None):
    return subprocess.run(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
        env=env,
        input=input_text,
        check=False,
    )
def fail(text):
    sys.stderr.write(redact(text).rstrip() + "\n")
    sys.exit(1)
def redact(text):
    if text is None:
        return ""
    out = text
    for secret in redact_secrets:
        if secret:
            out = out.replace(secret, "[redacted]")
    if ldap_pw:
        out = re.sub(
            r'((?:PASS(?:WORD)?|PW)[A-Z0-9_]*)(["\s:=]+)' + re.escape(ldap_pw) + r'\b',
            r"\1\2[redacted]",
            out,
            flags=re.IGNORECASE,
        )
    return out
def container_names():
    proc = run(["docker", "ps", "-a", "--format", "{{.Names}}"])
    if proc.returncode != 0:
        fail("docker ps failed")
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]
def listening_ports():
    found = set()
    proc = run(["ss", "-lnt"])
    if proc.returncode != 0:
        fail("ss failed")
    for line in proc.stdout.splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        _host, sep, got = parts[3].rpartition(":")
        if sep and got.isdigit():
            found.add(int(got))
    return found
def mapped_ports(rows):
    found = {}
    for line in rows:
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        if parts[0] in ("zimbra883-imap", "zimbra883-https", "zimbra883-admin"):
            if parts[3] != name or not parts[1].isdigit():
                fail("bad port row")
            found[parts[0]] = int(parts[1])
    return found
def bindable(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind((host, port))
        return True
    except OSError:
        return False
    finally:
        sock.close()
def replace_rows(imap_port, https_port, admin_port):
    imap_c = listen_ports.container_port("zimbra-883", "imap")
    admin_c = listen_ports.container_port("zimbra-883", "admin")
    new = (
        "zimbra883-imap\t%d\t%d\t%s\n"
        "zimbra883-https\t%d\t443\t%s\n"
        "zimbra883-admin\t%d\t%d\t%s\n"
    ) % (imap_port, imap_c, name, https_port, name, admin_port, admin_c, name)
    services = ("zimbra883-imap", "zimbra883-https", "zimbra883-admin")
    old = ""
    if os.path.exists(ports_path):
        with open(ports_path, encoding="utf-8") as handle:
            old = handle.read()
    kept = []
    for line in old.splitlines(keepends=True):
        parts = line.rstrip("\n").split("\t")
        if parts and parts[0] in services:
            continue
        if len(parts) >= 4 and parts[3] == name:
            continue
        if not line.endswith("\n"):
            line += "\n"
        kept.append(line)
    text = "".join(kept) + new
    parent = os.path.dirname(ports_path)
    os.makedirs(parent, exist_ok=True)
    tmp = os.path.join(parent, "local-ports.tsv.new")
    with open(tmp, "w", encoding="utf-8") as handle:
        handle.write(text)
    proc = run(["mv", tmp, ports_path])
    if proc.returncode != 0:
        fail("mv failed")
def container_bad(want):
    proc = run(
        [
            "docker",
            "inspect",
            "-f",
            "{{range $p, $b := .HostConfig.PortBindings}}{{$p}}\n{{end}}",
            name,
        ]
    )
    if proc.returncode != 0:
        return True
    got = set()
    for line in proc.stdout.splitlines():
        port = line.split("/", 1)[0]
        if port:
            got.add(port)
    if got != want:
        return True
    proc = run(
        [
            "docker",
            "inspect",
            "-f",
            "{{range .Mounts}}{{.Type}}\t{{.Source}}\n{{end}}",
            name,
        ]
    )
    if proc.returncode != 0:
        return True
    gen_real = os.path.realpath(mount_gen)
    seed_real = os.path.realpath(mount_seed)
    roots = (gen_real + os.sep, seed_real + os.sep)

    def under(path):
        real = os.path.realpath(path)
        return real == gen_real or real == seed_real or real.startswith(roots)

    for line in proc.stdout.splitlines():
        if not line.strip():
            continue
        kind, sep, source = line.partition("\t")
        if kind != "bind":
            continue
        if not sep or not source or not os.path.exists(source) or not under(source):
            return True
    return False
def require_seed_image():
    proc = run(["docker", "image", "inspect", "-f", "{{.Id}}", image])
    if proc.returncode != 0:
        fail("tools/test-servers/build-seed-image zimbra-883")
def zimbra_create(imap_port, https_port, admin_port):
    require_seed_image()
    imap_c = listen_ports.container_port("zimbra-883", "imap")
    admin_c = listen_ports.container_port("zimbra-883", "admin")
    cmd = [
        "docker", "create",
        "--name", name,
        "-p", "%d:%d" % (imap_port, imap_c),
        "-p", "%d:%d" % (admin_port, admin_c),
        image,
    ]
    proc = run(cmd, timeout=600, env=os.environ.copy())
    if proc.returncode != 0:
        err = redact((proc.stderr or "") + (proc.stdout or ""))
        fail("docker create failed: %s" % err[-500:])
def launch_zimbra(imap_port, https_port, admin_port):
    zimbra_create(imap_port, https_port, admin_port)
    proc = run(["docker", "start", name], timeout=180)
    if proc.returncode != 0:
        fail("docker start failed")
def reset_zimbra():
    require_seed_image()
    if name in container_names():
        proc = run(["docker", "rm", "-f", name])
        if proc.returncode != 0:
            fail("docker rm failed")
    rows_now = []
    if os.path.exists(ports_path):
        with open(ports_path, encoding="utf-8") as handle:
            rows_now = handle.read().splitlines()
    ports = mapped_ports(rows_now)
    if len(ports) < 3:
        fail("missing saved ports")
    zimbra_create(ports["zimbra883-imap"], ports["zimbra883-https"], ports["zimbra883-admin"])
def recreate_zimbra():
    require_seed_image()
    if name in container_names():
        proc = run(["docker", "rm", "-f", name])
        if proc.returncode != 0:
            fail("docker rm failed")
    rows_now = []
    if os.path.exists(ports_path):
        with open(ports_path, encoding="utf-8") as handle:
            rows_now = handle.read().splitlines()
    own = ("zimbra883-imap", "zimbra883-https", "zimbra883-admin")
    old = {}
    for line in rows_now:
        parts = line.split("\t")
        if len(parts) >= 2 and parts[0] in own and parts[1].isdigit():
            old[parts[0]] = int(parts[1])
    listening = listening_ports()
    chosen = []

    def other_uses(port):
        for line in rows_now:
            parts = line.split("\t")
            if len(parts) >= 2 and parts[1].isdigit() and int(parts[1]) == port and parts[0] not in own:
                return True
        return False

    def free(port):
        if port in chosen or other_uses(port) or port in listening:
            return False
        return bindable(port)

    def draw_one():
        for port in range(20000, 40000):
            if free(port):
                return port
        fail("no free ports")

    got = {}
    for key in own:
        previous = old.get(key)
        if previous is not None and free(previous):
            got[key] = previous
        else:
            got[key] = draw_one()
        chosen.append(got[key])
    launch_zimbra(got["zimbra883-imap"], got["zimbra883-https"], got["zimbra883-admin"])
    replace_rows(got["zimbra883-imap"], got["zimbra883-https"], got["zimbra883-admin"])
    if container_bad(want_ports):
        proc = run(["docker", "stop", name], timeout=60)
        if proc.returncode != 0:
            err = redact((proc.stderr or "") + (proc.stdout or ""))
            fail("docker stop failed: %s %s --recreate zimbra-883" % (err[-500:], script))
        fail(script + " --recreate zimbra-883")
    proc = run(["docker", "stop", name], timeout=60)
    if proc.returncode != 0:
        err = redact((proc.stderr or "") + (proc.stdout or ""))
        fail("docker stop failed: %s %s --recreate zimbra-883" % (err[-500:], script))
if mode == "--reset":
    reset_zimbra()
else:
    recreate_zimbra()
PY
  exit 0
fi
if [ "$mode" = --reset ]; then
  reset_from_seed "$server"
  exit 0
fi
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
      create_from_seed stalwart-01625 \
        -p "${1}:${cp_stalwart01625_imap}" \
        -p "${2}:${cp_stalwart01625_imaps}" \
        -p "${3}:${cp_stalwart01625_managesieve}" \
        -p "${4}:${cp_stalwart01625_https}" \
        -p "${5}:${cp_stalwart01625_submission}"
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
    stalwart-dev)
      ST_ADMIN_FILE="$GEN/stalwart-dev-admin.txt"
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        stalwart-dev-imap:${cp_stalwartdev_imap}:liveimap-stalwart-dev \
        stalwart-dev-imaps:${cp_stalwartdev_imaps}:liveimap-stalwart-dev \
        stalwart-dev-managesieve:${cp_stalwartdev_managesieve}:liveimap-stalwart-dev \
        stalwart-dev-https:${cp_stalwartdev_https}:liveimap-stalwart-dev \
        stalwart-dev-submission:${cp_stalwartdev_submission}:liveimap-stalwart-dev)
      if [ ! -s "$ST_ADMIN_FILE" ]; then
        umask 007
        python3 -c 'import secrets; print(secrets.token_hex(16))' > "$ST_ADMIN_FILE"
        umask 022
      fi
      create_from_seed stalwart-dev \
        -p "${1}:${cp_stalwartdev_imap}" \
        -p "${2}:${cp_stalwartdev_imaps}" \
        -p "${3}:${cp_stalwartdev_managesieve}" \
        -p "${4}:${cp_stalwartdev_https}" \
        -p "${5}:${cp_stalwartdev_submission}"
      {
        printf 'stalwart-dev-imap\t%s\t%s\tliveimap-stalwart-dev\n' "$1" "$cp_stalwartdev_imap"
        printf 'stalwart-dev-imaps\t%s\t%s\tliveimap-stalwart-dev\n' "$2" "$cp_stalwartdev_imaps"
        printf 'stalwart-dev-managesieve\t%s\t%s\tliveimap-stalwart-dev\n' "$3" "$cp_stalwartdev_managesieve"
        printf 'stalwart-dev-https\t%s\t%s\tliveimap-stalwart-dev\n' "$4" "$cp_stalwartdev_https"
        printf 'stalwart-dev-submission\t%s\t%s\tliveimap-stalwart-dev\n' "$5" "$cp_stalwartdev_submission"
      } > "$rows"
      replace_port_rows liveimap-stalwart-dev "" "$rows"
      ;;
    dovecot-dev)
      # shellcheck disable=SC2046
      set -- $(pick_ports dovecot-dev-imap:${cp_dovecotdev_imap}:liveimap-dovecot-dev)
      write_dovecot_dev_files
      create_from_seed dovecot-dev \
        -p "${1}:${cp_dovecotdev_imap}"
      {
        printf 'dovecot-dev-imap\t%s\t%s\tliveimap-dovecot-dev\n' "$1" "$cp_dovecotdev_imap"
      } > "$rows"
      replace_port_rows liveimap-dovecot-dev "" "$rows"
      ;;
    cyrus-3137)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        cyrus3137-imap:${cp_cyrus3137_imap}:liveimap-cyrus-3137 \
        cyrus3137-http:${cp_cyrus3137_http}:liveimap-cyrus-3137)
      create_from_seed cyrus-3137 \
        -p "${1}:${cp_cyrus3137_imap}" \
        -p "${2}:${cp_cyrus3137_http}"
      {
        printf 'cyrus3137-imap\t%s\t%s\tliveimap-cyrus-3137\n' "$1" "$cp_cyrus3137_imap"
        printf 'cyrus3137-http\t%s\t%s\tliveimap-cyrus-3137\n' "$2" "$cp_cyrus3137_http"
      } > "$rows"
      replace_port_rows liveimap-cyrus-3137 "" "$rows"
      ;;
    stalwart)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        stalwart-imap:${cp_stalwart_imap}:liveimap-stalwart \
        stalwart-imaps:${cp_stalwart_imaps}:liveimap-stalwart \
        stalwart-managesieve:${cp_stalwart_managesieve}:liveimap-stalwart \
        stalwart-https:${cp_stalwart_https}:liveimap-stalwart \
        stalwart-submission:${cp_stalwart_submission}:liveimap-stalwart)
      create_from_seed stalwart \
        -p "${1}:${cp_stalwart_imap}" \
        -p "${2}:${cp_stalwart_imaps}" \
        -p "${3}:${cp_stalwart_managesieve}" \
        -p "${4}:${cp_stalwart_https}" \
        -p "${5}:${cp_stalwart_submission}"
      {
        printf 'stalwart-imap\t%s\t%s\tliveimap-stalwart\n' "$1" "$cp_stalwart_imap"
        printf 'stalwart-imaps\t%s\t%s\tliveimap-stalwart\n' "$2" "$cp_stalwart_imaps"
        printf 'stalwart-managesieve\t%s\t%s\tliveimap-stalwart\n' "$3" "$cp_stalwart_managesieve"
        printf 'stalwart-https\t%s\t%s\tliveimap-stalwart\n' "$4" "$cp_stalwart_https"
        printf 'stalwart-submission\t%s\t%s\tliveimap-stalwart\n' "$5" "$cp_stalwart_submission"
      } > "$rows"
      replace_port_rows liveimap-stalwart "" "$rows"
      ;;
    cyrus-22)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus22-imap:${cp_cyrus22_imap}:liveimap-cyrus-22)
      create_from_seed cyrus-22 -p "${1}:${cp_cyrus22_imap}"
      printf 'cyrus22-imap\t%s\t%s\tliveimap-cyrus-22\n' "$1" "${cp_cyrus22_imap}" > "$rows"
      replace_port_rows liveimap-cyrus-22 "" "$rows"
      ;;
    cyrus-24)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus24-imap:${cp_cyrus24_imap}:liveimap-cyrus-24)
      create_from_seed cyrus-24 -p "${1}:${cp_cyrus24_imap}"
      printf 'cyrus24-imap\t%s\t%s\tliveimap-cyrus-24\n' "$1" "${cp_cyrus24_imap}" > "$rows"
      replace_port_rows liveimap-cyrus-24 "" "$rows"
      ;;
    cyrus-25)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus25-imap:${cp_cyrus25_imap}:liveimap-cyrus-25)
      create_from_seed cyrus-25 -p "${1}:${cp_cyrus25_imap}"
      printf 'cyrus25-imap\t%s\t%s\tliveimap-cyrus-25\n' "$1" "${cp_cyrus25_imap}" > "$rows"
      replace_port_rows liveimap-cyrus-25 "" "$rows"
      ;;
    cyrus-36)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus36-imap:${cp_cyrus36_imap}:liveimap-cyrus-36 cyrus36-http:${cp_cyrus36_http}:liveimap-cyrus-36)
      create_from_seed cyrus-36 -p "${1}:${cp_cyrus36_imap}" -p "${2}:${cp_cyrus36_http}"
      {
        printf 'cyrus36-imap\t%s\t%s\tliveimap-cyrus-36\n' "$1" "${cp_cyrus36_imap}"
        printf 'cyrus36-http\t%s\t%s\tliveimap-cyrus-36\n' "$2" "${cp_cyrus36_http}"
      } > "$rows"
      replace_port_rows liveimap-cyrus-36 "" "$rows"
      ;;
    dovecot-10)
      # shellcheck disable=SC2046
      set -- $(pick_ports dovecot10-imap:${cp_dovecot10_imap}:liveimap-dovecot-10)
      create_from_seed dovecot-10 -p "${1}:${cp_dovecot10_imap}"
      printf 'dovecot10-imap\t%s\t%s\tliveimap-dovecot-10\n' "$1" "${cp_dovecot10_imap}" > "$rows"
      replace_port_rows liveimap-dovecot-10 "" "$rows"
      ;;
    dovecot-12)
      # shellcheck disable=SC2046
      set -- $(pick_ports dovecot12-imap:${cp_dovecot12_imap}:liveimap-dovecot-12)
      create_from_seed dovecot-12 -p "${1}:${cp_dovecot12_imap}"
      printf 'dovecot12-imap\t%s\t%s\tliveimap-dovecot-12\n' "$1" "${cp_dovecot12_imap}" > "$rows"
      replace_port_rows liveimap-dovecot-12 "" "$rows"
      ;;
    dovecot-22)
      # shellcheck disable=SC2046
      set -- $(pick_ports dovecot22-imap:${cp_dovecot22_imap}:liveimap-dovecot-22)
      create_from_seed dovecot-22 -p "${1}:${cp_dovecot22_imap}"
      printf 'dovecot22-imap\t%s\t%s\tliveimap-dovecot-22\n' "$1" "${cp_dovecot22_imap}" > "$rows"
      replace_port_rows liveimap-dovecot-22 "" "$rows"
      ;;
    courier-50)
      # shellcheck disable=SC2046
      set -- $(pick_ports courier50-imap:${cp_courier50_imap}:liveimap-courier-50)
      create_from_seed courier-50 -p "${1}:${cp_courier50_imap}"
      printf 'courier50-imap\t%s\t%s\tliveimap-courier-50\n' "$1" "${cp_courier50_imap}" > "$rows"
      replace_port_rows liveimap-courier-50 "" "$rows"
      ;;
    uwimap)
      # shellcheck disable=SC2046
      set -- $(pick_ports uwimap-imap:${cp_uwimap_imap}:liveimap-uwimap)
      create_from_seed uwimap -p "${1}:${cp_uwimap_imap}"
      printf 'uwimap-imap\t%s\t%s\tliveimap-uwimap\n' "$1" "${cp_uwimap_imap}" > "$rows"
      replace_port_rows liveimap-uwimap "" "$rows"
      ;;
    stalwart-0155)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        stalwart015-imap:${cp_stalwart0155_imap}:liveimap-stalwart-0155 \
        stalwart015-imaps:${cp_stalwart0155_imaps}:liveimap-stalwart-0155 \
        stalwart015-managesieve:${cp_stalwart0155_managesieve}:liveimap-stalwart-0155 \
        stalwart015-http:${cp_stalwart0155_https}:liveimap-stalwart-0155 \
        stalwart015-submission:${cp_stalwart0155_submission}:liveimap-stalwart-0155)
      admin="$GEN/stalwart-0155-admin.txt"
      if [ ! -s "$admin" ]; then
        umask 007
        python3 -c 'import secrets; print(secrets.token_hex(16))' > "$admin"
        umask 022
      fi
      create_from_seed stalwart-0155 \
        -p "${1}:${cp_stalwart0155_imap}" \
        -p "${2}:${cp_stalwart0155_imaps}" \
        -p "${3}:${cp_stalwart0155_managesieve}" \
        -p "${4}:${cp_stalwart0155_https}" \
        -p "${5}:${cp_stalwart0155_submission}"
      {
        printf 'stalwart015-imap\t%s\t%s\tliveimap-stalwart-0155\n' "$1" "${cp_stalwart0155_imap}"
        printf 'stalwart015-imaps\t%s\t%s\tliveimap-stalwart-0155\n' "$2" "${cp_stalwart0155_imaps}"
        printf 'stalwart015-managesieve\t%s\t%s\tliveimap-stalwart-0155\n' "$3" "${cp_stalwart0155_managesieve}"
        printf 'stalwart015-http\t%s\t%s\tliveimap-stalwart-0155\n' "$4" "${cp_stalwart0155_https}"
        printf 'stalwart015-submission\t%s\t%s\tliveimap-stalwart-0155\n' "$5" "${cp_stalwart0155_submission}"
      } > "$rows"
      replace_port_rows liveimap-stalwart-0155 "" "$rows"
      ;;
    *)
      echo "ERROR: $server is not a lab server" >&2
      exit 1
      ;;
esac
rm -f "$rows"
finish_recreate "$name" "$server"
exit 0
