#!/bin/sh
# Dev lab servers: pinned Cyrus, Dovecot, GreenMail, and Stalwart.
# Four base accounts. Courier and UW-IMAP have no dev pin. No app install.
set -eu

SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")
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
GM_SRC="$SRC/greenmail"
ST_SRC="$SRC/stalwart"
DV_CONF="$GEN/dovecot-dev"

CY_SHA=a9d201bccdadd429c46bc4cf16995453a86d8eed
DV_SHA=d6c3486ed839ec06f6a1ec87a6e307211e3a5c92
GM_SHA=6a0409194734caf8a4d08779cf40f5cefe5c73f3
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

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -qx "$1"
}

container_running() {
  docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}

present=0
for name in liveimap-greenmail-dev liveimap-stalwart-dev liveimap-dovecot-dev \
  liveimap-cyrus-3137; do
  if container_exists "$name"; then
    present=1
    if ! container_running "$name"; then
      docker start "$name"
    fi
  fi
done
if [ "$present" -eq 0 ]; then

mkdir -p "$GEN" "$SRC"
umask 022
cat > "$PINS_OUT" <<EOF
cyrus cyrus-imapd-3.13.7 $CY_SHA
dovecot main $DV_SHA
greenmail master $GM_SHA
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
pin_checkout "$GM_SRC" https://github.com/greenmail-mail-test/greenmail "$GM_SHA"
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
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 }' /run/liveimap/accounts.tsv > /tmp/base-names
while read -r user; do
  [ -n "$user" ] || continue
  printf 'A1 CREATE user/%s\n' "$user" > /tmp/create-cmd
  timeout 20 "$IMTEST" -m login -a cyrus -w "$admin" -p 143 -f /tmp/create-cmd 127.0.0.1 >/tmp/imtest.out 2>&1 || true
done < /tmp/base-names
rm -f /tmp/base-names /tmp/imtest.out /tmp/create-cmd
unset admin
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

echo "building greenmail jar"
docker run --rm -v "$GM_SRC":/src -w /src maven:3.9-eclipse-temurin-21 \
  ./mvnw -DskipTests -DskipITs -Ddocker.skip=true package
set -- "$GM_SRC"/greenmail-standalone/target/greenmail-standalone-*.jar
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "greenmail jar absent" >&2
  exit 1
fi
mkdir -p "$GM_SRC/greenmail-docker/standalone/target"
cp "$1" "$GM_SRC/greenmail-docker/standalone/target/greenmail-standalone.jar"
python3 - "$1" "$GM_SRC/greenmail-docker/standalone/target/greenmail.p12" <<'PY'
import sys
import zipfile

jar, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as archive:
    names = [name for name in archive.namelist() if name.endswith("greenmail.p12")]
    if len(names) != 1:
        sys.stderr.write("greenmail keystore absent\n")
        sys.exit(1)
    with archive.open(names[0]) as src, open(dest, "wb") as out:
        out.write(src.read())
PY
if [ ! -f "$GM_SRC/greenmail-docker/standalone/target/greenmail-standalone.jar" ] \
  || [ ! -f "$GM_SRC/greenmail-docker/standalone/target/greenmail.p12" ]; then
  echo "greenmail jar absent" >&2
  exit 1
fi
echo "building greenmail image"
docker build -t liveimap-greenmail-dev:phase1 "$GM_SRC/greenmail-docker/standalone"

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
while len(chosen) < 12 and draws < 400:
    draws += 1
    port = random.randint(20000, 39999)
    if port in seen or port in used:
        seen.add(port)
        continue
    seen.add(port)
    if listening(port) or not bindable(port):
        continue
    chosen.append(port)

if len(chosen) < 12:
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
shift 9
DV_IMAP=$1
CY_IMAP=$2
CY_HTTP=$3

echo "starting greenmail dovecot stalwart cyrus"
docker run -d --name liveimap-greenmail-dev \
  -p "${GM_IMAP}:3143" \
  -p "${GM_IMAPS}:3993" \
  -p "${GM_SMTP}:3025" \
  -p "${GM_API}:8080" \
  liveimap-greenmail-dev:phase1

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
docker volume create liveimap-dovecot-dev-mail >/dev/null
docker run -d --name liveimap-dovecot-dev \
  -p "${DV_IMAP}:143" \
  -v "$DV_CONF/passwd":/etc/dovecot/passwd:ro \
  -v "$DV_CONF/auth.conf":/etc/dovecot/conf.d/auth.conf:ro \
  -v liveimap-dovecot-dev-mail:/srv/vmail \
  liveimap-dovecot-dev:phase1

docker volume create liveimap-stalwart-dev-etc >/dev/null
docker volume create liveimap-stalwart-dev-data >/dev/null
docker run --rm --user 0 --entrypoint /bin/sh \
  -v liveimap-stalwart-dev-etc:/etc/stalwart \
  liveimap-stalwart-dev:phase1 \
  -c 'umask 077; printf %s "{\"@type\":\"RocksDb\",\"path\":\"/var/lib/stalwart/\"}" > /etc/stalwart/config.json; chown 2000:2000 /etc/stalwart/config.json'
