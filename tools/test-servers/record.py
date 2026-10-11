#!/usr/bin/env python3
"""Read format-1 recordings.
  record.py check FILE...      validate; one TSV row per file
  record.py commands FILE...   command-name counts (client lines), TSV
  record.py shapes FILE...     response shapes per command per server, TSV of commands whose shapes differ
"""
import base64, collections, json, sys


def load(path):
    with open(path, encoding="utf-8") as f:
        rows = [json.loads(l) for l in f if l.strip()]
    if not rows or rows[0].get("type") != "run" or rows[0].get("format") != 1:
        raise ValueError("no format-1 run header")
    for r in rows[1:]:
        if r.get("dir") not in ("C", "S", "E"):
            raise ValueError(f"bad dir {r}")
        if "literal_b64" in r:
            base64.b64decode(r["literal_b64"], validate=True)
    return rows[0], rows[1:]


def cmd_of(line):
    t = line.split(" ")
    if len(t) < 2:
        return ""
    c = t[1].upper()
    if c == "UID" and len(t) > 2:
        c += " " + t[2].upper()
    return c


def main():
    act, files = sys.argv[1], sys.argv[2:]
    if act == "check":
        print("file\tok\tserver\ttool\tC\tS\tE\tliterals\tredacted")
        bad = 0
        for p in files:
            try:
                h, ev = load(p)
                n = collections.Counter(e["dir"] for e in ev)
                print(f"{p}\tok\t{h['server'].get('name')}\t{h.get('tool')}\t{n['C']}\t{n['S']}\t{n['E']}\t"
                      f"{sum('literal_b64' in e for e in ev)}\t{sum('redacted' in e for e in ev)}")
            except Exception as x:
                bad += 1
                print(f"{p}\tFAIL\t{x}")
        sys.exit(1 if bad else 0)
    if act == "commands":
        c = collections.Counter()
        for p in files:
            _, ev = load(p)
            for e in ev:
                if e["dir"] == "C" and "line" in e:
                    c[cmd_of(e["line"])] += 1
        for k, v in sorted(c.items()):
            print(f"{k}\t{v}")
    if act == "shapes":
        # Shape of a reply = the set of untagged kinds (EXISTS, FETCH(FLAGS UID ...), STATUS, ...) plus the tagged
        # status and response code. Numbers, strings and literals are dropped so only structure is compared.
        import re
        def shape(l):
            l = re.sub(r'"(?:[^"\\]|\\.)*"', 'S', l)
            l = re.sub(r"\{\d+\+?\}", "L", l)
            l = re.sub(r"\b\d+(:\d+|,\d+)*\b", "N", l)
            w = l.split()
            if not w: return ""
            if w[0] == "*":
                if len(w) > 2 and w[1] == "N": return f"* N {w[2]}" + (" " + " ".join(t.strip("()") for t in w[3:] if t.strip("()").isupper()) if w[2] == "FETCH" else "")
                return "* " + w[1] + (" " + w[2] if len(w) > 2 and w[2].startswith("[") else "")
            if w[0] == "+": return "+"
            if len(w) < 2: return "TEXT"   # literal continuation or bare line
            return "TAG " + w[1] + (" " + w[2].split("]")[0] + "]" if len(w) > 2 and w[2].startswith("[") else "")
        table = collections.defaultdict(dict)   # cmd -> server -> set(shapes)
        for p in files:
            h, ev = load(p)
            srv = h["server"].get("name") or p
            pend = {}
            for e in ev:
                if e.get("conn", "main") != "main" or "line" not in e: continue
                if e["dir"] == "C":
                    t = e["line"].split(" ", 1)
                    if len(t) == 2: pend[t[0]] = cmd_of(e["line"]); cur = t[0]
                elif e["dir"] == "S" and pend:
                    tag = e["line"].split(" ", 1)[0]
                    c = pend.get(tag) or pend[list(pend)[-1]]
                    table[c].setdefault(srv, set()).add(shape(e["line"]))
                    if tag in pend: del pend[tag]
        print("command\tservers\tdistinct_shapes\tshape\tservers_with_shape")
        for c in sorted(table):
            by = collections.defaultdict(list)
            for srv, sh in table[c].items(): by[" | ".join(sorted(sh))].append(srv)
            if len(by) < 2: continue
            for k, v in sorted(by.items(), key=lambda kv: -len(kv[1])):
                print(f"{c}\t{len(table[c])}\t{len(by)}\t{k[:300]}\t{','.join(sorted(v))}")


if __name__ == "__main__":
    main()
