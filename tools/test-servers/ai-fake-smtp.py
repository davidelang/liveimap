#!/usr/bin/env python3
"""Lab-only SMTP sink: listens on given ports, advertises DSN on ports marked +dsn, logs envelope commands, relays nothing."""
import os, socket, sys, threading, time, json
# LAB_LOG names the log file. Unset: ai-fake-smtp.log in the working directory.
LOG = open(os.environ.get("LAB_LOG") or "ai-fake-smtp.log", "a", buffering=1)
def serve(conn, port, dsn):
    f = conn.makefile("rb")
    def say(s): conn.sendall((s + "\r\n").encode())
    def log(**k): LOG.write(json.dumps(dict(t=time.strftime("%H:%M:%S"), port=port, **k)) + "\n")
    say("220 fake-lab ESMTP")
    while True:
        line = f.readline()
        if not line: break
        cmd = line.decode("utf-8", "replace").rstrip("\r\n")
        up = cmd.upper()
        if up.startswith(("EHLO", "HELO")):
            caps = ["8BITMIME", "SMTPUTF8", "PIPELINING"] + (["DSN"] if dsn else [])
            for c in caps: say("250-" + c)
            say("250 SIZE 10000000")
        elif up.startswith(("MAIL", "RCPT")):
            log(cmd=cmd); say("250 2.1.0 ok")
        elif up == "DATA":
            say("354 go"); n = 0; hdr = []
            while True:
                d = f.readline()
                if not d or d in (b".\r\n", b".\n"): break
                n += len(d)
                if len(hdr) < 40 and d.strip(): hdr.append(d.decode("utf-8", "replace").rstrip())
            log(data_bytes=n, headers=[h for h in hdr if h.split(":")[0] in ("From", "Reply-To", "To", "Subject", "X-Priority")])
            say("250 2.0.0 queued (discarded)")
        elif up == "QUIT":
            say("221 bye"); break
        elif up.startswith("RSET") or up.startswith("NOOP"):
            say("250 ok")
        else:
            say("502 no")
    conn.close()
def listen(spec):
    port = int(spec.split("+")[0]); dsn = spec.endswith("+dsn")
    s = socket.create_server(("0.0.0.0", port), reuse_port=True)
    while True:
        c, _ = s.accept(); threading.Thread(target=serve, args=(c, port, dsn), daemon=True).start()
for spec in sys.argv[1:]: threading.Thread(target=listen, args=(spec,), daemon=True).start()
while True: time.sleep(3600)
