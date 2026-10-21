import sys, subprocess, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab, ports
name, tls, run = sys.argv[1], sys.argv[2], sys.argv[3]
s = [x for x in lab.servers() if x["name"] == name][0]
lab.only_one(s["container"]); subprocess.run(["docker", "start", s["container"]], capture_output=True)
upcmd = lab.up_commands().get(s["container"])  # older images idle in a sleep loop: up.tsv starts the daemons (as lab.py run)
if upcmd: subprocess.run(["docker", "exec", s["container"], "sh", "-c", upcmd], capture_output=True)
try:
    time.sleep(int(os.environ.get("ACC_WAIT", "0"))); up = lab.greeting(s["imap"], False, timeout=900 if s["container"] in lab.ALONE else int(os.environ.get("ACC_UP", "300"))); print("greeting", up)
    if not up:
        sys.exit(1)
    time.sleep(5)
    if tls == "auto":
        tls = lab.resolve_auto(s)
        if isinstance(tls, str) and tls != "StartTls":
            print(tls)
            sys.exit(1)
    if not isinstance(tls, str):
        tls = "None"
    def smtp():
        a = lab.smtp_args(s, tls)
        if a: return a
        # smtp-extra.tsv: same columns as smtp.tsv, for servers smtp.tsv lacks
        for line in open(os.path.join(HERE, "smtp-extra.tsv")):
            f = line.rstrip("\n").split("\t")
            if len(f) == 3 and f[0] == name and f[1] == tls:
                cport = ports.container_port(name, "submission")
                hp = subprocess.run(["docker", "port", s["container"], str(cport)], capture_output=True, text=True).stdout.split("\n")[0].rsplit(":", 1)[-1].strip()
                if hp: return ["-e", "labSmtpPort", hp, "-e", "labRcpt", f"{lab.user_for(s).split('@')[0]}@{f[2]}"]
        return []
    print("smtp", smtp())
    r = lab.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.LAB_PKG}.AcceptanceLabTest#acceptanceMatrix",
                "-e", "accServer", name, "-e", "labHost", lab.EMU_HOST, "-e", "labPort", str(s["imap"]), "-e", "labTls", tls,
                "-e", "labRun", run, *smtp(), *(["-e", "accVariants", os.environ["ACC_VARIANTS"]] if os.environ.get("ACC_VARIANTS") else []), "-e", "labWriteHosts", lab.LAB_HOST, "-e", "labCa", lab.lab_ca(),
                "-e", "labUser", lab.user_for(s), "-e", "labPass", lab.PASS, lab.RUNNER, timeout=1500)
    ok = "OK (1 test)" in r.stdout
    print("OK" if ok else "FAIL")
    dest = os.path.join(lab.REC, name, run); os.makedirs(dest, exist_ok=True)
    for ext in ("txt", "json"):
        out = lab.adb("exec-out", "run-as", lab.PKG, "cat", f"cache/recordings/acceptance-{run}-Full.{ext}").stdout
        open(os.path.join(dest, f"acceptance.{ext}"), "w").write(out)
    print(open(os.path.join(dest, "acceptance.txt")).read()[:6000])
    if not ok:
        print("\n".join(l for l in r.stdout.splitlines() if "stack=" in l or "Exception" in l)[:1500])
finally:
    subprocess.run(["docker", "stop", "-t", "20", s["container"]], capture_output=True)
    lab.adb("shell", "am", "force-stop", lab.PKG)
