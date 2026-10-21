#!/bin/sh
# Dev lab servers: pinned Cyrus, Dovecot, and Stalwart.
# Four base accounts. Courier and UW-IMAP have no dev pin. No app install.
set -eu

SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")
cport() {
  python3 "$DIR/cport.py" "$1" "$2" || exit 1
}
SEED="$DIR/seed/accounts.tsv"
GEN="$DIR/generated"
PORTS_FILE="$GEN/local-ports.tsv"
ST_ADMIN_FILE="$GEN/stalwart-dev-admin.txt"
CY_ADMIN_FILE="$GEN/cyrus-3137-admin.txt"
ACCOUNTS_OUT="$GEN/dev-accounts.txt"
FETCH_OUT="$GEN/dev-fetch.txt"
PINS_OUT="$GEN/dev-pins.txt"
SRC="$GEN/dev-src"
CY_SRC="$SRC/cyrus"
DV_SRC="$SRC/dovecot"
ST_SRC="$SRC/stalwart"
DV_CONF="$GEN/dovecot-dev"

CY_SHA=a9d201bccdadd429c46bc4cf16995453a86d8eed
DV_SHA=d6c3486ed839ec06f6a1ec87a6e307211e3a5c92
ST_SHA=2f5b9f2c697ca0fb1d7f015dcdeab961cd7be893

if [ ! -f "$SEED" ]; then
  echo "missing seed file" >&2
  exit 1
fi

command -v docker >/dev/null
command -v python3 >/dev/null
command -v ss >/dev/null
command -v openssl >/dev/null
command -v setfacl >/dev/null
command -v git >/dev/null

require_seed_image() {
  server=$1
  if ! docker image inspect "liveimap-seed-$server" >/dev/null 2>&1; then
    echo "tools/test-servers/build-seed-image $server" >&2
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

assign_cports() {
  case "$1" in
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
    *) echo "assign_cports: unknown container $1" >&2; exit 1 ;;
  esac
}

expected_ports() {
  case "$1" in
    liveimap-stalwart-dev) echo "$cp_stalwartdev_imap $cp_stalwartdev_imaps $cp_stalwartdev_managesieve $cp_stalwartdev_https $cp_stalwartdev_submission" ;;
    liveimap-dovecot-dev) echo "$cp_dovecotdev_imap" ;;
    liveimap-cyrus-3137) echo "$cp_cyrus3137_imap $cp_cyrus3137_http" ;;
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
  python3 - "$SEED" "$DV_CONF/passwd" <<'PY'
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
  setfacl -m u:1000:r "$DV_CONF/passwd" "$DV_CONF/auth.conf"
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
  assign_cports "$name"
  case "$server" in
    stalwart-dev)
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
  esac
  rm -f "$rows"
  finish_recreate "$name" "$server"
  exit 0
fi

for name in liveimap-stalwart-dev liveimap-dovecot-dev \
  liveimap-cyrus-3137; do
  check_existing_name "$name"
done
present=0
for name in liveimap-stalwart-dev liveimap-dovecot-dev \
  liveimap-cyrus-3137; do
  if container_exists "$name"; then
    present=1
    if ! container_running "$name"; then
      docker start "$name"
    fi
  fi
done
if [ "$present" -eq 0 ]; then

require_seed_image stalwart-dev
require_seed_image dovecot-dev
require_seed_image cyrus-3137
mkdir -p "$GEN" "$SRC"
umask 022
cat > "$PINS_OUT" <<EOF
cyrus cyrus-imapd-3.13.7 $CY_SHA
dovecot main $DV_SHA
stalwart main $ST_SHA
EOF

export GIT_TERMINAL_PROMPT=0

pin_ok() {
  dest=$1
  sha=$2
  got=$(git -C "$dest" rev-parse HEAD)
  obj=$(git -C "$dest" rev-parse "$sha")
  want=$(git -C "$dest" rev-parse "${sha}^{commit}")
  if [ "$obj" != "$sha" ] || [ "$got" != "$want" ]; then
    echo "checkout HEAD differs from pin: $dest" >&2
    exit 1
  fi
}

pin_checkout() {
  dest=$1
  url=$2
  sha=$3
  if [ -d "$dest/.git" ]; then
    if git -C "$dest" rev-parse HEAD >/dev/null 2>&1; then
      pin_ok "$dest" "$sha"
      return 0
    fi
    rm -rf "$dest"
  elif [ -e "$dest" ]; then
    echo "checkout path exists without the pin: $dest" >&2
    exit 1
  fi
  mkdir -p "$dest"
  git -C "$dest" init
  git -C "$dest" remote add origin "$url"
  git -C "$dest" fetch --depth 1 origin "$sha"
  git -C "$dest" checkout FETCH_HEAD
  pin_ok "$dest" "$sha"
}

