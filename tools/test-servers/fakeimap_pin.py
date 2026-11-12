#!/usr/bin/env python3
"""Locate and start the pinned fakeimap binary.

Expected locations, after a version is locked and emerged:

  third_party/fakeimap/artifact/fakeimap          # the binary
  third_party/fakeimap/src/personalities/<name>.json  # after fetch-deps ro

There is no fallback to a host checkout, a PATH binary, or the old Python
replay fake. If the pin git_sha is still the 40-zero placeholder, or the
binary or personality tree is missing, this module exits with an error.

Start: artifact/fakeimap <config.json>. The process writes one JSON object of
bound addresses to stdout (listen, implicit_tls_listen, control, smtp, jmap,
jmap_tls, sieve) and flushes it. The control socket is one JSON object per
line in and one per line out: {"ok": true, ...} or {"ok": false, "error": "..."}.

Gaps versus the old in-tree replay fake (fake_imap.py / fake_line.py /
fake_model.py). These are not silently filled in:

  - Recording replay: the old fake replayed exact recorded IMAP, SMTP, and
    ManageSieve reply variants and logged unexpected / never-hit commands.
    fakeimap is a real implementation with personality files. Step-level
    comparison with a real-lab recording can still run; byte-for-byte replay
    and unexpected/never-hit logs have no equivalent.
  - FAKE_MODEL drop_caps / add_caps: no control op strips or adds
    capabilities. Callers write a derived personality file (see
    derive_personality) and start with that, or call set-personality.
  - FAKE_MODEL script at_noop (expunge_uid / append on the Nth NOOP):
    inject can append a message, but there is no expunge op and no
    apply-on-command hook. The safety "outside" case cannot be driven yet.
  - FAKE_MODEL esort_partial: depends on the personality honoring PARTIAL
    on ESORT (sort_partial_count_only must be false). There is no separate
    model flag.
  - LOGIN accepts any user (screenshot personality): fakeimap only accepts
    dataset accounts (accounts op can add one). The screenshot.json replay
    personality is no longer served.
  - FAKE_DUMP (raw bytes per connection): no equivalent; use log_path
    (kind=command|reply). FAKE_DELAY_MS maps to arm-fault do=delay.
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PIN_DIR = REPO / "third_party" / "fakeimap"
PIN_FILE = PIN_DIR / "libpin.toml"
ARTIFACT = PIN_DIR / "artifact" / "fakeimap"
PERSONALITIES = PIN_DIR / "src" / "personalities"
PLACEHOLDER_SHA = "0000000000000000000000000000000000000000"


def _git_sha():
    if not PIN_FILE.is_file():
        sys.exit(f"fakeimap pin: {PIN_FILE} is missing")
    try:
        import tomllib
    except ImportError:
        import tomli as tomllib  # type: ignore
    data = tomllib.loads(PIN_FILE.read_text(encoding="utf-8"))
    return (data.get("git_sha") or "").strip()


def require_ready():
    """Fail loudly unless the pin is locked and the binary plus personalities exist."""
    sha = _git_sha()
    if not sha or sha == PLACEHOLDER_SHA or sha == "UNSELECTED":
        sys.exit(
            "fakeimap pin: git_sha is UNSELECTED "
            f"({PLACEHOLDER_SHA}). Lock a commit in {PIN_FILE}, then run "
            "./third_party/fetch-deps ro fakeimap && "
            "./third_party/fetch-deps build --record fakeimap"
        )
    if not ARTIFACT.is_file() or not os.access(ARTIFACT, os.X_OK):
        sys.exit(
            f"fakeimap pin: git_sha is {sha} but {ARTIFACT} is not an "
            "executable. Emerge with: ./third_party/fetch-deps ro fakeimap && "
            "./third_party/fetch-deps build --record fakeimap"
        )
    if not PERSONALITIES.is_dir():
        sys.exit(
            f"fakeimap pin: {PERSONALITIES} is missing. "
            "Materialize with: ./third_party/fetch-deps ro fakeimap"
        )


def personality_path(name):
    require_ready()
    path = PERSONALITIES / f"{name}.json"
    if not path.is_file():
        sys.exit(f"fakeimap pin: no personality for {name} at {path}")
    return path


def derive_personality(src, dest, drop_caps=(), add_caps=(), sort_partial_count_only=None):
    """Copy a personality, stripping and/or appending capability tokens.

    fakeimap has no drop_caps/add_caps control op; this is the mapping.
    """
    data = json.loads(Path(src).read_text())
    drop = {c.upper() for c in drop_caps}
    add = list(add_caps)
    for key in (
        "greeting_capabilities",
        "pre_login_capabilities",
        "post_login_capabilities",
    ):
        if key not in data or data[key] is None:
            continue
        kept = [c for c in data[key] if c.upper().strip("[]") not in drop]
        have = {c.upper() for c in kept}
        for cap in add:
            if cap.upper() not in have:
                kept.append(cap)
                have.add(cap.upper())
        data[key] = kept
    if sort_partial_count_only is not None:
        data["sort_partial_count_only"] = bool(sort_partial_count_only)
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(data, indent=1) + "\n")
    return dest


def write_dataset(dest_dir, folder="LiveIMAP-Test", count=10, user=None, password=None,
                  special_use_trash=True):
    """Write a dataset directory with INBOX, [folder] of [count] generated messages, and optional Trash.

    user and password must be supplied by the caller (lab.USER / lab.PASS). They
    are written into dataset.json and are not printed.
    """
    if not user or password is None:
        sys.exit("fakeimap pin: write_dataset requires user and password")
    dest = Path(dest_dir)
    dest.mkdir(parents=True, exist_ok=True)
    mailboxes = [
        {"name": "INBOX", "uid_validity": 1, "messages": []},
        {
            "name": folder,
            "uid_validity": 17,
            "generated": {
                "count": count,
                "seed": "liveimap-lab",
                "from": "Lab Sender <sender@lab.test>",
                "to": f"{user} <{user}@lab.test>",
                "subject": "Lab",
                "start": "01-Jan-2020 00:00:00 +0000",
                "step_seconds": 86400,
            },
        },
    ]
    if special_use_trash:
        mailboxes.append(
            {"name": "Trash", "uid_validity": 2, "special_use": ["\\Trash"], "messages": []}
        )
    data = {"accounts": [{"username": user, "password": password}], "mailboxes": mailboxes}
    (dest / "dataset.json").write_text(json.dumps(data, indent=1) + "\n")
    return dest


def write_config(path, **keys):
    """Write a fakeimap config.json. Paths should be absolute (they are relative to cwd, not to the file)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(keys, indent=1) + "\n")
    return path


