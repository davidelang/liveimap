#!/usr/bin/env python3
"""LiveIMAP lab runner. Lab servers only; emulator-5554 only.

  lab.py list                       servers from generated/local-ports.tsv
  lab.py run --commit SHA [--server S ...] [--skip-install]

For each server: docker start, wait for the greeting, record CAPABILITY before and
after login (lab_imap.py), run LabSuiteTest on emulator-5554 for each TLS mode the
server offers, pull the recordings, and always docker stop.
Output: recordings/<server>/<YYYYMMDD-HHMMSS>/ and recordings/<date>-summary.tsv.
"""
import argparse, collections, os, re, socket, ssl, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
GEN = os.path.join(HERE, "generated")
REC = os.environ.get("LAB_REC") or os.path.join(HERE, "recordings")
SERIAL = "emulator-5554"


def host_env():
    env = {}
    path = os.path.join(HERE, "host.env")
    if os.path.exists(path):
        for line in open(path):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.strip().split("=", 1)
                env[k] = v.strip().strip('"')
    for k, v in os.environ.items():
        if k.startswith("LAB_"):
            env[k] = v
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
PKG = "com.davidlang.liveimap"
LAB_PKG = "org.dlang.liveimap.lab"
RUNNER = f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner"
USER, PASS = "liveimap", "liveimap-test"
ALONE = {"liveimap-zimbra-883"}


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def adb(*a, **kw):
    return sh(["adb", "-s", SERIAL, *a], **kw)


def servers():
    by = collections.OrderedDict()
    skip = {"greenmail", "greenmail-16", "greenmail-214", "greenmail-dev"}
    for line in open(os.path.join(GEN, "local-ports.tsv")):
        f = line.rstrip("\n").split("\t")
        if len(f) != 4 or f[0] == "service":
            continue
        svc, hport, cport, cont = f
        name = cont.replace("liveimap-", "")
        if name in skip:
            continue
        kind = svc.rsplit("-", 1)[-1]
        by.setdefault(cont, {"container": cont, "name": name})[kind] = int(hport)
    return [s for s in by.values() if "imap" in s]


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
    # The lab does not build or deploy. The test APK is already built; install that file only.
    apk = os.path.join(REPO, "app/build/outputs/apk/androidTest/x86_64/debug/app-x86_64-debug-androidTest.apk")
    if not os.path.exists(apk):
        sys.exit("test apk missing; lab does not build")
    r = adb("install", "-r", "-t", apk)
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
IDLE_NO_EXAMINE_PUSH = {"uwimap"}
POLICY_REFUSED = re.compile(r"disabled on the clear-text port|PRIVACYREQUIRED|cleartext logins? (are )?disabled|"
                            r"plaintext authentication disallowed", re.I)


def _text(value):
    if isinstance(value, bytes):
        value = value.decode("utf-8", "replace")
    elif not isinstance(value, str):
        value = str(value)
    return value.replace(PASS, "***")


def _close_imap(client):
    if client is None:
        return
    try:
        client.shutdown()
    except Exception:
        pass


def _lab_ssl():
    ctx = ssl._create_unverified_context()
    try:
        ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
        ctx.minimum_version = ssl.TLSVersion.TLSv1
    except Exception:
        pass
    return ctx


def _plain_login_reply(port, user):
    import imaplib
    client = None
    try:
        client = imaplib.IMAP4(LAB_HOST, port, timeout=20)
        typ, _dat = client.login(user, PASS)
        return typ
    except Exception as exc:
        arg = exc.args[0] if exc.args else exc
        return _text(arg)
    finally:
        _close_imap(client)


def _starttls_login(port, user):
    import imaplib
    client = None
    try:
        client = imaplib.IMAP4(LAB_HOST, port, timeout=20)
        client.starttls(_lab_ssl())
        client.login(user, PASS)
        return ""
    except Exception as exc:
        arg = exc.args[0] if exc.args else exc
        return _text(arg)
    finally:
        _close_imap(client)


def classify_plain(caps, login_reply):
    """StartTls, None, or one error string. Does not connect."""
    if not caps:
        return "no plaintext capability"
    if "LOGINDISABLED" in caps:
        return "StartTls"
    if login_reply == "OK":
        return None
    reply = login_reply or ""
    if (POLICY_REFUSED.search(reply) or "PRIVACYREQUIRED" in reply or "Cleartext" in reply
            or "Plaintext" in reply or "disabled" in reply):
        return "StartTls"
    return 'unexpected login reply "' + reply + '"'


