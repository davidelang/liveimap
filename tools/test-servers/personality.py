#!/usr/bin/env python3
"""Phase D: derive a fake-server personality from one server's newest app recording.
Usage: personality.py SERVER [OUT.json]. Picks the plaintext LOGIN-command run if present, else the plain
automatic run, else a TLS run (its post-TLS exchange is served in plaintext by the fake).
The personality is a case table keyed by command shape (tag dropped, numbers -> N); each shape keeps its
response variants in recorded order. Greeting/CAPABILITY drop STARTTLS, LOGINDISABLED and AUTH= so the fake
(plaintext, LOGIN only) stays usable; that is the one deliberate deviation from the real server."""
import base64, glob, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REC = os.path.join(HERE, "recordings")


def shape(cmd):
    cmd = re.sub(r"\{\d+\+?\}$", "{L}", cmd.strip())
    cmd = re.sub(r"\d+", "N", cmd)
    return re.sub(r"\s+", " ", cmd).upper() if cmd.split(" ", 1)[0].upper() in ("LOGIN", "AUTHENTICATE") else re.sub(r"\s+", " ", cmd)


def scrub_caps(line):
    if "CAPABILITY" not in line.upper():
        return line
    return re.sub(r" (STARTTLS|LOGINDISABLED|AUTH=\S+?)(?=[ \]]|$)", "", line, flags=re.I)


def pick(server):
    dirs = sorted(d for d in glob.glob(os.path.join(REC, server, "2*")) if glob.glob(os.path.join(d, f"{server}-*.jsonl")))
    for d in reversed(dirs):
        for pat in ("None-LOGIN-CMD", "None", "Implicit", "StartTls", "Implicit-LOGIN-CMD", "StartTls-LOGIN-CMD"):
            f = os.path.join(d, f"{server}-{pat}.jsonl")
            st = f[:-6] + ".steps.tsv"
            if os.path.exists(f) and os.path.exists(st) and "\tok\t" in open(st).read().split("\n", 2)[1] + "\t":
                return f
    return None


def build(path):
    rows = [json.loads(l) for l in open(path) if l.strip().startswith("{")]
    head = rows[0]
    rows = [r for r in rows[1:] if r.get("conn") == "main"]
    # After STARTTLS the exchange restarts; keep from the last tls/starttls point is not needed: the fake simply
    # never sees STARTTLS (scrubbed from caps), so drop the STARTTLS command itself.
    # Commands may be pipelined: pair responses by tag; untagged lines go to the oldest pending command.
    greeting, table, order = None, {}, []
    pending = []  # [shape, tag, items, authenticated]
    authed = False
    for r in rows:
        d = r.get("dir")
        if d == "E" and r.get("event") == "connect":
            authed, pending = False, []
            continue
        if d == "S" and greeting is None and "line" in r:
            greeting = scrub_caps(r["line"])
            continue
        if d == "C" and "line" in r:
            tag, _, rest = r["line"].partition(" ")
            if re.match(r"^[A-Za-z0-9.]+$", tag) and rest:
                pending.append(["__skip__" if rest.upper().startswith("STARTTLS") else shape(rest), tag, [], authed])
            continue
        if d == "C" and "redacted" in r and "line" not in r:
            if not pending or pending[-1][0] != "LOGIN":
                pending.append(["LOGIN", None, [], authed])
            continue
        if d != "S" or not pending:
            continue
        if "literal_b64" in r:
            pending[0][2].append({"literal_b64": r["literal_b64"]})
            continue
        line = r.get("line", "")
        first = line.split(" ", 1)[0]
        hit = next((c for c in pending if c[1] == first), None)
        if hit is None and first not in ("*", "+") and len(line.split(" ")) > 1 and line.split(" ")[1] in ("OK", "NO", "BAD"):
            hit = next((c for c in pending if c[1] is None), None)
        if hit is None:
            pending[0][2].append({"line": scrub_caps(line)})
            continue
        hit[2].append({"line": scrub_caps("{TAG}" + line[len(first):])})
        if hit[0] != "__skip__":
            # Variants are kept per login state, so a post-login CAPABILITY never replays the pre-login one.
            table.setdefault(hit[0], []).append({"auth": hit[3], "items": hit[2]})
            order.append(hit[0])
            if hit[0] == "LOGIN" and " OK" in line.upper():
                authed = True
        pending.remove(hit)
    return {"server": head["server"]["name"], "source": os.path.relpath(path, HERE), "commit": head.get("commit"),
            "greeting": greeting, "table": table, "order": order}


if __name__ == "__main__":
    server = sys.argv[1]
    src = pick(server)
    if not src:
        sys.exit(f"{server}: no usable recording")
    p = build(src)
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "generated", "personalities", f"{server}.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump(p, open(out, "w"), indent=1)
    print(f"{server}\t{p['source']}\tshapes={len(p['table'])}\tcommands={len(p['order'])}")
