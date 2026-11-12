#!/usr/bin/env python3
"""Targeted: the app's filter criteria with ESORT PARTIAL windows, against pinned fakeimap
with ESORT and PARTIAL added and sort_partial_count_only false, since no lab server honors
PARTIAL on ESORT (Cyrus ignores it; the Dovecots don't offer PARTIAL).

Usage: esort_partial_run.py [--skip-install] [SERVER (default dovecot-245)]
Passes when the sortWindowCriteria step is ok and the fakeimap command log saw
UID SORT RETURN (PARTIAL 1:3 COUNT) and (PARTIAL 4:6 COUNT), each carrying the UID
criteria.

Fails if the pin version is still UNSELECTED or the binary has not been emerged.
"""
import os, re, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab
import fakeimap_pin


def main():
    fakeimap_pin.require_ready()
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    name = args[0] if args else "dovecot-245"
    commit = lab.head()
    if "--skip-install" not in sys.argv:
        lab.install(commit)
    s = next(x for x in lab.servers() if x["name"] == name)
    pers = fakeimap_pin.personality_path(name)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(lab.REC, name, f"esort-partial-{stamp}")
    os.makedirs(dest, exist_ok=True)
    flog = os.path.join(dest, "fake-log.jsonl")
    folder = "LiveIMAP-Test"
    pfile = fakeimap_pin.derive_personality(
        pers, os.path.join(dest, "personality.json"),
        add_caps=["ESORT", "PARTIAL"], sort_partial_count_only=False)
    ds = fakeimap_pin.write_dataset(os.path.join(dest, "dataset"), folder=folder, count=10,
                                    user=lab.USER, password=lab.PASS)
    control = os.path.join(dest, "control.sock")
    if os.path.exists(control):
        os.remove(control)
    cfg = fakeimap_pin.write_config(
        os.path.join(dest, "config.json"),
        listen=f"{lab.LAB_HOST}:0",
        personality=os.path.abspath(pfile),
        dataset=os.path.abspath(ds),
        control=os.path.abspath(control),
        log_path=os.path.abspath(flog),
    )
    srv = fakeimap_pin.start(cfg, flog + ".err")
    s = dict(s, folder=folder)
    os.environ["LAB_STEPS"] = "sortWindowCriteria"
    run = f"{name}-esort-partial"
    try:
        lab.instrument(s, srv.port("listen"), "None", run, commit, "LOGIN-CMD", side=[])
    except subprocess.TimeoutExpired:
        pass
    finally:
        cmds = fakeimap_pin.command_lines(flog)
        srv.close()
    files = lab.pull(run, dest)
    st = [f for f in files if f.endswith(".steps.tsv")]
    rows = {r.split("\t")[0]: r.split("\t") for r in open(st[0]).read().splitlines()[1:]} if st else {}
    sorts = [c for c in cmds if re.search(r"UID\s+SORT\s+RETURN", c, re.I)]
    problems = []
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