def resolve_auto(s):
    """StartTls, None, or one error string. Does not seed."""
    caps = plain_caps(s["imap"])
    reply = ""
    if caps and "LOGINDISABLED" not in caps:
        reply = _plain_login_reply(s["imap"], USER)
    kind = classify_plain(caps, reply)
    if kind != "StartTls":
        return None if kind is None else _text(kind)
    err = _starttls_login(s["imap"], USER)
    return "StartTls" if not err else _text(err)


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


def smtp_args(s, tls):
    """Lab SMTP from smtp.tsv (server, TLS mode, recipient domain) for the run's TLS mode: the mapped host port and a lab-account
    recipient, so test mail only ever goes to the lab server's own local account."""
    import ports
    path = os.path.join(HERE, "smtp.tsv")
    for line in open(path):
        f = line.rstrip("\n").split("\t")
        if len(f) == 3 and f[0] == s["name"] and f[1] == tls:
            cport = ports.container_port(f[0], "submission")
            out = subprocess.run(["docker", "port", "liveimap-" + f[0], str(cport)], capture_output=True, text=True).stdout
            hp = out.split("\n")[0].rsplit(":", 1)[-1].strip()
            if hp:
                return ["-e", "labSmtpPort", hp, "-e", "labRcpt", f"{USER.split('@')[0]}@{f[2]}"]
    return []


def sieve_args(s):
    """Lab ManageSieve from sieve.tsv (server, TLS mode): mapped host port and mode."""
    import ports
    path = os.path.join(HERE, "sieve.tsv")
    for line in open(path):
        f = line.rstrip("\n").split("\t")
        if len(f) == 2 and f[0] == s["name"]:
            cport = ports.container_port(f[0], "managesieve")
            out = subprocess.run(["docker", "port", "liveimap-" + f[0], str(cport)], capture_output=True, text=True).stdout
            hp = out.split("\n")[0].rsplit(":", 1)[-1].strip()
            if hp:
                return ["-e", "labSievePort", hp, "-e", "labSieveTls", f[1]]
    return []


def check_published(container):
    """Each servers.tsv listen port must be published on the local-ports.tsv host port for that service. A miss returns one string."""
    server = container.split("liveimap-", 1)[-1]
    servers_path = os.path.join(HERE, "servers.tsv")
    try:
        handle = open(servers_path, encoding="utf-8")
    except OSError:
        return "cannot read " + servers_path
    wanted = []
    with handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) == 3 and fields[2].isdigit() and fields[0] == server:
                wanted.append((fields[1], int(fields[2])))
    if not wanted:
        return server + " no servers.tsv row"
    ports_path = os.path.join(GEN, "local-ports.tsv")
    try:
        handle = open(ports_path, encoding="utf-8")
    except OSError:
        return "cannot read " + ports_path
    saved = {}
    with handle:
        for line in handle:
            fields = line.rstrip("\n").split("\t")
            if len(fields) != 4 or fields[3] != container or not fields[1].isdigit():
                continue
            kind = fields[0].rsplit("-", 1)[-1]
            saved.setdefault(kind, int(fields[1]))
    for service, cport in wanted:
        proc = subprocess.run(["docker", "port", container, str(cport)], capture_output=True, text=True)
        line = (proc.stdout or "").split("\n")[0].strip()
        hp = line.rsplit(":", 1)[-1].strip() if line else ""
        kind = service
        # stalwart-0155 records its https listener as stalwart015-http.
        if service == "https" and "https" not in saved and "http" in saved:
            kind = "http"
        if hp.isdigit():
            published = hp
        else:
            published = "not published"
        if kind in saved:
            local = str(saved[kind])
        else:
            local = "no local-ports.tsv row"
        if published == "not published" or local == "no local-ports.tsv row" or saved.get(kind) != int(hp):
            return f"{server} {service} {cport} {published} {local}"
    return ""


