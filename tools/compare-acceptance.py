#!/usr/bin/env python3
"""Compare two acceptance reports (acceptance-<stamp>-<suite>.json): new failures, fixed, slower by more than 50%.
Usage: compare-acceptance.py OLD.json NEW.json   (exit 1 when NEW has new failures)"""
import json, sys


def rows(path):
    with open(path) as f:
        report = json.load(f)
    key = lambda r: (" / ".join(x for x in (r.get("server", ""), r.get("variant", "")) if x), r["name"])
    return report, {key(r): r for r in report.get("rows", [])}


def main(old_path, new_path):
    old_rep, old = rows(old_path)
    new_rep, new = rows(new_path)
    print(f"old: {old_path} ({old_rep.get('commit', '?')})\nnew: {new_path} ({new_rep.get('commit', '?')})")
    groups = {"new failures": [], "fixed": [], "slower >50%": [], "added": [], "removed": []}
    for k, r in new.items():
        o = old.get(k)
        name = (k[0] + " " if k[0] else "") + k[1]
        if o is None:
            groups["added"].append(f"{name}: {r['result']}")
            continue
        if r["result"] == "fail" and o["result"] != "fail":
            groups["new failures"].append(f"{name}: {r.get('reason', '')}")
        if o["result"] == "fail" and r["result"] == "pass":
            groups["fixed"].append(name)
        om, nm = o.get("ms", -1), r.get("ms", -1)
        if om > 0 and nm > om * 1.5 and nm - om >= 50:
            groups["slower >50%"].append(f"{name}: {om} -> {nm} ms")
    for k in old:
        if k not in new:
            groups["removed"].append((k[0] + " " if k[0] else "") + k[1])
    for title, items in groups.items():
        print(f"{title}: {len(items)}")
        for i in items:
            print("  " + i)
    return 1 if groups["new failures"] else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
