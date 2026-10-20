#!/bin/sh
# Older lab servers. Four base accounts. No app install.
# The only address literal is 127.0.0.1. Publish with -p host:container.
set -eu

SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")
cport() {
  python3 "$DIR/cport.py" "$1" "$2" || exit 1
}
SEED="$DIR/seed/accounts.tsv"
GEN="$DIR/generated"
PORTS_FILE="$GEN/local-ports.tsv"
ACCOUNTS_OUT="$GEN/older-accounts.txt"
FETCH_OUT="$GEN/older-fetch.txt"
PINS_OUT="$GEN/older-pins.txt"
ADMIN_DIR="$GEN/older-admins"
HOST=127.0.0.1

if [ ! -f "$SEED" ]; then
  echo "missing seed file" >&2
  exit 1
fi

command -v docker >/dev/null
command -v python3 >/dev/null
command -v ss >/dev/null

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

mkdir -p "$GEN" "$ADMIN_DIR" \
  "$GEN/dovecot-10" "$GEN/dovecot-12" "$GEN/dovecot-22"

running() {
  docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}

published_port() {
  name=$1
  cport=$2
  line=$(docker port "$name" "$cport" | head -n 1)
  port=${line##*:}
  case "$port" in
    ''|*[!0-9]*)
      echo "missing published port for $name" >&2
      exit 1
      ;;
  esac
  printf '%s\n' "$port"
}

draw_port() {
  python3 - "$PORTS_FILE" "$@" <<'PY'
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
for extra in sys.argv[2:]:
    if extra.isdigit():
        used.add(int(extra))

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
while len(chosen) < 1 and draws < 400:
    draws += 1
    port = random.randint(20000, 39999)
    if port in seen or port in used:
        seen.add(port)
        continue
    seen.add(port)
    if listening(port) or not bindable(port):
        continue
    chosen.append(port)

if len(chosen) < 1:
    sys.exit(1)
print(chosen[0])
PY
}

port_open() {
  python3 - "$HOST" "$1" <<'PY'
import socket
import sys
host, port = sys.argv[1], int(sys.argv[2])
sock = None
data = b""
try:
    sock = socket.create_connection((host, port), 2)
    sock.settimeout(2)
    data = sock.recv(16)
except OSError:
    sys.exit(1)
finally:
    if sock is not None:
        sock.close()
if not data:
    sys.exit(1)
PY
}

wait_port() {
  name=$1
  port=$2
  i=0
  while [ "$i" -lt 60 ]; do
    if port_open "$port"; then
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done
  echo "port did not open for $name" >&2
  return 1
}

write_passwd() {
  dest=$1
  umask 007
  python3 - "$SEED" "$dest" <<'PY'
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
        lines.append("%s:{PLAIN}%s:1000:1000::/srv/vmail/%s\n" % (user, password, user))
want = ["liveimap", "liveimap2", "liveimap-empty", "liveimap-utf8"]
got = [line.split(":", 1)[0] for line in lines]
if got != want:
    sys.stderr.write("unexpected base users\n")
    sys.exit(1)
with open(dest, "w", encoding="utf-8") as handle:
    handle.writelines(lines)
PY
  umask 022
}

write_admin() {
  dest=$1
  umask 007
  python3 -c 'import secrets; print(secrets.token_hex(16))' > "$dest"
  umask 022
}

copy_seed() {
  name=$1
  docker exec "$name" mkdir -p /run/liveimap
  docker cp "$SEED" "$name":/run/liveimap/accounts.tsv
}

require_version() {
  name=$1
  pkg=$2
  prefix=$3
  ver=$(docker exec "$name" dpkg-query -W -f '${Version}' "$pkg")
  case "$ver" in
    "$prefix"*) ;;
    *)
      echo "version mismatch for $pkg" >&2
      exit 1
      ;;
  esac
}

pin_packages() {
  name=$1
  shift
  docker exec "$name" dpkg-query -W "$@" | while IFS= read -r line; do
    printf '%s\t%s\n' "$name" "$line"
  done >> "$PINS_OUT"
}

configure_cyrus() {
  name=$1
  sep=$2
  want_http=$3
  docker exec -i -e MBOX_SEP="$sep" -e WANT_HTTP="$want_http" "$name" sh -s <<'EOF'
set -eu
export PATH="/usr/sbin:/sbin:$PATH"
conf=/etc/imapd.conf
set_key() {
  if grep -q "^$1:" "$conf"; then
    sed -i "s|^$1:.*|$1: $2|" "$conf"
  else
    printf '%s: %s\n' "$1" "$2" >> "$conf"
  fi
}
set_key allowplaintext yes
set_key sasl_pwcheck_method auxprop
set_key sasl_mech_list "PLAIN LOGIN"
set_key admins cyrus
set_key sasl_minimum_layer 0
if [ "$MBOX_SEP" = / ]; then
  set_key unixhierarchysep yes
fi
if [ "$WANT_HTTP" = yes ]; then
  set_key httpmodules jmap
  set_key conversations 1
  if grep -q 'listen="8008"' /etc/cyrus.conf; then
    sed -i 's/listen="8008"/listen="8080"/' /etc/cyrus.conf
  fi
  if ! grep -q 'httpd' /etc/cyrus.conf; then
    echo "cyrus.conf has no httpd" >&2
    exit 1
  fi
fi
for bin in pop3d nntpd httpd; do
  if [ ! -x "/usr/lib/cyrus/bin/$bin" ] && [ ! -x "/usr/sbin/$bin" ]; then
    sed -i "/^[[:space:]]*[^#].*${bin}/s/^/#/" /etc/cyrus.conf
  fi
done
if [ -x /usr/sbin/cyrus ] && ! /usr/sbin/cyrus tls_prune >/dev/null 2>&1; then
  sed -i "/^[[:space:]]*[^#].*tls_prune/s/^/#/" /etc/cyrus.conf
fi
sasl_body='pwcheck_method: auxprop
auxprop_plugin: sasldb
mech_list: plain login
sasldb_path: /etc/sasldb2
'
for dir in /usr/lib/sasl2 /usr/lib/x86_64-linux-gnu/sasl2 /etc/sasl2; do
  mkdir -p "$dir"
  printf '%s\n' "$sasl_body" > "$dir/Cyrus.conf"
done
admin=$(tr -d '\r\n' < /run/liveimap/admin)
printf '%s\n' "$admin" > /tmp/sasl-one
saslpasswd2 -p -c cyrus < /tmp/sasl-one
rm -f /tmp/sasl-one
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 }' /run/liveimap/accounts.tsv > /tmp/base-names
while read -r user; do
  [ -n "$user" ] || continue
  awk -F '\t' -v u="$user" 'NR > 1 && $1 == u { print $2; exit }' /run/liveimap/accounts.tsv > /tmp/sasl-one
  if saslpasswd2 -p -c "$user" < /tmp/sasl-one; then
    rm -f /tmp/sasl-one
    continue
  fi
  rm -f /tmp/sasl-one
  if [ "$user" = "liveimap-utf8" ]; then
    echo "utf8 sasl skipped" >&2
    continue
  fi
  echo "sasl user failed" >&2
  exit 1
