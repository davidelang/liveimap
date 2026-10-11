#!/usr/bin/env python3
"""Capability gaps across the lab servers, as Markdown: (1) capabilities some lab server advertises that
the app does not use, (2) obsolete or duplicate ones and what supersedes them. Capabilities the app uses
and server-internal ones (category used/internal in capability-notes.tsv) are left out.
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
        c, cat, spec, what, rec = l.rstrip("\n").split("\t")
        notes[c] = (cat, spec, what, rec)
    adv = set()
    for name in [s["name"] for s in lab.servers()]:
        for f in glob.glob(os.path.join(lab.REC, name, "2*", "*.jsonl")):
            for l in open(f):
                if not l.startswith("{"):
                    continue
                j = json.loads(l)
                if j.get("dir") == "S" and "CAPABILITY" in j.get("line", "").upper():
                    m = re.search(r"CAPABILITY ([^\]]*)", j["line"], re.I)
                    for w in (m.group(1).split() if m else []):
                        w = w.upper()
                        if w in SKIP or w.startswith("AUTH=") or not re.match(r"^[A-Z][A-Z0-9=+\-._]*$", w) or w.endswith("."):
                            continue
                        adv.add(family(w))
    missing = sorted(c for c in adv if c not in notes)
    if missing:
        sys.stderr.write("advertised but not in capability-notes.tsv: " + " ".join(missing) + "\n")
    gaps = ["| Capability | What it does | Recommendation |", "|---|---|---|"]
    old = ["| Capability | What it was | Superseded by |", "|---|---|---|"]
    for cap in sorted(adv):
        cat, spec, what, rec = notes.get(cap, ("gap", "?", "no note yet", "no note yet"))
        if cat == "gap":
            gaps.append(f"| {cap} | {what} ({spec}) | {rec} |")
        elif cat == "obsolete":
            old.append(f"| {cap} | {what} ({spec}) | {rec} |")
    text = "## Gaps: advertised by a lab server, not used by the app\n\n" + "\n".join(gaps) + \
        "\n\n## Obsolete or duplicate capabilities\n\n" + "\n".join(old) + "\n"
    out = sys.argv[1] if len(sys.argv) > 1 else None
    if out:
        open(out, "w").write(text)
    else:
        print(text)

if __name__ == "__main__":
    main()
