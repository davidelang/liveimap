#!/usr/bin/env python3
"""External SCRAM(-PLUS) client for lab IMAP servers: STARTTLS on host:port, then AUTHENTICATE.
Usage: scram_probe.py host port user pass MECH [tls12]. Prints the GS2 header, cbind type and the outcome."""
import base64, hashlib, hmac, os, socket, ssl, sys
host, port, user, pw, mech = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
cap12 = len(sys.argv) > 6
h = hashlib.sha256 if "256" in mech else hashlib.sha1
s = socket.create_connection((host, port), 10); f = s.makefile("rb")
def rd():
    l = f.readline().decode().rstrip("\r\n"); print("S:", l[:140]); return l
def wr(x):
    print("C:", x[:140]); w.write((x + "\r\n").encode()); w.flush()
rd(); w = s.makefile("wb"); wr("a STARTTLS")
while not rd().startswith("a "): pass
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
if cap12: ctx.maximum_version = ssl.TLSVersion.TLSv1_2
t = ctx.wrap_socket(s); f = t.makefile("rb"); w = t.makefile("wb")
print("TLS:", t.version(), t.cipher()[0])
plus = mech.endswith("-PLUS")
if plus:
    if t.version() == "TLSv1.3":
        cbt, cb = "tls-exporter", t._sslobj.export_keying_material(b"EXPORTER-Channel-Binding", 32, None) if hasattr(t._sslobj, "export_keying_material") else None
        if cb is None: sys.exit("python lacks tls-exporter; use tls12")
    else:
        cbt, cb = "tls-unique", t.get_channel_binding("tls-unique")
    gs2 = f"p={cbt},,"
else:
    cbt, cb, gs2 = "none", b"", "n,,"
print("GS2:", gs2)
nonce = base64.b64encode(os.urandom(18)).decode()
first_bare = f"n={user},r={nonce}"
wr(f"b AUTHENTICATE {mech}")
l = rd()
if l.startswith("+"): wr(base64.b64encode((gs2 + first_bare).encode()).decode())
l = rd()
if not l.startswith("+ "): sys.exit("no server-first")
sf = base64.b64decode(l[2:]).decode(); print("server-first:", sf)
d = dict(kv.split("=", 1) for kv in sf.split(","))
salted = hashlib.pbkdf2_hmac(h().name, pw.encode(), base64.b64decode(d["s"]), int(d["i"]))
ck = hmac.new(salted, b"Client Key", h).digest(); sk = h(ck).digest()
cbind = base64.b64encode(gs2.encode() + cb).decode()
final_wo = f"c={cbind},r={d['r']}"
am = f"{first_bare},{sf},{final_wo}"
sig = hmac.new(sk, am.encode(), h).digest()
proof = base64.b64encode(bytes(a ^ b for a, b in zip(ck, sig))).decode()
wr(base64.b64encode(f"{final_wo},p={proof}".encode()).decode())
while True:
    l = rd()
    if l.startswith("+"): wr("")
    if l.startswith("b "): print("RESULT:", l); break
