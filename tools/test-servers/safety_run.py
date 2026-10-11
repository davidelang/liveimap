#!/usr/bin/env python3
"""Mail-safety suites on the strict fake in model mode, for every personality (no containers).
Usage: safety_run.py [--skip-install] SERVER... | --all
Each case runs LabSafetyTest with one scenario and settings against fake_imap.py FAKE_MODEL, then judges
the fake's command log:
  - no unexpected command (BAD from the strict fake, incl. a command needing a capability not offered);
  - no plain EXPUNGE ever, CLOSE only with Auto-expunge on, UID MOVE only for MoveMethod=ImapMove with MOVE
    offered, UID EXPUNGE only for DeletePermanently with UIDPLUS, and only of the deleted UID;
  - the messages that disappeared are exactly the ones the scenario and settings intend.
Writes recordings/safety-<stamp>.tsv (one row per case) and prints it."""
import json, os, socket, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab, personality

# name, scenario, settings, drop_caps
CASES = [
    ("view", "view", {}, []),
    ("view-autoexp", "view", {"auto": True}, []),
    ("delete-mark", "delete", {"policy": "MarkDeleted"}, []),
    ("delete-mark-autoexp", "delete", {"policy": "MarkDeleted", "auto": True}, []),
    ("delete-perm", "delete", {"policy": "DeletePermanently"}, []),
    ("delete-trash", "delete", {"policy": "MoveToTrash"}, []),
    ("delete-trash-imapmove", "delete", {"policy": "MoveToTrash", "move": "ImapMove"}, []),
    ("delete-perm-noUIDPLUS", "delete", {"policy": "DeletePermanently"}, ["UIDPLUS"]),
    ("move-copy", "move", {"move": "CopyThenMarkDeleted"}, []),
    ("move-imap", "move", {"move": "ImapMove"}, []),
    ("move-imap-noMOVE", "move", {"move": "ImapMove"}, ["MOVE"]),
    ("leave", "leave", {}, []),
    ("leave-autoexp", "leave", {"auto": True}, []),
    ("leave-noUNSELECT", "leave", {}, ["UNSELECT"]),
    ("outside", "outside", {}, []),
]
SCRIPT = [{"at_noop": 1, "expunge_uid": 5}, {"at_noop": 2, "append": 1}]


def intended(scenario, cfg, caps, uids):
    """UIDs the user asked to remove from the test mailbox, given settings and offered capabilities."""
    auto = cfg.get("auto", False)
    if scenario == "delete":
        perm = cfg.get("policy") == "DeletePermanently" and "UIDPLUS" in caps
        trash_move = cfg.get("policy") == "MoveToTrash" and cfg.get("move") == "ImapMove" and "MOVE" in caps
        return {uids[1]} if perm or trash_move or auto else set()
    if scenario == "move":
        imap_move = cfg.get("move") == "ImapMove" and "MOVE" in caps
        return {uids[2]} if imap_move or auto else set()
    if scenario == "leave":
        return {uids[3]} if auto else set()
    if scenario == "outside":
        return {uids[1]} if auto else set()
    return set()


def judge(ev, scenario, cfg, caps, uids):
    bad = []
    auto = cfg.get("auto", False)
    for e in ev:
        c = e.get("cmd", "").upper()
        w = c.split()
        verb = " ".join(w[:2]) if w[:1] == ["UID"] else (w[0] if w else "")
        if e.get("unexpected"):
            bad.append(f"unexpected: {e['cmd'][:60]} ({e.get('why') or 'not in personality'})")
        if verb == "EXPUNGE":
            bad.append("plain EXPUNGE")
        if verb == "CLOSE" and not auto:
            bad.append("CLOSE with Auto-expunge off")
        if verb == "UID MOVE" and not (cfg.get("move") == "ImapMove" and "MOVE" in caps):
            bad.append("UID MOVE not asked for")
        if verb == "UID EXPUNGE" and not (cfg.get("policy") == "DeletePermanently" and "UIDPLUS" in caps):
            bad.append("UID EXPUNGE not asked for")
    lost = {u for e in ev for (b, u, why) in e.get("expunged", []) if why != "outside" and b != "INBOX"}
    want = intended(scenario, cfg, caps, uids)
    if lost != want:
        bad.append(f"removed {sorted(lost)} expected {sorted(want)}")
    return bad


