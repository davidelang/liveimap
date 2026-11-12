#!/usr/bin/env python3
"""Mail-safety suites on pinned fakeimap, for every lab-server personality (no containers).
Usage: safety_run.py [--skip-install] SERVER... | --all

Each case runs LabSafetyTest with one scenario and settings against fakeimap,
then judges the command log and the mailbox (via the control socket):

  - no plain EXPUNGE ever, CLOSE only with Auto-expunge on, UID MOVE only for
    MoveMethod=ImapMove with MOVE offered, UID EXPUNGE only for DeletePermanently
    with UIDPLUS, and only of the deleted UID;
  - the messages that disappeared (list-mail before vs after) are exactly the
    ones the scenario and settings intend.

drop_caps is applied by writing a derived personality (fakeimap has no drop_caps
control op). The "outside" case is recorded as unsupported: fakeimap can inject
a message but has no expunge control op and no at_noop hook (see fakeimap_pin.py).
The old unexpected-command log (BAD from the strict replay fake) has no
equivalent; it is not treated as a pass.

Fails if the pin version is still UNSELECTED or the binary has not been emerged.

Writes recordings/safety-<stamp>.tsv (one row per case) and prints it.
"""
import json, os, re, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab
import fakeimap_pin

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


def intended(scenario, cfg, caps, uids):
    """UIDs the user asked to remove from the test mailbox, given settings and offered capabilities."""
    auto = cfg.get("auto", False)
    if scenario == "delete":
        perm = cfg.get("policy") == "DeletePermanently" and "UIDPLUS" in caps
        trash_move = (cfg.get("policy") == "MoveToTrash" and cfg.get("move") == "ImapMove" and "MOVE" in caps
                      and "SPECIAL-USE" in caps and "LIST-EXTENDED" in caps)
        return {uids[1]} if perm or trash_move or auto else set()
    if scenario == "move":
        imap_move = cfg.get("move") == "ImapMove" and "MOVE" in caps
        return {uids[2]} if imap_move or auto else set()
    if scenario == "leave":
        return {uids[3]} if auto else set()
    if scenario == "outside":
        return {uids[1]} if auto else set()
    return set()


def caps_from_personality(path):
    data = json.loads(open(path).read())
    caps = set()
    for key in ("greeting_capabilities", "pre_login_capabilities", "post_login_capabilities"):
        for c in data.get(key) or []:
            caps.add(c.upper())
    return caps


def list_uids(srv, mailbox):
    items = srv.control({"op": "list-mail", "mailbox": mailbox}).get("messages") or []
    return [int(it["uid"]) for it in items]


def judge_commands(cmds, scenario, cfg, caps):
    """Command text may be the IMAP command or a tagged line; accept both forms."""
    bad = []
    auto = cfg.get("auto", False)
    for c in cmds:
        u = c.upper()
        if re.search(r"(^|\s)EXPUNGE\b", u) and not re.search(r"(^|\s)UID\s+EXPUNGE\b", u):
            bad.append("plain EXPUNGE")
        if re.search(r"(^|\s)CLOSE\b", u) and not auto:
            bad.append("CLOSE with Auto-expunge off")
        if re.search(r"(^|\s)UID\s+MOVE\b", u) and not (cfg.get("move") == "ImapMove" and "MOVE" in caps):
            bad.append("UID MOVE not asked for")
        if re.search(r"(^|\s)UID\s+EXPUNGE\b", u) and not (cfg.get("policy") == "DeletePermanently" and "UIDPLUS" in caps):
            bad.append("UID EXPUNGE not asked for")
    return bad

