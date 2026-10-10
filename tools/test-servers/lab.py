#!/usr/bin/env python3
"""LiveIMAP lab runner. Lab servers only; emulator-5554 only.

  lab.py list                       servers from generated/local-ports.tsv
  lab.py run [--server S ...] [--skip-install]

For each server: docker start, wait for the greeting, record CAPABILITY before and
after login (lab_imap.py), run LabSuiteTest on emulator-5554 for each TLS mode the
server offers, pull the recordings, and always docker stop.
Output: recordings/<server>/<YYYYMMDD-HHMM>/ and recordings/<date>-summary.tsv.
"""
import argparse, collections, os, re, socket, ssl, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
GEN = os.path.join(HERE, "generated")
REC = os.path.join(HERE, "recordings")
SERIAL = "emulator-5554"


def host_env():
    env = {}
    path = os.path.join(HERE, "host.env")
    if os.path.exists(path):
        for line in open(path):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.strip().split("=", 1)
                env[k] = v.strip().strip('"')
    return env


HOSTENV = host_env()
LAB_HOST = HOSTENV.get("LAB_BIND", "")  # the host's LAN address; servers publish there, devices connect there
if not LAB_HOST:
    sys.exit("tools/test-servers/host.env needs LAB_BIND=<LAN IPv4> (see host.env.example)")
EMU_HOST = LAB_HOST


def up_commands():
    cmds = {}
    for line in open(os.path.join(HERE, "up.tsv")):
        if line.startswith("#") or "\t" not in line:
            continue
        name, cmd = line.rstrip("\n").split("\t", 1)
        cmds[name] = cmd
    return cmds
PKG = "org.dlang.liveimap"
RUNNER = f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner"
USER, PASS = "liveimap", "liveimap-test"
ALONE = {"liveimap-zimbra-883"}


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def adb(*a, **kw):
    return sh(["adb", "-s", SERIAL, *a], **kw)


def servers():
    by = collections.OrderedDict()
    for line in open(os.path.join(GEN, "local-ports.tsv")):
        f = line.rstrip("\n").split("\t")
        if len(f) != 4 or f[0] == "service":
            continue
        svc, hport, cport, cont = f
        kind = svc.rsplit("-", 1)[-1]
        by.setdefault(cont, {"container": cont, "name": cont.replace("liveimap-", "")})[kind] = int(hport)
    return [s for s in by.values() if "imap" in s]


def user_for(s):
    return "liveimap@zimbra.zcs-foss.test" if "zimbra" in s["name"] else USER


def greeting(port, tls, timeout=180):
    end = time.time() + timeout
    while time.time() < end:
        try:
            c = socket.create_connection((LAB_HOST, port), 3)
            if tls:
                ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
                c = ctx.wrap_socket(c)
            c.settimeout(10)
            g = c.recv(200)
            c.close()
            if g.startswith(b"* OK") or g.startswith(b"* PREAUTH"):
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def head():
    return sh(["git", "-C", REPO, "rev-parse", "HEAD"]).stdout.strip()


