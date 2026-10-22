#!/usr/bin/env python3
"""Print one free host port that no local-ports.tsv row uses (build containers only)."""
import socket
import sys

used = set()
for line in open(sys.argv[1], encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    if len(f) >= 2 and f[1].isdigit():
        used.add(int(f[1]))
for _ in range(50):
    s = socket.socket()
    s.bind(("", 0))
    port = s.getsockname()[1]
    s.close()
    if port not in used:
        print(port)
        sys.exit(0)
sys.exit("no free build port")