def command_lines(log_path):
    """Return the 'text' of each kind=command record in a fakeimap log_path file."""
    out = []
    path = Path(log_path)
    if not path.is_file():
        return out
    for line in path.read_text().splitlines():
        if not line.strip().startswith("{"):
            continue
        rec = json.loads(line)
        if rec.get("kind") == "command":
            out.append(rec.get("text") or "")
    return out


class Server:
    def __init__(self, proc, bound, err_path):
        self.proc = proc
        self.bound = bound
        self.err_path = err_path

    def port(self, key="listen"):
        addr = self.bound.get(key) or ""
        if not addr or ":" not in addr:
            sys.exit(f"fakeimap pin: bound JSON has no {key} address: {self.bound}")
        return int(addr.rsplit(":", 1)[1])

    def control(self, obj, timeout=5):
        path = self.bound.get("control")
        if not path:
            sys.exit("fakeimap pin: bound JSON has no control socket")
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(timeout)
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
            sys.exit(f"fakeimap pin: no control reply for {obj.get('op')}")
        reply = json.loads(line)
        if not reply.get("ok"):
            sys.exit(f"fakeimap pin: control {obj.get('op')} failed: {reply.get('error') or reply}")
        return reply

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()


def start(config_path, err_path, timeout=5):
    """Start artifact/fakeimap and return a Server after reading the bound-address line."""
    require_ready()
    config_path = Path(config_path)
    err_path = Path(err_path)
    err_path.parent.mkdir(parents=True, exist_ok=True)
    err = open(err_path, "w")
    proc = subprocess.Popen(
        [str(ARTIFACT), str(config_path)],
        stdout=subprocess.PIPE,
        stderr=err,
        text=True,
        cwd=str(REPO),
    )
    deadline = time.time() + timeout
    line = ""
    try:
        while time.time() < deadline:
            if proc.poll() is not None:
                break
            # readline can block; use a short select on stdout
            import select
            r, _, _ = select.select([proc.stdout], [], [], 0.2)
            if r:
                line = proc.stdout.readline()
                break
        if not line and proc.stdout and proc.poll() is not None:
            # process exited; try whatever was left
            line = proc.stdout.readline()
    except Exception:
        proc.terminate()
        err.close()
        raise
    if not line.strip():
        proc.terminate()
        err.close()
        sys.exit(
            f"fakeimap pin: process wrote no bound-address JSON to stdout "
            f"(exit {proc.poll()}, see {err_path})"
        )
    try:
        bound = json.loads(line)
    except json.JSONDecodeError as e:
        proc.terminate()
        err.close()
        sys.exit(f"fakeimap pin: first stdout line is not JSON: {line!r} ({e})")
    return Server(proc, bound, str(err_path))
