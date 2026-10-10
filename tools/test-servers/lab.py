#!/usr/bin/env python3
"""LiveIMAP lab runner. Lab servers only; emulator-5554 only.

  lab.py list                       servers from generated/local-ports.tsv
  lab.py run [--server S ...] [--skip-install]

For each server: docker start, wait for the greeting, record CAPABILITY before and
after login (lab_imap.py), run LabSuiteTest on emulator-5554 for each TLS mode the
server offers, pull the recordings, and always docker stop.
Output: recordings/<server>/<YYYYMMDD-HHMM>/ and recordings/<date>-summary.tsv.
"""
import argparse, collections, os, socket, ssl, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
GEN = os.path.join(HERE, "generated")
REC = os.path.join(HERE, "recordings")
SERIAL = "emulator-5554"
EMU_HOST = "10.0.2.2"  # the emulator's alias for the host loopback, where the lab ports publish
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
            c = socket.create_connection(("127.0.0.1", port), 3)
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
    r = sh(["./deploy", "emulator"], cwd=REPO)
    if r.returncode:
        sys.exit("deploy emulator failed:\n" + r.stderr[-800:])
    r = adb("install", "-r", "-t", os.path.join(REPO, "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    if r.returncode:
        sys.exit("test apk install failed: " + r.stdout[-400:] + r.stderr[-400:])


def instrument(s, port, tls, run, commit):
    a = ["shell", "am", "instrument", "-w", "-r",
         "-e", "class", f"{PKG}.lab.LabSuiteTest#imapSuite",
         "-e", "labHost", EMU_HOST, "-e", "labPort", str(port), "-e", "labTls", tls,
         "-e", "labServer", s["name"], "-e", "labRun", run, "-e", "labCommit", commit,
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


def run_one(s, commit, stamp, out):
    name, cont = s["name"], s["container"]
    dest = os.path.join(REC, name, stamp)
    os.makedirs(dest, exist_ok=True)
    t0 = time.time()
    sh(["docker", "start", cont])
    try:
        if not greeting(s["imap"], False):
            out.write(f"{name}\timap\t-\tfail\tno greeting\t{int(time.time()-t0)}\t{dest}\n")
            return
        modes = [("None", s["imap"])]
        if "imaps" in s and greeting(s["imaps"], True, 60):
            modes.append(("Implicit", s["imaps"]))
        lab = os.path.join(HERE, "lab_imap.py")
        seed = sh([sys.executable, lab, "seed", "127.0.0.1", str(s["imap"]), user_for(s), PASS,
                   "--allow", f"127.0.0.1:{s['imap']}", "--count", "60"])
        open(os.path.join(dest, "seed.txt"), "w").write(seed.stdout + seed.stderr[-500:])
        caps = sh([sys.executable, lab, "caps", "127.0.0.1", str(s["imap"]), user_for(s), PASS,
                   "--allow", f"127.0.0.1:{s['imap']}", "--record", os.path.join(dest, "caps.jsonl"),
                   "--server-name", name, "--commit", commit, "--suite", "caps"])
        open(os.path.join(dest, "caps.txt"), "w").write(caps.stdout + caps.stderr[-500:])
        if "STARTTLS" in caps.stdout:
            modes.append(("StartTls", s["imap"]))
        for tls, port in modes:
            run = f"{name}-{tls}"
            t1 = time.time()
            try:
                res, msg = instrument(s, port, tls, run, commit)
            except subprocess.TimeoutExpired:
                res, msg = "fail", "timeout"
            files = pull(run, dest)
            steps = ""
            st = [p for p in files if p.endswith(".steps.tsv")]
            if st:
                rows = [l.split("\t") for l in open(st[0]).read().splitlines()[1:]]
                bad = [r[0] for r in rows if len(r) > 1 and r[1] != "ok"]
                steps = f"{len(rows)-len(bad)}/{len(rows)} ok" + (f" bad={','.join(bad)}" if bad else "")
            out.write(f"{name}\timap\t{tls}\t{res}\t{(msg + ' ' + steps).strip()}\t{int(time.time()-t1)}\t{dest}\n")
            out.flush()
    finally:
        sh(["docker", "stop", "-t", "20", cont])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["list", "run"])
    p.add_argument("--server", action="append", default=[])
    p.add_argument("--skip-install", action="store_true")
    a = p.parse_args()
    ss = servers()
    if a.action == "list":
        for s in ss:
            print("\t".join(f"{k}={v}" for k, v in s.items()))
        return
    if a.server:
        ss = [s for s in ss if s["name"] in a.server]
    commit = head()
    if not a.skip_install:
        install(commit)
    os.makedirs(REC, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M")
    summ = os.path.join(REC, f"{stamp}-summary.tsv")
    with open(summ, "w") as out:
        out.write("server\tprotocol\ttls\tresult\tdetail\tseconds\tdir\n")
        # One server at a time keeps memory well inside the budget; Zimbra is therefore alone.
        for s in ss:
            run_one(s, commit, stamp, out)
    print(summ)


if __name__ == "__main__":
    main()
