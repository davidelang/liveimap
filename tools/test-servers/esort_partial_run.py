#!/usr/bin/env python3
"""Targeted: the app's filter criteria with ESORT PARTIAL windows, against the strict fake in model mode with a
personality that honors PARTIAL on ESORT (fake_model esort_partial + add_caps ESORT PARTIAL), since no lab server
does (Cyrus ignores PARTIAL on ESORT; the Dovecots don't offer PARTIAL).
Usage: esort_partial_run.py [--skip-install] [SERVER (default dovecot-245)]
Passes when the sortWindowCriteria step is ok and the fake saw UID SORT RETURN (PARTIAL 1:3 COUNT) and
(PARTIAL 4:6 COUNT), each carrying the UID criteria, and no unexpected command."""
import json, os, re, socket, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab, personality


def main():
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    name = args[0] if args else "dovecot-245"
    commit = lab.head()
    if "--skip-install" not in sys.argv:
        lab.install(commit)
    s = next(x for x in lab.servers() if x["name"] == name)
    src = personality.pick(name)
    pj = os.path.join(lab.GEN, "personalities", f"{name}.json")
    if not os.path.exists(pj):
        json.dump(personality.build(src), open(pj, "w"), indent=1)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(lab.REC, name, f"esort-partial-{stamp}")
    os.makedirs(dest, exist_ok=True)
    flog = os.path.join(dest, "fake-log.jsonl")
    model = {"folder": "LiveIMAP-Test", "count": 10, "esort_partial": True, "add_caps": ["ESORT", "PARTIAL"]}
    sock = socket.socket(); sock.bind((lab.LAB_HOST, 0)); port = sock.getsockname()[1]; sock.close()
    fk = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_imap.py"), pj, lab.LAB_HOST, str(port), flog],
                          stdout=subprocess.DEVNULL, stderr=open(flog + ".err", "w"),
                          env=dict(os.environ, FAKE_MODEL=json.dumps(model), LAB_STEPS=""))
    time.sleep(0.8)
    s = dict(s, folder="LiveIMAP-Test")
    os.environ["LAB_STEPS"] = "sortWindowCriteria"
    run = f"{name}-esort-partial"
    try:
        res, msg = lab.instrument(s, port, "None", run, commit, "LOGIN-CMD", side=[])
    finally:
        fk.terminate(); fk.wait()
    files = lab.pull(run, dest)
    st = [f for f in files if f.endswith(".steps.tsv")]
    rows = {r.split("\t")[0]: r.split("\t") for r in open(st[0]).read().splitlines()[1:]} if st else {}
    ev = [json.loads(l) for l in open(flog)] if os.path.exists(flog) else []
    sorts = [e["cmd"] for e in ev if re.match(r"UID SORT RETURN", e.get("cmd", ""), re.I)]
    problems = [f"unexpected: {e['cmd'][:70]}" for e in ev if e.get("unexpected")]
    step = rows.get("sortWindowCriteria", ["", "missing", ""])
    if step[1] != "ok" or not step[2].startswith("windows"):
        problems.append(f"step {step[1]} {step[2][:100]}")
    for w in ("PARTIAL 1:3 COUNT", "PARTIAL 4:6 COUNT"):
        if not any(w in c and re.search(r" UID \d", c) for c in sorts):
            problems.append(f"no UID SORT with {w} and UID criteria")
    print(f"{name}\t{'pass' if not problems else 'FAIL'}\t{step[2][:100]}")
    for c in sorts:
        print("  sent:", c[:160])
    for p in problems:
        print("  problem:", p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