echo "checking out pins"
pin_checkout "$CY_SRC" https://github.com/cyrusimap/cyrus-imapd "$CY_SHA"
pin_checkout "$DV_SRC" https://github.com/dovecot/core "$DV_SHA"
pin_checkout "$ST_SRC" https://github.com/stalwartlabs/stalwart "$ST_SHA"

echo "preparing cyrus image"
cat > "$CY_SRC/xxd" <<'EOF'
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
cat > "$CY_SRC/rsync" <<'EOF'
#!/bin/sh
# Present for configure. Cyrus calls rsync only to copy search indexes.
exit 0
EOF
chmod 755 "$CY_SRC/xxd" "$CY_SRC/rsync"
cat > "$CY_SRC/imapd.conf" <<'EOF'
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
cat > "$CY_SRC/cyrus.conf" <<'EOF'
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
cat > "$CY_SRC/entrypoint.sh" <<'EOF'
#!/bin/sh
set -eu
master=""
if [ -x /usr/sbin/cyrmaster ]; then
  master=/usr/sbin/cyrmaster
else
  for cand in /usr/local/cyrus/libexec/master /usr/local/cyrus/sbin/master /usr/local/cyrus/bin/master /usr/sbin/master; do
    if [ -x "$cand" ]; then
      master=$cand
      break
    fi
  done
fi
if [ -z "$master" ]; then
  echo "cyrus master missing" >&2
  exit 1
fi
rm -f /var/run/cyrus-master.pid /run/cyrus-master.pid /var/run/cyrmaster.pid /run/cyrus/master.pid
"$master" -d
exec sleep infinity

