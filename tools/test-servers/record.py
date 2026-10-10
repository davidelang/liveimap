#!/usr/bin/env python3
"""Read format-1 recordings.
  record.py check FILE...      validate; one TSV row per file
  record.py commands FILE...   command-name counts (client lines), TSV
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


if __name__ == "__main__":
    main()