def install(commit):
    # Approved emulator route: ./deploy emulator (never the Pixels), then the test APK on the emulator only.
    # The test APK must be built at HEAD too; the build check runs from it.
    r = sh(["./build_app", "--", "assembleDebugAndroidTest"], cwd=REPO)
    if r.returncode:
        sys.exit("test apk build failed:\n" + (r.stdout + r.stderr)[-800:])
    r = sh(["./deploy", "emulator"], cwd=REPO)
    if r.returncode:
        sys.exit("deploy emulator failed:\n" + r.stderr[-800:])
    r = adb("install", "-r", "-t", os.path.join(REPO, "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    if r.returncode:
        sys.exit("test apk install failed: " + r.stdout[-400:] + r.stderr[-400:])


def lab_ca():
    path = os.path.join(GEN, "ca", "ca.der")
    if not os.path.exists(path):
        return ""
    import base64
    return base64.b64encode(open(path, "rb").read()).decode()


def plain_caps(port):
    """CAPABILITY before TLS and before login (empty when it cannot be read)."""
    import imaplib
    try:
        m = imaplib.IMAP4(LAB_HOST, port, timeout=20)
        caps = " ".join(m.capabilities)
        m.shutdown()
        return caps
    except Exception:
        return ""


MECHS = False


def mode_caps(tls, port):
    """CAPABILITY before login in one TLS mode (lab only, certificate not verified)."""
    import imaplib, ssl
    ctx = ssl._create_unverified_context()
    try:
        ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
        ctx.minimum_version = ssl.TLSVersion.TLSv1
    except Exception:
        pass
    try:
        if tls == "Implicit":
            m = imaplib.IMAP4_SSL(LAB_HOST, port, ssl_context=ctx, timeout=20)
        else:
            m = imaplib.IMAP4(LAB_HOST, port, timeout=20)
            if tls == "StartTls":
                m.starttls(ctx)
        typ, data = m.capability()
        caps = data[0].decode() if data and data[0] else " ".join(m.capabilities)
        m.shutdown()
        return caps
    except Exception:
        return ""


def mechanisms(caps):
    """Every advertised SASL mechanism plus the IMAP LOGIN command (unless LOGINDISABLED)."""
    words = caps.upper().split()
    mechs = sorted({w[5:] for w in words if w.startswith("AUTH=")})
    if "LOGINDISABLED" not in words:
        mechs.append("LOGIN-CMD")
    return mechs


def instrument(s, port, tls, run, commit, mech=""):
    a = ["shell", "am", "instrument", "-w", "-r",
         "-e", "class", f"{PKG}.lab.LabSuiteTest#imapSuite",
         "-e", "labHost", EMU_HOST, "-e", "labPort", str(port), "-e", "labTls", tls,
         "-e", "labServer", s["name"], "-e", "labRun", run, "-e", "labCommit", commit,
         "-e", "labFolder", s.get("folder", "LiveIMAP-Test"),
         "-e", "labCa", lab_ca(),
         "-e", "labOldTls", "true" if s["name"] in OLD_TLS and os.environ.get("LAB_OLD_TLS", "1") != "0" else "false",
         "-e", "labMech", "LOGIN" if mech == "LOGIN-CMD" else mech,
         "-e", "labUser", user_for(s), "-e", "labPass", PASS, RUNNER]
    r = adb(*a, timeout=900)
    out = r.stdout
    if "OK (1 test)" in out:
        return "pass", ""
    if "AssumptionViolated" in out or "OK (0 tests)" in out:
        return "skip", "assumption"
    msg = ""
    for line in out.splitlines():
        if "stack=" in line or "AssertionError" in line or "Exception" in line:
            msg = line.strip()[:200]
            break
    return "fail", msg


def pull(run, dest):
    got = []
    for ext in ("jsonl", "steps.tsv"):
        r = adb("exec-out", "run-as", PKG, "cat", f"cache/recordings/{run}.{ext}")
        if r.returncode == 0 and r.stdout:
            p = os.path.join(dest, f"{run}.{ext}")
            open(p, "w").write(r.stdout)
            got.append(p)
        adb("shell", "run-as", PKG, "rm", "-f", f"cache/recordings/{run}.{ext}")
    return got


OLD_TLS = set(open(os.path.join(HERE, "old-tls.txt")).read().split()) if os.path.exists(os.path.join(HERE, "old-tls.txt")) else set()


def running_lab():
    r = sh(["docker", "ps", "--format", "{{.Names}}"])
    return [n for n in r.stdout.split() if n.startswith("liveimap-")]


def only_one(cont):
    """Strictly one lab server at a time (RAM), Zimbra included: stop any other first."""
    for other in running_lab():
        if other != cont:
            sh(["docker", "stop", "-t", "20", other])
    left = [n for n in running_lab() if n != cont]
    if left:
        sys.exit("lab containers still running: " + " ".join(left))


def run_one(s, commit, stamp, out):
    name, cont = s["name"], s["container"]
    only_one(cont)
    dest = os.path.join(REC, name, stamp)
    os.makedirs(dest, exist_ok=True)
    t0 = time.time()
    sh(["docker", "start", cont])
    up = up_commands().get(cont)
    if up:
        sh(["docker", "exec", cont, "sh", "-c", up])
    try:
        if not greeting(s["imap"], False, 900 if cont in ALONE else 240):
            out.write(f"{name}\timap\t-\tfail\tno greeting\t{int(time.time()-t0)}\t{dest}\n")
            out.flush()
            return
        modes = [("None", s["imap"])]
        if "imaps" in s and greeting(s["imaps"], True, 60):
            modes.append(("Implicit", s["imaps"]))
        lab = os.path.join(HERE, "lab_imap.py")
        seed = sh([sys.executable, lab, "seed", LAB_HOST, str(s["imap"]), user_for(s), PASS,
                   "--allow", f"{LAB_HOST}:{s['imap']}", "--count", "60"])
        # Servers that refuse a plaintext login are seeded over TLS (implicit if offered, else STARTTLS).
        seed_port, seed_tls = s["imap"], ""
        offers_starttls = "STARTTLS" in plain_caps(s["imap"])
        if seed.returncode != 0 and re.search(r"disabled|disallowed|PRIVACYREQUIRED|cleartext|clear-text", seed.stdout + seed.stderr, re.I):
            seed_port, seed_tls = (s["imaps"], "Implicit") if "imaps" in s else (s["imap"], "StartTls" if offers_starttls else "")
            seed = sh([sys.executable, lab, "seed", LAB_HOST, str(seed_port), user_for(s), PASS, "--tls", seed_tls,
                       "--allow", f"{LAB_HOST}:{seed_port}", "--count", "60"])
        open(os.path.join(dest, "seed.txt"), "w").write(seed.stdout + seed.stderr[-500:])
        folder = seed.stdout.split(":", 1)[0].strip() if ": " in seed.stdout else "LiveIMAP-Test"
        s["folder"] = folder
        caps = sh([sys.executable, lab, "caps", LAB_HOST, str(seed_port), user_for(s), PASS,
                   *(["--tls", seed_tls] if seed_tls else []), "--allow", f"{LAB_HOST}:{seed_port}", "--record", os.path.join(dest, "caps.jsonl"),
                   "--server-name", name, "--commit", commit, "--suite", "caps"])
        open(os.path.join(dest, "caps.txt"), "w").write(caps.stdout + caps.stderr[-500:])
        # Never STARTTLS unless the pre-TLS CAPABILITY lists it.
        if offers_starttls:
            modes.append(("StartTls", s["imap"]))
        plan = []
        for tls, port in modes:
            if MECHS:
                for mech in mechanisms(mode_caps(tls, port)):
                    plan.append((tls, port, mech))
            else:
                plan.append((tls, port, ""))
        for tls, port, mech in plan:
            run = f"{name}-{tls}" + (f"-{mech}" if mech else "")
            t1 = time.time()
            try:
                res, msg = instrument(s, port, tls, run, commit, mech)
            except subprocess.TimeoutExpired:
                res, msg = "fail", "timeout"
            files = pull(run, dest)
            steps = ""
            st = [p for p in files if p.endswith(".steps.tsv")]
            if st:
                rows = [l.split("\t") for l in open(st[0]).read().splitlines()[1:]]
                bad = [r[0] for r in rows if len(r) > 1 and r[1] != "ok"]
                steps = f"{len(rows)-len(bad)}/{len(rows)} ok" + (f" bad={','.join(bad)}" if bad else "")
            # Servers in old-tls.txt run with the app's Allow old TLS and weak keys setting on (labOldTls).
            out.write(f"{name}\timap{('/' + mech) if mech else ''}\t{tls}\t{res}\t{(msg + ' ' + steps).strip()}\t{int(time.time()-t1)}\t{dest}\n")
            out.flush()
    finally:
        sh(["docker", "stop", "-t", "20", cont])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["list", "run"])
    p.add_argument("--server", action="append", default=[])
    p.add_argument("--skip-install", action="store_true")
    p.add_argument("--mechs", action="store_true", help="one app run per advertised mechanism plus LOGIN")
    a = p.parse_args()
    ss = servers()
    if a.action == "list":
        for s in ss:
            print("\t".join(f"{k}={v}" for k, v in s.items()))
        return
    if a.server:
        ss = [s for s in ss if s["name"] in a.server]
    commit = head()
    global MECHS
    MECHS = a.mechs
    if not a.skip_install:
        install(commit)
    os.makedirs(REC, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M")
    summ = os.path.join(REC, f"{stamp}-summary.tsv")
    with open(summ, "w") as out:
        out.write("server\tprotocol\ttls\tresult\tdetail\tseconds\tdir\n")
        # Strictly one server at a time (only_one stops anything else first); Zimbra too.
        for s in ss:
            try:
                run_one(s, commit, stamp, out)
            except Exception as e:  # one broken server must not end the run
                out.write(f"{s['name']}\timap\t-\tfail\trunner error: {type(e).__name__}: {str(e)[:120]}\t0\t-\n")
                out.flush()
                sh(["docker", "stop", "-t", "20", s["container"]])
    print(summ)


if __name__ == "__main__":
    main()