done < /tmp/base-names
rm -f /tmp/base-names
if getent group sasl >/dev/null 2>&1; then
  chown cyrus:sasl /etc/sasldb2
else
  chown cyrus /etc/sasldb2
fi
chmod 640 /etc/sasldb2
mkdir -p /run/cyrus/socket /var/run/cyrus/socket /var/lib/cyrus /var/spool/cyrus/mail
if id cyrus >/dev/null 2>&1; then
  chown -R cyrus:mail /run/cyrus /var/run/cyrus /var/lib/cyrus /var/spool/cyrus 2>/dev/null \
    || chown -R cyrus /run/cyrus /var/run/cyrus /var/lib/cyrus /var/spool/cyrus
fi
for init in /etc/init.d/cyrus2.2 /etc/init.d/cyrus2.4 /etc/init.d/cyrus2.5 /etc/init.d/cyrus-imapd; do
  if [ -x "$init" ]; then
    "$init" stop || true
  fi
done
for pidf in /var/run/cyrus-master.pid /run/cyrus-master.pid /var/run/cyrmaster.pid; do
  if [ -s "$pidf" ]; then
    kill "$(cat "$pidf")" 2>/dev/null || true
    rm -f "$pidf"
  fi
done
sleep 1
if [ -x /usr/sbin/cyrmaster ]; then
  setsid /usr/sbin/cyrmaster -d </dev/null >/tmp/cyrmaster.out 2>&1 || {
    echo "cyrmaster failed" >&2
    exit 1
  }
else
  echo "cyrmaster missing" >&2
  exit 1
fi
EOF
}

ensure_cyrus_master() {
  name=$1
  port=$2
  if port_open "$port"; then
    return 0
  fi
  docker exec "$name" sh -c 'export PATH="/usr/sbin:/usr/bin:/sbin:$PATH"; setsid /usr/sbin/cyrmaster -d </dev/null >/tmp/cyrmaster.out 2>&1' || true
  wait_port "$name" "$port"
}

create_cyrus_mailboxes() {
  name=$1
  port=$2
  sep=$3
  admin="$ADMIN_DIR/${name}.txt"
  python3 - "$HOST" "$port" "$admin" "$sep" "$SEED" <<'PY'
import csv
import socket
import ssl
import sys
import time

host, port_s, admin_path, sep, seed = sys.argv[1:]
port = int(port_s)
with open(admin_path, encoding="utf-8") as handle:
    admin_pass = handle.read().strip()
users = []
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("profile") == "base":
            users.append(row["user"])

def connect():
    sock = socket.create_connection((host, port), 10)
    sock.settimeout(30)
    return sock

class Imap:
    def __init__(self):
        self.s = connect()
        self.buf = b""
        self.n = 0

    def readline(self):
        while b"\n" not in self.buf:
            chunk = self.s.recv(8192)
            if not chunk:
                break
            self.buf += chunk
        line, sep_b, rest = self.buf.partition(b"\n")
        self.buf = rest
        return line.decode("utf-8", "replace").rstrip("\r")

    def command(self, text):
        self.n += 1
        tag = "a%d" % self.n
        self.s.sendall(("%s %s\r\n" % (tag, text)).encode("utf-8"))
        lines = []
        while True:
            line = self.readline()
            lines.append(line)
            if line.startswith(tag + " "):
                return lines

    def starttls(self):
        lines = self.command("STARTTLS")
        if not any(" OK" in line for line in lines):
            sys.exit(1)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.s = ctx.wrap_socket(self.s, server_hostname="localhost")
        self.buf = b""

deadline = time.time() + 60
imap = None
greeting = ""
while time.time() < deadline:
    try:
        imap = Imap()
        greeting = imap.readline()
    except OSError:
        time.sleep(0.5)
        continue
    if "OK" in greeting:
        break
if imap is None or "OK" not in greeting:
    sys.stderr.write("cyrus greeting missing OK\n")
    sys.exit(1)
if "LOGINDISABLED" in greeting:
    imap.starttls()
lines = imap.command('LOGIN cyrus "%s"' % admin_pass)
if not any(line.split(" ", 2)[1:2] == ["OK"] for line in lines):
    sys.stderr.write("cyrus admin login failed\n")
    sys.exit(1)
for user in users:
    mailbox = "user" + sep + user
    lines = imap.command("CREATE %s" % mailbox)
    text = " ".join(lines).lower()
    good = any(line.split(" ", 2)[1:2] == ["OK"] for line in lines) or "exist" in text
    if not good:
        sys.stderr.write("mailbox create failed\n")
        sys.exit(1)
PY
}

