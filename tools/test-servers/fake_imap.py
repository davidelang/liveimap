#!/usr/bin/env python3
"""Phase D/E: strict scripted fake IMAP server serving one personality (see personality.py).
Usage: fake_imap.py PERSONALITY.json HOST PORT LOG.jsonl. Plaintext only. Each command is matched by shape;
variants are served in recorded order (sticking at the last). Unknown shapes get BAD and are logged as
unexpected. Mail-changing commands (EXPUNGE, CLOSE, MOVE, DELETE) are logged as never-hits."""
import base64, json, re, socket, sys, threading, time
sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
from personality import shape

p = json.load(open(sys.argv[1]))
host, port, logpath = sys.argv[2], int(sys.argv[3]), sys.argv[4]
NEVER = re.compile(r"^(UID )?(EXPUNGE|CLOSE|MOVE)\b|^DELETE\b", re.I)
lock = threading.Lock()
DUMP = __import__("os").environ.get("FAKE_DUMP", "")  # diagnosis: raw bytes sent per connection
DELAY = int(__import__("os").environ.get("FAKE_DELAY_MS", "0")) / 1000.0
log = open(logpath, "a")


def note(**kw):
    with lock:
        log.write(json.dumps(kw) + "\n"); log.flush()


def serve(conn, cid):
    used = {}
    authed = False
    f = conn.makefile("rb")
    dump = open(f"{DUMP}.{cid}.bin", "wb") if DUMP else None
    def send(b):
        if dump:
            dump.write(b); dump.flush()
        conn.sendall(b)
    send((p["greeting"] + "\r\n").encode("latin-1"))
    while True:
        raw = f.readline()
        if not raw:
            break
        line = raw.decode("latin-1").rstrip("\r\n")
        m = re.search(r"\{(\d+)(\+?)\}$", line)
        if m:  # client literal: accept and swallow
            if not m.group(2):
                send(b"+ OK\r\n")
            f.read(int(m.group(1)))
            line = line[:m.start()] + "{L}" + f.readline().decode("latin-1").rstrip("\r\n")
        tag, _, rest = line.partition(" ")
        cmd = rest.split(" ", 1)[0].upper()
        sh = "LOGIN" if cmd in ("LOGIN", "AUTHENTICATE") else shape(rest)
        never = bool(NEVER.match(rest))
        if cmd == "IDLE":
            send(b"+ idling\r\n"); f.readline(); send(f"{tag} OK IDLE done\r\n".encode()); note(c=cid, cmd=rest[:120], shape=sh, ok=True); continue
        allv = p["table"].get(sh) or []
        approx = False
        if not allv and "(QRESYNC" in sh.upper():  # cached-state QRESYNC open: answer as the plain open
            allv = p["table"].get(re.sub(r" \(QRESYNC .*$", "", sh, flags=re.I)) or []
            approx = bool(allv)
        variants = [v["items"] for v in allv if v["auth"] == authed] or [v["items"] for v in allv] or None
        if variants is None and cmd == "LOGOUT":
            variants = [[{"line": "* BYE fake"}, {"line": "{TAG} OK LOGOUT"}]]
        if variants is None and cmd == "NOOP":
            variants = [[{"line": "{TAG} OK NOOP"}]]
        if variants is None:
            note(c=cid, cmd=rest[:200], shape=sh, ok=False, unexpected=True, never=never)
            send(f"{tag} BAD unexpected command in strict fake\r\n".encode())
            continue
        # Prefer the variant recorded for this exact command (same UIDs), else the next one in recorded order.
        exact = [v["items"] for v in allv if v.get("cmd") == rest and v["auth"] == authed]
        key = (sh, authed)
        i = used.get(key, 0); used[key] = i + 1
        items = exact[0] if exact else variants[min(i, len(variants) - 1)]
        out = bytearray()
        for it in items:
            if "literal_b64" in it:
                out += base64.b64decode(it["literal_b64"])
            else:
                if out and not out.endswith(b"\r\n") and not re.search(rb"\{\d+\}$", bytes(out)):
                    pass
                out += it["line"].replace("{TAG}", tag, 1).encode("latin-1")
                out += b"" if re.search(r"\{\d+\}$", it["line"]) else b"\r\n"
        # A literal line "{n}" is followed directly by its bytes, then the rest of the line.
        if DELAY:
            time.sleep(DELAY)  # FAKE_DELAY_MS: pace replies like a real server (diagnosis of pipelining timing)
        send(fix_literal_lines(items, tag))
        note(c=cid, cmd=rest[:200], shape=sh, ok=True, never=never, approx=approx)
        if sh == "LOGIN" and b" OK" in fix_literal_lines(items, tag).split(b"\r\n")[-2 if fix_literal_lines(items, tag).endswith(b"\r\n") else -1].upper():
            authed = True
        if cmd == "LOGOUT":
            break
    conn.close()


def fix_literal_lines(items, tag):
    out = bytearray()
    for k, it in enumerate(items):
        if "literal_b64" in it:
            out += base64.b64decode(it["literal_b64"])
            continue
        # Every recorded line lost its CRLF, including a "{n}" literal header: RFC 3501 puts CRLF between
        # "{n}" and the literal bytes. Leaving it out shifted each literal by 2 bytes, which libetpan
        # tolerated until a burst of pipelined literals (indexNewest) broke the parse mid-body.
        out += it["line"].replace("{TAG}", tag, 1).encode("latin-1") + b"\r\n"
    return bytes(out)


srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind((host, port)); srv.listen(8)
print(f"listening {host}:{srv.getsockname()[1]}", flush=True)
n = 0
while True:
    c, _ = srv.accept(); n += 1
    threading.Thread(target=serve, args=(c, n), daemon=True).start()
