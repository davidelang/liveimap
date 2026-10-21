#!/bin/sh
# One Zimbra 8.8.3 container on 127.0.0.1. Record setup, logins, one partial fetch, then stop.
set -eu
set +x

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

stop_zimbra() {
  docker stop liveimap-zimbra-883 || exit 1
}
trap stop_zimbra EXIT INT TERM

# Upstream single-node setup values. They stay in this script only.
export TZDATA_AREA=US
export TZDATA_ZONE=Pacific
export ZIMBRA_DEFAULT_DOMAIN=zimbra.zcs-foss.test
export ZIMBRA_HOST_NAME=zimbra.zcs-foss.test
export TIME_ZONE_ID=PDT
export ADMIN_PW=test123
export LDAP_ADMIN_PW=zimbra
export LDAP_AMAVIS_PW=zimbra
export LDAP_POSTFIX_PW=zimbra
export LDAP_REPLICATION_PW=zimbra
export LDAP_ROOT_PW=zimbra
export LDAP_BES_PW=zimbra
export LDAP_NGINX_PW=zimbra

python3 - "$ROOT" "$@" <<'PY'
import csv
import os
import re
import socket
import ssl
import subprocess
import sys
import time

root = sys.argv[1]
sys.path.insert(0, os.path.join(root, "tools", "test-servers"))
import ports as listen_ports
name = "liveimap-zimbra-883"
image = "liveimap-seed-zimbra-883"
host = "127.0.0.1"
domain = os.environ.get("ZIMBRA_DEFAULT_DOMAIN") or ""
gen = os.path.join(root, "tools/test-servers/generated")
ports_path = os.path.join(gen, "local-ports.tsv")
accounts_path = os.path.join(root, "tools/test-servers/seed/accounts.tsv")
out_path = os.path.join(gen, "zimbra-883.txt")
setup_keys = (
    "TZDATA_AREA",
    "TZDATA_ZONE",
    "ZIMBRA_DEFAULT_DOMAIN",
    "ZIMBRA_HOST_NAME",
    "TIME_ZONE_ID",
    "ADMIN_PW",
    "LDAP_ADMIN_PW",
    "LDAP_AMAVIS_PW",
    "LDAP_POSTFIX_PW",
    "LDAP_REPLICATION_PW",
    "LDAP_ROOT_PW",
    "LDAP_BES_PW",
    "LDAP_NGINX_PW",
)
users = ("liveimap", "liveimap2", "liveimap-empty", "liveimap-utf8")

admin_pw = os.environ.get("ADMIN_PW") or ""
ldap_pw = os.environ.get("LDAP_ROOT_PW") or ""
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

passwords = {}
with open(accounts_path, newline="", encoding="utf-8") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        user = row.get("user") or ""
        if user in users:
            passwords[user] = row.get("password") or ""

redact_secrets.append(admin_pw)
for user in users:
    secret = passwords.get(user) or ""
    if not secret:
        fail("missing account %s" % user)
    redact_secrets.append(secret)

for key in setup_keys:
    if not os.environ.get(key):
        fail("missing setup value")
if domain != "zimbra.zcs-foss.test":
    fail("unexpected domain")

def container_names():
    proc = run(["docker", "ps", "-a", "--format", "{{.Names}}"])
    if proc.returncode != 0:
        fail("docker ps failed")
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]

def container_running():
    proc = run(["docker", "inspect", "-f", "{{.State.Running}}", name])
    return proc.returncode == 0 and proc.stdout.strip() == "true"

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

def read_port_rows():
    rows = []
    with open(ports_path, encoding="utf-8") as handle:
        rows = handle.read().splitlines()
    return rows

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

def used_host_ports(rows):
    used = set()
    for index, line in enumerate(rows):
        parts = line.split("\t")
        if index == 0 and parts and parts[0] == "service":
            continue
        if len(parts) >= 2 and parts[1].isdigit():
            used.add(int(parts[1]))
    used.update(listening_ports())
    return used

