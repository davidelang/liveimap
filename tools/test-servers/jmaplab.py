import sys, subprocess, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab
name, field_tmpl, run = sys.argv[1], sys.argv[2], sys.argv[3]
s = [x for x in lab.servers() if x["name"] == name][0]
field = field_tmpl.format(host=lab.LAB_HOST, **{k: v for k, v in s.items() if k not in ("host",)})
lab.only_one(s["container"]); subprocess.run(["docker", "start", s["container"]], capture_output=True)
try:
    lab.greeting(s["imap"], "None"); time.sleep(5)
    r = lab.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.PKG}.lab.JmapLabSuiteTest#jmapSuite",
                "-e", "jmapServer", field, "-e", "labHost", lab.EMU_HOST, "-e", "labRun", run, "-e", "labCa", lab.lab_ca(),
                "-e", "labUser", lab.user_for(s), "-e", "labPass", lab.PASS, lab.RUNNER, timeout=600)
    print("OK" if "OK (1 test)" in r.stdout else "FAIL")
    out = lab.adb("exec-out", "run-as", lab.PKG, "cat", f"cache/recordings/{run}.steps.tsv").stdout
    print(out)
    dest = os.path.join(lab.REC, name, run); os.makedirs(dest, exist_ok=True)
    open(os.path.join(dest, "jmap.steps.tsv"), "w").write(out)
    if "OK (1 test)" not in r.stdout:
        print("\n".join(l for l in r.stdout.splitlines() if "stack=" in l or "Exception" in l)[:1500])
finally:
    subprocess.run(["docker", "stop", "-t", "20", s["container"]], capture_output=True)
    lab.adb("shell", "am", "force-stop", lab.PKG)
