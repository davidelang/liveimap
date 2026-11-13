#!/usr/bin/env python3
"""One SMTP send to the lab fakeimap SMTP sink (AcceptanceLabTest#smtpAccepts), IMAP open via dovecot-dev (None TLS). No build, no deploy. The commit is the first argument; it is not checked against a built-in sha."""
import json, sys, subprocess, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab, fakeimap_pin
COMMIT = sys.argv[1]; RUN = COMMIT; NAME = "dovecot-dev"; PORT = "2525"
LOGDIR = os.environ.get("LAB_LOG") or os.getcwd()
os.makedirs(LOGDIR, exist_ok=True)
log = open(os.path.join(LOGDIR, f"{RUN}.log"), "w", buffering=1)
def say(*a): print(*a); log.write(" ".join(map(str, a)) + "\n")
s = [x for x in lab.servers() if x["name"] == NAME][0]
work = os.path.join(LOGDIR, f"{RUN}-fakesmtp")
smtp = fakeimap_pin.start_smtp_sink(
    work,
    f"0.0.0.0:{PORT}",
    os.path.join(work, "fakeimap.err"),
    log_path=os.path.join(work, "fakeimap.log"),
)
lab.only_one(s["container"]); subprocess.run(["docker", "start", s["container"]], capture_output=True)
try:
    up = lab.greeting(s["imap"], False, timeout=300); say("greeting", up)
    if not up:
        sys.exit(1)
    problem = lab.entry_version_problem(NAME, s["imap"])
    if problem:
        say(problem)
        sys.exit(1)
    time.sleep(5)
    r = lab.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.LAB_PKG}.AcceptanceLabTest#smtpAccepts",
                "-e", "labHost", lab.EMU_HOST, "-e", "labPort", str(s["imap"]), "-e", "labTls", "None",
                "-e", "labSmtpPort", PORT, "-e", "labRcpt", f"{lab.USER.split('@')[0]}@lab.test",
                "-e", "labWriteHosts", lab.LAB_HOST, "-e", "labCa", lab.lab_ca(),
                "-e", "labUser", lab.USER, "-e", "labPass", lab.PASS, lab.RUNNER, timeout=600)
    log.write(r.stdout + "\n")
    lc = subprocess.run(["adb", "-s", "emulator-5554", "logcat", "-d", "-s", "AcceptanceLab"], capture_output=True, text=True).stdout
    log.write(lc[-3000:] + "\n")
    say("OK" if "OK (1 test)" in r.stdout else "FAIL")
    say("\n".join(l for l in r.stdout.splitlines() if "stack=" in l or "Exception" in l or "INSTRUMENTATION_STATUS: test" in l)[:1500])
finally:
    subprocess.run(["docker", "stop", "-t", "20", s["container"]], capture_output=True)
    lab.adb("shell", "am", "force-stop", lab.PKG)
    try:
        msgs = smtp.control({"op": "list-smtp"}).get("messages") or []
        say("FAKESMTP", json.dumps(msgs)[:1500])
    except Exception as e:
        say("FAKESMTP no list-smtp", type(e).__name__)
    smtp.close()
