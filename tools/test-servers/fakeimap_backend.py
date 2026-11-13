"""Start and stop pinned fakeimap as a drop-in for one lab docker server.

Each lab server is paired with the personality of the same name (cyrus-3137
with cyrus-3137, and so on). Listeners follow smtp.tsv / sieve.tsv / jmap.tsv.
Ports are written to a JSON overlay that lab.servers() reads when
LAB_FAKEIMAP_PORTS is set, so instrumented regress can aim the app at
fakeimap without docker.

Bind defaults to 127.0.0.1 (host-side probes). Set LAB_BIND / --bind to
0.0.0.0 when an emulator must reach the ports.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
GEN = HERE / "generated"
DEFAULT_WORK = REPO / "sandbox" / "tmp-rescue" / "fakeimap-regress"

# Overlay path used by lab.servers() when this backend is running.
OVERLAY_ENV = "LAB_FAKEIMAP_PORTS"
BACKEND_ENV = "LAB_BACKEND"


def _tsv_rows(path, min_fields):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) >= min_fields:
                rows.append(fields)
    return rows


def listeners_for(name):
    """Which extra listeners this lab server exposes, from the lab tables."""
    smtp_modes = [r[1] for r in _tsv_rows(HERE / "smtp.tsv", 3) if r[0] == name]
    sieve = [r for r in _tsv_rows(HERE / "sieve.tsv", 2) if r[0] == name]
    jmap = [r for r in _tsv_rows(HERE / "jmap.tsv", 3) if r[0] == name]
    return {
        "smtp_modes": smtp_modes,  # e.g. None, StartTls, Implicit
        "sieve": bool(sieve),
        "sieve_tls": sieve[0][1] if sieve else "",
        "jmap": [(r[1], r[2]) for r in jmap],  # (http|https, ...)
    }


def cert_paths():
    cert = GEN / "ca" / "server.crt"
    key = GEN / "ca" / "server.key"
    if not cert.is_file() or not key.is_file():
        sys.exit(f"fakeimap_backend: missing lab TLS cert {cert} or {key}")
    return cert, key


def write_overlay(path, server_dict):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"servers": [server_dict]}
    path.write_text(json.dumps(payload, indent=1) + "\n", encoding="utf-8")
    return path


def start_lab_fakeimap(name, personality=None, work_dir=None, bind="127.0.0.1",
                       overlay_path=None, personality_dir=None, fakeimap_bin=None):
    """Start fakeimap for lab server [name]. Returns (Server, overlay_path, info).

    info has personality, work_dir, dataset, listeners, no secrets.
    """
    import fakeimap_pin
    import fakeimap_seed
    fakeimap_pin.require_ready()
    pers_name = personality or name
    if personality_dir:
        pers = Path(personality_dir) / f"{pers_name}.json"
    else:
        pers = Path(fakeimap_pin.personality_path(pers_name))
    if not pers.is_file():
        sys.exit(f"fakeimap_backend: no personality {pers}")
    work = Path(work_dir) if work_dir else DEFAULT_WORK / name
    work.mkdir(parents=True, exist_ok=True)
    ds = fakeimap_seed.write_seed_dataset(work / "dataset", pers_name)
    cert, key = cert_paths()
    control = fakeimap_pin.short_control_path("fi-" + name.replace("_", "")[:20])
    want = listeners_for(name)
    addr = f"{bind}:0"
    services = {r[1] for r in _tsv_rows(HERE / "servers.tsv", 3) if r[0] == name}
    keys = dict(
        listen=addr,
        cert_file=str(cert.resolve()),
        key_file=str(key.resolve()),
        personality=str(Path(pers).resolve()),
        dataset=str(ds.resolve()),
        control=str(Path(control).resolve()),
        log_path=str((work / "fakeimap.log").resolve()),
    )
    if "imaps" in services:
        keys["implicit_tls_listen"] = addr
    if want["smtp_modes"]:
        keys["smtp_listen"] = addr
        if any(m == "Implicit" for m in want["smtp_modes"]):
            keys["smtp_tls_listen"] = addr
        # Lab submission authenticates as the seed account.
        keys["smtp_auth_required"] = True
    if want["sieve"]:
        keys["sieve_listen"] = addr
    if want["jmap"]:
        if any(kind == "http" for kind, _rest in want["jmap"]):
            keys["jmap_listen"] = addr
        if any(kind == "https" for kind, _rest in want["jmap"]):
            keys["jmap_tls_listen"] = addr
    cfg = fakeimap_pin.write_config(work / "config.json", **keys)
    err_path = work / "fakeimap.err"
    # A full corpus (~1600 messages) can take more than 5s to load.
    server = fakeimap_pin.start(cfg, err_path, timeout=60, binary=fakeimap_bin)
    overlay = {
        "name": name,
        "container": "",
        "imap": server.port("listen"),
    }
    bound = server.bound
    if bound.get("implicit_tls_listen"):
        overlay["imaps"] = server.port("implicit_tls_listen")
    if bound.get("smtp") or bound.get("smtp_listen"):
        # start() may use either key; try both
        try:
            overlay["submission"] = server.port("smtp")
        except SystemExit:
            if bound.get("smtp_listen"):
                overlay["submission"] = int(str(bound["smtp_listen"]).rsplit(":", 1)[-1])
    if bound.get("smtp_tls") or bound.get("smtp_tls_listen"):
        try:
            overlay["smtp_tls"] = server.port("smtp_tls")
        except SystemExit:
            pass
    if bound.get("sieve"):
        try:
            overlay["managesieve"] = server.port("sieve")
        except SystemExit:
            if ":" in str(bound.get("sieve") or ""):
                overlay["managesieve"] = int(str(bound["sieve"]).rsplit(":", 1)[-1])
    if bound.get("jmap"):
        try:
            overlay["http"] = server.port("jmap")
        except SystemExit:
            if ":" in str(bound.get("jmap") or ""):
                overlay["http"] = int(str(bound["jmap"]).rsplit(":", 1)[-1])
    if bound.get("jmap_tls"):
        try:
            overlay["https"] = server.port("jmap_tls")
        except SystemExit:
            if ":" in str(bound.get("jmap_tls") or ""):
                overlay["https"] = int(str(bound["jmap_tls"]).rsplit(":", 1)[-1])
    if overlay_path is None:
        overlay_path = work / "ports.json"
    write_overlay(overlay_path, overlay)
    info = {
        "name": name,
        "personality": pers_name,
        "work_dir": str(work),
        "dataset": str(ds),
        "bound": {k: bound[k] for k in bound if k != "control"},
        "overlay": overlay,
        "listeners": want,
    }
    (work / "backend-info.json").write_text(json.dumps(info, indent=1) + "\n", encoding="utf-8")
    return server, Path(overlay_path), info


def stop_lab_fakeimap(server):
    if server is not None:
        server.close()


if __name__ == "__main__":
    import argparse
    import time
    p = argparse.ArgumentParser(description="Start fakeimap for one lab server personality.")
    p.add_argument("--server", required=True)
    p.add_argument("--personality", default="")
    p.add_argument("--bind", default=os.environ.get("LAB_BIND") or "127.0.0.1")
    p.add_argument("--work", default="")
    p.add_argument("--hold", action="store_true", help="keep running until SIGTERM")
    a = p.parse_args()
    srv, overlay, info = start_lab_fakeimap(
        a.server, personality=a.personality or None,
        work_dir=a.work or None, bind=a.bind,
    )
    print(json.dumps({"overlay": info["overlay"], "personality": info["personality"],
                      "bound": info["bound"]}, indent=1))
    try:
        if a.hold:
            while srv.proc.poll() is None:
                time.sleep(1)
            sys.exit(srv.proc.returncode or 1)
    finally:
        if not a.hold:
            stop_lab_fakeimap(srv)
