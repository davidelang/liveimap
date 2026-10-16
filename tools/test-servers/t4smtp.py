#!/usr/bin/env python3
"""One SMTP send to the lab fake SMTP sink (AcceptanceLabTest#smtpAccepts), IMAP open via greenmail-dev (None TLS). No build, no deploy. The commit is the first argument; it is not checked against a built-in sha."""
import sys, subprocess, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab
COMMIT = sys.argv[1]; RUN = COMMIT; NAME = "greenmail-dev"; PORT = "2525"
LOGDIR = os.environ.get("LAB_LOG") or os.getcwd()
os.makedirs(LOGDIR, exist_ok=True)
log = open(os.path.join(LOGDIR, f"{RUN}.log"), "w", buffering=1)
def say(*a): print(*a); log.write(" ".join(map(str, a)) + "\n")
s = [x for x in lab.servers() if x["name"] == NAME][0]
fake_log = os.path.join(LOGDIR, "ai-fake-smtp.log")
child = os.environ.copy(); child["LAB_LOG"] = fake_log
smtp = subprocess.Popen([sys.executable, os.path.join(HERE, "ai-fake-smtp.py"), PORT], stdout=log, stderr=log, env=child)
lab.only_one(s["container"]); subprocess.run(["docker", "start", s["container"]], capture_output=True)
try:
    say("greeting", lab.greeting(s["imap"], False, timeout=300)); time.sleep(5)
    sr = subprocess.run(["timeout", "900", sys.executable, os.path.join(HERE, "seed_corpus.py"), lab.LAB_HOST, str(s["imap"]), "--user", lab.user_for(s)], capture_output=True, text=True)
    say("seed", sr.returncode, (sr.stdout + sr.stderr).strip().splitlines()[-1:])
    r = lab.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.PKG}.lab.AcceptanceLabTest#smtpAccepts",
                "-e", "labHost", lab.EMU_HOST, "-e", "labPort", str(s["imap"]), "-e", "labTls", "None",
                "-e", "labSmtpPort", PORT, "-e", "labRcpt", f"{lab.user_for(s).split('@')[0]}@lab.test",
                "-e", "labWriteHosts", lab.LAB_HOST, "-e", "labCa", lab.lab_ca(),
                "-e", "labUser", lab.user_for(s), "-e", "labPass", lab.PASS, lab.RUNNER, timeout=600)
    log.write(r.stdout + "\n")
    lc = subprocess.run(["adb", "-s", "emulator-5554", "logcat", "-d", "-s", "AcceptanceLab"], capture_output=True, text=True).stdout
    log.write(lc[-3000:] + "\n")
    say("OK" if "OK (1 test)" in r.stdout else "FAIL")
    say("\n".join(l for l in r.stdout.splitlines() if "stack=" in l or "Exception" in l or "INSTRUMENTATION_STATUS: test" in l)[:1500])
finally:
    subprocess.run(["docker", "stop", "-t", "20", s["container"]], capture_output=True)
    lab.adb("shell", "am", "force-stop", lab.PKG); smtp.terminate()
    try:
        say("FAKESMTP", "".join(l for l in open(fake_log) if f'"port": {PORT}' in l)[-1500:])
    except FileNotFoundError: say("FAKESMTP no log")
