#!/usr/bin/env python3
"""Host check of the regress flow judgment. No device and no fakeimap."""
import importlib.machinery
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))


def load():
    path = os.path.join(HERE, "regress")
    loader = importlib.machinery.SourceFileLoader("regress_flow_under_test", path)
    spec = importlib.util.spec_from_loader(loader.name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


def fail(text):
    sys.exit(text)


def run(stopped_step=False, text="Board deck for tomorrow", run_stopped=False):
    return {
        "runs": [{
            "stopped": run_stopped,
            "reason": "not found" if run_stopped or stopped_step else "",
            "flows": [{
                "stopped": run_stopped or stopped_step,
                "steps": [{
                    "id": "subject",
                    "stopped": stopped_step or run_stopped,
                    "message": "not found" if stopped_step or run_stopped else "",
                    "values": {"text": text},
                }],
            }],
        }],
    }


def main():
    mod = load()
    flow = {"steps": [
        {"id": "subject", "read_text": {"text": "Board deck for tomorrow"}, "save_as": "subject"},
    ]}
    ids = mod.flow_save_ids(flow)
    if ids != {"subject": "subject"}:
        fail("save ids %r" % (ids,))
    expected = {"subject": "Board deck for tomorrow"}
    if mod.judge_flow_report(run(), expected, ids):
        fail("match should pass")
    if not mod.judge_flow_report(run(text="other"), expected, ids):
        fail("mismatch should fail")
    if mod.flow_failure(0, run(text="other"), expected, ids) == "":
        fail("exit 0 with a different saved value should fail")
    if not mod.judge_flow_report(run(stopped_step=True), expected, ids):
        fail("stopped step should fail")
    if not mod.judge_flow_report(run(run_stopped=True, text="Board deck for tomorrow"), expected, ids):
        fail("stopped run should fail")
    if not mod.flow_failure(1, run(), expected, ids):
        fail("script-android-app non-zero should fail")
    if mod.flow_failure(0, run(), expected, ids):
        fail("script-android-app zero with a match should pass")
    if not mod.judge_flow_report(None, expected, ids):
        fail("missing report should fail")
    older = run(text="other")
    newer = run()
    both = {"runs": [older["runs"][0], newer["runs"][0]]}
    if mod.judge_flow_report(both, expected, ids):
        fail("latest run is the one that counts")
    reversed_runs = {"runs": [newer["runs"][0], older["runs"][0]]}
    if not mod.judge_flow_report(reversed_runs, expected, ids):
        fail("an older match does not hide a later mismatch")
    if mod.completed_run_code([{"suite": "imap", "result": "FAIL"}]) != 0:
        fail("a non-flow FAIL stays exit 0")
    if mod.completed_run_code([
        {"suite": "flow", "result": "FAIL"},
        {"suite": "imap", "result": "PASS"},
    ]) != 1:
        fail("a flow FAIL exits 1")
    if mod.completed_run_code([{"suite": "flow", "result": "PASS"}]) != 0:
        fail("a flow PASS exits 0")
    for name in ("open-inbox", "broken-fetch"):
        flow_path = os.path.join(REPO, "regress", "flows", name + ".json")
        expected_path = os.path.join(REPO, "regress", "flows", name + ".expected.json")
        live = json.load(open(flow_path, encoding="utf-8"))
        want = json.load(open(expected_path, encoding="utf-8"))
        if live.get("schema") != "capture-flow/1":
            fail(name + " schema")
        for step in live["steps"]:
            if "extra" in step or "launch" in step and step["launch"] is not True:
                fail(name + " sets an activity extra")
        saves = mod.flow_save_ids(live)
        if set(saves) != set(want):
            fail(name + " save_as does not match expected keys")
    print("ok")


if __name__ == "__main__":
    main()
