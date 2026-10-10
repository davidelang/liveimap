#!/bin/sh
# Start the four existing GreenMail containers, record login and a partial
# fetch, then stop them. No APPEND, no user create, no other container.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

stop_four() {
  for name in liveimap-greenmail liveimap-greenmail-214 liveimap-greenmail-dev liveimap-greenmail-16; do
    docker stop "$name" >/dev/null 2>&1 || true
  done
}
trap stop_four EXIT INT TERM

python3 - "$ROOT" <<'PY'
import csv
import hashlib
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

root = sys.argv[1]
ports_path = os.path.join(root, "sandbox/test-servers/generated/local-ports.tsv")
accounts_path = os.path.join(root, "sandbox/test-servers/seed/accounts.tsv")
out_path = os.path.join(root, "sandbox/test-servers/generated/greenmail-retest.txt")
host = "127.0.0.1"

containers = (
    "liveimap-greenmail",
    "liveimap-greenmail-214",
    "liveimap-greenmail-dev",
    "liveimap-greenmail-16",
)
port_keys = {
    "liveimap-greenmail": ("greenmail-imap", "greenmail-api"),
    "liveimap-greenmail-214": ("greenmail214-imap", "greenmail214-api"),
    "liveimap-greenmail-dev": ("greenmail-dev-imap", "greenmail-dev-api"),
    "liveimap-greenmail-16": ("greenmail16-imap", "greenmail16-http"),
}
users = ("liveimap", "liveimap2", "liveimap-empty", "liveimap-utf8")
absent_ok = "liveimap-greenmail"

