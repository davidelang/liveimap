#!/usr/bin/env python3
"""Targeted: client-side sort (no SORT capability) against the strict fake in model mode with SORT/ESORT dropped.
Usage: client_sort_run.py [--skip-install] [SERVER (default dovecot-245)]
Passes when the clientSortSize step is ok, the fake saw no SORT command, and the client order equals the model's
RFC822.SIZE order (ascending, UID tiebreak)."""
import json, os, re, socket, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab, personality
from fake_model import model_size


def main():
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    name = args[0] if args else "dovecot-245"
    commit = lab.head()
    if "--skip-install" not in sys.argv:
        lab.install(commit)
    s = next(x for x in lab.servers() if x["name"] == name)
    pj = os.path.join(lab.GEN, "personalities", f"{name}.json")
    if not os.path.exists(pj):
        json.dump(personality.build(personality.pick(name)), open(pj, "w"), indent=1)
    dest = os.path.join(lab.REC, name, "client-sort-" + time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(dest, exist_ok=True)
    flog = os.path.join(dest, "fake-log.jsonl")
    count = 10
    model = {"folder": "LiveIMAP-Test", "count": count,
             "drop_caps": ["SORT", "ESORT", "SORT=DISPLAY", "THREAD=REFERENCES", "THREAD=ORDEREDSUBJECT"]}
    sock = socket.socket(); sock.bind((lab.LAB_HOST, 0)); port = sock.getsockname()[1]; sock.close()
    fk = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_imap.py"), pj, lab.LAB_HOST, str(port), flog],
                          stdout=subprocess.DEVNULL, stderr=open(flog + ".err", "w"),
                          env=dict(os.environ, FAKE_MODEL=json.dumps(model), LAB_STEPS=""))
    time.sleep(0.8)
    s = dict(s, folder="LiveIMAP-Test")
    os.environ["LAB_STEPS"] = "clientSortSize"
    run = f"{name}-client-sort"
    try:
        lab.instrument(s, port, "None", run, commit, "LOGIN-CMD", side=[])
    finally:
        fk.terminate(); fk.wait()
    files = lab.pull(run, dest)
    st = [f for f in files if f.endswith(".steps.tsv")]
    rows = {r.split("\t")[0]: r.split("\t") for r in open(st[0]).read().splitlines()[1:]} if st else {}
    ev = [json.loads(l) for l in open(flog)] if os.path.exists(flog) else []
    problems = [f"unexpected: {e['cmd'][:70]}" for e in ev if e.get("unexpected")]
    problems += [f"SORT sent: {e['cmd'][:70]}" for e in ev if re.search(r"\bSORT\b", e.get("cmd", ""), re.I)]
    step = rows.get("clientSortSize", ["", "missing", ""])
    expect = sorted(range(1, count + 1), key=lambda u: (model_size(u), u))
    m = re.search(r"order=([\d,]*)", step[2])
    got = [int(x) for x in m.group(1).split(",") if x] if m else []
    if step[1] != "ok":
        problems.append(f"step {step[1]} {step[2][:100]}")
    elif got != expect:
        problems.append(f"order {got} != expected {expect}")
    print(f"{name}\t{'pass' if not problems else 'FAIL'}\t{step[2][:120]}")
    for c in [e["cmd"] for e in ev if "RFC822.SIZE" in e.get("cmd", "").upper()][:3]:
        print("  sent:", c[:120])
    for p in problems:
        print("  problem:", p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
