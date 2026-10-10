#!/bin/sh
# One Zimbra 8.8.3 container on 127.0.0.1. Record setup, logins, one partial fetch, then stop.
set -eu
set +x

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

stop_zimbra() {
  docker stop liveimap-zimbra-883 >/dev/null 2>&1 || true
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

python3 - "$ROOT" <<'PY'
import csv
import os
import re
import socket
import ssl
import subprocess
import sys
import time

root = sys.argv[1]
name = "liveimap-zimbra-883"
volume = "liveimap-zimbra-883-opt"
image = "zimbra/zcs-foss:8.8.3"
host = "127.0.0.1"
domain = os.environ.get("ZIMBRA_DEFAULT_DOMAIN") or ""
gen = os.path.join(root, "sandbox/test-servers/generated")
ports_path = os.path.join(gen, "local-ports.tsv")
accounts_path = os.path.join(root, "sandbox/test-servers/seed/accounts.tsv")
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
message = (
    "From: liveimap@test\r\n"
    "To: liveimap@test\r\n"
    "Subject: Lab note one\r\n"
    "Date: Fri, 9 Oct 2026 12:30:00 -0700\r\n"
    "\r\n"
    "Lab server note.\r\n"
).encode("ascii")

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

def append_rows(imap_port, https_port, admin_port):
    with open(ports_path, "rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        if size:
            handle.seek(-1, os.SEEK_END)
            last = handle.read(1)
        else:
            last = b"\n"
    prefix = b"" if last == b"\n" else b"\n"
    payload = (
        "zimbra883-imap\t%d\t143\t%s\n"
        "zimbra883-https\t%d\t443\t%s\n"
        "zimbra883-admin\t%d\t7071\t%s\n"
    ) % (imap_port, name, https_port, name, admin_port, name)
    with open(ports_path, "ab") as handle:
        handle.write(prefix + payload.encode("ascii"))

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

rows = read_port_rows()
ports = mapped_ports(rows)
exists = name in container_names()
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
        append_rows(imap_port, https_port, admin_port)
        ports = {
            "zimbra883-imap": imap_port,
            "zimbra883-https": https_port,
            "zimbra883-admin": admin_port,
        }
    else:
        imap_port = ports["zimbra883-imap"]
        https_port = ports["zimbra883-https"]
        admin_port = ports["zimbra883-admin"]
    cmd = [
        "docker", "run", "-d",
        "--name", name,
        "--hostname", domain,
        "--restart", "no",
        "--entrypoint", "/zimbra/init",
        "-p", "%s:%d:143" % (host, ports["zimbra883-imap"]),
        "-p", "%s:%d:443" % (host, ports["zimbra883-https"]),
        "-p", "%s:%d:7071" % (host, ports["zimbra883-admin"]),
        "-v", "%s:/opt/zimbra" % volume,
    ]
    for key in setup_keys:
        cmd.extend(["-e", key])
    cmd.append(image)
    proc = run(cmd, timeout=600, env=os.environ.copy())
    if proc.returncode != 0:
        err = redact((proc.stderr or "") + (proc.stdout or ""))
        fail("docker run failed: %s" % err[-500:])

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

    def append_once(self, payload):
        self.n += 1
        tag = "a%d" % self.n
        self.s.sendall(
            ("%s APPEND INBOX {%d}\r\n" % (tag, len(payload))).encode("ascii")
        )
        lines = []
        while True:
            line = self.readline()
            if line.startswith("+"):
                self.s.sendall(payload + b"\r\n")
                lines.extend(self.read_tag(tag))
                return tag, lines
            lines.append(line)
            self.consume_literal(line)
            if line.startswith(tag + " "):
                return tag, lines

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

def ensure_domain():
    body = (
        "set +x\n"
        "export HOME=/opt/zimbra\n"
        "export PATH=/opt/zimbra/bin:$PATH\n"
        "if zmprov gad | grep -qx '%s'; then exit 0; fi\n"
        "zmprov cd '%s'\n"
    ) % (domain, domain)
    proc = zmprov_script(body)
    if proc is None or proc.returncode != 0:
        text = ""
        if proc is not None:
            text = (proc.stdout or "") + (proc.stderr or "")
        if "already exists" not in text.lower():
            sys.stderr.write(redact("domain create failed\n"))

def create_account(user, secret):
    addr = "%s@%s" % (user, domain)
    env = os.environ.copy()
    env["LIVEIMAP_ADDR"] = addr
    env["LIVEIMAP_ACCOUNT_PW"] = secret
    inner = (
        "set +x\n"
        "export HOME=/opt/zimbra\n"
        "export PATH=/opt/zimbra/bin:$PATH\n"
        "export LANG=C.UTF-8\n"
        "export LC_ALL=C.UTF-8\n"
        'zmprov ca "$LIVEIMAP_ADDR" "$LIVEIMAP_ACCOUNT_PW"\n'
        "status=$?\n"
        "if [ \"$status\" -ne 0 ]; then\n"
        '  zmprov sp "$LIVEIMAP_ADDR" "$LIVEIMAP_ACCOUNT_PW"\n'
        "fi\n"
    )
    try:
        proc = run(
            [
                "docker", "exec", "-i", "-u", "zimbra",
                "-e", "LIVEIMAP_ADDR",
                "-e", "LIVEIMAP_ACCOUNT_PW",
                name,
                "/bin/bash", "-s",
            ],
            timeout=180,
            env=env,
            input_text=inner,
        )
    except subprocess.TimeoutExpired:
        return False
    text = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode == 0:
        return True
    if "ACCOUNT_EXISTS" in text or "already exists" in text.lower():
        return True
    sys.stderr.write(redact("account create failed for %s\n" % addr))
    return False

lines_out = ["setup complete", "greeting ok"]
ensure_domain()
for user in users:
    create_account(user, passwords[user])

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
        tag, reply = kept.append_once(message)
        if tagged_status(reply, tag) != "OK":
            lines_out.append("fetch skipped")
        else:
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
