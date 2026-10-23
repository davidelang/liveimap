import sys, subprocess, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab, ports
name, run = sys.argv[1], sys.argv[2]
row = [l.rstrip("\n").split("\t") for l in open(os.path.join(HERE, "jmap.tsv")) if not l.startswith("#") and l.split("\t")[0] == name][0]
s = [x for x in lab.servers() if x["name"] == name][0]
def expand(cell):
    """jmap.tsv placeholders: LAB_MAIL_DOMAIN = the lab mail domain, USER = the lab user."""
    return cell.replace("LAB_MAIL_DOMAIN", lab.mail_domain()).replace("USER", lab.USER.split("@")[0])
if len(row) != 5 or "" in row:
    sys.exit(f"jmap.tsv {name}: needs name scheme login from rcpt, got {row}")
submit_user, sender, rcpt = (expand(c) for c in row[2:5])
# JMAP_DOMAIN_USER=1: the run's domain-qualified lab account (<user>@LAB_MAIL_DOMAIN), whose Identity has an address.
RUNUSER = (lab.USER.split("@")[0] + "@" + lab.mail_domain()) if os.environ.get("JMAP_DOMAIN_USER") else lab.USER
lab.only_one(s["container"]); subprocess.run(["docker", "start", s["container"]], capture_output=True)
try:
    lab.greeting(s["imap"], False, 300); time.sleep(5)
    problem = lab.entry_version_problem(name, s["imap"])
    if problem:
        print(problem)
        sys.exit(1)
    cport = ports.container_port(name, row[1])
    hp = subprocess.run(["docker", "port", s["container"], str(cport)], capture_output=True, text=True).stdout.split("\n")[0].rsplit(":", 1)[-1].strip()
    field = f"{row[1]}://{lab.EMU_HOST}:{hp}"
    base = f"{row[1]}://{lab.LAB_HOST}:{hp}"
    print("submit user", submit_user, "from", sender, "rcpt", rcpt)
    r = lab.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.LAB_PKG}.JmapLabSuiteTest#jmapSuite",
                "-e", "jmapServer", field, "-e", "jmapRcpt", rcpt, "-e", "jmapSubmitUser", submit_user, "-e", "jmapFrom", sender, "-e", "labHost", lab.EMU_HOST, "-e", "labRun", run, "-e", "labCa", lab.lab_ca(),
                "-e", "labUser", RUNUSER, "-e", "labPass", lab.PASS, lab.RUNNER, timeout=900)
    out = lab.adb("exec-out", "run-as", lab.PKG, "cat", f"cache/recordings/{run}.steps.tsv").stdout
    print(name, field, "OK" if "OK (1 test)" in r.stdout else "FAIL"); print(out)
    dest = os.path.join(lab.REC, name, run); os.makedirs(dest, exist_ok=True)
    open(os.path.join(dest, "jmap.steps.tsv"), "w").write(out)
finally:
    subprocess.run(["docker", "stop", "-t", "20", s["container"]], capture_output=True)
    lab.adb("shell", "am", "force-stop", lab.PKG)
