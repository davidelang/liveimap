import sys, subprocess, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab
name, run = sys.argv[1], sys.argv[2]
row = [l.rstrip("\n").split("\t") for l in open(os.path.join(HERE, "jmap.tsv")) if not l.startswith("#") and l.split("\t")[0] == name][0]
s = [x for x in lab.servers() if x["name"] == name][0]
def derive_rcpt(base, user):
    """The recipient the server itself names: its JMAP Identity email, else the session username (no guessing)."""
    import base64, json, ssl, urllib.request
    auth = "Basic " + base64.b64encode(f"{user}:{lab.PASS}".encode()).decode()
    ctx = ssl.create_default_context(cadata=open(os.path.join(lab.GEN, "ca", "ca.der"), "rb").read()); ctx.check_hostname = False
    def req(url, body=None):
        r = urllib.request.Request(url, data=body, headers={"Authorization": auth, "Content-Type": "application/json"})
        return json.loads(urllib.request.urlopen(r, context=ctx if url.startswith("https") else None, timeout=20).read())
    sess = req(base + "/.well-known/jmap")
    api = sess["apiUrl"] if sess["apiUrl"].startswith("http") else base + sess["apiUrl"]
    acct = sess.get("primaryAccounts", {}).get("urn:ietf:params:jmap:mail", "")
    try:
        ids = req(api, json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:submission"], "methodCalls": [["Identity/get", {"accountId": acct}, "0"]]}).encode())
        emails = [i["email"] for i in ids["methodResponses"][0][1].get("list", []) if i.get("email")]
        if emails: return emails[0]
        # No identity carries an address. Use LAB_MAIL_DOMAIN; stop when that key is missing.
        local = sess.get("username", user).split("@")[0]
        host = lab.HOSTENV.get("LAB_MAIL_DOMAIN")
        if not host:
            sys.exit("needs LAB_MAIL_DOMAIN")
        if host:
            made = req(api, json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:submission"], "methodCalls": [["Identity/set", {"accountId": acct, "create": {"n": {"name": local, "email": f"{local}@{host}"}}}, "0"]]}).encode())
            created = made["methodResponses"][0][1].get("created") or {}
            if "n" in created: return f"{local}@{host}"
            print("identity create:", json.dumps(made["methodResponses"][0][1].get("notCreated"))[:200])
    except Exception as e:
        print("identity lookup:", type(e).__name__)
    return sess.get("username", user)
# JMAP_DOMAIN_USER=1: the run's domain-qualified lab account (<user>@LAB_MAIL_DOMAIN), whose Identity has an address.
if os.environ.get("JMAP_DOMAIN_USER") and not lab.HOSTENV.get("LAB_MAIL_DOMAIN"):
    sys.exit("needs LAB_MAIL_DOMAIN")
RUNUSER = (lab.USER.split("@")[0] + "@" + lab.HOSTENV["LAB_MAIL_DOMAIN"]) if os.environ.get("JMAP_DOMAIN_USER") else lab.user_for(s)
lab.only_one(s["container"]); subprocess.run(["docker", "start", s["container"]], capture_output=True)
try:
    lab.greeting(s["imap"], False, 300); time.sleep(5)
    hp = subprocess.run(["docker", "port", s["container"], row[1]], capture_output=True, text=True).stdout.split("\n")[0].rsplit(":", 1)[-1].strip()
    field = f"{row[2]}://{lab.EMU_HOST}:{hp}"
    rcpt = f"{RUNUSER.split('@')[0]}@{row[3]}" if row[3] != "-" else derive_rcpt(f"{row[2]}://{lab.LAB_HOST}:{hp}", RUNUSER)
    print("rcpt", rcpt)
    r = lab.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{lab.PKG}.lab.JmapLabSuiteTest#jmapSuite",
                "-e", "jmapServer", field, "-e", "jmapRcpt", rcpt, "-e", "labHost", lab.EMU_HOST, "-e", "labRun", run, "-e", "labCa", lab.lab_ca(),
                "-e", "labUser", RUNUSER, "-e", "labPass", lab.PASS, lab.RUNNER, timeout=900)
    out = lab.adb("exec-out", "run-as", lab.PKG, "cat", f"cache/recordings/{run}.steps.tsv").stdout
    print(name, field, "OK" if "OK (1 test)" in r.stdout else "FAIL"); print(out)
    dest = os.path.join(lab.REC, name, run); os.makedirs(dest, exist_ok=True)
    open(os.path.join(dest, "jmap.steps.tsv"), "w").write(out)
finally:
    subprocess.run(["docker", "stop", "-t", "20", s["container"]], capture_output=True)
    lab.adb("shell", "am", "force-stop", lab.PKG)