RECREATE_HINTS = {
    "liveimap-stalwart-01625": "tools/test-servers/start-latest.sh --recreate stalwart-01625",
    "liveimap-dovecot-245": "tools/test-servers/start-latest.sh --recreate dovecot-245",
    "liveimap-cyrus-3124": "tools/test-servers/start-latest.sh --recreate cyrus-3124",
    "liveimap-courier-606": "tools/test-servers/start-latest.sh --recreate courier-606",
    "liveimap-stalwart-dev": "tools/test-servers/start-dev.sh --recreate stalwart-dev",
    "liveimap-dovecot-dev": "tools/test-servers/start-dev.sh --recreate dovecot-dev",
    "liveimap-cyrus-3137": "tools/test-servers/start-dev.sh --recreate cyrus-3137",
    "liveimap-stalwart": "tools/test-servers/start-local.sh --recreate stalwart",
    "liveimap-cyrus-22": "tools/test-servers/start-older.sh --recreate cyrus-22",
    "liveimap-cyrus-24": "tools/test-servers/start-older.sh --recreate cyrus-24",
    "liveimap-cyrus-25": "tools/test-servers/start-older.sh --recreate cyrus-25",
    "liveimap-cyrus-36": "tools/test-servers/start-older.sh --recreate cyrus-36",
    "liveimap-dovecot-10": "tools/test-servers/start-older.sh --recreate dovecot-10",
    "liveimap-dovecot-12": "tools/test-servers/start-older.sh --recreate dovecot-12",
    "liveimap-dovecot-22": "tools/test-servers/start-older.sh --recreate dovecot-22",
    "liveimap-courier-50": "tools/test-servers/start-older.sh --recreate courier-50",
    "liveimap-uwimap": "tools/test-servers/start-older.sh --recreate uwimap",
    "liveimap-stalwart-0155": "tools/test-servers/start-older.sh --recreate stalwart-0155",
    "liveimap-zimbra-883": "tools/test-servers/zimbra-883.sh --recreate zimbra-883",
}
SEED_SERVERS = [name[len("liveimap-"):] for name in RECREATE_HINTS]
GREENMAIL = {
    "liveimap-greenmail",
    "liveimap-greenmail-16",
    "liveimap-greenmail-214",
    "liveimap-greenmail-dev",
}
emulator_line = ""


def recreate_hint(container):
    return RECREATE_HINTS.get(container) or (container + " no recreate script")


def mount_problems(names, mounts_by_name):
    """Any Mounts element is a problem. Returns "" or one string."""
    for cont in names:
        for item in mounts_by_name.get(cont) or []:
            typ = item.get("Type") or item.get("type") or "mount"
            if typ == "volume":
                src = item.get("Name") or item.get("Source") or ""
            else:
                src = item.get("Source") or item.get("Name") or ""
            dest = item.get("Destination") or item.get("destination") or ""
            if cont in GREENMAIL:
                return f"{cont} was dropped {typ} {src} {dest} docker rm -f {cont}"
            return f"{cont} {typ} {src} {dest} {recreate_hint(cont)}"
    return ""


def source_address():
    proc = subprocess.run(["ip", "-4", "route", "get", "1.1.1.1"], capture_output=True, text=True, errors="replace")
    match = re.search(r"\bsrc\s+(\d+\.\d+\.\d+\.\d+)\b", proc.stdout or "")
    if proc.returncode != 0 or not match:
        return ""
    return match.group(1)


def cert_problem():
    """Returns "" or one string naming lab-ca.sh or client-cert.sh."""
    ca = os.path.join(GEN, "ca")
    crt = os.path.join(ca, "server.crt")
    if not os.path.isfile(crt):
        return "server.crt missing tools/test-servers/lab-ca.sh"
    addr = source_address()
    if not addr:
        return "no source address tools/test-servers/lab-ca.sh"
    proc = subprocess.run(
        ["openssl", "x509", "-in", crt, "-noout", "-ext", "subjectAltName"],
        capture_output=True, text=True, errors="replace",
    )
    san = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0 or addr not in san:
        return "server.crt SAN missing the source address tools/test-servers/lab-ca.sh"
    for name in ("client.crt", "client.key", "client.p12"):
        if not os.path.isfile(os.path.join(ca, name)):
            return "RSA client cert missing tools/test-servers/client-cert.sh"
    for name in ("client-ec.crt", "client-ec.key", "client-ec.p12"):
        if not os.path.isfile(os.path.join(ca, name)):
            return "EC client cert missing tools/test-servers/client-cert.sh ec"
    return ""


def _port_rows(text):
    rows = []
    seen = {}
    for line in text.splitlines():
        fields = line.rstrip("\n").split("\t")
        if len(fields) != 4 or fields[0] == "service":
            continue
        if not fields[1].isdigit() or not fields[2].isdigit():
            continue
        if fields[0] in seen:
            return None, fields[0], fields[3]
        seen[fields[0]] = True
        rows.append((fields[0], int(fields[1]), int(fields[2]), fields[3]))
    return rows, "", ""


def _server_rows(text):
    rows = []
    for line in text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.rstrip("\n").split("\t")
        if len(fields) == 3 and fields[2].isdigit():
            rows.append((fields[0], fields[1], int(fields[2])))
    return rows


def _kind_matches(service, service_key):
    kind = service_key.rsplit("-", 1)[-1]
    if kind == service:
        return True
    if service == "https" and kind == "http":
        return True
    return False


