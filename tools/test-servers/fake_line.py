#!/usr/bin/env python3
"""Phase E (SMTP, ManageSieve): strict scripted fakes built from a lab recording's "smtp" or "sieve" connection.
Usage:  fake_line.py build PROTO RECORDING.jsonl OUT.json      (PROTO = smtp | sieve)
        fake_line.py serve PERSONALITY.json HOST PORT LOG.jsonl
Plaintext only: the exchange is taken from after STARTTLS (the post-TLS EHLO / capability block, with STARTTLS
itself removed), so the app runs the same dialogue without TLS. Commands are matched by verb; variants are
served in recorded order (sticking at the last). Unknown verbs get an error reply and are logged unexpected.
SMTP DATA bodies are swallowed up to the "." line and logged (size only)."""
import base64, json, re, socket, sys, threading

SIEVE_VERBS = {"AUTHENTICATE", "STARTTLS", "LOGOUT", "CAPABILITY", "HAVESPACE", "PUTSCRIPT", "LISTSCRIPTS",
               "SETACTIVE", "GETSCRIPT", "DELETESCRIPT", "RENAMESCRIPT", "CHECKSCRIPT", "NOOP", "UNAUTHENTICATE"}


def verb(proto, line, redacted=False):
    if line is None:
        return "AUTH" if proto == "smtp" else "AUTHENTICATE"
    w = line.split()
    if proto == "smtp":
        if not w:
            return ""
        v = w[0].upper()
        return f"{v} {w[1].split(':')[0].upper()}" if v in ("MAIL", "RCPT") and len(w) > 1 else v.split(":")[0]
    for t in w[:2]:
        if t.upper().strip('"') in SIEVE_VERBS:
            return t.upper().strip('"')
    return w[0].upper() if w else ""


def build(proto, path):
    rows = [json.loads(l) for l in open(path) if l.strip().startswith("{")]
    rows = [r for r in rows if r.get("conn") == proto and r.get("dir") in ("C", "S")]
    # Restart after STARTTLS: everything the server says after its STARTTLS reply is the plaintext script.
    cut = 0
    for i, r in enumerate(rows):
        if r["dir"] == "C" and verb(proto, r.get("line")) == "STARTTLS":
            cut = i + 2
    tls = cut > 0
    first = next((r.get("line", "") for r in rows if r["dir"] == "S"), "220 fake ESMTP")
    rows = rows[cut:]
    greeting, table, cur, in_data = [], {}, None, False
    for r in rows:
        if r["dir"] == "S":
            line = r.get("line", "")
            if re.search(r"STARTTLS", line, re.I) and (line.startswith("250-") or line.startswith('"STARTTLS"')):
                continue
            (cur[1] if cur else greeting).append(line)
            continue
        if in_data:
            if r.get("line") == ".":
                in_data = False
                cur = ["DATA-END", []]; table.setdefault("DATA-END", []).append(cur[1])
            continue
        if "literal_b64" in r or (r.get("line") == "" and proto == "sieve"):
            continue
        v = verb(proto, r.get("line"), "redacted" in r)
        cur = [v, []]; table.setdefault(v, []).append(cur[1])
        if proto == "smtp" and v == "DATA":
            in_data = True
    if proto == "smtp" and tls:
        greeting = [first] + greeting
    if proto == "smtp" and table.get("QUIT") == [[]]:
        table["QUIT"] = [["221 2.0.0 Bye (not recorded: the client closed first)"]]
    return {"proto": proto, "source": path, "greeting": greeting, "table": table}


def serve(p, conn, cid, log, lock):
    proto, used = p["proto"], {}
    f = conn.makefile("rb")
    def send(lines):
        conn.sendall("".join(l + "\r\n" for l in lines).encode("latin-1"))
    def note(**kw):
        with lock:
            log.write(json.dumps({"c": cid, **kw}) + "\n"); log.flush()
    send(p["greeting"])
    last = None
    while True:
        raw = f.readline()
        if not raw:
            break
        line = raw.decode("latin-1").rstrip("\r\n")
        m = re.search(r"\{(\d+)(\+?)\}$", line)
        if proto == "sieve" and m:
            if not m.group(2):
                send(['OK ""'])
            f.read(int(m.group(1))); f.readline()
        v = verb(proto, line)
        if proto == "smtp" and last and last[0] in ("AUTH",) and last[1].startswith("334"):
            v = "AUTH"  # SASL continuation line
        if proto == "sieve" and last and last[0] == "AUTHENTICATE" and last[1].startswith("{") or (proto == "sieve" and line.startswith('"') and last and last[0] == "AUTHENTICATE"):
            v = "AUTHENTICATE"
        allv = p["table"].get(v)
        if not allv:
            note(cmd=line[:120] if v not in ("AUTH", "AUTHENTICATE") else v, verb=v, unexpected=True)
            send(["500 5.5.1 unexpected command in strict fake"] if proto == "smtp" else ['NO "unexpected command in strict fake"'])
            continue
        i = used.get(v, 0); used[v] = i + 1
        out = allv[min(i, len(allv) - 1)]
        if proto == "smtp" and v == "DATA" and out and out[-1].startswith("354"):
            send(out)
            n = 0
            while True:
                b = f.readline()
                if not b or b.rstrip(b"\r\n") == b".":
                    break
                n += len(b)
            note(cmd="DATA", verb="DATA", body_bytes=n)
            out = (p["table"].get("DATA-END") or [["250 2.0.0 OK"]])[0]
            v = "DATA-END"
        send(out)
        note(cmd=line[:120] if v not in ("AUTH", "AUTHENTICATE") else v, verb=v)
        last = (v, out[-1] if out else "")
        if v in ("QUIT", "LOGOUT"):
            break
    conn.close()


def main():
    if sys.argv[1] == "build":
        json.dump(build(sys.argv[2], sys.argv[3]), open(sys.argv[4], "w"), indent=1)
        return
    p = json.load(open(sys.argv[2]))
    host, port, log = sys.argv[3], int(sys.argv[4]), open(sys.argv[5], "a")
    lock = threading.Lock()
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port)); srv.listen(8)
    n = 0
    while True:
        c, _ = srv.accept(); n += 1
        threading.Thread(target=serve, args=(p, c, n, log, lock), daemon=True).start()


if __name__ == "__main__":
    main()
