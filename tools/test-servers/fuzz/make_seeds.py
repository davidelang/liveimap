#!/usr/bin/env python3
"""Fuzz seeds from lab recordings: each server reply to one command (untagged lines, literals, then the tagged
line, retagged "1" as the harness expects), plus greetings (first byte 0x01 = greeting mode, 0x00 = response).
Usage: make_seeds.py RECORDINGS_DIR OUT_DIR [MAX]"""
import base64, glob, hashlib, json, os, re, sys

rec, out = sys.argv[1], sys.argv[2]
cap = int(sys.argv[3]) if len(sys.argv) > 3 else 4000
os.makedirs(out, exist_ok=True)
seen, n = set(), 0


def shape(b):
    return re.sub(rb"\d+", b"9", b[:200])


def emit(mode, data):
    global n
    if not data or len(data) > 65536 or n >= cap:
        return
    k = shape(data)
    if k in seen:
        return
    seen.add(k)
    open(os.path.join(out, hashlib.sha1(data).hexdigest()[:16]), "wb").write(bytes([mode]) + data)
    n += 1


for f in sorted(glob.glob(os.path.join(rec, "*", "*", "*.jsonl"))):
    if os.path.basename(f) in ("caps.jsonl",) or "fake" in f:
        continue
    bufs, first = {}, set()
    for line in open(f, errors="replace"):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("dir") != "S":
            continue
        c = e.get("conn", "main")
        b = bufs.setdefault(c, bytearray())
        if "literal_b64" in e:
            b += base64.b64decode(e["literal_b64"])
            continue
        text = e.get("line", "").encode("latin-1", "replace")
        if c not in first:
            first.add(c)
            emit(1, text + b"\r\n")
            continue
        m = re.match(rb"([A-Za-z0-9.]+) (OK|NO|BAD)\b(.*)$", text)
        if m and not text.startswith(b"*") and not text.startswith(b"+"):
            b += b"1 " + m.group(2) + m.group(3) + b"\r\n"
            emit(0, bytes(b))
            bufs[c] = bytearray()
        else:
            b += text + b"\r\n"  # a literal line "{n}" is followed by CRLF, then its bytes
print(f"{n} seeds in {out}")
