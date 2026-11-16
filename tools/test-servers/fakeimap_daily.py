#!/usr/bin/env python3
"""Host side of FakeimapDailyTest. Starts pinned fakeimap and a small HTTP bridge.

The emulator cannot start the binary or read its command log. The bridge serves
GET /log (the log_path file) and POST /control (one JSON control object). The
instrumented test uses the app's own sessions for IMAP, SMTP, and JMAP.

Usage: fakeimap_daily.py [--skip-install] generated|faults|bearer|session|tls|bearer-auth|all
"""
import base64
import json
import os
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab
import fakeimap_pin

CLASS = "org.dlang.liveimap.lab.FakeimapDailyTest"


def max_seed_count():
    highest = 0
    with open(os.path.join(HERE, "seed", "folders.tsv"), encoding="utf-8") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#") or line.startswith("user\t"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) > 2 and fields[2].isdigit():
                highest = max(highest, int(fields[2]))
    return highest


def control_reply(srv, obj):
    path = srv.bound.get("control")
    sock = __import__("socket").socket(__import__("socket").AF_UNIX, __import__("socket").SOCK_STREAM)
    sock.settimeout(5)
    try:
        sock.connect(path)
        f = sock.makefile("rw")
        f.write(json.dumps(obj) + "\n")
        f.flush()
        line = f.readline()
        f.close()
    finally:
        sock.close()
    if not line:
        return {"ok": False, "error": "no control reply"}
    return json.loads(line)


