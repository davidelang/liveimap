#!/usr/bin/env python3
"""Targeted: client-side sort (no SORT capability) against pinned fakeimap with SORT/ESORT dropped.
Usage: client_sort_run.py [--skip-install] [SERVER (default dovecot-245)]

Passes when the clientSortSize step is ok, the fakeimap command log saw no SORT
command, and the client order equals the mailbox's RFC822.SIZE order (ascending,
UID tiebreak) from the control list-mail sizes.

Fails if the pin version is still UNSELECTED or the binary has not been emerged.
"""
import os, re, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab
import fakeimap_pin

DROP = ["SORT", "ESORT", "SORT=DISPLAY", "THREAD=REFERENCES", "THREAD=ORDEREDSUBJECT"]


def main():
    fakeimap_pin.require_ready()
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    name = args[0] if args else "dovecot-245"
    commit = lab.head()
    if "--skip-install" not in sys.argv:
        lab.install(commit)
    s = next(x for x in lab.servers() if x["name"] == name)
    pers = fakeimap_pin.personality_path(name)
    dest = os.path.join(lab.REC, name, "client-sort-" + time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(dest, exist_ok=True)
    flog = os.path.join(dest, "fake-log.jsonl")
    count = 10
    folder = "LiveIMAP-Test"
    pfile = fakeimap_pin.derive_personality(pers, os.path.join(dest, "personality.json"), drop_caps=DROP)
    ds = fakeimap_pin.write_dataset(os.path.join(dest, "dataset"), folder=folder, count=count,
                                    user=lab.USER, password=lab.PASS)
    control = fakeimap_pin.short_control_path("cs")
    cfg = fakeimap_pin.write_config(
        os.path.join(dest, "config.json"),
        listen=f"{lab.LAB_HOST}:0",
        personality=os.path.abspath(pfile),
        dataset=os.path.abspath(ds),
        control=str(control),
        log_path=os.path.abspath(flog),
    )
    srv = fakeimap_pin.start(cfg, flog + ".err")
    s = dict(s, folder=folder)
    os.environ["LAB_STEPS"] = "clientSortSize"
    run = f"{name}-client-sort"
    try:
        items = srv.control({"op": "list-mail", "mailbox": folder}).get("messages") or []
        expect = [int(it["uid"]) for it in sorted(items, key=lambda it: (int(it["size"]), int(it["uid"])))]
        lab.instrument(s, srv.port("listen"), "None", run, commit, "LOGIN-CMD", side=[])
    except subprocess.TimeoutExpired:
        expect = []
    finally:
        cmds = fakeimap_pin.command_lines(flog)
        srv.close()
    files = lab.pull(run, dest)
    st = [f for f in files if f.endswith(".steps.tsv")]
    rows = {r.split("\t")[0]: r.split("\t") for r in open(st[0]).read().splitlines()[1:]} if st else {}
    problems = [f"SORT sent: {c[:70]}" for c in cmds if re.search(r"\bSORT\b", c, re.I)]
    step = rows.get("clientSortSize", ["", "missing", ""])
    m = re.search(r"order=([\d,]*)", step[2])
    got = [int(x) for x in m.group(1).split(",") if x] if m else []
    if step[1] != "ok":
        problems.append(f"step {step[1]} {step[2][:100]}")
    elif got != expect:
        problems.append(f"order {got} != expected {expect}")
    print(f"{name}\t{'pass' if not problems else 'FAIL'}\t{step[2][:120]}")
    for c in [c for c in cmds if "RFC822.SIZE" in c.upper()][:3]:
        print("  sent:", c[:120])
    for p in problems:
        print("  problem:", p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