EOF
chmod 755 "$CY_SRC/entrypoint.sh"
cat > "$CY_SRC/Dockerfile" <<'EOF'
FROM debian:bookworm
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential autoconf automake libtool pkg-config bison flex gperf \
    cmake libmoo-perl libapp-cmd-perl libtype-tiny-perl \
    libssl-dev libjansson-dev libxml2-dev libsqlite3-dev libical-dev \
    libsasl2-dev libsasl2-modules sasl2-bin libpcre2-dev uuid-dev \
    libicu-dev libxapian-dev zlib1g-dev libnghttp2-dev \
    && rm -rf /var/lib/apt/lists/*
COPY xxd /usr/local/bin/xxd
COPY rsync /usr/local/bin/rsync
RUN chmod 755 /usr/local/bin/xxd /usr/local/bin/rsync
# Bookworm libical is 3.0. Cyrus 3.13 http requires libical >= 3.99.99.
COPY libical-4.0.5.tar.gz /tmp/libical-4.0.5.tar.gz
RUN tar -xzf /tmp/libical-4.0.5.tar.gz -C /tmp \
    && cmake -S /tmp/libical-4.0.5 -B /tmp/libical-build \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=/usr/local \
      -DLIBICAL_GLIB=OFF \
      -DLIBICAL_GOBJECT_INTROSPECTION=OFF \
      -DLIBICAL_JAVA_BINDINGS=OFF \
      -DLIBICAL_CXX_BINDINGS=OFF \
      -DLIBICAL_BUILD_DOCS=OFF \
      -DLIBICAL_BUILD_EXAMPLES=OFF \
    && cmake --build /tmp/libical-build -j4 \
    && cmake --install /tmp/libical-build \
    && printf '%s\n' /usr/local/lib > /etc/ld.so.conf.d/usr-local.conf \
    && ldconfig \
    && rm -rf /tmp/libical-4.0.5 /tmp/libical-build /tmp/libical-4.0.5.tar.gz
ENV PKG_CONFIG_PATH=/usr/local/lib/pkgconfig:/usr/local/lib/x86_64-linux-gnu/pkgconfig
COPY . /src
RUN cd /src \
    && autoreconf -i \
    && ./configure --prefix=/usr/local/cyrus --enable-http --enable-jmap --enable-xapian \
    && make -j4 \
    && make install \
    && rm -rf /src
COPY imapd.conf /etc/imapd.conf
COPY cyrus.conf /etc/cyrus.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
EOF

echo "fetching libical 4.0.5"
python3 - "$CY_SRC/libical-4.0.5.tar.gz" <<'PY'
import hashlib
import os
import sys
import urllib.request

dest = sys.argv[1]
url = "https://github.com/libical/libical/releases/download/v4.0.5/libical-4.0.5.tar.gz"
want = "cc09a3ac41d60e6144e644bd3fcf97d47106d659c4a0b8965102581401e67c9c"
if os.path.isfile(dest):
    digest = hashlib.sha256(open(dest, "rb").read()).hexdigest()
    if digest == want:
        sys.exit(0)
    os.remove(dest)
req = urllib.request.Request(url, headers={"User-Agent": "liveimap-lab"})
with urllib.request.urlopen(req, timeout=180) as resp, open(dest, "wb") as out:
    while True:
        chunk = resp.read(1024 * 1024)
        if not chunk:
            break
        out.write(chunk)
digest = hashlib.sha256(open(dest, "rb").read()).hexdigest()
if digest != want:
    sys.stderr.write("libical tarball hash mismatch\n")
    sys.exit(1)
PY

echo "building cyrus image"
docker build -t liveimap-cyrus-3137:phase2 "$CY_SRC"

echo "preparing dovecot image"
cat > "$DV_SRC/dovecot-lab.conf" <<'EOF'
dovecot_config_version = 2.4.0
dovecot_storage_version = 2.4.0

protocols {
  imap = yes
}

mail_driver = maildir
mail_path = ~/Maildir

namespace inbox {
  inbox = yes
  separator = /
}

ssl = no
auth_allow_cleartext = yes

default_internal_user = vmail
default_login_user = vmail

!include /etc/dovecot/conf.d/auth.conf
EOF
cat > "$DV_SRC/entrypoint.sh" <<'EOF'
#!/bin/sh
set -eu
mkdir -p /srv/vmail /run/dovecot /usr/local/var/run/dovecot
chown vmail:vmail /srv/vmail
exec /usr/local/sbin/dovecot -F
EOF
chmod 755 "$DV_SRC/entrypoint.sh"
cat > "$DV_SRC/Dockerfile" <<'EOF'
FROM debian:13-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git ca-certificates autoconf automake libtool gettext \
    pkg-config bison flex libssl-dev zlib1g-dev liblz4-dev libzstd-dev \
    libcap-dev libsodium-dev libpam0g-dev libsqlite3-dev libicu-dev \
    python3 wget xz-utils \
    && rm -rf /var/lib/apt/lists/*
COPY . /src
WORKDIR /src
RUN ./autogen.sh
RUN ./configure --prefix=/usr/local --with-sqlite --with-icu --with-ssl=openssl > /tmp/configure.out 2>&1; \
    code=$?; \
    cat /tmp/configure.out; \
    if [ "$code" -ne 0 ]; then exit "$code"; fi; \
    if grep -E '^                 :' /tmp/configure.out | grep -Ei 'sqlite|icu|openssl|sodium|lz4|zstd|libcap|pam'; then \
      echo "configure named a missing dependency" >&2; \
      exit 1; \
    fi
RUN make -j4 && make install
RUN groupadd -g 1000 vmail \
    && useradd -u 1000 -g 1000 -d /srv/vmail -M -s /usr/sbin/nologin vmail \
    && groupadd --system dovecot \
    && useradd --system -g dovecot -d /nonexistent -M -s /usr/sbin/nologin dovecot \
    && mkdir -p /usr/local/etc/dovecot /srv/vmail /run/dovecot \
    && chown vmail:vmail /srv/vmail
COPY dovecot-lab.conf /usr/local/etc/dovecot/dovecot.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
EOF

echo "building dovecot image"
docker build -t liveimap-dovecot-dev:phase1 "$DV_SRC"

echo "building stalwart image"
if ! grep -q '^ENV CARGO_BUILD_JOBS=' "$ST_SRC/Dockerfile"; then
  sed -i '/^FROM --platform=\$BUILDPLATFORM chef AS builder/a ENV CARGO_BUILD_JOBS=2' "$ST_SRC/Dockerfile"
fi
plugin_home="$GEN/docker-home"
plugin_dir="$plugin_home/.docker/cli-plugins"
plugin_bin="$plugin_dir/docker-buildx"
mkdir -p "$plugin_dir"
if [ ! -x "$plugin_bin" ]; then
  python3 - "$plugin_bin" <<'PY'
import sys
import urllib.request

dest = sys.argv[1]
url = "https://github.com/docker/buildx/releases/download/v0.38.0/buildx-v0.38.0.linux-amd64"
urllib.request.urlretrieve(url, dest)
PY
  chmod 755 "$plugin_bin"
fi
HOME="$plugin_home" DOCKER_BUILDKIT=1 docker build --cpuset-cpus=0-3 \
  -t liveimap-stalwart-dev:phase1 "$ST_SRC"

echo "drawing ports"
PORTS=$(python3 - "$PORTS_FILE" <<'PY'
import random
import socket
import subprocess
import sys

ports_file = sys.argv[1]
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
while len(chosen) < 8 and draws < 400:
    draws += 1
    port = random.randint(20000, 39999)
    if port in seen or port in used:
        seen.add(port)
        continue
    seen.add(port)
    if listening(port) or not bindable(port):
        continue
    chosen.append(port)

if len(chosen) < 8:
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
shift 5
DV_IMAP=$1
CY_IMAP=$2
CY_HTTP=$3

echo "starting dovecot stalwart cyrus"

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
python3 - "$SEED" "$DV_CONF/passwd" <<'PY'
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
setfacl -m u:1000:r "$DV_CONF/passwd" "$DV_CONF/auth.conf"
assign_cports liveimap-dovecot-dev
assign_cports liveimap-stalwart-dev
assign_cports liveimap-cyrus-3137
create_from_seed dovecot-dev \
  -p "${DV_IMAP}:${cp_dovecotdev_imap}"

create_from_seed stalwart-dev \
  -p "${ST_IMAP}:${cp_stalwartdev_imap}" \
  -p "${ST_IMAPS}:${cp_stalwartdev_imaps}" \
  -p "${ST_SIEVE}:${cp_stalwartdev_managesieve}" \
  -p "${ST_HTTPS}:${cp_stalwartdev_https}" \
  -p "${ST_SUB}:${cp_stalwartdev_submission}"

ready=0
i=0
while [ "$i" -lt 120 ]; do
  if docker exec liveimap-stalwart-dev /bin/sh -c 'curl -fsS -o /dev/null http://127.0.0.1:8080/healthz/live'; then
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

listener_json=$(docker exec -i liveimap-stalwart-dev /bin/sh -c 'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap' <<'JSON'
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
docker restart liveimap-stalwart-dev >/dev/null
ready=0
i=0
while [ "$i" -lt 120 ]; do
  if docker exec liveimap-stalwart-dev /bin/sh -c 'curl -fsS -o /dev/null http://127.0.0.1:8080/healthz/live'; then
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

ST_REQ=$(mktemp "$GEN/stalwart-dev-req.XXXXXX")
python3 - "$SEED" > "$ST_REQ" <<'PY'
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
docker exec -i liveimap-stalwart-dev /bin/sh -c 'curl -sS -o /tmp/acct-body -w "%{http_code}" -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap > /tmp/acct-code' < "$ST_REQ"
rm -f "$ST_REQ"
st_code=$(docker exec liveimap-stalwart-dev cat /tmp/acct-code)
st_body=$(docker exec liveimap-stalwart-dev cat /tmp/acct-body)
docker exec liveimap-stalwart-dev rm -f /tmp/acct-body /tmp/acct-code
case "$st_body" in
  *notCreated*)
    python3 - "$SEED" "$st_code" "$st_body" <<'PY'
import csv
import sys

seed, code, body = sys.argv[1], sys.argv[2], sys.argv[3]
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("password"):
            body = body.replace(row["password"], "[redacted]")
excerpt = body.replace("\n", " ")[:400]
sys.stderr.write("stalwart account error %s %s\n" % (code, excerpt))
sys.exit(1)
PY
    ;;
  *'"created"'*) ;;
  *)
    python3 - "$SEED" "$st_code" "$st_body" <<'PY'
import csv
import sys

seed, code, body = sys.argv[1], sys.argv[2], sys.argv[3]
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("password"):
            body = body.replace(row["password"], "[redacted]")
excerpt = body.replace("\n", " ")[:400]
sys.stderr.write("stalwart account error %s %s\n" % (code, excerpt))
sys.exit(1)
PY
    ;;
esac
unset st_code st_body

create_from_seed cyrus-3137 \
  -p "${CY_IMAP}:${cp_cyrus3137_imap}" \
  -p "${CY_HTTP}:${cp_cyrus3137_http}"

echo "login append jmap"
python3 - "$SEED" "$ACCOUNTS_OUT" "$FETCH_OUT" \
  "$ST_IMAP" "$ST_HTTPS" "$DV_IMAP" "$CY_IMAP" "$CY_HTTP" <<'PY'
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
) = sys.argv[1:]
ports = {
    "stalwart-dev": int(st_imap),
    "dovecot-dev": int(dv_imap),
    "cyrus-3137": int(cy_imap),
}
order = ["stalwart-dev", "dovecot-dev", "cyrus-3137"]
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

account_lines = [
    "courier no dev tarball\n",
    "uw-imap no dev branch\n",
]
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

def http_get_retry(url, user, password, tls=False, seconds=90):
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        try:
            return http_get(url, user, password, tls)
        except urllib.error.URLError as err:
            last = err
            time.sleep(1)
    sys.stderr.write("jmap request failed for %s\n" % url)
    if last is not None:
        sys.stderr.write("%s\n" % last)
    sys.exit(1)

for server in ("dovecot-dev",):
    account_lines.append("jmap %s no JMAP listener\n" % server)

cy_status, cy_body = http_get_retry(
    "http://%s:%s/.well-known/jmap" % (host, cy_http),
    "liveimap",
    password_of["liveimap"],
)
cy_urn = "urn:ietf:params:jmap:mail" in cy_body
account_lines.append(
    "jmap cyrus-3137 %s %s\n" % (cy_status, "urn:ietf:params:jmap:mail" if cy_urn else "missing-urn")
)
if cy_status != 200 or not cy_urn:
    failed = True

st_status, st_jmap = http_get_retry(
    "https://%s:%s/.well-known/jmap" % (host, st_https),
    "liveimap",
    password_of["liveimap"],
    tls=True,
)
st_urn = "urn:ietf:params:jmap:mail" in st_jmap
st_count = 0
try:
    parsed = json.loads(st_jmap)
    accounts = parsed.get("accounts")
    if isinstance(accounts, dict):
        st_count = len(accounts)
except json.JSONDecodeError:
    st_count = 0
account_lines.append(
    "jmap stalwart-dev %s %s accounts=%s\n"
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
  printf 'stalwart-dev-imap\t%s\t%s\tliveimap-stalwart-dev\n' "$ST_IMAP" "$cp_stalwartdev_imap"
  printf 'stalwart-dev-imaps\t%s\t%s\tliveimap-stalwart-dev\n' "$ST_IMAPS" "$cp_stalwartdev_imaps"
  printf 'stalwart-dev-managesieve\t%s\t%s\tliveimap-stalwart-dev\n' "$ST_SIEVE" "$cp_stalwartdev_managesieve"
  printf 'stalwart-dev-https\t%s\t%s\tliveimap-stalwart-dev\n' "$ST_HTTPS" "$cp_stalwartdev_https"
  printf 'stalwart-dev-submission\t%s\t%s\tliveimap-stalwart-dev\n' "$ST_SUB" "$cp_stalwartdev_submission"
  printf 'dovecot-dev-imap\t%s\t%s\tliveimap-dovecot-dev\n' "$DV_IMAP" "$cp_dovecotdev_imap"
  printf 'cyrus3137-imap\t%s\t%s\tliveimap-cyrus-3137\n' "$CY_IMAP" "$cp_cyrus3137_imap"
  printf 'cyrus3137-http\t%s\t%s\tliveimap-cyrus-3137\n' "$CY_HTTP" "$cp_cyrus3137_http"
} > "$GEN/port-rows.new"
replace_port_rows \
  "liveimap-stalwart-dev,liveimap-dovecot-dev,liveimap-cyrus-3137" \
  "" \
  "$GEN/port-rows.new"
rm -f "$GEN/port-rows.new"

echo "containers"
docker inspect -f '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}}' \
  liveimap-stalwart-dev liveimap-dovecot-dev \
  liveimap-cyrus-3137
echo "ports"
echo "stalwart-dev-imap $ST_IMAP"
echo "stalwart-dev-imaps $ST_IMAPS"
echo "stalwart-dev-managesieve $ST_SIEVE"
echo "stalwart-dev-https $ST_HTTPS"
echo "stalwart-dev-submission $ST_SUB"
echo "dovecot-dev-imap $DV_IMAP"
echo "cyrus3137-imap $CY_IMAP"
echo "cyrus3137-http $CY_HTTP"
echo "accounts"
cat "$ACCOUNTS_OUT"
echo "fetch"
cat "$FETCH_OUT"
fi
for name in liveimap-stalwart-dev liveimap-dovecot-dev \
  liveimap-cyrus-3137; do
  if container_exists "$name"; then
    docker stop "$name"
  fi
done