class Bridge(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        if self.path.split("?", 1)[0] != "/log":
            self.send_error(404)
            return
        data = Path(self.server.log_path).read_bytes() if os.path.isfile(self.server.log_path) else b""
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/control":
            self.send_error(404)
            return
        n = int(self.headers.get("Content-Length") or "0")
        try:
            obj = json.loads(self.rfile.read(n) or b"{}")
            reply = control_reply(self.server.srv, obj)
        except Exception as exc:
            reply = {"ok": False, "error": str(exc)}
        raw = json.dumps(reply).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def serve(srv, log_path):
    httpd = ThreadingHTTPServer((lab.LAB_HOST, 0), Bridge)
    httpd.srv = srv
    httpd.log_path = log_path
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return httpd


def instrument(method, extra):
    a = ["shell", "am", "instrument", "-w", "-r", "-e", "class", f"{CLASS}#{method}",
         "-e", "labHost", lab.EMU_HOST, "-e", "labCommit", lab.head(), *extra, lab.RUNNER]
    r = lab.adb(*a, timeout=300)
    print(r.stdout)
    if "OK (1 test)" not in (r.stdout or ""):
        sys.exit(1)


def start_server(work, personality, dataset, extra, log_path):
    control = fakeimap_pin.short_control_path("daily")
    keys = dict(
        listen=f"{lab.LAB_HOST}:0",
        personality=str(Path(personality).resolve()),
        dataset=str(Path(dataset).resolve()),
        control=str(Path(control).resolve()),
        log_path=str(Path(log_path).resolve()),
    )
    keys.update(extra)
    cfg = fakeimap_pin.write_config(Path(work) / "config.json", **keys)
    return fakeimap_pin.start(cfg, str(Path(work) / "fakeimap.err"))


def run_generated(work):
    count = max_seed_count() + 1
    folder = "Generated"
    ds = fakeimap_pin.write_dataset(
        Path(work) / "dataset", folder=folder, count=count, user=lab.USER, password=lab.PASS,
    )
    log_path = str(Path(work) / "fakeimap.jsonl")
    srv = start_server(
        work, fakeimap_pin.personality_path("binary"), ds, {}, log_path,
    )
    httpd = serve(srv, log_path)
    try:
        port = httpd.server_address[1]
        instrument("generatedMailboxStatusAndOneBody", [
            "-e", "labCase", "generated",
            "-e", "labPort", str(srv.port("listen")),
            "-e", "labFolder", folder,
            "-e", "labCount", str(count),
            "-e", "labMaxSeedCount", str(count - 1),
            "-e", "labUser", lab.USER,
            "-e", "labPass", lab.PASS,
            "-e", "labBridge", f"http://{lab.LAB_HOST}:{port}",
        ])
    finally:
        httpd.shutdown()
        srv.close()


def run_faults(work):
    folder = "LiveIMAP-Test"
    ds = fakeimap_pin.write_dataset(
        Path(work) / "dataset", folder=folder, count=4, user=lab.USER, password=lab.PASS,
    )
    pers = Path(work) / "personality.json"
    pers.write_text(json.dumps({
        "greeting": "daily fault imap ready",
        "delimiter": "/",
        "greeting_capabilities": ["IMAP4rev1", "AUTH=PLAIN"],
        "pre_login_capabilities": ["IMAP4rev1", "AUTH=PLAIN"],
        "post_login_capabilities": ["IMAP4rev1", "AUTH=PLAIN"],
        "fetch_item_order": ["FLAGS", "UID", "RFC822.SIZE", "RFC822"],
    }, indent=1) + "\n", encoding="utf-8")
    log_path = str(Path(work) / "fakeimap.jsonl")
    srv = start_server(work, pers, ds, {}, log_path)
    httpd = serve(srv, log_path)
    try:
        port = httpd.server_address[1]
        instrument("faultsLeaveTheMailbox", [
            "-e", "labCase", "faults",
            "-e", "labPort", str(srv.port("listen")),
            "-e", "labFolder", folder,
            "-e", "labUser", lab.USER,
            "-e", "labPass", lab.PASS,
            "-e", "labBridge", f"http://{lab.LAB_HOST}:{port}",
        ])
    finally:
        httpd.shutdown()
        srv.close()


def run_bearer(work):
    dataset = fakeimap_pin.PERSONALITIES.parent / "datasets" / "demo"
    log_path = str(Path(work) / "fakeimap.jsonl")
    addr = f"{lab.LAB_HOST}:0"
    srv = start_server(
        work,
        fakeimap_pin.PERSONALITIES / "stalwart-oauth.json",
        dataset,
        {"smtp_listen": addr, "smtp_auth_required": True, "jmap_listen": addr},
        log_path,
    )
    httpd = serve(srv, log_path)
    try:
        port = httpd.server_address[1]
        instrument("bearerFromIssueOpensThroughTheApp", [
            "-e", "labCase", "bearer",
            "-e", "labPort", str(srv.port("listen")),
            "-e", "labSmtpPort", str(srv.port("smtp")),
            "-e", "labJmapPort", str(srv.port("jmap")),
            "-e", "labUser", "demo",
            "-e", "labPass", "",
            "-e", "labBridge", f"http://{lab.LAB_HOST}:{port}",
        ])
    finally:
        httpd.shutdown()
        srv.close()


SESSION_CASES = (
    ("imapSilentFetch", "imap-silent"),
    ("imapDelayFetch", "imap-delay"),
    ("imapHalfCloseFetch", "imap-half-close"),
    ("imapFetchNo", "imap-fetch-no"),
    ("imapStoreNo", "imap-store-no"),
    ("imapAppendNo", "imap-append-no"),
    ("smtpDropAfter354", "smtp-drop"),
    ("smtpBounce", "smtp-bounce"),
    ("smtpOverquota", "smtp-overquota"),
    ("jmapServerFail", "jmap-server-fail"),
    ("jmapUnauthorized", "jmap-unauthorized"),
    ("jmapDropEventSource", "jmap-drop-eventsource"),
    ("idleCloseEndsWatch", "idle-close"),
    ("longSearchSilent", "search-silent"),
    ("longSearchClose", "search-close"),
    ("clientSortExpungeMid", "sort-expunge"),
    ("expungeMidMatchesList", "imap-expunge-mid"),
    ("armInjectMatchesList", "imap-inject"),
    ("sievePutNoAndClose", "sieve-put"),
)


def session_personality(path):
    path.write_text(json.dumps({
        "greeting": "daily session imap ready",
        "delimiter": "/",
        "greeting_capabilities": ["IMAP4rev1", "AUTH=PLAIN"],
        "pre_login_capabilities": ["IMAP4rev1", "AUTH=PLAIN"],
        "post_login_capabilities": ["IMAP4rev1", "AUTH=PLAIN", "IDLE"],
        "fetch_item_order": ["FLAGS", "UID", "RFC822.SIZE", "RFC822"],
    }, indent=1) + "\n", encoding="utf-8")
    return path


def session_extra(srv, bridge, case, folder):
    return [
        "-e", "labCase", case,
        "-e", "labPort", str(srv.port("listen")),
        "-e", "labSmtpPort", str(srv.port("smtp")),
        "-e", "labJmapPort", str(srv.port("jmap")),
        "-e", "labSievePort", str(srv.port("sieve")),
        "-e", "labFolder", folder,
        "-e", "labUser", lab.USER,
        "-e", "labPass", lab.PASS,
        "-e", "labBridge", bridge,
    ]


def run_session(work):
    folder = "LiveIMAP-Test"
    ds = fakeimap_pin.write_dataset(
        Path(work) / "dataset", folder=folder, count=4, user=lab.USER, password=lab.PASS,
    )
    pers = session_personality(Path(work) / "personality.json")
    log_path = str(Path(work) / "fakeimap.jsonl")
    addr = f"{lab.LAB_HOST}:0"
    srv = start_server(work, pers, ds, {
        "smtp_listen": addr,
        "jmap_listen": addr,
        "sieve_listen": addr,
    }, log_path)
    httpd = serve(srv, log_path)
    try:
        bridge = f"http://{lab.LAB_HOST}:{httpd.server_address[1]}"
        for method, case in SESSION_CASES:
            instrument(method, session_extra(srv, bridge, case, folder))
    finally:
        httpd.shutdown()
        srv.close()


def write_lab_cert(work):
    cert = Path(work) / "cert.pem"
    key = Path(work) / "key.pem"
    der = Path(work) / "cert.der"
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-keyout", str(key), "-out", str(cert), "-days", "2",
        "-subj", "/CN=liveimap-lab",
        "-addext", f"subjectAltName=IP:{lab.LAB_HOST}",
    ], check=True, capture_output=True)
    subprocess.run([
        "openssl", "x509", "-in", str(cert), "-outform", "DER", "-out", str(der),
    ], check=True, capture_output=True)
    return cert, key, base64.b64encode(der.read_bytes()).decode("ascii")