start_cyrus() {
  name=$1
  image=$2
  pkg=$3
  port=$4
  http_port=$5
  sep=$6
  caldav=$7
  version_prefix=$8
  admin="$ADMIN_DIR/${name}.txt"
  write_admin "$admin"
  server=${name#liveimap-}
  imap_c=$(cport "$server" imap)
  if [ -n "$http_port" ]; then
    http_c=$(cport "$server" http)
    create_from_seed "$server" \
      -p "${port}:${imap_c}" \
      -p "${http_port}:${http_c}"
  else
    create_from_seed "$server" \
      -p "${port}:${imap_c}"
  fi
  docker exec "$name" apt-get update
  if [ "$caldav" = yes ]; then
    docker exec -e DEBIAN_FRONTEND=noninteractive "$name" \
      apt-get install -y "$pkg" sasl2-bin cyrus-caldav
    pin_packages "$name" "$pkg" sasl2-bin cyrus-caldav
  else
    docker exec -e DEBIAN_FRONTEND=noninteractive "$name" \
      apt-get install -y "$pkg" sasl2-bin
    pin_packages "$name" "$pkg" sasl2-bin
  fi
  if [ -n "$version_prefix" ]; then
    require_version "$name" "$pkg" "$version_prefix"
  fi
  copy_seed "$name"
  docker cp "$admin" "$name":/run/liveimap/admin
  configure_cyrus "$name" "$sep" "$caldav"
  echo "configured $name" >&2
  ensure_cyrus_master "$name" "$port"
  create_cyrus_mailboxes "$name" "$port" "$sep"
  echo "mailboxes $name" >&2
}

patch_dovecot_10() {
  python3 - "$1" "$2" <<'PY'
import sys
path = sys.argv[1]
style = sys.argv[2]
with open(path, encoding="utf-8", errors="replace") as handle:
    lines = handle.read().splitlines(keepends=True)

def assignment_key(line):
    raw = line.split("#", 1)[0].strip()
    if not raw or "=" not in raw or "{" in raw or "}" in raw:
        return None
    key = raw.split("=", 1)[0].strip()
    if not key or " " in key:
        return None
    return key

def set_global(lines, key, value):
    replaced = False
    out = []
    for line in lines:
        if line.lstrip().startswith("#"):
            out.append(line)
            continue
        if assignment_key(line) == key:
            if not replaced:
                out.append("%s = %s\n" % (key, value))
                replaced = True
            continue
        out.append(line)
    if not replaced:
        out.insert(0, "%s = %s\n" % (key, value))
    return out

def skip_block(lines, start):
    depth = 0
    i = start
    while i < len(lines):
        depth += lines[i].count("{") - lines[i].count("}")
        i += 1
        if depth <= 0:
            return i
    sys.exit(1)

out = []
i = 0
while i < len(lines):
    stripped = lines[i].strip()
    if stripped.startswith("passdb pam {") or stripped.startswith("userdb passwd {"):
        kind = "passdb" if stripped.startswith("passdb") else "userdb"
        i = skip_block(lines, i)
        out.append("  %s passwd-file {\n" % kind)
        out.append("    args = /etc/dovecot/passwd\n")
        out.append("  }\n")
        continue
    out.append(lines[i])
    i += 1
out = set_global(out, "disable_plaintext_auth", "no")
if style == "v12":
    out = set_global(out, "ssl", "no")
else:
    out = set_global(out, "ssl_disable", "yes")
out = set_global(out, "protocols", "imap")
out = set_global(out, "mail_location", "maildir:/srv/vmail/%u/Maildir")
text = "".join(out)
if "passdb pam {" in text or "userdb passwd {" in text:
    sys.exit(1)
if "passdb passwd-file {" not in text or "userdb passwd-file {" not in text:
    sys.exit(1)
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text)
PY
}

configure_dovecot_22() {
  name=$1
  docker exec -i "$name" sh -s <<'EOF'
set -eu
if [ ! -f /etc/dovecot/conf.d/auth-system.conf.ext ]; then
  echo "dovecot auth file missing" >&2
  exit 1
fi
cat > /etc/dovecot/conf.d/auth-system.conf.ext <<'AUTH'
passdb {
  driver = passwd-file
  args = /etc/dovecot/passwd
}
userdb {
  driver = passwd-file
  args = /etc/dovecot/passwd
}
AUTH
sed -i 's/^#\?disable_plaintext_auth.*/disable_plaintext_auth = no/' \
  /etc/dovecot/conf.d/10-auth.conf
if [ -f /etc/dovecot/conf.d/10-ssl.conf ]; then
  sed -i 's/^#\?ssl *=.*/ssl = no/' /etc/dovecot/conf.d/10-ssl.conf
fi
if [ -f /etc/dovecot/conf.d/10-mail.conf ]; then
  if grep -q '^mail_location' /etc/dovecot/conf.d/10-mail.conf; then
    sed -i 's|^mail_location *=.*|mail_location = maildir:/srv/vmail/%u/Maildir|' \
      /etc/dovecot/conf.d/10-mail.conf
  else
    printf '\nmail_location = maildir:/srv/vmail/%u/Maildir\n' >> /etc/dovecot/conf.d/10-mail.conf
  fi
fi
doveconf -n >/dev/null
EOF
}

start_dovecot() {
  name=$1
  image=$2
  port=$3
  style=$4
  passfile="$GEN/${name#liveimap-}/passwd"
  write_passwd "$passfile"
  server=${name#liveimap-}
  imap_c=$(cport "$server" imap)
  create_from_seed "$server" \
    -p "${port}:${imap_c}"
  docker exec "$name" apt-get update
  docker exec -e DEBIAN_FRONTEND=noninteractive "$name" apt-get install -y dovecot-imapd
  pin_packages "$name" dovecot-imapd
  copy_seed "$name"
  docker exec "$name" mkdir -p /etc/dovecot /srv/vmail
  docker cp "$passfile" "$name":/etc/dovecot/passwd
  docker exec "$name" sh -c 'if getent group dovecot >/dev/null 2>&1; then chown root:dovecot /etc/dovecot/passwd; else chown root /etc/dovecot/passwd; fi; chmod 640 /etc/dovecot/passwd'
  if [ "$style" = v22 ]; then
    configure_dovecot_22 "$name"
  else
    conf="$GEN/${name#liveimap-}/dovecot.conf"
    docker cp "$name":/etc/dovecot/dovecot.conf "$conf"
    patch_dovecot_10 "$conf" "$style"
    docker cp "$conf" "$name":/etc/dovecot/dovecot.conf
  fi
  docker exec -i "$name" sh -s <<'EOF'
set -eu
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 }' /run/liveimap/accounts.tsv > /tmp/base-names
while read -r user; do
  [ -n "$user" ] || continue
  mkdir -p "/srv/vmail/$user/Maildir"
done < /tmp/base-names
rm -f /tmp/base-names
chown -R 1000:1000 /srv/vmail
if [ -s /var/run/dovecot/master.pid ]; then
  kill "$(cat /var/run/dovecot/master.pid)" 2>/dev/null || true
  sleep 1
fi
if [ -x /etc/init.d/dovecot ]; then
  /etc/init.d/dovecot stop || true
fi
if ! setsid dovecot </dev/null >/tmp/dovecot.out 2>&1; then
  echo "dovecot rejected config" >&2
  exit 1
fi
EOF
  wait_port "$name" "$port"
}

start_courier() {
  name=liveimap-courier-50
  port=$CO
  create_from_seed courier-50 \
    -p "${port}:$(cport courier-50 imap)"
  docker exec "$name" apt-get update
  docker exec -e DEBIAN_FRONTEND=noninteractive "$name" \
    apt-get install -y courier-imap courier-authlib-userdb
  pin_packages "$name" courier-imap courier-authlib-userdb
  require_version "$name" courier-imap 5.0.13
  copy_seed "$name"
  docker exec -i "$name" sh -s <<'EOF'
set -eu
export PATH="/usr/sbin:/usr/bin:/sbin:/bin"
sed -i 's/^authmodulelist=.*/authmodulelist="authuserdb"/' /etc/courier/authdaemonrc
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 }' /run/liveimap/accounts.tsv > /tmp/base-names
while read -r user; do
  [ -n "$user" ] || continue
  home="/var/vmail/$user"
  userdb "$user" set "uid=1000" "gid=1000" "home=$home" "mail=$home/Maildir"
  awk -F '\t' -v u="$user" 'NR > 1 && $1 == u { print $2; exit }' /run/liveimap/accounts.tsv > /tmp/userdb-one
  if ! userdbpw -md5 < /tmp/userdb-one | userdb "$user" set systempw; then
    rm -f /tmp/userdb-one
    if [ "$user" = "liveimap-utf8" ]; then
      echo "utf8 userdb skipped" >&2
      continue
    fi
    echo "userdb failed" >&2
    exit 1
  fi
  rm -f /tmp/userdb-one
  mkdir -p "$home"
  maildirmake "$home/Maildir"
  chown -R 1000:1000 "$home"