def bindable(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind((host, port))
        return True
    except OSError:
        return False
    finally:
        sock.close()

def draw_ports(used):
    chosen = []
    for port in range(20000, 40000):
        if port in used or port in chosen:
            continue
        if not bindable(port):
            continue
        chosen.append(port)
        if len(chosen) == 3:
            return chosen
    fail("no free ports")

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

def docker_logs(tail=None):
    cmd = ["docker", "logs"]
    if tail is not None:
        cmd.extend(["--tail", str(tail)])
    cmd.append(name)
    proc = run(cmd, timeout=120)
    text = (proc.stdout or "") + (proc.stderr or "")
    return text

def write_out(lines):
    text = "\n".join(lines).rstrip() + "\n"
    cleaned = redact(text)
    for secret in redact_secrets:
        if secret and secret in cleaned:
            fail("password in output")
    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        handle.write(cleaned)
    os.replace(tmp, out_path)
    sys.stdout.write(cleaned)

def stop_now():
    run(["docker", "stop", name], timeout=60)

def record_timeout(marker, logs):
    lines = [marker]
    tail = redact(logs).splitlines()[-100:]
    lines.extend(tail)
    write_out(lines)
    stop_now()
    sys.exit(0)

script = os.path.join(root, "tools/test-servers/zimbra-883.sh")
mount_gen = os.path.join(root, "tools/test-servers/generated")
mount_seed = os.path.join(root, "tools/test-servers/seed")
want_ports = {"143", "443", "7071"}

def lab_owner(server):
    owners = {
        "stalwart-01625": "start-latest.sh",
        "dovecot-245": "start-latest.sh",
        "cyrus-3124": "start-latest.sh",
        "courier-606": "start-latest.sh",
        "stalwart-dev": "start-dev.sh",
        "dovecot-dev": "start-dev.sh",
        "cyrus-3137": "start-dev.sh",
        "cyrus-22": "start-older.sh",
        "cyrus-24": "start-older.sh",
        "cyrus-25": "start-older.sh",
        "cyrus-36": "start-older.sh",
        "dovecot-10": "start-older.sh",
        "dovecot-12": "start-older.sh",
        "dovecot-22": "start-older.sh",
        "courier-50": "start-older.sh",
        "uwimap": "start-older.sh",
        "stalwart-0155": "start-older.sh",
        "stalwart": "start-local.sh",
        "zimbra-883": "zimbra-883.sh",
    }
    return owners.get(server)

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

args = sys.argv[2:]
if args and args[0] in ("--recreate", "--reset"):
    if len(args) != 2 or not args[1]:
        fail("usage: %s %s <server>" % (script, args[0]))
    owner = lab_owner(args[1])
    if owner is None:
        fail("%s is not a lab server" % args[1])
    owner_path = os.path.join(root, "tools/test-servers", owner)
    if owner_path != script:
        fail("%s belongs to %s" % (args[1], owner_path))
    if args[0] == "--reset":
        reset_zimbra()
    else:
        recreate_zimbra()
    sys.exit(0)

rows = read_port_rows()
ports = mapped_ports(rows)
exists = name in container_names()
if exists and container_bad(want_ports):
    fail(script + " --recreate zimbra-883")
seen_setup = docker_logs().count("SETUP COMPLETE") if exists else 0
if exists:
    if len(ports) < 3:
        fail("missing saved ports")
    proc = run(["docker", "start", name], timeout=180)
    if proc.returncode != 0:
        fail("docker start failed")
else:
    if len(ports) < 3:
        imap_port, https_port, admin_port = draw_ports(used_host_ports(rows))
        replace_rows(imap_port, https_port, admin_port)
        ports = {
            "zimbra883-imap": imap_port,
            "zimbra883-https": https_port,
            "zimbra883-admin": admin_port,
        }
    else:
        imap_port = ports["zimbra883-imap"]
        https_port = ports["zimbra883-https"]
        admin_port = ports["zimbra883-admin"]
    launch_zimbra(imap_port, https_port, admin_port)

deadline = time.time() + 30 * 60
stable = None
last_logs = ""
ready = False
while time.time() < deadline:
    logs = docker_logs()
    if logs.count("SETUP COMPLETE") > seen_setup:
        ready = True
        break
    if not container_running():
        if logs == last_logs:
            if stable is None:
                stable = time.time()
            elif time.time() - stable >= 20:
                break
        else:
            stable = None
            last_logs = logs
    sys.stderr.write("setup wait\n")
    time.sleep(10)

if not ready:
    record_timeout("setup timeout", docker_logs(100))

class Imap:
    def __init__(self, port):
        self.s = socket.create_connection((host, port), 10)
        self.s.settimeout(60)
        self.buf = b""
        self.n = 0

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass

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
            return b""
        need = int(match.group(1))
        if need > 65536:
            raise OSError("literal too large")
        while len(self.buf) < need:
            chunk = self.s.recv(8192)
            if not chunk:
                break
            self.buf += chunk
        if len(self.buf) < need:
            raise OSError("literal short")
        data = self.buf[:need]
        self.buf = self.buf[need:]
        return data

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
        return tag, self.read_tag(tag)

    def starttls(self):
        tag, lines = self.command("STARTTLS")
        if tagged_status(lines, tag) != "OK":
            raise OSError("starttls failed")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.s = ctx.wrap_socket(self.s, server_hostname=host)
        self.buf = b""

    def login(self, user, secret):
        safe = (
            secret.isascii()
            and " " not in secret
            and '"' not in secret
            and "\\" not in secret
        )
        if safe:
            return self.command('LOGIN %s "%s"' % (user, secret))
        literal = secret.encode("utf-8")
        self.n += 1
        tag = "a%d" % self.n
        self.s.sendall(
            ("%s LOGIN %s {%d}\r\n" % (tag, user, len(literal))).encode("ascii")
        )
        cont = self.readline()
        if not cont.startswith("+"):
            if cont.startswith(tag + " "):
                return tag, [cont]
            rest = self.read_tag(tag)
            return tag, [cont] + rest
        self.s.sendall(literal + b"\r\n")
        return tag, self.read_tag(tag)

def tagged_status(lines, tag):
    for line in reversed(lines):
        if line.startswith(tag + " "):
            parts = line.split(" ", 2)
            if len(parts) >= 2:
                return parts[1]
            return ""
    return ""

def tagged_text(lines, tag):
    for line in reversed(lines):
        if line.startswith(tag + " "):
            return line[len(tag) + 1:]
    return "NO"

def open_greeting(port, deadline):
    while time.time() < deadline:
        try:
            imap = Imap(port)
            greeting = imap.readline()
        except OSError:
            time.sleep(2)
            continue
        if "OK" in greeting:
            return imap, greeting
        imap.close()
        time.sleep(2)
    return None, ""

def section_token(lines):
    for line in lines:
        match = re.search(r"(BODY(?:\.PEEK)?\[[^\]]*\](?:<\d+(?:\.\d+)?>)?\s*\{(\d+)\})", line)
        if match:
            return match.group(1), match.group(2)
        match = re.search(r'(BODY(?:\.PEEK)?\[[^\]]*\](?:<\d+(?:\.\d+)?>)?) "((?:\\.|[^"])*)"', line)
        if match:
            return match.group(1), str(len(match.group(2).encode("utf-8")))
    return None

def exists_count(lines):
    found = None
    for line in lines:
        match = re.match(r"\* (\d+) EXISTS$", line)
        if match:
            found = int(match.group(1))
    return found

imap, greeting = open_greeting(ports["zimbra883-imap"], time.time() + 120)
if imap is None:
    record_timeout("setup complete\ngreeting none", docker_logs(100))
imap.close()

def fresh_imap():
    client, banner = open_greeting(ports["zimbra883-imap"], time.time() + 30)
    if client is None:
        return None
    try:
        _tag, lines = client.command("CAPABILITY")
    except OSError:
        client.close()
        return None
    blob = banner + "\n" + "\n".join(lines)
    if "LOGINDISABLED" in blob:
        try:
            client.starttls()
        except OSError:
            client.close()
            return None
    return client

def zmprov_script(body):
    env = os.environ.copy()
    try:
        return run(
            [
                "docker", "exec", "-i", "-u", "zimbra",
                name,
                "/bin/bash", "-s",
            ],
            timeout=180,
            env=env,
            input_text=body,
        )
    except subprocess.TimeoutExpired:
        return None

lines_out = ["setup complete", "greeting ok"]

kept = None
live_ok = False
for user in users:
    addr = "%s@%s" % (user, domain)
    imap = fresh_imap()
    if imap is None:
        lines_out.append("login %s NO closed" % addr)
        continue
    try:
        tag, reply = imap.login(addr, passwords[user])
        status = tagged_status(reply, tag)
        if status == "OK":
            lines_out.append("login %s ok" % addr)
            if user == "liveimap":
                live_ok = True
                kept = imap
                continue
        else:
            lines_out.append("login %s %s" % (addr, redact(tagged_text(reply, tag))))
        try:
            imap.command("LOGOUT")
        except OSError:
            pass
        imap.close()
    except OSError:
        lines_out.append("login %s NO closed" % addr)
        imap.close()

if live_ok and kept is not None:
    try:
        tag, reply = kept.command("EXAMINE INBOX")
        count = exists_count(reply)
        if tagged_status(reply, tag) != "OK" or not count:
            lines_out.append("fetch skipped")
        else:
            tag, reply = kept.command("FETCH %d BODY.PEEK[1]<0.32>" % count)
            parsed = section_token(reply)
            if tagged_status(reply, tag) != "OK" or parsed is None:
                lines_out.append("fetch skipped")
            else:
                token, nbytes = parsed
                lines_out.append("fetch %s %s" % (token, nbytes))
        try:
            kept.command("LOGOUT")
        except OSError:
            pass
    except OSError:
        lines_out.append("fetch skipped")
    finally:
        kept.close()
else:
    lines_out.append("fetch skipped")
    if imap is not None:
        imap.close()

jmap_port = ports["zimbra883-https"]
jmap_url = "https://%s:%d/.well-known/jmap" % (host, jmap_port)
jmap_line = "jmap no listener"
curl = run(
    ["curl", "-sk", "--max-time", "20", "-o", "/dev/null", "-w", "%{http_code}", jmap_url],
    timeout=30,
)
code = (curl.stdout or "").strip()
if curl.returncode == 0 and code.isdigit() and code != "000":
    jmap_line = "jmap %s" % code
else:
    try:
        ctx = ssl._create_unverified_context()
        raw = socket.create_connection((host, jmap_port), 20)
        tls = ctx.wrap_socket(raw, server_hostname=host)
        tls.settimeout(20)
        tls.sendall(
            ("GET /.well-known/jmap HTTP/1.0\r\nHost: %s\r\n\r\n" % host).encode("ascii")
        )
        data = b""
        while b"\n" not in data and len(data) < 512:
            chunk = tls.recv(256)
            if not chunk:
                break
            data += chunk
        tls.close()
        first = data.decode("iso-8859-1", "replace").splitlines()[0] if data else ""
        parts = first.split()
        if len(parts) >= 2 and parts[0].startswith("HTTP/") and parts[1].isdigit():
            jmap_line = "jmap %s" % parts[1]
    except (OSError, IndexError):
        pass
lines_out.append(jmap_line)

write_out(lines_out)
PY