def port_problems(servers_text, ports_text, published):
    """Returns "" or one string. Does not draw a port."""
    rows, dup, dup_cont = _port_rows(ports_text)
    if rows is None:
        return f"duplicate service key {dup} {recreate_hint(dup_cont)}"
    for server, service, cport in _server_rows(servers_text):
        cont = "liveimap-" + server
        hint = recreate_hint(cont)
        matches = [row for row in rows if row[3] == cont and _kind_matches(service, row[0])]
        if not matches:
            return f"{server} {service} no local-ports.tsv row {hint}"
        if len(matches) > 1:
            return f"{server} {service} duplicate local-ports.tsv row {hint}"
        _key, hport, saved_cport, _cont = matches[0]
        if saved_cport != cport:
            return f"{server} {service} container port {saved_cport} != {cport} {hint}"
        if published is not None and cont in published:
            got = published[cont].get(cport, "not published")
            if str(got) != str(hport):
                return f"{server} {service} {cport} {got} {hport} {hint}"
    return ""


def seed_problem(server, image_inspect):
    """Returns "" or one string naming build-seed-image."""
    fix = "tools/test-servers/build-seed-image " + server
    if not image_inspect:
        return "missing seed image " + fix
    config = image_inspect.get("Config") or {}
    volumes = config.get("Volumes") or {}
    if volumes:
        return "Config.Volumes is not empty " + fix
    labels = config.get("Labels") or {}
    if not labels.get("liveimap.seed-manifest"):
        return "missing liveimap.seed-manifest " + fix
    return ""


def emulator_in_use():
    """True when adb times out, adb exits non-zero, or the resumed activity is not the launcher."""
    global emulator_line
    try:
        proc = subprocess.run(
            ["adb", "-s", SERIAL, "shell", "dumpsys", "activity", "activities"],
            capture_output=True, text=True, errors="replace", timeout=20,
        )
    except subprocess.TimeoutExpired:
        emulator_line = "adb failed"
        return True
    except OSError:
        emulator_line = "adb failed"
        return True
    if proc.returncode != 0:
        emulator_line = "adb failed"
        return True
    activity = ""
    for line in (proc.stdout or "").splitlines():
        if "mResumedActivity" not in line and "topResumedActivity" not in line:
            continue
        match = re.search(r"(\S+/\S+)", line)
        if match:
            activity = match.group(1).rstrip("},")
            break
    if activity and "launcher" not in activity.lower():
        emulator_line = activity
        return True
    emulator_line = ""
    return False


def client_cert_args(tls):
    """LAB_CLIENT_CERT=1: present the lab client cert (client-cert.sh, CN = lab user) on TLS modes."""
    if os.environ.get("LAB_CLIENT_CERT", "0") != "1" or tls == "None":
        return []
    import base64
    # LAB_CLIENT_CERT_KEY=ec uses client-ec.p12 (client-cert.sh ec); LAB_CLIENT_CERT_KIND=keystore puts the key
    # in the emulator app's AndroidKeyStore (the KeyChain signing path) instead of the .p12 import.
    name = "client-ec.p12" if os.environ.get("LAB_CLIENT_CERT_KEY") == "ec" else "client.p12"
    p12 = open(os.path.join(HERE, "generated", "ca", name), "rb").read()
    return ["-e", "labClientCertB64", base64.b64encode(p12).decode(), "-e", "labClientCertPass", "liveimap",
            "-e", "labClientCertKind", os.environ.get("LAB_CLIENT_CERT_KIND", "p12")]