done < /tmp/base-names
rm -f /tmp/base-names
makeuserdb
setsid /etc/init.d/courier-authdaemon start </dev/null >/tmp/courier-auth.out 2>&1
setsid /etc/init.d/courier-imap start </dev/null >/tmp/courier-imap.out 2>&1
EOF
  wait_port "$name" "$port"
}

stalwart_accounts() {
  name=liveimap-stalwart-0155
  if ! docker exec "$name" /bin/sh -c 'command -v curl >/dev/null 2>&1'; then
    echo "stalwart curl missing" >&2
    exit 1
  fi
  python3 - "$SEED" "$name" <<'PY'
import csv
import json
import subprocess
import sys

seed, name = sys.argv[1], sys.argv[2]

def run_curl(script, payload, label):
    proc = subprocess.run(
        ["docker", "exec", "-i", name, "/bin/sh", "-c", script],
        input=payload,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    out = proc.stdout.decode("utf-8", "replace").strip()
    if proc.returncode == 0 and out == "ok":
        return "ok"
    code = "request"
    parts = out.split()
    if len(parts) == 2 and parts[0] == "fail" and parts[1].isdigit():
        code = parts[1]
    if label is None:
        return code
    sys.stderr.write("%s error %s\n" % (label, code))
    sys.exit(1)

domain_sh = r"""
curl -sS -o /tmp/st-body -w "%{http_code}" -u "$STALWART_RECOVERY_ADMIN" -X POST -H "Content-Length: 0" \
  http://127.0.0.1:8080/api/domain/localhost.local > /tmp/st-code || {
  rm -f /tmp/st-body /tmp/st-code
  echo fail 0
  exit 1
}
code=$(tr -d '\r\n' < /tmp/st-code)
norm=$(tr 'A-Z' 'a-z' < /tmp/st-body | tr -d ' \t\r\n')
rm -f /tmp/st-body /tmp/st-code
case "$norm" in
  *alreadyexists*) echo ok; exit 0 ;;
esac
case "$code" in
  200) echo ok; exit 0 ;;
esac
echo "fail $code"
exit 1
"""
# v0.15.5 has no /api/domain route. A 404 falls through to a domain principal.
domain_code = run_curl(domain_sh, b"", None)

users = []
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("profile") != "base":
            continue
        user = row.get("user") or ""
        password = row.get("password") or ""
        if not user or not password:
            sys.stderr.write("unexpected base users\n")
            sys.exit(1)
        users.append((user, password))
want = ["liveimap", "liveimap2", "liveimap-empty", "liveimap-utf8"]
if [item[0] for item in users] != want:
    sys.stderr.write("unexpected base users\n")
    sys.exit(1)

def sha512_crypt(password):
    proc = subprocess.run(
        ["openssl", "passwd", "-6", "-stdin"],
        input=password.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if proc.returncode != 0:
        sys.stderr.write("hash failed\n")
        sys.exit(1)
    hashed = proc.stdout.decode("utf-8", "replace").strip()
    if not hashed.startswith("$6$") or any(ch.isspace() for ch in hashed):
        sys.stderr.write("hash failed\n")
        sys.exit(1)
    return hashed

account_sh = r"""
curl -sS -o /tmp/st-body -w "%{http_code}" -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- \
  http://127.0.0.1:8080/api/principal > /tmp/st-code || {
  rm -f /tmp/st-body /tmp/st-code
  echo fail 0
  exit 1
}
code=$(tr -d '\r\n' < /tmp/st-code)
norm=$(tr 'A-Z' 'a-z' < /tmp/st-body | tr -d ' \t\r\n')
rm -f /tmp/st-body /tmp/st-code
case "$norm" in
  *alreadyexists*) echo ok; exit 0 ;;
esac
case "$code" in
  200)
    printf '%s' "$norm" | grep -Eq '^[0-9]+$' && echo ok && exit 0
    printf '%s' "$norm" | grep -Eq '"data":[0-9]+' && echo ok && exit 0
    echo "fail $code"
    exit 1
    ;;
esac
echo "fail $code"
exit 1
"""
if domain_code != "ok":
    if domain_code != "404":
        sys.stderr.write("stalwart domain error %s\n" % domain_code)
        sys.exit(1)
    run_curl(
        account_sh,
        json.dumps({"type": "domain", "name": "localhost.local"}).encode("utf-8"),
        "stalwart domain",
    )
for user, password in users:
    payload = {
        "type": "individual",
        "name": user,
        "secrets": [sha512_crypt(password)],
        "emails": ["%s@localhost.local" % user],
    }
    run_curl(account_sh, json.dumps(payload).encode("utf-8"), "stalwart account")
    role_sh = (
        "curl -sS -o /tmp/st-body -w \"%{http_code}\" -u \"$STALWART_RECOVERY_ADMIN\" "
        "-H \"Content-Type: application/json\" -X PATCH --data-binary @- "
        "http://127.0.0.1:8080/api/principal/" + user + " > /tmp/st-code || {\n"
        "  rm -f /tmp/st-body /tmp/st-code\n"
        "  echo fail 0\n"
        "  exit 1\n"
        "}\n"
        "code=$(tr -d '\\r\\n' < /tmp/st-code)\n"
        "norm=$(tr 'A-Z' 'a-z' < /tmp/st-body | tr -d ' \\t\\r\\n')\n"
        "rm -f /tmp/st-body /tmp/st-code\n"
        "case \"$norm\" in\n"
        "  *alreadyexists*) echo ok; exit 0 ;;\n"
        "esac\n"
        "case \"$code\" in\n"
        "  200) echo ok; exit 0 ;;\n"
        "esac\n"
        "echo \"fail $code\"\n"
        "exit 1\n"
    )
    run_curl(
        role_sh,
        json.dumps([{"action": "addItem", "field": "roles", "value": "user"}]).encode("utf-8"),
        "stalwart role",
    )
PY
}

probe_uw() {
  if ! docker run --rm debian/eol:squeeze sh -c 'apt-get update >&2 && apt-cache policy uw-imapd' > "$GEN/uw-policy.txt"; then
    echo "apt-get update failed on debian/eol:squeeze" >&2
    rm -f "$GEN/uw-policy.txt"
    return 1
  fi
  if ! awk '/Candidate:/ { if ($2 == "8:2007e~dfsg-3.1") ok=1 } END { exit !ok }' "$GEN/uw-policy.txt"; then
    echo "uw-imapd candidate is not 8:2007e~dfsg-3.1" >&2
    rm -f "$GEN/uw-policy.txt"
    return 1
  fi
  rm -f "$GEN/uw-policy.txt"
  return 0
}

start_uw() {
  name=liveimap-uwimap
  port=$UW
  require_seed_image uwimap
  if ! docker create --name "$name" \
    -p "${port}:$(cport uwimap imap)" \
    liveimap-seed-uwimap; then
    return 1
  fi
  if ! docker start "$name"; then
    return 1
  fi
  if ! docker exec "$name" apt-get update; then
    return 1
  fi
  if ! docker exec -e DEBIAN_FRONTEND=noninteractive "$name" apt-get install -y uw-imapd; then
    return 1
  fi
  ver=$(docker exec "$name" dpkg-query -W -f '${Version}' uw-imapd) || return 1
  if [ "$ver" != "8:2007e~dfsg-3.1" ]; then
    echo "version mismatch for uw-imapd" >&2
    return 1
  fi
  pin_packages "$name" uw-imapd || return 1
  docker exec "$name" mkdir -p /run/liveimap || return 1
  docker cp "$SEED" "$name":/run/liveimap/accounts.tsv || return 1
  umask 007
  if ! python3 - "$SEED" "$GEN/uw-chpasswd" <<'PY'
import csv
import sys
seed, dest = sys.argv[1], sys.argv[2]
lines = []
with open(seed, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if row.get("profile") != "base":
            continue
        lines.append("%s:%s\n" % (row["user"], row["password"]))
with open(dest, "w", encoding="utf-8") as handle:
    handle.writelines(lines)
PY
  then
    umask 022
    return 1
  fi
  umask 022
  if ! docker cp "$GEN/uw-chpasswd" "$name":/run/liveimap/chpasswd; then
    rm -f "$GEN/uw-chpasswd"
    return 1
  fi
  rm -f "$GEN/uw-chpasswd"
  if ! docker exec -i "$name" sh -s <<'EOF'
set -eu
awk -F '\t' 'NR > 1 && $3 == "base" { print $1 }' /run/liveimap/accounts.tsv > /tmp/base-names
while read -r user; do
  [ -n "$user" ] || continue
  id "$user" >/dev/null 2>&1 || useradd -m -s /usr/sbin/nologin "$user"
  touch "/home/$user/mbox" "/var/mail/$user"
  chown "$user:$user" "/home/$user/mbox" "/var/mail/$user" || chown "$user" "/home/$user/mbox" "/var/mail/$user"
done < /tmp/base-names
rm -f /tmp/base-names
while IFS= read -r line; do
  [ -n "$line" ] || continue
  user=${line%%:*}
  printf '%s\n' "$line" | chpasswd && continue
  if [ "$user" = "liveimap-utf8" ]; then
    echo "utf8 chpasswd skipped" >&2
    continue
  fi
  echo "chpasswd failed" >&2
  exit 1
done < /run/liveimap/chpasswd
rm -f /run/liveimap/chpasswd
if [ -f /etc/inetd.conf ] && grep -q '^#.*imap' /etc/inetd.conf; then
  sed -i '/imap/s/^#//' /etc/inetd.conf
fi
if [ -x /etc/init.d/openbsd-inetd ]; then
  setsid /etc/init.d/openbsd-inetd start </dev/null >/tmp/inetd.out 2>&1
elif [ -x /usr/sbin/inetd ]; then
  setsid /usr/sbin/inetd </dev/null >/tmp/inetd.out 2>&1
else
  echo "inetd missing" >&2
  exit 1
fi
EOF
  then
    return 1
  fi
  wait_port "$name" "$port"
}

keep_running() {
  if running "$1"; then
    return 0
  fi
  if container_exists "$1"; then
    docker start "$1"
    return 0
  fi
  echo "container is absent: $1" >&2
  exit 1
}

stop_owned() {
  for name in \
    liveimap-cyrus-22 liveimap-cyrus-24 liveimap-cyrus-25 liveimap-cyrus-36 \
    liveimap-dovecot-10 liveimap-dovecot-12 liveimap-dovecot-22 \
    liveimap-courier-50 liveimap-uwimap \
    liveimap-stalwart-0155
  do
    if container_exists "$name"; then
      docker stop "$name"
    fi
  done
}

expected_ports() {
  case "$1" in
    liveimap-cyrus-22) echo "$(cport cyrus-22 imap)" ;;
    liveimap-cyrus-24) echo "$(cport cyrus-24 imap)" ;;
    liveimap-cyrus-25) echo "$(cport cyrus-25 imap)" ;;
    liveimap-dovecot-10) echo "$(cport dovecot-10 imap)" ;;
    liveimap-dovecot-12) echo "$(cport dovecot-12 imap)" ;;
    liveimap-dovecot-22) echo "$(cport dovecot-22 imap)" ;;
    liveimap-courier-50) echo "$(cport courier-50 imap)" ;;
    liveimap-uwimap) echo "$(cport uwimap imap)" ;;
    liveimap-cyrus-36) echo "$(cport cyrus-36 imap) $(cport cyrus-36 http)" ;;
    liveimap-stalwart-0155) echo "$(cport stalwart-0155 imap) $(cport stalwart-0155 imaps) $(cport stalwart-0155 managesieve) $(cport stalwart-0155 https) $(cport stalwart-0155 submission)" ;;
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
    cyrus-22)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus22-imap:$(cport cyrus-22 imap):liveimap-cyrus-22)
      create_from_seed cyrus-22 -p "${1}:$(cport cyrus-22 imap)"
      printf 'cyrus22-imap\t%s\t%s\tliveimap-cyrus-22\n' "$1" "$(cport cyrus-22 imap)" > "$rows"
      replace_port_rows liveimap-cyrus-22 "" "$rows"
      ;;
    cyrus-24)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus24-imap:$(cport cyrus-24 imap):liveimap-cyrus-24)
      create_from_seed cyrus-24 -p "${1}:$(cport cyrus-24 imap)"
      printf 'cyrus24-imap\t%s\t%s\tliveimap-cyrus-24\n' "$1" "$(cport cyrus-24 imap)" > "$rows"
      replace_port_rows liveimap-cyrus-24 "" "$rows"
      ;;
    cyrus-25)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus25-imap:$(cport cyrus-25 imap):liveimap-cyrus-25)
      create_from_seed cyrus-25 -p "${1}:$(cport cyrus-25 imap)"
      printf 'cyrus25-imap\t%s\t%s\tliveimap-cyrus-25\n' "$1" "$(cport cyrus-25 imap)" > "$rows"
      replace_port_rows liveimap-cyrus-25 "" "$rows"
      ;;
    cyrus-36)
      # shellcheck disable=SC2046
      set -- $(pick_ports cyrus36-imap:$(cport cyrus-36 imap):liveimap-cyrus-36 cyrus36-http:$(cport cyrus-36 http):liveimap-cyrus-36)
      create_from_seed cyrus-36 -p "${1}:$(cport cyrus-36 imap)" -p "${2}:$(cport cyrus-36 http)"
      {
        printf 'cyrus36-imap\t%s\t%s\tliveimap-cyrus-36\n' "$1" "$(cport cyrus-36 imap)"
        printf 'cyrus36-http\t%s\t%s\tliveimap-cyrus-36\n' "$2" "$(cport cyrus-36 http)"
      } > "$rows"
      replace_port_rows liveimap-cyrus-36 "" "$rows"
      ;;
    dovecot-10)
      # shellcheck disable=SC2046
      set -- $(pick_ports dovecot10-imap:$(cport dovecot-10 imap):liveimap-dovecot-10)
      create_from_seed dovecot-10 -p "${1}:$(cport dovecot-10 imap)"
      printf 'dovecot10-imap\t%s\t%s\tliveimap-dovecot-10\n' "$1" "$(cport dovecot-10 imap)" > "$rows"
      replace_port_rows liveimap-dovecot-10 "" "$rows"
      ;;
    dovecot-12)
      # shellcheck disable=SC2046
      set -- $(pick_ports dovecot12-imap:$(cport dovecot-12 imap):liveimap-dovecot-12)
      create_from_seed dovecot-12 -p "${1}:$(cport dovecot-12 imap)"
      printf 'dovecot12-imap\t%s\t%s\tliveimap-dovecot-12\n' "$1" "$(cport dovecot-12 imap)" > "$rows"
      replace_port_rows liveimap-dovecot-12 "" "$rows"
      ;;
    dovecot-22)
      # shellcheck disable=SC2046
      set -- $(pick_ports dovecot22-imap:$(cport dovecot-22 imap):liveimap-dovecot-22)
      create_from_seed dovecot-22 -p "${1}:$(cport dovecot-22 imap)"
      printf 'dovecot22-imap\t%s\t%s\tliveimap-dovecot-22\n' "$1" "$(cport dovecot-22 imap)" > "$rows"
      replace_port_rows liveimap-dovecot-22 "" "$rows"
      ;;
    courier-50)
      # shellcheck disable=SC2046
      set -- $(pick_ports courier50-imap:$(cport courier-50 imap):liveimap-courier-50)
      create_from_seed courier-50 -p "${1}:$(cport courier-50 imap)"
      printf 'courier50-imap\t%s\t%s\tliveimap-courier-50\n' "$1" "$(cport courier-50 imap)" > "$rows"
      replace_port_rows liveimap-courier-50 "" "$rows"
      ;;
    uwimap)
      # shellcheck disable=SC2046
      set -- $(pick_ports uwimap-imap:$(cport uwimap imap):liveimap-uwimap)
      create_from_seed uwimap -p "${1}:$(cport uwimap imap)"
      printf 'uwimap-imap\t%s\t%s\tliveimap-uwimap\n' "$1" "$(cport uwimap imap)" > "$rows"
      replace_port_rows liveimap-uwimap "" "$rows"
      ;;
    stalwart-0155)
      # shellcheck disable=SC2046
      set -- $(pick_ports \
        stalwart015-imap:$(cport stalwart-0155 imap):liveimap-stalwart-0155 \
        stalwart015-imaps:$(cport stalwart-0155 imaps):liveimap-stalwart-0155 \
        stalwart015-managesieve:$(cport stalwart-0155 managesieve):liveimap-stalwart-0155 \
        stalwart015-http:$(cport stalwart-0155 https):liveimap-stalwart-0155 \
        stalwart015-submission:$(cport stalwart-0155 submission):liveimap-stalwart-0155)
      admin="$GEN/stalwart-0155-admin.txt"
      if [ ! -s "$admin" ]; then
        umask 007
        python3 -c 'import secrets; print(secrets.token_hex(16))' > "$admin"
        umask 022
      fi
      ST_ADMIN_PASS=$(cat "$admin")
      create_from_seed stalwart-0155 \
        -e "STALWART_RECOVERY_ADMIN=admin:${ST_ADMIN_PASS}" \
        -p "${1}:$(cport stalwart-0155 imap)" \
        -p "${2}:$(cport stalwart-0155 imaps)" \
        -p "${3}:$(cport stalwart-0155 managesieve)" \
        -p "${4}:$(cport stalwart-0155 https)" \
        -p "${5}:$(cport stalwart-0155 submission)"
      unset ST_ADMIN_PASS
      {
        printf 'stalwart015-imap\t%s\t%s\tliveimap-stalwart-0155\n' "$1" "$(cport stalwart-0155 imap)"
        printf 'stalwart015-imaps\t%s\t%s\tliveimap-stalwart-0155\n' "$2" "$(cport stalwart-0155 imaps)"
        printf 'stalwart015-managesieve\t%s\t%s\tliveimap-stalwart-0155\n' "$3" "$(cport stalwart-0155 managesieve)"
        printf 'stalwart015-http\t%s\t%s\tliveimap-stalwart-0155\n' "$4" "$(cport stalwart-0155 https)"
        printf 'stalwart015-submission\t%s\t%s\tliveimap-stalwart-0155\n' "$5" "$(cport stalwart-0155 submission)"
      } > "$rows"
      replace_port_rows liveimap-stalwart-0155 "" "$rows"
      ;;
  esac
  rm -f "$rows"
  finish_recreate "$name" "$server"
  exit 0