umask 007
ST_ADMIN_PASS=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
printf '%s\n' "$ST_ADMIN_PASS" > "$ST_ADMIN_FILE"
umask 022
docker run -d --name liveimap-stalwart-dev \
  -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
  -p "${ST_IMAP}:143" \
  -p "${ST_IMAPS}:993" \
  -p "${ST_SIEVE}:4190" \
  -p "${ST_HTTPS}:443" \
  -p "${ST_SUB}:587" \
  -v liveimap-stalwart-dev-etc:/etc/stalwart \
  -v liveimap-stalwart-dev-data:/var/lib/stalwart \
  liveimap-stalwart-dev:phase1
unset ST_ADMIN_PASS

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

umask 007
python3 -c 'import secrets; print(secrets.token_hex(16))' > "$CY_ADMIN_FILE"
umask 022
docker run -d --name liveimap-cyrus-3137 \
  -p "${CY_IMAP}:143" \
  -p "${CY_HTTP}:8080" \
  -v "$SEED":/run/liveimap/accounts.tsv:ro \
  -v "$CY_ADMIN_FILE":/run/liveimap/admin:ro \
  liveimap-cyrus-3137:phase2

echo "login append jmap"
python3 - "$SEED" "$ACCOUNTS_OUT" "$FETCH_OUT" \
  "$GM_IMAP" "$GM_API" "$ST_IMAP" "$ST_HTTPS" "$DV_IMAP" "$CY_IMAP" "$CY_HTTP" <<'PY'
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
    gm_imap,
    gm_api,
    st_imap,
    st_https,
    dv_imap,
    cy_imap,
    cy_http,
) = sys.argv[1:]
ports = {
    "greenmail-dev": int(gm_imap),
    "stalwart-dev": int(st_imap),
    "dovecot-dev": int(dv_imap),
    "cyrus-3137": int(cy_imap),
}
order = ["greenmail-dev", "stalwart-dev", "dovecot-dev", "cyrus-3137"]
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

def post_greenmail(api_port):
    deadline = time.time() + 180
    while time.time() < deadline:
        if wait_tcp(api_port, 2):
            break
        time.sleep(0.5)
    for user, password in users:
        body = json.dumps(
            {"email": "%s@test" % user, "login": user, "password": password}
        ).encode("utf-8")
        req = urllib.request.Request(
            "http://%s:%s/api/user" % (host, api_port),
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
        if code not in (200, 201):
            sys.stderr.write("greenmail user create failed %s\n" % code)
            sys.exit(1)

post_greenmail(int(gm_api))

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

for server in ("greenmail-dev", "dovecot-dev"):
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
  printf 'greenmail-dev-imap\t%s\t3143\tliveimap-greenmail-dev\n' "$GM_IMAP"
  printf 'greenmail-dev-imaps\t%s\t3993\tliveimap-greenmail-dev\n' "$GM_IMAPS"
  printf 'greenmail-dev-smtp\t%s\t3025\tliveimap-greenmail-dev\n' "$GM_SMTP"
  printf 'greenmail-dev-api\t%s\t8080\tliveimap-greenmail-dev\n' "$GM_API"
  printf 'stalwart-dev-imap\t%s\t143\tliveimap-stalwart-dev\n' "$ST_IMAP"
  printf 'stalwart-dev-imaps\t%s\t993\tliveimap-stalwart-dev\n' "$ST_IMAPS"
  printf 'stalwart-dev-managesieve\t%s\t4190\tliveimap-stalwart-dev\n' "$ST_SIEVE"
  printf 'stalwart-dev-https\t%s\t443\tliveimap-stalwart-dev\n' "$ST_HTTPS"
  printf 'stalwart-dev-submission\t%s\t587\tliveimap-stalwart-dev\n' "$ST_SUB"
  printf 'dovecot-dev-imap\t%s\t143\tliveimap-dovecot-dev\n' "$DV_IMAP"
  printf 'cyrus3137-imap\t%s\t143\tliveimap-cyrus-3137\n' "$CY_IMAP"
  printf 'cyrus3137-http\t%s\t8080\tliveimap-cyrus-3137\n' "$CY_HTTP"
} >> "$PORTS_FILE"

echo "containers"
docker inspect -f '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}}' \
  liveimap-greenmail-dev liveimap-stalwart-dev liveimap-dovecot-dev \
  liveimap-cyrus-3137
echo "ports"
echo "greenmail-dev-imap $GM_IMAP"
echo "greenmail-dev-imaps $GM_IMAPS"
echo "greenmail-dev-smtp $GM_SMTP"
echo "greenmail-dev-api $GM_API"
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
for name in liveimap-greenmail-dev liveimap-stalwart-dev liveimap-dovecot-dev \
  liveimap-cyrus-3137; do
  if container_exists "$name"; then
    docker stop "$name"
  fi
done