def instrument(s, port, tls, run, commit, mech="", side=None):
    """side: replacement SMTP/ManageSieve extras (fake runs); None uses smtp.tsv / sieve.tsv."""
    a = ["shell", "am", "instrument", "-w", "-r",
         "-e", "class", f"{LAB_PKG}.LabSuiteTest#imapSuite",
         "-e", "labHost", EMU_HOST, "-e", "labPort", str(port), "-e", "labTls", tls,
         "-e", "labServer", s["name"], "-e", "labRun", run, "-e", "labCommit", commit,
         "-e", "labFolder", s.get("folder", "LiveIMAP-Test"),
         "-e", "labCa", lab_ca(),
         "-e", "labOldTls", "true" if s["name"] in OLD_TLS and os.environ.get("LAB_OLD_TLS", "1") != "0" else "false",
         "-e", "labMaxTls12", os.environ.get("LAB_MAX_TLS12", "false"),
         *(["-e", "labMech", "IMAP-LOGIN" if mech == "LOGIN-CMD" else mech] if mech else []),
         *(side if side is not None else [*smtp_args(s, tls), *(sieve_args(s) if tls != "None" else [])]),
         *client_cert_args(tls),
         *(["-e", "labSteps", os.environ["LAB_STEPS"]] if os.environ.get("LAB_STEPS") else []),
         *(["-e", "labAltHost", HOSTENV["LAB_ALT_HOST"]] if HOSTENV.get("LAB_ALT_HOST") else []),
         "-e", "labUser", USER, "-e", "labPass", PASS, RUNNER]
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
        seed = sh([sys.executable, lab, "seed", LAB_HOST, str(s["imap"]), USER, PASS,
                   "--allow", f"{LAB_HOST}:{s['imap']}", "--count", "60"])
        # Servers that refuse a plaintext login are seeded over TLS (implicit if offered, else STARTTLS).
        seed_port, seed_tls = s["imap"], ""
        offers_starttls = "STARTTLS" in plain_caps(s["imap"])
        if seed.returncode != 0 and re.search(r"disabled|disallowed|PRIVACYREQUIRED|cleartext|clear-text", seed.stdout + seed.stderr, re.I):
            seed_port, seed_tls = (s["imaps"], "Implicit") if "imaps" in s else (s["imap"], "StartTls" if offers_starttls else "")
            seed = sh([sys.executable, lab, "seed", LAB_HOST, str(seed_port), USER, PASS, "--tls", seed_tls,
                       "--allow", f"{LAB_HOST}:{seed_port}", "--count", "60"])
        if os.environ.get("LAB_DEEP") == "1":
            # Deeply nested MIME messages (100 and 5000 levels) in LiveIMAP-Test/Deep for the deepNesting step.
            deep = sh([sys.executable, lab, "seed-deep", LAB_HOST, str(seed_port), USER, PASS,
                       *(["--tls", seed_tls] if seed_tls else []), "--allow", f"{LAB_HOST}:{seed_port}"])
            seed.stdout += "\n" + deep.stdout + deep.stderr[-500:]
        if os.environ.get("LAB_SPOOF") == "1":
            # Attachments whose declared type/filename lie about the content, for the typeSniff step.
            spoof = sh([sys.executable, lab, "seed-spoof", LAB_HOST, str(seed_port), USER, PASS,
                        *(["--tls", seed_tls] if seed_tls else []), "--allow", f"{LAB_HOST}:{seed_port}"])
            seed.stdout += "\n" + spoof.stdout + spoof.stderr[-500:]
        open(os.path.join(dest, "seed.txt"), "w").write(seed.stdout + seed.stderr[-500:])
        folder = seed.stdout.split(":", 1)[0].strip() if ": " in seed.stdout else "LiveIMAP-Test"
        s["folder"] = folder
        caps = sh([sys.executable, lab, "caps", LAB_HOST, str(seed_port), USER, PASS,
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
                # A server that refuses plaintext login by policy (e.g. Stalwart 0.16: "LOGIN is disabled on the
                # clear-text port") is expected, not an app failure.
                if tls == "None" and bad == ["open"] and any(
                        r[0] == "open" and len(r) > 2 and POLICY_REFUSED.search(r[2]) for r in rows):
                    res, msg = "expected", "server policy: plaintext login refused"
                # UW-IMAP (mbox) checks for new mail every 60 s during IDLE but reports it only to SELECTed
                # sessions; the app's watch uses EXAMINE on purpose (David, 2026-10-11), so no EXISTS ever arrives.
                elif name in IDLE_NO_EXAMINE_PUSH and bad == ["idle"]:
                    res, msg = "expected", "expected-by-server-behaviour: no IDLE push to EXAMINE sessions"
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
    p.add_argument("--commit", default="", help="installed commit; required for run")
    p.add_argument("--steps", default="", help="comma list: run only these LabSuiteTest steps (open/select always run)")
    p.add_argument("--mechs", action="store_true", help="one app run per advertised mechanism plus LOGIN")
    a = p.parse_args()
    if a.steps:
        os.environ["LAB_STEPS"] = a.steps
    ss = servers()
    if a.action == "list":
        for s in ss:
            print("\t".join(f"{k}={v}" for k, v in s.items()))
        return
    if not a.commit:
        sys.exit("lab.py run requires --commit")
    if a.server:
        ss = [s for s in ss if s["name"] in a.server]
    commit = a.commit
    global MECHS
    MECHS = a.mechs
    if not a.skip_install:
        install(commit)
    os.makedirs(REC, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")  # seconds: back-to-back runs must not overwrite
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