def main():
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    names = [s["name"] for s in lab.servers()] if args == ["--all"] else args
    stamp = time.strftime("%Y%m%d-%H%M%S")
    commit = lab.head()
    if "--skip-install" not in sys.argv:
        lab.install(commit)
    out = open(os.path.join(lab.REC, f"safety-{stamp}.tsv"), "w")
    out.write("server\tcase\tresult\tapp_steps\tcommands\tproblems\n")
    for name in names:
        s = next(x for x in lab.servers() if x["name"] == name)
        src = personality.pick(name)
        if not src:
            out.write(f"{name}\t-\tno-recording\t\t\t\n"); continue
        pj = os.path.join(lab.GEN, "personalities", f"{name}.json")
        if not os.path.exists(pj):
            json.dump(personality.build(src), open(pj, "w"), indent=1)
        folder = "LiveIMAP-Test"
        seed = os.path.join(os.path.dirname(src), "seed.txt")
        if os.path.exists(seed) and ": " in open(seed).read():
            folder = open(seed).read().split(":", 1)[0].strip()
        dest = os.path.join(lab.REC, name, f"safety-{stamp}")
        os.makedirs(dest, exist_ok=True)
        for case, scenario, cfg, drop in CASES:
            flog = os.path.join(dest, f"{case}.fake.jsonl")
            model = {"folder": folder, "drop_caps": drop, "script": SCRIPT if scenario == "outside" else []}
            sock = socket.socket(); sock.bind((lab.LAB_HOST, 0)); port = sock.getsockname()[1]; sock.close()
            env = dict(os.environ, FAKE_MODEL=json.dumps(model))
            fk = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_imap.py"), pj, lab.LAB_HOST, str(port), flog],
                                  stdout=subprocess.DEVNULL, stderr=open(flog + ".err", "w"), env=env)
            time.sleep(0.7)
            run = f"{name}-safety-{case}"
            a = ["shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.PKG}.lab.LabSafetyTest#safety",
                 "-e", "labHost", lab.EMU_HOST, "-e", "labPort", str(port), "-e", "labServer", name,
                 "-e", "labRun", run, "-e", "labCommit", commit, "-e", "labFolder", folder,
                 "-e", "labScenario", scenario, "-e", "labAutoExpunge", "true" if cfg.get("auto") else "false",
                 "-e", "labMoveMethod", cfg.get("move", "CopyThenMarkDeleted"),
                 "-e", "labDeletePolicy", cfg.get("policy", "MarkDeleted"),
                 "-e", "labUser", lab.user_for(s), "-e", "labPass", lab.PASS, lab.RUNNER]
            try:
                r = lab.adb(*a, timeout=300)
                passed = "OK (1 test)" in r.stdout
            except subprocess.TimeoutExpired:
                passed = False
            finally:
                fk.terminate(); fk.wait()
            files = lab.pull(run, dest)
            st = [f for f in files if f.endswith(".steps.tsv")]
            rows = [l.split("\t") for l in open(st[0]).read().splitlines()[1:]] if st else []
            info = {r[0]: r for r in rows}
            caps = set(info.get("open", ["", "", ""])[2].upper().split()) if "open" in info else set()
            uids = [int(x) for x in info.get("list", ["", "", ""])[2].split(",") if x.strip().isdigit()] if "list" in info else []
            ev = [json.loads(l) for l in open(flog)] if os.path.exists(flog) else []
            problems = []
            if len(uids) < 4:
                problems.append("could not list the test mailbox")
            else:
                problems += judge(ev, scenario, cfg, caps, uids)
            failed_steps = [r[0] + ":" + r[2][:80] for r in rows if len(r) > 2 and r[1] == "fail"]
            if not passed and not failed_steps:
                failed_steps = ["test did not finish"]
            if scenario == "outside" and "afterAppend" in info:
                after = info["afterAppend"][2].split(",")
                if "5" in after:
                    problems.append("app still lists the UID expunged by another client")
                if str(max(uids) + 1) not in after:
                    problems.append("app missed the message added by another client")
            result = "pass" if not problems and not failed_steps else ("unsafe" if problems else "app-fail")
            cmds = " ; ".join(e["cmd"][:40] for e in ev if e.get("model") or e.get("never"))
            out.write(f"{name}\t{case}\t{result}\t{'; '.join(failed_steps)[:300]}\t{cmds[:400]}\t{'; '.join(problems)[:300]}\n")
            out.flush()
            print(name, case, result, "; ".join(problems + failed_steps)[:200], flush=True)
    print(out.name)


if __name__ == "__main__":
    main()