def main():
    fakeimap_pin.require_ready()
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    names = [s["name"] for s in lab.servers()] if args == ["--all"] else args
    stamp = time.strftime("%Y%m%d-%H%M%S")
    commit = lab.head()
    if "--skip-install" not in sys.argv:
        lab.install(commit)
    out = open(os.path.join(lab.REC, f"safety-{stamp}.tsv"), "w")
    out.write("server\tcase\tresult\tapp_steps\tcommands\tproblems\n")
    for name in names:
        problem = lab.entry_version_problem(name, None)
        if problem:
            print(problem)
            sys.exit(1)
        s = next(x for x in lab.servers() if x["name"] == name)
        pers = fakeimap_pin.PERSONALITIES / f"{name}.json"
        if not pers.is_file():
            out.write(f"{name}\t-\tno-personality\t\t\t\n"); out.flush()
            print(name, "-", "no-personality", str(pers), flush=True)
            continue
        folder = "LiveIMAP-Test"
        dest = os.path.join(lab.REC, name, f"safety-{stamp}")
        os.makedirs(dest, exist_ok=True)
        for case, scenario, cfg, drop in CASES:
            if scenario == "outside":
                msg = "unsupported: fakeimap has no at_noop expunge/append control op"
                out.write(f"{name}\t{case}\tunsupported\t\t\t{msg}\n"); out.flush()
                print(name, case, "unsupported", msg, flush=True)
                continue
            flog = os.path.join(dest, f"{case}.fake.jsonl")
            pfile = pers
            if drop:
                pfile = fakeimap_pin.derive_personality(
                    pers, os.path.join(dest, f"{case}.personality.json"), drop_caps=drop)
            ds = fakeimap_pin.write_dataset(os.path.join(dest, f"{case}.dataset"), folder=folder,
                                            user=lab.USER, password=lab.PASS)
            control = os.path.join(dest, f"{case}.control.sock")
            if os.path.exists(control):
                os.remove(control)
            cfgpath = fakeimap_pin.write_config(
                os.path.join(dest, f"{case}.config.json"),
                listen=f"{lab.LAB_HOST}:0",
                personality=os.path.abspath(pfile),
                dataset=os.path.abspath(ds),
                control=os.path.abspath(control),
                log_path=os.path.abspath(flog),
            )
            srv = fakeimap_pin.start(cfgpath, flog + ".err")
            run = f"{name}-safety-{case}"
            a = ["shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.LAB_PKG}.LabSafetyTest#safety",
                 "-e", "labHost", lab.EMU_HOST, "-e", "labPort", str(srv.port("listen")), "-e", "labServer", name,
                 "-e", "labRun", run, "-e", "labCommit", commit, "-e", "labFolder", folder,
                 "-e", "labScenario", scenario, "-e", "labAutoExpunge", "true" if cfg.get("auto") else "false",
                 "-e", "labMoveMethod", cfg.get("move", "CopyThenMarkDeleted"),
                 "-e", "labDeletePolicy", cfg.get("policy", "MarkDeleted"),
                 "-e", "labUser", lab.USER, "-e", "labPass", lab.PASS, lab.RUNNER]
            before, after, passed, cmds = [], [], False, []
            try:
                before = list_uids(srv, folder)
                r = lab.adb(*a, timeout=300)
                passed = "OK (1 test)" in r.stdout
                after = list_uids(srv, folder)
            except subprocess.TimeoutExpired:
                passed = False
            finally:
                cmds = fakeimap_pin.command_lines(flog)
                srv.close()
            files = lab.pull(run, dest)
            st = [f for f in files if f.endswith(".steps.tsv")]
            rows = [l.split("\t") for l in open(st[0]).read().splitlines()[1:]] if st else []
            info = {r[0]: r for r in rows}
            caps = caps_from_personality(pfile)
            uids = before if len(before) >= 4 else (
                [int(x) for x in info.get("list", ["", "", ""])[2].split(",") if x.strip().isdigit()] if "list" in info else [])
            problems = []
            if len(uids) < 4:
                problems.append("could not list the test mailbox")
            else:
                problems += judge_commands(cmds, scenario, cfg, caps)
                lost = set(uids) - set(after)
                want = intended(scenario, cfg, caps, uids)
                if lost != want:
                    problems.append(f"removed {sorted(lost)} expected {sorted(want)}")
            failed_steps = [r[0] + ":" + r[2][:80] for r in rows if len(r) > 2 and r[1] == "fail"]
            if not passed and not failed_steps:
                failed_steps = ["test did not finish"]
            result = "pass" if not problems and not failed_steps else ("unsafe" if problems else "app-fail")
            cmdsum = " ; ".join(c[:40] for c in cmds)
            out.write(f"{name}\t{case}\t{result}\t{'; '.join(failed_steps)[:300]}\t{cmdsum[:400]}\t{'; '.join(problems)[:300]}\n")
            out.flush()
            print(name, case, result, "; ".join(problems + failed_steps)[:200], flush=True)
    print(out.name)


if __name__ == "__main__":
    main()