fi

for name in \
  liveimap-cyrus-22 liveimap-cyrus-24 liveimap-cyrus-25 liveimap-cyrus-36 \
  liveimap-dovecot-10 liveimap-dovecot-12 liveimap-dovecot-22 \
  liveimap-courier-50 liveimap-uwimap \
  liveimap-stalwart-0155
do
  check_existing_name "$name"
done

echo "keeping older containers"
keep_running liveimap-cyrus-22
keep_running liveimap-cyrus-24
keep_running liveimap-cyrus-25
keep_running liveimap-cyrus-36
keep_running liveimap-dovecot-10
keep_running liveimap-dovecot-12
keep_running liveimap-dovecot-22
keep_running liveimap-courier-50
keep_running liveimap-stalwart-0155

CY22=$(published_port liveimap-cyrus-22 "$(cport cyrus-22 imap)")
CY24=$(published_port liveimap-cyrus-24 "$(cport cyrus-24 imap)")
CY25=$(published_port liveimap-cyrus-25 "$(cport cyrus-25 imap)")
CY36=$(published_port liveimap-cyrus-36 "$(cport cyrus-36 imap)")
CY36_HTTP=$(published_port liveimap-cyrus-36 "$(cport cyrus-36 http)")
DV10=$(published_port liveimap-dovecot-10 "$(cport dovecot-10 imap)")
DV12=$(published_port liveimap-dovecot-12 "$(cport dovecot-12 imap)")
DV22=$(published_port liveimap-dovecot-22 "$(cport dovecot-22 imap)")
CO=$(published_port liveimap-courier-50 "$(cport courier-50 imap)")
ST_IMAP=$(published_port liveimap-stalwart-0155 "$(cport stalwart-0155 imap)")
ST_IMAPS=$(published_port liveimap-stalwart-0155 "$(cport stalwart-0155 imaps)")
ST_SIEVE=$(published_port liveimap-stalwart-0155 "$(cport stalwart-0155 managesieve)")
ST_HTTPS=$(published_port liveimap-stalwart-0155 "$(cport stalwart-0155 https)")
ST_SUB=$(published_port liveimap-stalwart-0155 "$(cport stalwart-0155 submission)")

