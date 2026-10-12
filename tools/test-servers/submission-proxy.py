#!/usr/bin/env python3
"""Lab-only: reach a container's unpublished SMTP submission port, and give it a relay to deliver to.
Usage: submission-proxy.py CONTAINER_IP:PORT LISTEN_PORT SINK_HOST:SINK_PORT SINK_LOG
 - LISTEN_PORT (all interfaces) forwards to CONTAINER_IP:PORT, so the emulator reaches it at 10.0.2.2.
 - SINK_HOST:SINK_PORT is a minimal SMTP server that accepts every message and logs envelope + size
   (JSON lines); point the container's relay at it (dovecot: submission_relay_host/port). Nothing leaves.
Run with LAB_SMTP_HOSTPORT=LISTEN_PORT so lab.py uses the listener instead of a published port."""
import json, socket, sys, threading, time


def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d:
                break
            b.sendall(d)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def forward(listen, target):
    srv = socket.create_server(("0.0.0.0", listen), reuse_port=True)
    while True:
        c, _ = srv.accept()
        try:
            u = socket.create_connection(target, timeout=10)
        except OSError:
            c.close()
            continue
        u.settimeout(None)
        threading.Thread(target=pipe, args=(c, u), daemon=True).start()
        threading.Thread(target=pipe, args=(u, c), daemon=True).start()


def sink_conn(c, log, lock):
    f = c.makefile("rb")
    def say(s):
        c.sendall(s.encode() + b"\r\n")
    say("220 lab-sink ESMTP")
    env = {"from": None, "rcpt": []}
    while True:
        line = f.readline()
        if not line:
            break
        cmd = line.decode("latin-1").rstrip("\r\n")
        v = cmd.split(" ")[0].upper()
        if v in ("EHLO", "HELO", "LHLO"):
            say("250-lab-sink\r\n250-8BITMIME\r\n250 SIZE 50000000")
        elif v == "MAIL":
            env = {"from": cmd[10:], "rcpt": []}
            say("250 ok")
        elif v == "RCPT":
            env["rcpt"].append(cmd[8:])
            say("250 ok")
        elif v == "DATA":
            say("354 go")
            n = 0
            while True:
                d = f.readline()
                if not d or d in (b".\r\n", b".\n"):
                    break
                n += len(d)
            with lock:
                log.write(json.dumps({"t": time.time(), **env, "bytes": n}) + "\n")
                log.flush()
            say("250 queued")
        elif v == "QUIT":
            say("221 bye")
            break
        elif v in ("RSET", "NOOP"):
            say("250 ok")
        else:
            say("502 no")
    c.close()


def sink(host, port, path):
    srv = socket.create_server((host, port), reuse_port=True)
    log, lock = open(path, "a"), threading.Lock()
    while True:
        c, _ = srv.accept()
        threading.Thread(target=sink_conn, args=(c, log, lock), daemon=True).start()


def main():
    th, tp = sys.argv[1].rsplit(":", 1)
    sh, sp = sys.argv[3].rsplit(":", 1)
    threading.Thread(target=sink, args=(sh, int(sp), sys.argv[4]), daemon=True).start()
    forward(int(sys.argv[2]), (th, int(tp)))


if __name__ == "__main__":
    main()
