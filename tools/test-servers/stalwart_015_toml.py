#!/usr/bin/env python3
"""Seed build only: Stalwart 0.15 TOML for the lab certificate (COPY certs/ -> /etc/lab) and one listener per
servers.tsv row of SERVER (container ports from servers.tsv). Usage: stalwart_015_toml.py SERVER"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ports  # noqa: E402

server = sys.argv[1]
KINDS = {"imap": ("imap", False), "imaps": ("imap", True), "managesieve": ("managesieve", False),
         "https": ("http", True), "submission": ("smtp", False)}
out = ['[certificate.lab]', 'cert = "%{file:/etc/lab/chain.crt}%"', 'private-key = "%{file:/etc/lab/server.key}%"',
       'default = true']
services = [line.split("\t")[1] for line in open(os.path.join(HERE, "servers.tsv"), encoding="utf-8")
            if line.startswith(server + "\t")]
if not services:
    sys.exit(f"no servers.tsv rows for {server}")
for service in services:
    if service not in KINDS:
        sys.exit(f"servers.tsv {server} {service}: no Stalwart listener kind")
    protocol, implicit = KINDS[service]
    out += ['', f'[server.listener.{service}]', f'bind = "0.0.0.0:{ports.container_port(server, service)}"',
            f'protocol = "{protocol}"', f'tls.implicit = {"true" if implicit else "false"}']
print("\n".join(out))
