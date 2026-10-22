#!/usr/bin/env python3
"""Shared lab host ports. Only one lab server runs at a time, so every seed server publishes the same host port
for the same service (imap, imaps, submission, managesieve, https, http, admin, ...), each mapped to that
server's own container port from servers.tsv.

generated/lab-ports.tsv (service<TAB>hostport) is drawn once per service and never redrawn: a recorded port that
is taken fails loudly. generated/local-ports.tsv is derived from it for the existing readers (lab.servers(),
lab-ready, regress): its seed-server rows are rewritten as <server>-<service>, every other row (greenmail, the
fake-smtp-sink, lan_ipv4) is kept as it is.

Usage:
  lab_ports.py sync            draw missing services, rewrite the derived local-ports.tsv rows
  lab_ports.py flags SERVER    print the docker -p arguments for SERVER (after sync)
  lab_ports.py port SERVICE    print one shared host port (after sync)
"""
import os
import socket
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, "generated")
LAB_PORTS = os.path.join(GEN, "lab-ports.tsv")
LOCAL_PORTS = os.path.join(GEN, "local-ports.tsv")
# Ports for host-side helpers that are not seed-server services but live in the same row set.
EXTRA_SERVICES = ["fake-smtp-sink"]
LOW, HIGH = 20000, 40000


def fail(text):
    sys.stderr.write("lab_ports: " + text + "\n")
    sys.exit(1)


def seed_servers():
    sys.path.insert(0, HERE)
    import lab
    return list(lab.SEED_SERVERS)


def server_rows():
    """(server, service, container_port) for every seed server, from servers.tsv."""
    seeds = set(seed_servers())
    out = []
    with open(os.path.join(HERE, "servers.tsv"), encoding="utf-8") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            f = line.rstrip("\n").split("\t")
            if len(f) >= 3 and f[0] in seeds:
                if not f[2].isdigit():
                    fail(f"servers.tsv {f[0]} {f[1]}: container port {f[2]!r} is not a number")
                out.append((f[0], f[1], int(f[2])))
    return out


def read_lab_ports():
    got = {}
    if not os.path.exists(LAB_PORTS):
        return got
    with open(LAB_PORTS, encoding="utf-8") as handle:
        for n, line in enumerate(handle, 1):
            if not line.strip() or line.startswith("#"):
                continue
            f = line.rstrip("\n").split("\t")
            if len(f) != 2 or not f[1].isdigit():
                fail(f"{LAB_PORTS}:{n}: want service<TAB>port, got {line.rstrip()!r}")
            if f[0] in got:
                fail(f"{LAB_PORTS}:{n}: duplicate service {f[0]}")
            got[f[0]] = int(f[1])
    return got


def other_rows():
    """local-ports.tsv rows that are not seed-server rows (kept verbatim)."""
    seeds = {"liveimap-" + s for s in seed_servers()}
    keep = []
    if os.path.exists(LOCAL_PORTS):
        with open(LOCAL_PORTS, encoding="utf-8") as handle:
            for line in handle:
                f = line.rstrip("\n").split("\t")
                if len(f) == 4 and f[3] in seeds:
                    continue
                if f and f[0] in EXTRA_SERVICES:
                    continue  # rewritten from lab-ports.tsv
                keep.append(line.rstrip("\n"))
    return keep


def row_names():
    """(server, service) -> local-ports.tsv row name. The names the app and the tests already read are kept
    (dovecot245-imap, stalwart015-http, ...); a server without a recorded row gets <server without dashes>-<service>."""
    import lab
    recorded = {}
    if os.path.exists(LOCAL_PORTS):
        with open(LOCAL_PORTS, encoding="utf-8") as handle:
            for line in handle:
                f = line.rstrip("\n").split("\t")
                if len(f) == 4 and f[3].startswith("liveimap-"):
                    recorded.setdefault(f[3][len("liveimap-"):], []).append(f[0])
    out = {}
    for server, service, _cport in server_rows():
        hits = [k for k in recorded.get(server, []) if lab._kind_matches(service, k)]
        if len(hits) > 1:
            fail(f"local-ports.tsv: {server} {service} matches rows {hits}")
        if hits:
            out[(server, service)] = hits[0]
        elif recorded.get(server):
            prefix = recorded[server][0].rsplit("-", 1)[0]
            out[(server, service)] = f"{prefix}-{service}"
        else:
            prefix = server if server.endswith("-dev") else server.replace("-", "")
            out[(server, service)] = f"{prefix}-{service}"
    return out


def bindable(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def sync():
    services = []
    for _server, service, _cport in server_rows():
        if service not in services:
            services.append(service)
    services += [s for s in EXTRA_SERVICES if s not in services]
    have = read_lab_ports()
    if os.path.exists(LOCAL_PORTS):  # first sync: keep the helper ports already recorded (the sink port is baked
        with open(LOCAL_PORTS, encoding="utf-8") as handle:  # into the dovecot-245 image)
            for line in handle:
                f = line.rstrip("\n").split("\t")
                if len(f) == 4 and f[0] in EXTRA_SERVICES and f[0] not in have and f[1].isdigit():
                    have[f[0]] = int(f[1])
    kept = other_rows()
    used = set(have.values())
    for line in kept:
        f = line.split("\t")
        if len(f) >= 2 and f[1].isdigit():
            used.add(int(f[1]))
    if len(set(have.values())) != len(have):
        fail(f"{LAB_PORTS}: two services share one host port")
    for service in services:
        if service in have:
            continue
        for port in range(LOW, HIGH):
            if port not in used and bindable(port):
                have[service] = port
                used.add(port)
                break
        else:
            fail(f"no free port for {service} in {LOW}-{HIGH}")
    os.makedirs(GEN, exist_ok=True)
    tmp = LAB_PORTS + ".new"
    with open(tmp, "w", encoding="utf-8") as out:
        out.write("# service\thostport (drawn once, never redrawn; every lab server publishes these)\n")
        for service in sorted(have):
            out.write(f"{service}\t{have[service]}\n")
    os.replace(tmp, LAB_PORTS)
    rows = [line for line in kept if line]
    if not rows or not rows[0].startswith("service\t"):
        rows.insert(0, "service\thost_port\tcontainer_port\tcontainer")
    for service in EXTRA_SERVICES:
        rows.append(f"{service}\t{have[service]}\t{have[service]}\thost")
    names = row_names()
    for server, service, cport in server_rows():
        rows.append(f"{names[(server, service)]}\t{have[service]}\t{cport}\tliveimap-{server}")
    tmp = LOCAL_PORTS + ".new"
    with open(tmp, "w", encoding="utf-8") as out:
        out.write("\n".join(rows) + "\n")
    os.replace(tmp, LOCAL_PORTS)


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "sync":
        sync()
        return
    have = read_lab_ports()
    if len(sys.argv) == 3 and sys.argv[1] == "flags":
        rows = [r for r in server_rows() if r[0] == sys.argv[2]]
        if not rows:
            fail(f"{sys.argv[2]}: no servers.tsv rows")
        out = []
        for _server, service, cport in rows:
            if service not in have:
                fail(f"{service}: not in {LAB_PORTS}; run lab_ports.py sync")
            out.append(f"-p {have[service]}:{cport}")
        print(" ".join(out))
        return
    if len(sys.argv) == 3 and sys.argv[1] == "port":
        if sys.argv[2] not in have:
            fail(f"{sys.argv[2]}: not in {LAB_PORTS}; run lab_ports.py sync")
        print(have[sys.argv[2]])
        return
    fail("usage: lab_ports.py sync | flags SERVER | port SERVICE")


if __name__ == "__main__":
    main()