passwords = {}
redact = []
with open(accounts_path, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        user = row.get("user") or ""
        password = row.get("password") or ""
        if password:
            redact.append(password)
        if user in users:
            passwords[user] = password

def fail(text):
    for secret in redact:
        text = text.replace(secret, "[redacted]")
    sys.stderr.write(text.rstrip() + "\n")
    sys.exit(1)

for user in users:
    if not passwords.get(user):
        fail("missing account %s" % user)

table = {}
with open(ports_path, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        service = row.get("service") or ""
        if service == "lan_ipv4":
            continue
        table[service] = row.get("host_port") or ""

ports = {}
for name, (imap_key, api_key) in port_keys.items():
    imap_port = table.get(imap_key, "")
    api_port = table.get(api_key, "")
    if not imap_port.isdigit() or not api_port.isdigit():
        fail("missing port for %s" % name)
    ports[name] = (int(imap_port), int(api_port))

def digest(path):
    hasher = hashlib.sha256()
    with open(path, "rb") as handle:
        hasher.update(handle.read())
    return hasher.hexdigest()

ports_digest = digest(ports_path)
if os.path.exists(out_path):
    os.remove(out_path)

def docker_start(name):
    if name not in containers:
        fail("refusing start")
    proc = subprocess.run(
        ["docker", "start", name],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if proc.returncode != 0:
        err = proc.stderr.strip() or "docker start failed"
        fail("start failed %s: %s" % (name, err))

class Imap:
    def __init__(self, port):
        self.s = socket.create_connection((host, port), 10)
        self.s.settimeout(30)
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
            return
        need = int(match.group(1))
        if need > 2000000:
            raise OSError("literal too large")
        while len(self.buf) < need:
            chunk = self.s.recv(8192)
            if not chunk:
                break
            self.buf += chunk
        if len(self.buf) < need:
            raise OSError("literal short")
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
        return tag, self.read_tag(tag)

    def starttls(self):
        tag, lines = self.command("STARTTLS")
        if tagged_status(lines, tag) != "OK":
            raise OSError("starttls failed")
        import ssl
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.s = ctx.wrap_socket(self.s, server_hostname="localhost")
        self.buf = b""

    def login(self, user, password):
        safe = (
            user.isascii()
            and password.isascii()
            and " " not in password
            and '"' not in password
            and "\\" not in password
        )
        if safe:
            return self.command('LOGIN %s "%s"' % (user, password))
        literal = password.encode("utf-8")
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

def open_ready(port, deadline):
    while time.time() < deadline:
        try:
            imap = Imap(port)
            greeting = imap.readline()
        except OSError:
            time.sleep(0.4)
            continue
        if "OK" in greeting:
            try:
                if "LOGINDISABLED" in greeting:
                    imap.starttls()
            except OSError:
                imap.close()
                time.sleep(0.4)
                continue
            return imap
        imap.close()
        time.sleep(0.4)
    return None

def connect(port):
    deadline = time.time() + 30
    imap = open_ready(port, deadline)
    if imap is None:
        fail("greeting missing OK")
    return imap

def exists_count(lines):
    found = None
    for line in lines:
        match = re.match(r"\* (\d+) EXISTS$", line)
        if match:
            found = int(match.group(1))
    return found

def section_token(lines):
    for line in lines:
        start = line.find("BODY")
        if start < 0:
            continue
        brace = line.find("{", start)
        end = line.find("}", brace if brace >= 0 else len(line))
        if brace < 0 or end <= brace:
            continue
        count = line[brace + 1:end]
        if not count.isdigit():
            continue
        return line[start:end + 1], count
    return None

def jmap_status(api_port):
    url = "http://%s:%d/.well-known/jmap" % (host, api_port)
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            resp.read(256)
            return str(resp.status)
    except urllib.error.HTTPError as err:
        err.read(256)
        return str(err.code)
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError):
        return "no listener"

lines_out = []

def record(line):
    lines_out.append(line)
    sys.stdout.write(line + "\n")
    sys.stdout.flush()

def login_user(name, port, user):
    deadline = time.time() + 90 if user == "liveimap" else time.time() + 30
    while True:
        imap = open_ready(port, deadline)
        if imap is None:
            fail("greeting missing OK for %s" % name)
        try:
            tag, reply = imap.login(user, passwords[user])
            status = tagged_status(reply, tag)
        except OSError:
            imap.close()
            if time.time() + 0.4 >= deadline:
                fail("login closed for %s %s" % (name, user))
            time.sleep(0.4)
            continue
        imap.close()
        if status == "OK":
            return "ok"
        if status in ("NO", "BAD") and name == absent_ok and user != "liveimap":
            return "absent"
        if user == "liveimap" and time.time() + 0.4 < deadline:
            time.sleep(0.4)
            continue
        detail = reply[-1] if reply else status
        fail("login failed %s %s %s" % (name, user, detail))

for name in containers:
    docker_start(name)
    imap_port, api_port = ports[name]
    for user in users:
        result = login_user(name, imap_port, user)
        record("login %s %s %s" % (name, user, result))
    imap = connect(imap_port)
    try:
        tag, reply = imap.login("liveimap", passwords["liveimap"])
        if tagged_status(reply, tag) != "OK":
            fail("login failed %s liveimap" % name)
        tag, reply = imap.command("EXAMINE INBOX")
        if tagged_status(reply, tag) != "OK":
            fail("examine failed %s" % name)
        count = exists_count(reply)
        if count is None:
            fail("examine failed %s" % name)
        if count == 0:
            record("fetch %s empty" % name)
        else:
            tag, reply = imap.command("FETCH 1 BODY.PEEK[1]<0.32>")
            if tagged_status(reply, tag) != "OK":
                fail("fetch failed %s" % name)
            parsed = section_token(reply)
            if parsed is None:
                fail("fetch prefix missing %s" % name)
            token, nbytes = parsed
            record("fetch %s %s %s" % (name, token, nbytes))
        imap.command("LOGOUT")
    except OSError:
        fail("imap closed for %s" % name)
    finally:
        imap.close()
    record("jmap %s %s" % (name, jmap_status(api_port)))

if digest(ports_path) != ports_digest:
    fail("ports file changed")

for secret in redact:
    for line in lines_out:
        if secret in line:
            fail("password in output")

text = "\n".join(lines_out) + "\n"
with open(out_path, "w", encoding="utf-8") as handle:
    handle.write(text)
sys.stdout.write(text)
PY
