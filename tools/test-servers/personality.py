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
    greeting, table, order = None, {}, []
    cur = None  # (shape, tag, items)
    for r in rows:
        d = r.get("dir")
        if d == "S" and greeting is None and "line" in r:
            greeting = scrub_caps(r["line"])
            continue
        if d == "C" and "line" in r and cur is None:
            line = r["line"]
            tag, _, rest = line.partition(" ")
            if rest.upper().startswith("STARTTLS"):
                cur = ("__skip__", tag, [])
            else:
                cur = (shape(rest), tag, [])
            continue
        if d == "C" and "redacted" in r and "line" not in r and cur is None:
            cur = ("LOGIN", None, [])
            continue
        if d == "C" and cur is not None:
            continue  # continuation data (e.g. AUTHENTICATE response) - not replayed
        if d == "S" and cur is not None:
            sh, tag, items = cur
            if "literal_b64" in r:
                items.append({"literal_b64": r["literal_b64"]})
                continue
            line = r.get("line", "")
            first = line.split(" ", 1)[0]
            done = (tag is not None and first == tag) or (tag is None and first not in ("*", "+") and len(line.split(" ")) > 1 and line.split(" ")[1] in ("OK", "NO", "BAD"))
            items.append({"line": scrub_caps("{TAG}" + line[len(first):] if done else line)})
            if done:
                if sh != "__skip__":
                    table.setdefault(sh, []).append(items)
                    order.append(sh)
                cur = None
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