echo "stalwart accounts"
stalwart_accounts

UW_ABSENT=0
if running liveimap-uwimap; then
  UW=$(published_port liveimap-uwimap "$(cport uwimap imap)")
elif container_exists liveimap-uwimap; then
  docker start liveimap-uwimap
  UW=$(published_port liveimap-uwimap "$(cport uwimap imap)")
else
  echo "drawing uw port"
  UW=$(draw_port "$CY22" "$CY24" "$CY25" "$CY36" "$CY36_HTTP" \
    "$DV10" "$DV12" "$DV22" "$CO" \
    "$ST_IMAP" "$ST_IMAPS" "$ST_SIEVE" "$ST_HTTPS" "$ST_SUB")
  echo "pulling uw image"
  require_seed_image uwimap
  if ! docker pull debian/eol:squeeze; then
    echo "uw image pull failed" >&2
    UW_ABSENT=1
  elif probe_uw; then
    echo "starting uwimap"
    if ! start_uw; then
      echo "uw setup failed" >&2
      UW_ABSENT=1
    fi
  else
    echo "uw setup failed" >&2
    UW_ABSENT=1
  fi
fi

echo "login append jmap"
python3 - "$SEED" "$ACCOUNTS_OUT" "$FETCH_OUT" "$HOST" \
  "$CY22" "$CY24" "$CY25" "$CY36" "$DV10" "$DV12" "$DV22" "$CO" \
  "$ST_IMAP" "$ST_HTTPS" "$CY36_HTTP" \
  "$UW" "$UW_ABSENT" <<'PY'
