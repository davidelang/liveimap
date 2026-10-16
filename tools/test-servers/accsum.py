import glob, collections, os, re
HERE = os.path.dirname(os.path.abspath(__file__))
REC = os.environ.get("LAB_REC") or os.path.join(HERE, "recordings")
fails = collections.defaultdict(set)
print(f"{'server':16} {'P':>4} {'F':>4} {'S':>4}  tls")
for f in sorted(glob.glob(os.path.join(REC, "*", "acc*-*", "acceptance.txt"))):
    t = open(f).read(); rel = os.path.relpath(f, REC); srv = rel.split(os.sep)[0]
    srv += ("*" if "/acc2-" in f else "") + (" [a65a]" if "/acc3-" in f else "") + (" [b2]" if "/acc4-" in f else "")
    m = re.search(r"# summary: (\d+) pass, (\d+) fail, (\d+) skip", t); imap = re.search(r"# imap: \S+ (\S+)", t)
    print(f"{srv:16} {m[1]:>4} {m[2]:>4} {m[3]:>4}  {imap[1] if imap else ''}" if m else f"{srv:16} (no summary)")
    for line in t.splitlines():
        p = line.split("\t")
        if len(p) == 5 and p[3] == "fail":
            fails[(p[2], re.sub(r"\(.*?secs\)", "", p[4])[:110].strip())].add(srv)
print("\ndistinct failures:")
for (test, why), s in sorted(fails.items()): print(f"- {test}: {why}  [{', '.join(sorted(s))}]")
