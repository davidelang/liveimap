#!/usr/bin/env python3
"""Capability-gap table across all lab servers, as Markdown.
Usage: capgap_table.py [OUT.md]. Server columns come from the recordings (every CAPABILITY a server sent,
before or after login, in any real lab run); "exercised" means some app command in those recordings or in
the safety suite (safety-*) used it. Description and recommendation come from capability-notes.tsv; a
capability without a note is listed as "no note yet"."""
import glob, json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab
from fake_run import USES

USES = dict(USES, ENABLE=r"^\S+ ENABLE ", THREAD=r"THREAD (REFERENCES|ORDEREDSUBJECT)", QUOTA=r"GETQUOTA",
            APPENDLIMIT=r"(?!)", MESSAGELIMIT=r"(?!)", RIGHTS=r"MYRIGHTS|GETACL", ACL=r"MYRIGHTS|GETACL",
            PREVIEW=r"PREVIEW", OBJECTID=r"EMAILID|THREADID|MAILBOXID", URLAUTH=r"GENURLAUTH|URLFETCH",
            CHILDREN=r"(?!)", **{"LOGIN-REFERRALS": r"(?!)"})
FAMILY = [(r"^RIGHTS=", "RIGHTS"), (r"^QUOTA(=|SET$)", "QUOTA"), (r"^X-QUOTA", "X-QUOTA"),
          (r"^APPENDLIMIT", "APPENDLIMIT"), (r"^MESSAGELIMIT", "MESSAGELIMIT"), (r"^SAVELIMIT", "SAVELIMIT"),
          (r"^THREAD=", "THREAD"), (r"^PREVIEW", "PREVIEW"), (r"^OBJECTID", "OBJECTID"), (r"^URLAUTH", "URLAUTH"),
          (r"^X-REPLICATION", "X-REPLICATION")]
# Information the server volunteers; no client command uses it.
PASSIVE = {"CHILDREN", "LOGIN-REFERRALS", "MAILBOX-REFERRALS", "NO_ATOMIC_RENAME", "APPENDLIMIT", "MESSAGELIMIT",
           "SAVELIMIT", "RIGHTS"}
SKIP = {"CAPABILITY", "OK", "*", "IMAP4REV1", "IMAP4", "STARTTLS", "LOGINDISABLED", "COMPLETED", "PRE-LOGIN",
        "LOGGED", "IN"}


def family(cap):
    for rx, name in FAMILY:
        if re.match(rx, cap):
            return name
    return cap


def main():
    notes = {}
    for l in open(os.path.join(HERE, "capability-notes.tsv")):
        if l.startswith("#") or not l.strip():
            continue
        c, spec, what, rec = l.rstrip("\n").split("\t")
        notes[c] = (spec, what, rec)
    adv, variants, used_lines = {}, {}, []
    servers = [s["name"] for s in lab.servers()]
    for name in servers:
        for f in glob.glob(os.path.join(lab.REC, name, "2*", "*.jsonl")) + glob.glob(os.path.join(lab.REC, name, "safety-*", "*.jsonl")):
            safety = "safety-" in f
            for l in open(f):
                if not l.startswith("{"):
                    continue
                j = json.loads(l)
                if j.get("dir") == "C" and "line" in j:
                    used_lines.append(j["line"])
                elif not safety and j.get("dir") == "S" and "CAPABILITY" in j.get("line", "").upper():
                    m = re.search(r"CAPABILITY ([^\]]*)", j["line"], re.I)
                    for w in (m.group(1).split() if m else []):
                        w = w.upper()
                        if w in SKIP or w.startswith("AUTH=") or not re.match(r"^[A-Z][A-Z0-9=+\-._]*$", w) or w.endswith("."):
                            continue
                        fam = family(w)
                        adv.setdefault(fam, set()).add(name)
                        if fam != w:
                            variants.setdefault(fam, set()).add(w)
    rows = []
    for cap in sorted(adv, key=lambda c: (-len(adv[c]), c)):
        exercised = "passive" if cap in PASSIVE else any(re.search(USES.get(cap, r"(?!)"), u, re.I) for u in used_lines)
        spec, what, rec = notes.get(cap, ("?", "no note yet", "no note yet"))
        missing = [s for s in servers if s not in adv[cap]]
        names = ("all" if not missing else "all except " + ", ".join(missing)) if len(missing) < len(servers) / 2 \
            else ", ".join(s for s in servers if s in adv[cap])
        var = f" ({', '.join(sorted(variants[cap]))})" if cap in variants else ""
        rows.append(f"| {cap}{var} | {spec} | {len(adv[cap])}/{len(servers)} | {names} | {exercised if exercised == 'passive' else ('yes' if exercised else 'no')} | {what} | {rec} |")
    head = ["| Capability | Spec | Servers | Advertised by | App exercised it in lab | What it does | Recommendation |",
            "|---|---|---|---|---|---|---|"]
    text = "\n".join(head + rows) + "\n"
    out = sys.argv[1] if len(sys.argv) > 1 else None
    if out:
        open(out, "w").write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()