def run_tls(work):
    folder = "LiveIMAP-Test"
    ds = fakeimap_pin.write_dataset(
        Path(work) / "dataset", folder=folder, count=1, user=lab.USER, password=lab.PASS,
    )
    pers = session_personality(Path(work) / "personality.json")
    cert, key, der_b64 = write_lab_cert(work)
    log_path = str(Path(work) / "fakeimap.jsonl")
    srv = start_server(work, pers, ds, {
        "implicit_tls_listen": f"{lab.LAB_HOST}:0",
        "cert_file": str(cert.resolve()),
        "key_file": str(key.resolve()),
    }, log_path)
    httpd = serve(srv, log_path)
    try:
        port = httpd.server_address[1]
        instrument("tlsCertFaultsFailBeforeLogin", [
            "-e", "labCase", "tls-certs",
            "-e", "labPort", str(srv.port("implicit_tls_listen")),
            "-e", "labFolder", folder,
            "-e", "labUser", lab.USER,
            "-e", "labPass", lab.PASS,
            "-e", "labCa", der_b64,
            "-e", "labBridge", f"http://{lab.LAB_HOST}:{port}",
        ])
    finally:
        httpd.shutdown()
        srv.close()


def run_bearer_auth_no(work):
    dataset = fakeimap_pin.PERSONALITIES.parent / "datasets" / "demo"
    log_path = str(Path(work) / "fakeimap.jsonl")
    srv = start_server(
        work,
        fakeimap_pin.PERSONALITIES / "stalwart-oauth.json",
        dataset,
        {},
        log_path,
    )
    httpd = serve(srv, log_path)
    try:
        port = httpd.server_address[1]
        instrument("bearerAuthenticateNo", [
            "-e", "labCase", "bearer-auth-no",
            "-e", "labPort", str(srv.port("listen")),
            "-e", "labUser", "demo",
            "-e", "labPass", "",
            "-e", "labBridge", f"http://{lab.LAB_HOST}:{port}",
        ])
    finally:
        httpd.shutdown()
        srv.close()


WHICH = (
    "generated", "faults", "bearer", "session", "tls", "bearer-auth", "all",
)


def main():
    fakeimap_pin.require_ready()
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    which = args[0] if args else "all"
    if "--skip-install" not in sys.argv:
        lab.install(lab.head())
    root = Path(lab.REC) / "fakeimap-daily"
    root.mkdir(parents=True, exist_ok=True)
    if which in ("generated", "all"):
        run_generated(root / "generated")
    if which in ("faults", "all"):
        run_faults(root / "faults")
    if which in ("bearer", "all"):
        run_bearer(root / "bearer")
    if which in ("session", "all"):
        run_session(root / "session")
    if which in ("tls", "all"):
        run_tls(root / "tls")
    if which in ("bearer-auth", "all"):
        run_bearer_auth_no(root / "bearer-auth")
    if which not in WHICH:
        sys.exit("usage: fakeimap_daily.py [--skip-install] " + "|".join(WHICH))


if __name__ == "__main__":
    main()