import base64
import csv
import json
import os
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
    host,
    cy22,
    cy24,
    cy25,
    cy36,
    dv10,
    dv12,
    dv22,
    co,
    st_imap,
    st_https,
    cy_http,
    uw,
    uw_absent,
) = sys.argv[1:]
ports = {
    "cyrus22": int(cy22),
    "cyrus24": int(cy24),
    "cyrus25": int(cy25),
    "cyrus36": int(cy36),
    "dovecot10": int(dv10),
    "dovecot12": int(dv12),
    "dovecot22": int(dv22),
    "courier50": int(co),
    "stalwart015": int(st_imap),
}
if uw_absent != "1":
    ports["uwimap"] = int(uw)
order = [
    "cyrus22",
    "cyrus24",
    "cyrus25",
    "cyrus36",
    "dovecot10",
    "dovecot12",
    "dovecot22",
    "courier50",
    "stalwart015",
]
if uw_absent != "1":
    order.append("uwimap")
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

    def starttls(self, legacy=False):
        lines = self.command("STARTTLS")
        if not any(" OK" in line for line in lines):
            raise OSError("starttls failed")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        if legacy:
            ctx.minimum_version = ssl.TLSVersion.TLSv1
            ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
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

account_lines = []
fetch_lines = []
failed = False
prior_fetch = {}
if os.path.isfile(fetch_out):
    with open(fetch_out, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            key = line.split(" ", 1)[0]
            if key not in prior_fetch:
                prior_fetch[key] = line if line.endswith("\n") else line + "\n"

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
    state = {"legacy": False, "imap": imap}
    if "LOGINDISABLED" in greeting:
        try:
            imap.starttls(legacy=False)
        except ssl.SSLError:
            try:
                imap.s.close()
            except OSError:
                pass
            imap = Imap(port)
            greeting = imap.readline()
            imap.starttls(legacy=True)
            state["legacy"] = True
        state["imap"] = imap

    def reopen():
        fresh = Imap(port)
        greet = fresh.readline()
        if "OK" not in greet:
            raise OSError("greeting missing OK")
        if "LOGINDISABLED" in greet or state["legacy"]:
            fresh.starttls(legacy=state["legacy"])
        return fresh

    def login_ready(user, password):
        lines = state["imap"].login(user, password)
        if ok_lines(lines):
            return lines
        if not any("disabled" in line.lower() for line in lines):
            return lines
        try:
            state["imap"].starttls(legacy=state["legacy"])
        except ssl.SSLError:
            try:
                state["imap"].s.close()
            except OSError:
                pass
            if state["legacy"]:
                return lines
            state["legacy"] = True
            state["imap"] = reopen()
        return state["imap"].login(user, password)

    live_ok = False
    for user, password in users:
        lines = login_ready(user, password)
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
            try:
                state["imap"].command("LOGOUT")
            except OSError:
                pass
            state["imap"] = reopen()
    imap = state["imap"]
    if server in prior_fetch:
        fetch_lines.append(prior_fetch[server])
        try:
            imap.command("LOGOUT")
        except OSError:
            pass
        continue
    if not live_ok:
        fetch_lines.append("%s append-rejected\n" % server)
        failed = True
        continue
    if not ok_lines(login_ready("liveimap", password_of["liveimap"])):
        fetch_lines.append("%s append-rejected\n" % server)
        try:
            state["imap"].command("LOGOUT")
        except OSError:
            pass
        continue
    imap = state["imap"]
    appended = None
    rejected = False
    for _attempt in range(10):
        imap.n += 1
        tag = "a%d" % imap.n
        imap.s.sendall(("%s APPEND INBOX {%d}\r\n" % (tag, len(message))).encode("ascii"))
        cont = imap.readline()
        if cont.startswith("+"):
            imap.s.sendall(message + b"\r\n")
            appended = imap.read_tag(tag)
            if ok_lines(appended):
                rejected = False
                break
            rejected = True
            break
        rejected = True
        if not cont.startswith(tag + " "):
            try:
                imap.read_tag(tag)
            except OSError:
                pass
        time.sleep(1)
    if rejected or not appended or not ok_lines(appended):
        fetch_lines.append("%s append-rejected\n" % server)
        try:
            imap.command("LOGOUT")
        except OSError:
            pass
        continue
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
        fetch_lines.append("%s fetch-prefix-missing\n" % server)
    else:
        missing = False
        idx = prefix.rfind("{")
        if idx <= 0 or prefix[idx - 1] != " ":
            missing = True
        if missing:
            fetch_lines.append("%s missing-space %s %s\n" % (server, prefix, count))
        else:
            fetch_lines.append("%s %s %s\n" % (server, prefix, count))
    try:
        imap.command("LOGOUT")
    except OSError:
        pass

def http_get(url, user, password, tls=False):
    req = urllib.request.Request(url)
    token = base64.b64encode(("%s:%s" % (user, password)).encode("utf-8")).decode("ascii")
    req.add_header("Authorization", "Basic " + token)
    ctx = ssl._create_unverified_context() if tls else None
    try:
        with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode("utf-8", "replace")
    except urllib.error.URLError:
        return 0, ""

for server in (
    "cyrus22",
    "cyrus24",
    "cyrus25",
    "dovecot10",
    "dovecot12",
    "dovecot22",
    "courier50",
):
    account_lines.append("jmap %s no JMAP listener\n" % server)
if uw_absent != "1":
    account_lines.append("jmap uwimap no JMAP listener\n")

cy_status, cy_body = http_get(
    "http://%s:%s/.well-known/jmap" % (host, cy_http),
    "liveimap",
    password_of["liveimap"],
)
cy_urn = "urn:ietf:params:jmap:mail" in cy_body
account_lines.append(
    "jmap cyrus36 %s %s\n" % (cy_status, "urn:ietf:params:jmap:mail" if cy_urn else "missing-urn")
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
account_lines.append(
    "jmap stalwart015 %s %s\n"
    % (st_status, "urn:ietf:params:jmap:mail" if st_urn else "missing-urn")
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

append_port() {
  svc=$1
  host_port=$2
  container_port=$3
  cname=$4
  if [ -f "$PORTS_FILE" ] && awk -F '\t' -v svc="$svc" '$1 == svc { found = 1 } END { exit !found }' "$PORTS_FILE"; then
    return 0
  fi
  printf '%s\t%s\t%s\t%s\n' "$svc" "$host_port" "$container_port" "$cname" > "$GEN/port-rows.new"
  replace_port_rows "" "$svc" "$GEN/port-rows.new"
  rm -f "$GEN/port-rows.new"
}

append_port cyrus22-imap "$CY22" "$(cport cyrus-22 imap)" liveimap-cyrus-22
append_port cyrus24-imap "$CY24" "$(cport cyrus-24 imap)" liveimap-cyrus-24
append_port cyrus25-imap "$CY25" "$(cport cyrus-25 imap)" liveimap-cyrus-25
append_port cyrus36-imap "$CY36" "$(cport cyrus-36 imap)" liveimap-cyrus-36
append_port dovecot10-imap "$DV10" "$(cport dovecot-10 imap)" liveimap-dovecot-10
append_port dovecot12-imap "$DV12" "$(cport dovecot-12 imap)" liveimap-dovecot-12
append_port dovecot22-imap "$DV22" "$(cport dovecot-22 imap)" liveimap-dovecot-22
append_port courier50-imap "$CO" "$(cport courier-50 imap)" liveimap-courier-50
if [ "$UW_ABSENT" -eq 0 ]; then
  append_port uwimap-imap "$UW" "$(cport uwimap imap)" liveimap-uwimap
fi
append_port cyrus36-http "$CY36_HTTP" "$(cport cyrus-36 http)" liveimap-cyrus-36
append_port stalwart015-imap "$ST_IMAP" "$(cport stalwart-0155 imap)" liveimap-stalwart-0155
append_port stalwart015-imaps "$ST_IMAPS" "$(cport stalwart-0155 imaps)" liveimap-stalwart-0155
append_port stalwart015-managesieve "$ST_SIEVE" "$(cport stalwart-0155 managesieve)" liveimap-stalwart-0155
append_port stalwart015-http "$ST_HTTPS" "$(cport stalwart-0155 https)" liveimap-stalwart-0155
append_port stalwart015-submission "$ST_SUB" "$(cport stalwart-0155 submission)" liveimap-stalwart-0155

echo "containers"
if [ "$UW_ABSENT" -eq 0 ]; then
  docker inspect -f '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}}' \
    liveimap-cyrus-22 liveimap-cyrus-24 liveimap-cyrus-25 liveimap-cyrus-36 \
    liveimap-dovecot-10 liveimap-dovecot-12 liveimap-dovecot-22 \
    liveimap-courier-50 liveimap-uwimap liveimap-stalwart-0155
else
  docker inspect -f '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}}' \
    liveimap-cyrus-22 liveimap-cyrus-24 liveimap-cyrus-25 liveimap-cyrus-36 \
    liveimap-dovecot-10 liveimap-dovecot-12 liveimap-dovecot-22 \
    liveimap-courier-50 liveimap-stalwart-0155
fi
echo "ports"
echo "cyrus22-imap $CY22"
echo "cyrus24-imap $CY24"
echo "cyrus25-imap $CY25"
echo "cyrus36-imap $CY36"
echo "cyrus36-http $CY36_HTTP"
echo "dovecot10-imap $DV10"
echo "dovecot12-imap $DV12"
echo "dovecot22-imap $DV22"
echo "courier50-imap $CO"
if [ "$UW_ABSENT" -eq 0 ]; then
  echo "uwimap-imap $UW"
fi
echo "stalwart015-imap $ST_IMAP"
echo "stalwart015-imaps $ST_IMAPS"
echo "stalwart015-managesieve $ST_SIEVE"
echo "stalwart015-http $ST_HTTPS"
echo "stalwart015-submission $ST_SUB"
echo "accounts"
cat "$ACCOUNTS_OUT"
echo "fetch"
cat "$FETCH_OUT"

append_pin() {
  row=$1
  if [ -f "$PINS_OUT" ] && grep -Fxq "$row" "$PINS_OUT"; then
    return 0
  fi
  printf '%s\n' "$row" >> "$PINS_OUT"
}
append_pin 'image	stalwartlabs/stalwart:v0.15.5'
stop_owned
if [ "$UW_ABSENT" -eq 1 ]; then
  echo "uw setup failed" >&2
  exit 1
fi
