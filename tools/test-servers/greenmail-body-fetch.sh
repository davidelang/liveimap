#!/bin/sh
# Append one INBOX note on each existing GreenMail container, record
# BODY.PEEK[1]<0.32>, then stop those four containers.
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

root = sys.argv[1]
ports_path = os.path.join(root, "sandbox/test-servers/generated/local-ports.tsv")
accounts_path = os.path.join(root, "sandbox/test-servers/seed/accounts.tsv")
out_path = os.path.join(root, "sandbox/test-servers/generated/greenmail-body-fetch.txt")
host = "127.0.0.1"

containers = (
    "liveimap-greenmail",
    "liveimap-greenmail-214",
    "liveimap-greenmail-dev",
    "liveimap-greenmail-16",
)
port_keys = {
    "liveimap-greenmail": "greenmail-imap",
    "liveimap-greenmail-214": "greenmail214-imap",
    "liveimap-greenmail-dev": "greenmail-dev-imap",
    "liveimap-greenmail-16": "greenmail16-imap",
}
message = (
    "From: liveimap@test\r\n"
    "To: liveimap@test\r\n"
    "Subject: Lab note one\r\n"
    "Date: Fri, 9 Oct 2026 12:30:00 -0700\r\n"
    "\r\n"
    "Lab server note.\r\n"
).encode("ascii")

password = ""
with open(accounts_path, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        if (row.get("user") or "") == "liveimap":
            password = row.get("password") or ""
            break

def fail(text):
    if password:
        text = text.replace(password, "[redacted]")
    sys.stderr.write(text.rstrip() + "\n")
    sys.exit(1)

if not password:
    fail("missing account liveimap")

def digest(path):
    hasher = hashlib.sha256()
    with open(path, "rb") as handle:
        hasher.update(handle.read())
    return hasher.hexdigest()

ports_digest = digest(ports_path)
table = {}
with open(ports_path, newline="") as handle:
    for row in csv.DictReader(handle, delimiter="\t"):
        service = row.get("service") or ""
        if service == "lan_ipv4":
            continue
        table[service] = row.get("host_port") or ""

ports = {}
for name, key in port_keys.items():
    imap_port = table.get(key, "")
    if not imap_port.isdigit():
        fail("missing port for %s" % name)
    ports[name] = int(imap_port)

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
        self.s.settimeout(60)
        self.buf = b""
        self.n = 0
        self.trace = []

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
        text = line.decode("utf-8", "replace").rstrip("\r")
        shown = text
        if password:
            shown = shown.replace(password, "[redacted]")
        self.trace.append(shown)
        if len(self.trace) > 12:
            del self.trace[0]
        return text

    def consume_literal(self, line):
        match = re.search(r"\{(\d+)\}\s*$", line)
        if not match:
            return
        need = int(match.group(1))
        if need > 4096:
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
        if not text.startswith("LOGIN"):
            self.trace.append(">> %s" % text)
        self.s.sendall(("%s %s\r\n" % (tag, text)).encode("ascii"))
        return tag, self.read_tag(tag)

    def starttls(self):
        tag, lines = self.command("STARTTLS")
        if tagged_status(lines, tag) != "OK":
            raise OSError("starttls failed")
        import ssl
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.s = ctx.wrap_socket(self.s, server_hostname=host)
        self.buf = b""

    def login(self, user, secret):
        safe = (
            user.isascii()
            and secret.isascii()
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
        self.trace.append(">> APPEND INBOX {%d}" % len(payload))
        self.s.sendall(
            ("%s APPEND INBOX {%d}\r\n" % (tag, len(payload))).encode("ascii")
        )
        lines = []
        while True:
            line = self.readline()
            if line.startswith("+"):
                # GreenMail reads a CRLF after the literal before APPEND ends.
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

def login_session(name, port):
    deadline = time.time() + 90
    while True:
        imap = open_ready(port, deadline)
        if imap is None:
            fail("greeting missing OK for %s" % name)
        try:
            tag, reply = imap.login("liveimap", password)
            status = tagged_status(reply, tag)
        except OSError:
            imap.close()
            if time.time() + 0.4 >= deadline:
                fail("login closed for %s" % name)
            time.sleep(0.4)
            continue
        if status == "OK":
            return imap
        imap.close()
        if time.time() + 0.4 >= deadline:
            detail = reply[-1] if reply else status
            fail("login failed %s %s" % (name, detail))
        time.sleep(0.4)

lines_out = []

for name in containers:
    docker_start(name)
    imap = login_session(name, ports[name])
    try:
        tag, reply = imap.append_once(message)
        if tagged_status(reply, tag) != "OK":
            detail = reply[-1] if reply else "append failed"
            fail("append failed %s %s" % (name, detail))
        tag, reply = imap.command("EXAMINE INBOX")
        if tagged_status(reply, tag) != "OK":
            fail("examine failed %s" % name)
        count = exists_count(reply)
        if count is None or count < 1:
            fail("empty mailbox %s" % name)
        tag, reply = imap.command("FETCH 1 BODY.PEEK[1]<0.32>")
        if tagged_status(reply, tag) != "OK":
            fail("fetch failed %s" % name)
        parsed = section_token(reply)
        if parsed is None:
            fail("fetch prefix missing %s" % name)
        token, nbytes = parsed
        lines_out.append("fetch %s %s %s" % (name, token, nbytes))
        try:
            imap.command("LOGOUT")
        except OSError:
            pass
    except OSError as err:
        shown = " | ".join(imap.trace[-8:])
        fail("imap closed for %s: %s :: %s" % (name, err, shown))
    finally:
        imap.close()

if digest(ports_path) != ports_digest:
    fail("ports file changed")

if len(lines_out) != len(containers):
    fail("fetch line count")

seen = []
for line in lines_out:
    if password and password in line:
        fail("password in output")
    parts = line.split(" ")
    if len(parts) < 4 or parts[0] != "fetch":
        fail("bad fetch line")
    if parts[1] not in containers or parts[1] in seen:
        fail("bad container")
    seen.append(parts[1])
    if not parts[-1].isdigit():
        fail("bad count")
    token = " ".join(parts[2:-1])
    if "BODY" not in token or not token.endswith("}"):
        fail("bad token")
    brace = token.rfind("{")
    if brace < 0 or not token[brace + 1:-1].isdigit():
        fail("bad token")

if seen != list(containers):
    fail("fetch line count")

text = "\n".join(lines_out) + "\n"
tmp_path = out_path + ".tmp"
with open(tmp_path, "w", encoding="utf-8") as handle:
    handle.write(text)
os.replace(tmp_path, out_path)
sys.stdout.write(text)
PY
