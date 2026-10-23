#!/usr/bin/env python3
"""Edges for the lab runners beside the other test-server tools.
Run from the repo root: python3 tools/test-servers/test_lab_config.py"""
import os, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab

lab_py = open(os.path.join(HERE, "lab.py"), encoding="utf-8").read()
assert "lab does not build" in lab_py
assert "app-x86_64-debug-androidTest.apk" in lab_py
assert "assembleX86_64DebugAndroidTest" not in lab_py
assert "HERE = os.path.dirname(os.path.abspath(__file__))" in lab_py
needles = (
    "sandbox" + "/" + "test-servers",
    "/" + "home" + "/" + "dlang",
    "8f4d" + "8e42",
    "0854" + "4af3",
    "a5f5" + "b80ae",
)
assert needles[0] not in lab_py
names = (
    "lab.py", "acclab.py", "accsum.py", "ai-fake-smtp.py", "cysub.py",
    "jmapall.sh", "jmaplab.py", "jmaprun.py", "jmap.tsv", "s155.sh",
    "sess.py", "sj2.py", "stwfix.py", "seed_corpus.py", "cyrus_domain_user.py",
    "stalwart_republish.py", "smtp-extra.tsv", "test_lab_config.py",
    "t1run.sh", "t2run.sh", "t3run.sh", "t4smtp.py",
)
for name in names:
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    for bad in needles:
        assert bad not in text, (name, bad)
assert os.path.dirname(os.path.abspath(lab.__file__)) == os.path.abspath(HERE)

# Edge 1: an unset LAB_LOG resolves to the caller's absolute directory.
td = tempfile.mkdtemp()
env = os.environ.copy()
env.pop("LAB_LOG", None)
pwd = subprocess.run(
    ["bash", "-c", 'mkdir -p "${LAB_LOG:-.}" && cd "${LAB_LOG:-.}" && pwd'],
    cwd=td, capture_output=True, text=True, env=env,
)
assert pwd.returncode == 0, pwd.stderr
assert pwd.stdout.strip() == os.path.abspath(td)
log_assign = 'LOGDIR=$(mkdir -p "${LAB_LOG:-.}" && cd "${LAB_LOG:-.}" && pwd)'
rec_assign = 'REC=$(mkdir -p "${LAB_REC:-$HERE/recordings}" && cd "${LAB_REC:-$HERE/recordings}" && pwd)'
for name in ("t1run.sh", "t2run.sh", "t3run.sh"):
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    cd_at = text.find('cd "$REPO"')
    log_at = text.find(log_assign)
    assert log_at != -1 and cd_at != -1 and log_at < cd_at, name
for name in ("t2run.sh", "t3run.sh"):
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    cd_at = text.find('cd "$REPO"')
    rec_at = text.find(rec_assign)
    assert rec_at != -1 and cd_at != -1 and rec_at < cd_at, name

# Edge 2: generate generated/corpus-v1. The acceptance runners do not seed.
seed = open(os.path.join(HERE, "seed_corpus.py"), encoding="utf-8").read()
assert "generated" in seed and "corpus-v1" in seed
assert "make_corpus.load_tables" in seed and "make_corpus.generate" in seed
assert "manifest.tsv" in seed
acc = open(os.path.join(HERE, "acclab.py"), encoding="utf-8").read()
assert "seed_corpus.py" not in acc
t4 = open(os.path.join(HERE, "t4smtp.py"), encoding="utf-8").read()
assert "seed_corpus.py" not in t4
assert 'NAME = "dovecot-dev"' in t4
assert 'PORT = "2525"' in t4

# Edge 3: LAB_* overlay, no baked zimbra address, missing domain or user stops the run.
assert 'startswith("LAB_")' in lab_py
assert "liveimap@zimbra.zcs-foss.test" not in lab_py
sentinel = "LAB_EDGE_SENTINEL"
os.environ[sentinel] = "edge-value"
try:
    overlaid = lab.host_env()
    assert overlaid.get(sentinel) == "edge-value"
    file_keys = [k for k in overlaid if k.startswith("LAB_") and k != sentinel]
    if file_keys:
        key = file_keys[0]
        old = os.environ.get(key)
        os.environ[key] = "overlay-edge"
        try:
            assert lab.host_env()[key] == "overlay-edge"
        finally:
            if old is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = old
finally:
    os.environ.pop(sentinel, None)
example = open(os.path.join(HERE, "host.env.example"), encoding="utf-8").read()
assert "requires" in example
values = [line.split("=", 1)[1] for line in example.splitlines() if line.startswith("LAB_MAIL_DOMAIN=")]
assert values and values[0].strip().strip('"') == ""
saved_hostenv = lab.HOSTENV
try:
    lab.HOSTENV = {}
    assert lab.mail_domain() == "lab.liveimap.test"
    lab.HOSTENV = {"LAB_MAIL_DOMAIN": ""}
    assert lab.mail_domain() == "lab.liveimap.test"
    lab.HOSTENV = {"LAB_MAIL_DOMAIN": "example.test"}
    assert lab.mail_domain() == "example.test"
finally:
    lab.HOSTENV = saved_hostenv
banned = ("user" + "_for", "LAB_" + "ZIMBRA" + "_USER")
banned_files = (
    "lab.py", "acclab.py", "jmaplab.py", "jmaprun.py", "safety_run.py",
    "t4smtp.py", "cysub.py", "sess.py", "seed_corpus.py", "host.env.example",
    "test_lab_config.py",
)
for name in banned_files:
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    for bad in banned:
        assert bad not in text, (name, bad)

# servers() skips greenmail, greenmail-16, greenmail-214, and greenmail-dev.
ports_dir = tempfile.mkdtemp()
assert os.path.abspath(ports_dir) != os.path.abspath(os.path.join(HERE, "generated"))
saved_gen = lab.GEN
try:
    lab.GEN = ports_dir
    with open(os.path.join(ports_dir, "local-ports.tsv"), "w", encoding="utf-8") as handle:
        handle.write("service\thost_port\tcontainer_port\tcontainer\n")
        handle.write("dovecot-dev-imap\t21001\t143\tliveimap-dovecot-dev\n")
        handle.write("greenmail-dev-imap\t21002\t3143\tliveimap-greenmail-dev\n")
    got = [row["name"] for row in lab.servers()]
    assert got == ["dovecot-dev"], got
finally:
    lab.GEN = saved_gen

no_greenmail = (
    "start.sh",
    "regress", "seed-snapshot.sh",
    "tls-install.sh", "lab-ca.sh", "smtp.tsv", "smtp-extra.tsv", "t4smtp.py",
)
for name in no_greenmail:
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    assert "greenmail" not in text.lower(), name

jmap = open(os.path.join(HERE, "jmaprun.py"), encoding="utf-8").read()
assert "Config.Hostname" not in jmap
assert jmap.count("lab.mail_domain()") >= 2
assert 'sys.exit("needs LAB_MAIL_DOMAIN")' not in jmap
assert "JMAP_DOMAIN_USER" in jmap
cyrus = open(os.path.join(HERE, "cyrus_domain_user.py"), encoding="utf-8").read()
assert "lab.mail_domain()" in cyrus
assert 'sys.exit("needs LAB_MAIL_DOMAIN")' not in cyrus

# Seed-image edges 1-4. Text only; this does not run docker.
start_names = ("start.sh",)
for name in start_names:
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    assert "docker volume create" not in text, name
    assert "-v liveimap-" not in text, name
snap = open(os.path.join(HERE, "seed-snapshot.sh"), encoding="utf-8").read()
assert "docker commit" in snap and "liveimap-seed-" in snap
assert "seed-snapshot.tar" not in snap
regress = open(os.path.join(HERE, "regress"), encoding="utf-8").read()
assert "--reset" in regress
assert "/liveimap-reseed" not in regress

def function_body(text, name):
    at = text.find("\n" + name + "() {")
    assert at != -1, name
    end = text.find("\n}\n", at)
    assert end != -1, name
    return text[at:end + 2]

latest = open(os.path.join(HERE, "start.sh"), encoding="utf-8").read()
# Shared lab ports: start.sh creates from the seed image with lab_ports.py flags, one server at a time.
assert 'image="liveimap-seed-$server"' in latest
assert "tools/test-servers/build-seed-image $server" in latest
assert 'python3 "$DIR/lab_ports.py" sync' in latest
assert 'port_flags=$(python3 "$DIR/lab_ports.py" flags "$server")' in latest
assert "one lab server at a time" in latest
assert "pick_ports" not in latest and "draw_ports" not in latest and "replace_port_rows" not in latest
assert "stalwartlabs/stalwart" not in latest
assert "liveimap.hostname" in latest

# Follow-up edges 1-3. Text only; this does not run docker.
up = open(os.path.join(HERE, "up.tsv"), encoding="utf-8").read()
assert "/liveimap-reseed" not in up
nine = (
    "liveimap-cyrus-22",
    "liveimap-cyrus-24",
    "liveimap-cyrus-25",
    "liveimap-cyrus-36",
    "liveimap-dovecot-10",
    "liveimap-dovecot-12",
    "liveimap-dovecot-22",
    "liveimap-courier-50",
    "liveimap-uwimap",
)
rows = {}
for line in up.splitlines():
    if line.startswith("#") or "\t" not in line:
        continue
    name, cmd = line.split("\t", 1)
    rows[name] = cmd
for name in nine:
    assert name not in rows, name
assert "liveimap-courier-606" not in rows
assert "cyrmaster" not in up
assert "setsid dovecot" not in up
assert "courier-imap" not in up
assert "inetd" not in up
commit_at = snap.find('docker commit "$name"')
assert commit_at != -1
before_commit = snap[:commit_at]
assert "Config.Entrypoint" in before_commit
assert "Config.Cmd" in before_commit
assert "Config.Env" in before_commit
assert "/liveimap-reseed" in before_commit
assert "LIVEIMAP_" in before_commit
assert "/entrypoint.sh" in before_commit
assert "|| true" not in snap
assert "draw_ports 10" not in latest
assert "draw_ports 11" not in latest
assert 'docker cp "$GEN/dovecot-245/relay.conf" "liveimap-dovecot-245:/etc/dovecot/conf.d/relay.conf"' not in latest
assert "fake-smtp-sink:*:host" not in latest

# Follow-up 2 edges. Text only; this does not run docker or a start script.
lp = open(os.path.join(HERE, "lab_ports.py"), encoding="utf-8").read()
assert 'EXTRA_SERVICES = ["fake-smtp-sink"]' in lp
assert "never redrawn" in lp
assert "a recorded port that\nis taken fails loudly" in lp or "is taken fails loudly" in lp
assert "Could not find the file" in snap
assert snap.count("Could not find the file") == 2
assert 'mktemp -d "$here/generated/seed-snapshot.XXXXXX"' in snap
assert "/tmp" not in snap
assert 'docker cp "$name:/liveimap-reseed" "$tmp/reseed" 2>&1' in snap
assert 'docker cp "$name:/entrypoint.sh" "$tmp/entrypoint.sh" 2>&1' in snap
for line in snap.splitlines():
    if "docker cp " in line:
        assert "/dev/null" not in line, line
assert '[ "$grep_status" -eq 0 ]' in snap
assert '[ "$grep_status" -ne 1 ]' in snap
assert ">/dev/null 2>&1" not in snap.split('docker cp "$name:/liveimap-reseed"', 1)[1]

# servers.tsv edges 1 and 2. Text only; this does not run docker.
import ports
assert ports.container_port("dovecot-245", "submission") == 31587
assert ports.container_port("dovecot-245", "imap") == 31143
try:
    ports.container_port("no-such", "imap")
    raise AssertionError("missing servers.tsv row should exit")
except SystemExit as e:
    text = e.code if isinstance(e.code, str) else ""
    assert e.code != 0
    assert "no-such" in text and "imap" in text

def p_arguments(text):
    found = []
    for line in text.splitlines():
        tokens = []
        buf = []
        quote = ""
        for ch in line:
            if quote:
                buf.append(ch)
                if ch == quote:
                    quote = ""
                continue
            if ch in "\"'":
                quote = ch
                buf.append(ch)
                continue
            if ch.isspace():
                if buf:
                    tokens.append("".join(buf))
                    buf = []
                continue
            buf.append(ch)
        if buf:
            tokens.append("".join(buf))
        for i, tok in enumerate(tokens):
            bare = tok.strip("\"',")
            if bare == "-p" and i + 1 < len(tokens):
                found.append(tokens[i + 1].strip("\"'\\,"))
    return found

suffixes = (":143", ":993", ":4190", ":443", ":587", ":8080", ":31143", ":31587", ":7071")
for name in start_names:
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    for arg in p_arguments(text):
        for suffix in suffixes:
            assert not arg.endswith(suffix), (name, arg)

# Port-verdict edges 1 and 2. Text only; this does not run docker.
def py_function(text, name):
    at = text.find("\ndef " + name + "(")
    assert at != -1, name
    nxt = text.find("\ndef ", at + 1)
    assert nxt != -1, name
    return text[at:nxt]

published = py_function(lab_py, "check_published")
assert "sys.exit" not in published
assert 'return ""' in published
assert "not published" in published
assert "no local-ports.tsv row" in published
checked = regress.find('err = timed_step(f"{name} check_published", lambda: lab.check_published(s["container"]))')
assert checked != -1
window = regress[checked:checked + 220]
assert 'if err:' in window
stop_line = 'return f"{name}: {err}"'  # setup_server's reason; main() turns it into a BLOCKED server
assert stop_line in window
assert window.find("if err:") < window.find(stop_line)

def whole_cport(line):
    code = line.strip()
    if "=$(cport " not in code:
        return False
    name, rhs = code.split("=", 1)
    if not name or not name.replace("_", "").isalnum():
        return False
    if not rhs.startswith("$(cport ") or not rhs.endswith(")"):
        return False
    if rhs.count("$(") != 1:
        return False
    return True

# Container ports come from servers.tsv through lab_ports.py, never from start.sh itself.
assert "$(cport" not in latest
assert 'os.path.join(HERE, "servers.tsv")' in lp

# Seed-image build edges 1-7. Text and pure functions. This does not run docker, adb, or build-seed-image.
assert "MOUNT_ROOTS" not in regress
vol = {"Type": "volume", "Name": "mail", "Source": "/var/lib/docker/volumes/mail/_data", "Destination": "/srv/vmail"}
one = lab.mount_problems(["liveimap-dovecot-245"], {"liveimap-dovecot-245": [vol]})
assert one and "\n" not in one
assert "volume" in one and "mail" in one and "/srv/vmail" in one
assert "tools/test-servers/start.sh --recreate dovecot-245" in one
green = lab.mount_problems(
    ["liveimap-greenmail"],
    {"liveimap-greenmail": [{"Type": "volume", "Name": "gm", "Destination": "/home/greenmail"}]},
)
assert "docker rm -f liveimap-greenmail" in green
assert "recreate" not in green
assert lab.mount_problems(["liveimap-dovecot-245"], {"liveimap-dovecot-245": []}) == ""
for name in ("check_published", "mount_problems", "cert_problem", "port_problems", "seed_problem"):
    body = py_function(lab_py, name)
    assert "sys.exit" not in body, name
dry_fn = py_function(regress, "dry_run_problems")
assert dry_fn.find("cert_problem") < dry_fn.find("port_problems") < dry_fn.find("seed_problem")
assert "lab_mount_errors" in dry_fn[dry_fn.find("seed_problem"):]
assert "docker start" not in dry_fn
assert "VERDICT BLOCKED" in dry_fn
assert "build-seed-image" in py_function(lab_py, "seed_problem")
dry_at = regress.find("if dry_run:")
lock_at = regress.find('write_lock("preflight")', dry_at)
dry_window = regress[dry_at:lock_at]
assert "dry_run_problems()" in dry_window
assert "stop(" in dry_window
assert dry_window.find("stop(") < dry_window.find('print("servers:')
assert "docker start" not in dry_window
ready = open(os.path.join(HERE, "lab-ready"), encoding="utf-8").read()
assert "LAB-READY BLOCKED" in ready and "LAB-READY OK" in ready
assert "git checkout" not in ready
ready_main = ready[ready.find("\ndef main("):]
ready_order = (
    'git_commit("HEAD")',
    "mem_available_mb()",
    "docker_names(False)",
    "cert_problem()",
    "port_problems(",
    "is missing",
    "mount_problems(",
    "seed_problem(",
    "test.json",
    "emulator_in_use()",
    "commit_stop(",
)
ready_pos = 0
for key in ready_order:
    at = ready_main.find(key, ready_pos)
    assert at != -1, key
    ready_pos = at
assert "sys.exit(1)" in ready[:ready.find("\ndef main(")]
assert 'blocked("commit"' in ready_main
assert ready_main.find('blocked("commit"') < ready_main.find("mem_available_mb()")

class _Proc:
    def __init__(self, code, out=""):
        self.returncode = code
        self.stdout = out
        self.stderr = ""

def _adb_fail(*_a, **_k):
    return _Proc(1, "")

saved_run = lab.subprocess.run
try:
    lab.subprocess.run = _adb_fail
    assert lab.emulator_in_use() is True
    assert lab.emulator_line == "adb failed"
finally:
    lab.subprocess.run = saved_run

assert latest.find('docker image inspect "$image"') < latest.find('docker rm -f "$name"')
assert "seed-snapshot.sh" not in latest
builder = open(os.path.join(HERE, "build-seed-image"), encoding="utf-8").read()
# The static Dockerfiles live in images/<server>/Dockerfile; build-seed-image copies each into its build context.
image_files = sorted(os.path.join(HERE, "images", d, "Dockerfile") for d in os.listdir(os.path.join(HERE, "images")))
for path in image_files:
    assert 'cp "$DIR/images/%s/Dockerfile" ' % os.path.basename(os.path.dirname(path)) in builder, path
builder_images = "".join(open(path, encoding="utf-8").read() for path in image_files)
assert "unknown server" in builder
for server in lab.SEED_SERVERS:
    assert server in builder, server
assert len(lab.SEED_SERVERS) == 19
for line in builder.splitlines():
    if "docker rm" in line and 'docker rmi "liveimap-seed-$server"' not in line:  # an image failing its audit is removed
        assert "liveimap-build-" in line, line
    if "$(" in line and not line.strip().startswith("#"):
        code = line.strip()
        assert "=" in code, line
        lhs, rhs = code.split("=", 1)
        assert lhs.replace("_", "").isalnum(), line
        if rhs.endswith(" || exit 1"):
            rhs = rhs[: -len(" || exit 1")]
        assert rhs.startswith("$(") and rhs.endswith(")") and rhs.count("$(") == 1, line
for name in ("build-seed-image",):
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    fn = function_body(text, "cport")
    assert "python3" in fn and "|| exit 1" in fn, name

# Phase 2 edge 11. Text only; this does not run docker or build-seed-image.
phase2 = (
    "cyrus-3124", "courier-606", "cyrus-3137", "dovecot-dev",
    "cyrus-22", "cyrus-24", "cyrus-25", "cyrus-36",
    "dovecot-10", "dovecot-12", "dovecot-22", "courier-50", "uwimap",
)
assert "no from-scratch builder" not in builder
assert "type=secret,id=accounts" in builder
assert "VOLUME" not in builder
assert "|| true" not in builder
assert "2>/dev/null" not in builder
for server in phase2:
    assert server in builder, server
for line in builder.splitlines():
    if "COPY" in line:
        assert "accounts.tsv" not in line, line
    if "docker create" in line:
        assert "-p" in line and "-v" not in line and "--mount" not in line, line
assert "/run/liveimap/accounts.tsv" not in latest
for marker in (
    'cat > "$CY_BUILD/entrypoint.sh"',
    'cat > "$CO_BUILD/entrypoint.sh"',
    'cat > "$CY_SRC/entrypoint.sh"',
):
    at = builder.find(marker)
    assert at != -1, marker
    end = builder.find("\nEOF\n", at)
    body = builder[at:end]
    assert "/run/liveimap/accounts.tsv" not in body, marker
    assert "apt-get" not in body, marker

# Phase 3 edges 8-10. Text only; this does not run docker or build-seed-image.
dove = builder.split("# dovecot-245-build", 1)[1].split("# end dovecot-245-build", 1)[0]
assert dove.find("fake-smtp-sink") < dove.find("open_build"), "sink row before another port"
assert "VOLUME" not in builder
assert 'upstream=$(python3 "$DIR/upstream_pins.py" image dovecot/dovecot:2.4.5)' in dove
assert "FROM $upstream AS upstream" in dove
assert "\nFROM dovecot/dovecot:2.4.5\n" not in dove
assert "FROM debian:bookworm-slim" in dove
assert "submission_relay_host = %s" in dove
assert "submission_relay_port = %s" in dove
for line in dove.splitlines():
    if "submission_relay_host = " in line and "%s" not in line:
        raise AssertionError(line)
assert "client-cert-install.sh" in dove
# Dovecot 2.4.5 start edges 1-4. Text only; this does not run docker or build-seed-image.
# The seed keeps the upstream ENV, PATH, ENTRYPOINT, CMD and USER: appended from the upstream image, then checked.
assert 'upstream_config.py" tail "$upstream"' in dove
assert 'upstream_config.py" check "$upstream"' in dove
assert "exec dovecot -F" not in dove
assert "ssl = yes" not in dove
assert "ssl_server_cert_file" not in dove
assert "auth_allow_weak_schemes = yes" in dove
assert "VOLUME" not in builder
open_body = function_body(builder, "open_build")
log_at = open_body.find("docker logs --tail 80")
fail_at = open_body.find("exit 1", log_at)
assert log_at != -1 and fail_at != -1 and log_at < fail_at
assert builder.count("docker logs --tail 80") == 9
assert 'docker inspect "liveimap-build-$server"' not in builder
assert "|| true" not in builder
assert "2>/dev/null" not in builder
assert "ssl_server_cert_file" not in builder
assert "STALWART_RECOVERY_ADMIN=admin" not in latest
assert 'upstream=$(python3 "$DIR/upstream_pins.py" image stalwartlabs/stalwart:v0.16)\n' in builder
assert 'upstream=$(python3 "$DIR/upstream_pins.py" image stalwartlabs/stalwart:v0.16.25)\n' in builder
assert 'upstream=$(python3 "$DIR/upstream_pins.py" image stalwartlabs/stalwart:v0.15.5)\n' in builder
assert "FROM $upstream AS upstream" in builder
assert "liveimap-stalwart-dev:phase1" in builder
assert "stalwart-accounts.sh" in builder
# Zimbra is created like every other server: shared ports, hostname from the seed image label, no -e, no mounts.
assert "zimbra" not in latest
assert "127.0.0.1" not in latest and "sandbox" not in latest and "443" not in latest
assert "docker create" in latest and " -e " not in latest
assert "missing liveimap-zimbra-883:lan" in builder
assert "FROM liveimap-zimbra-883:lan AS upstream" in builder_images
assert "zimbra-tls.sh" in builder

# No-test-time-seed edges 1-4. Text only; this does not run docker, regress, or build-seed-image.
assert "seed_corpus.py" not in regress
assert "seed_one(" not in regress
verify_at = regress.find("blocked, drifted = timed_step(f\"{name} verify-seed\", lambda: verify_one(")
assert verify_at != -1
stop_at = regress.find("return f", verify_at)
assert stop_at != -1
verify_fail = regress[verify_at:regress.find("\n", stop_at)]
assert "build-seed-image" in verify_fail
assert "if blocked:" in verify_fail
assert "seed_corpus.py" not in acc
assert "seed_corpus.py" not in t4
assert "seed_corpus.py" in builder
ep_at = builder.find("    stalwart-0155)\n      cat > \"$work/entrypoint.sh\" <<'EOF'\n")
assert ep_at != -1
ep_end = builder.find("\nEOF\n", ep_at)
ep = builder[ep_at:ep_end]
assert "exec /usr/local/bin/stalwart" in ep
assert "/opt/stalwart/stalwart-mail" not in ep
assert "/usr/local/bin/stalwart --init /opt/stalwart" in ep
assert "config=/opt/stalwart/etc/config.toml" in ep and '--config "$config"' in ep
assert 'if [ -n "${STALWART_PUBLIC_URL:-}" ]' in ep and "[http]" in ep
assert "stalwart-provision" not in ep
assert "stalwart binary missing" in ep

# acclab auto TLS edges 1-3. Fixture strings and source text; this does not run acclab or docker.
assert lab.classify_plain("IMAP4rev1 LOGINDISABLED", "") == "StartTls"
assert lab.classify_plain("IMAP4rev1 STARTTLS", "OK") is None
assert lab.classify_plain("IMAP4rev1", "NO PRIVACYREQUIRED") == "StartTls"
assert lab.classify_plain("", "") == "no plaintext capability"
bad_reply = lab.classify_plain("IMAP4rev1", "NO [UNAVAILABLE]")
assert isinstance(bad_reply, str) and bad_reply != "StartTls"
assert "NO [UNAVAILABLE]" in bad_reply
acc_auto = open(os.path.join(HERE, "acclab.py"), encoding="utf-8").read()
assert "resolve_auto" in acc_auto
assert "seed_corpus.py" not in acc_auto
acc_lines = acc_auto.splitlines()
auto_at = acc_lines.index('    if tls == "auto":')
auto_end = auto_at + 1
while auto_end < len(acc_lines) and (acc_lines[auto_end].startswith("        ") or acc_lines[auto_end].strip() == ""):
    auto_end += 1
auto_branch = "\n".join(acc_lines[auto_at:auto_end])
assert "resolve_auto" in auto_branch
for line in auto_branch.splitlines():
    left, sep, right = line.partition("=")
    if sep and "==" not in line and "!=" not in line:
        assert "None" not in right, line
resolve_src = py_function(lab_py, "resolve_auto")
assert "print" not in resolve_src
assert "stdout" not in resolve_src
assert "seed_corpus.py" not in resolve_src
assert "lab_imap.py" not in resolve_src
for line in resolve_src.splitlines():
    if "PASS" in line:
        assert "***" in line, line
# Auto probe logs in as the server user. Edges 1-3.
for fn in ("_plain_login_reply", "_starttls_login"):
    src = py_function(lab_py, fn)
    assert "login(user, PASS)" in src
    assert "login(USER, PASS)" not in src
assert "login" in resolve_src and "USER" in resolve_src
assert ("user" + "_for") not in resolve_src
assert "resolve_auto(s)" in acc_auto
assert "seed_corpus.py" not in acc_auto
# Image-only provision edges 1-4. Text only; this does not run docker or regress.
callers = (
    "start.sh", "regress", "lab.py", "acclab.py", "t4smtp.py",
    "jmaplab.py", "jmaprun.py", "safety_run.py", "cysub.py", "sess.py",
)
forbidden = (
    "zmprov ca", "zmprov cd", "zmprov sp", "saslpasswd2 -c",
    "stalwart-accounts.sh", "seed_corpus.py", "seed-deep",
)
for name in callers:
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    for bad in forbidden:
        assert bad not in text, (name, bad)
assert "seed_corpus.py" in builder
assert "stalwart-accounts.sh" in builder
assert "saslpasswd2 -p -c" in builder
assert "--no-reseed" not in regress
assert "LAB_NO_RESEED" not in regress
assert "domain_login_check(s)" in regress
assert snap.find("domain_login_problem") != -1
assert snap.find("domain_login_problem") < snap.rfind("docker commit")
z_at = builder.find("build_zimbra() {")
z_end = builder.find("\nbuild_volume_servers()", z_at)
zbody = builder[z_at:z_end]
assert "zimbraDefaultDomainName" in zbody
assert 'cp "$DIR/images/zimbra-883/Dockerfile" "$work/Dockerfile"' in zbody
zdf = open(os.path.join(HERE, "images", "zimbra-883", "Dockerfile"), encoding="utf-8").read()
assert "FROM scratch" in zdf and "debian:bookworm-slim" not in zdf
bare_at = zbody.find("bare login")
close_at = zbody.rfind("\n  close_build\n")
login_py = zbody.rfind("python3 - ", 0, close_at)
assert login_py != -1 and bare_at != -1 and close_at != -1
assert login_py < bare_at < close_at
chunk = zbody[login_py:close_at]
assert "sys.exit(1)" in chunk and "lab.USER" in chunk and "***" in chunk
snap_commit = snap.rfind("docker commit")
snap_bare = snap.find("bare login")
snap_py = snap.rfind("python3 - ", 0, snap_commit)
assert snap_py != -1 and snap_bare != -1 and snap_py < snap_bare < snap_commit
snap_chunk = snap[snap_py:snap_commit]
assert "zimbra-883" in snap[max(0, snap_py - 600):snap_py]
assert "lab.USER" in snap_chunk and "***" in snap_chunk and "sys.exit(1)" in snap_chunk
assert "lab.mail_domain()" in snap
# Domain login. Text and the pure check. This does not run docker or regress.
# login-policy.tsv decides: domain=yes rows accept the servers.tsv domain login, domain=no rows refuse it.
policy_rows = {}
for line in open(os.path.join(HERE, "login-policy.tsv"), encoding="utf-8"):
    if not line.strip() or line.startswith("#"):
        continue
    f = line.rstrip("\n").split("\t")
    policy_rows[f[0]] = lab.login_policy(f[0])
accept_names = {n for n, p in policy_rows.items() if p["domain"] == "yes"}
assert "uwimap" not in accept_names and lab.login_policy("uwimap")["reason"]
imap_seen = set()
for line in open(os.path.join(HERE, "servers.tsv"), encoding="utf-8"):
    if not line.strip() or line.startswith("#"):
        continue
    fields = line.rstrip("\n").split("\t")
    assert len(fields) >= 5 and fields[2].isdigit(), fields
    if fields[1] != "imap":
        assert fields[3] == "" and fields[4] == "", fields
        continue
    imap_seen.add(fields[0])
    assert fields[4] in ("accept", "refuse"), fields
    if fields[0] == "zimbra-883":
        assert fields[3] == "zimbra.zcs-foss.test" and fields[4] == "accept"
    elif fields[0].startswith("stalwart"):
        # Stalwart accounts live in the domain stalwart-accounts.sh / stalwart_015_accounts.py create.
        assert fields[3] == "localhost.local", fields
    else:
        assert fields[3] == "LAB_MAIL_DOMAIN", fields
    assert fields[4] == ("accept" if fields[0] in accept_names else "refuse"), fields
assert imap_seen == set(policy_rows), sorted(imap_seen ^ set(policy_rows))
assert accept_names <= imap_seen
problem_fn = py_function(lab_py, "domain_login_problem")
assert "sys.exit" not in problem_fn
assert 'return ""' in problem_fn
assert lab.domain_login_problem({"name": "cyrus-22"}, "OK") == ""
assert lab.domain_login_problem({"name": "zimbra-883"}, "OK") == ""
assert lab.domain_login_problem({"name": "dovecot-dev"}, "OK") == ""
assert lab.domain_login_problem({"name": "uwimap"}, "NO LOGIN failed") == ""
mismatch = lab.domain_login_problem({"name": "uwimap"}, "OK")
assert mismatch == "uwimap OK"
assert lab.domain_login_problem({"name": "dovecot-dev"}, "NO [AUTHENTICATIONFAILED] Authentication failed").startswith("dovecot-dev ")
# Clear-text refusal is expected only where login-policy.tsv says cleartext=no; LOGIN-DOMAIN only where domain=yes.
assert all(p["cleartext"] == "yes" for p in policy_rows.values())
assert "LOGIN-DOMAIN" in lab.mechanisms("IMAP4rev1 AUTH=PLAIN", "dovecot-245")
assert "LOGIN-DOMAIN" not in lab.mechanisms("IMAP4rev1 AUTH=PLAIN", "uwimap")
assert "LOGIN-DOMAIN" not in lab.mechanisms("IMAP4rev1 LOGINDISABLED AUTH=PLAIN", "dovecot-245")
assert lab.login_user("dovecot-245", "LOGIN-CMD") == lab.USER
assert lab.login_user("stalwart", "LOGIN-DOMAIN") == lab.USER.split("@")[0] + "@localhost.local"
assert lab.login_user("cyrus-22", "LOGIN-DOMAIN") == lab.USER.split("@")[0] + "@" + lab.mail_domain()
try:
    lab.login_policy("no-such-server")
    raise AssertionError("login_policy default")
except ValueError:
    pass
run_src = py_function(lab_py, "run_one")
assert 'policy["cleartext"] == "no"' in run_src and "login-policy.tsv says cleartext=yes" in run_src
refused = lab.domain_login_problem({"name": "cyrus-36"}, "NO LOGIN failed")
assert refused.startswith("cyrus-36 ") and "NO LOGIN failed" in refused
other = lab.domain_login_problem({"name": "courier-50"}, "timed out")
assert other == "courier-50 timed out"
missing = lab.domain_login_problem({"name": "not-a-server"}, "OK")
assert missing.startswith("not-a-server ") and "OK" in missing
hidden = lab.domain_login_problem({"name": "zimbra-883"}, "NO " + lab.PASS)
assert lab.PASS not in hidden and "***" in hidden and hidden.startswith("zimbra-883 ")
check_fn = py_function(regress, "domain_login_check")
assert "sys.exit" not in check_fn
assert "domain_login_problem" in check_fn
assert "lab.mail_domain()" in check_fn
assert "needs LAB_MAIL_DOMAIN" not in check_fn
call = regress.find("problem = timed_step(f\"{name} login\", lambda: domain_login_check(s))")
assert call != -1
call_window = regress[call:call + 200]
assert "return f\"{name} {problem} tools/test-servers/build-seed-image {name}\"" in call_window
assert "cyrus" + "_domain_user.py" not in regress
assert "cyrus" + "_domain_user.py" not in builder
cyrus_at = builder.find("cyrus-3124|cyrus-3137|cyrus-22|cyrus-24|cyrus-25|cyrus-36)")
sasl_at = builder.find("saslpasswd2", cyrus_at)
assert cyrus_at != -1 and sasl_at != -1 and cyrus_at < sasl_at
cyrus_block = builder[cyrus_at:sasl_at + 800]
assert "virtdomains" in cyrus_block and "needs LAB_MAIL_DOMAIN" in cyrus_block
assert "lab.mail_domain()" in cyrus_block
assert 'awk -F= \'$1 == "LAB_MAIL_DOMAIN"' not in builder
assert "***" in cyrus_block

# Suite fixture edges 1-8. Pure helpers and source text. This does not run docker, regress, or build-seed-image.
ns_inbox = b'(("INBOX." "."))'
dot_list = '* LIST (\\Noselect) "." ""'
slash_list = '* LIST (\\Noselect) "/" ""'
assert lab.suite_root(ns_inbox) == "INBOX.LiveIMAP-Test"
assert lab.suite_root(b"") == "LiveIMAP-Test"
assert lab.suite_root(None) == "LiveIMAP-Test"
prefixed = lab.suite_mailboxes(ns_inbox, dot_list)
assert [name for name, _count in prefixed] == [
    "INBOX.LiveIMAP-Test",
    "INBOX.LiveIMAP-Test.Sub",
    "INBOX.LiveIMAP-Test.Trash",
    "INBOX.LiveIMAP-Test.T&AOs-st-&ANw-n&AO8-",
    "INBOX.LiveIMAP-Test.Deep",
    "INBOX.LiveIMAP-Test.Spoof",
]
assert [count for _name, count in prefixed] == [60, 6, 0, 1, 2, 4]
plain = lab.suite_mailboxes(b"", slash_list)
assert plain[0] == ("LiveIMAP-Test", 60)
assert plain[1][0] == "LiveIMAP-Test/Sub"
exact = {name: count for name, count in prefixed}
assert lab.fixture_problem(exact, prefixed) == ""
absent = dict(exact)
del absent["INBOX.LiveIMAP-Test.Sub"]
assert lab.fixture_problem(absent, prefixed) == "INBOX.LiveIMAP-Test.Sub: missing"
short = {name: count for name, count in plain}
short["LiveIMAP-Test"] = 59
assert lab.fixture_problem(short, plain) == "LiveIMAP-Test: count 59 != 60"
assert lab.require_suite_count(60) == ""
assert lab.require_suite_count(200) == "suite count"
assert lab_py.count('\\(\\("') == 1
assert '\\(\\("' in py_function(lab_py, "suite_root")
imap_src = open(os.path.join(HERE, "lab_imap.py"), encoding="utf-8").read()
for fn in ("seed", "seed_deep", "seed_spoof"):
    assert '\\(\\("' not in py_function(imap_src, fn)
suite_fn = function_body(builder, "seed_suite")
for cmd in (
    'python3 "$DIR/lab_imap.py" seed "$lab_host" "$host_port" --tls StartTls --allow "${lab_host}:${host_port}" --count 60',
    'python3 "$DIR/lab_imap.py" seed-deep "$lab_host" "$host_port" --tls StartTls --allow "${lab_host}:${host_port}"',
    'python3 "$DIR/lab_imap.py" seed-spoof "$lab_host" "$host_port" --tls StartTls --allow "${lab_host}:${host_port}"',
):
    assert cmd in suite_fn
assert "$(" not in suite_fn
assert "|| true" not in suite_fn
assert "2>/dev/null" not in suite_fn
assert "docker rm" not in suite_fn
assert "PASS" not in suite_fn
assert builder.count("seed_suite() {") == 1
close = function_body(builder, "close_build")
assert close.find("seed_corpus.py") < close.find("\n  seed_suite\n") < close.find("docker stop")
last_corpus = builder.rfind('"$DIR/seed_corpus.py"')
tail_suite = builder.find("\nseed_suite\n", last_corpus)
tail_stop = builder.find("\ndocker stop ", last_corpus)
assert last_corpus != -1 and tail_suite != -1 and tail_stop != -1
assert last_corpus < tail_suite < tail_stop
stage = builder.find('docker commit --change "ENV STALWART_RECOVERY_ADMIN=admin:$token"')
stage_close = builder.find("\n  close_build\n", stage)
assert stage != -1 and stage_close != -1
assert "seed_suite" not in builder[stage:stage_close]
run_src = py_function(lab_py, "run_one")
assert "suite_root" in run_src
assert 's["folder"] = "LiveIMAP-Test"' not in run_src
assert "seed-deep" not in lab_py and "seed-spoof" not in lab_py
assert "seed-deep" not in regress and "seed-spoof" not in regress
assert "sys.exit" not in py_function(lab_py, "fixture_check")
assert "sys.exit" not in py_function(lab_py, "fixture_problem")
fix_at = regress.find("problem = timed_step(f\"{name} fixture\", lambda: lab.fixture_check(s))")
# verify_one (regress start/verify) also checks the suite boxes, so an empty seed folder blocks there too.
assert "suite = lab.fixture_check(s)" in py_function(regress, "verify_one")
lab_imap_src = open(os.path.join(HERE, "lab_imap.py"), encoding="utf-8").read()
for fn in ("seed", "seed_deep", "seed_spoof"):
    body = py_function(lab_imap_src, fn)
    assert "m.append(" not in body and "append_one(" in body and "require_count(" in body, fn
assert "m.close()" in py_function(lab_imap_src, "append_one") and "sys.exit" in py_function(lab_imap_src, "append_one")
assert "sys.exit" in py_function(lab_imap_src, "require_count")
assert fix_at != -1
fix_window = regress[fix_at:fix_at + 220]
assert "return f" in fix_window and "build-seed-image" in fix_window
guard = regress.rfind('if "imap" in suites:', 0, fix_at)
assert guard != -1 and fix_at - guard < 80

# Suite fixture edges 9-14. Pure helpers and source text. This does not run docker, regress, or build-seed-image.
assert lab.version_problem("cyrus-22", "1", "1", "1") == ""
assert lab.version_problem("cyrus-22", "2", "2", "1") == ""
assert lab.version_problem("cyrus-22", 1, 1, 1) == ""
low = lab.version_problem("cyrus-22", "1", "1", "2")
assert low == "cyrus-22 label 1 marker 1 need 2 tools/test-servers/build-seed-image cyrus-22"
missing_label = lab.version_problem("cyrus-22", None, "1", "1")
assert missing_label == "cyrus-22 label  marker 1 need 1 tools/test-servers/build-seed-image cyrus-22"
abc = lab.version_problem("cyrus-22", "abc", "abc", "1")
assert abc == "cyrus-22 label abc marker abc need 1 tools/test-servers/build-seed-image cyrus-22"
disagree = lab.version_problem("cyrus-22", "2", "1", "1")
assert disagree == "cyrus-22 label 2 marker 1 need 1 tools/test-servers/build-seed-image cyrus-22"
assert "sys.exit" not in py_function(lab_py, "version_problem")
assert "fixture_inputs_hash" not in py_function(lab_py, "version_problem")
assert "fixture_inputs_hash" not in py_function(lab_py, "entry_version_problem")
assert "sys.exit" not in py_function(lab_py, "fixture_inputs_hash")
assert "sys.exit" not in py_function(lab_py, "publish_problem")
fresh = lab.fixture_inputs_hash("cyrus-22")
assert fresh == lab.fixture_inputs_hash("zimbra-883")
assert fresh != lab.fixture_inputs_hash("not-a-server")
inputs_line = [line for line in open(os.path.join(HERE, "seed", "suite-fixture.tsv"), encoding="utf-8") if line.startswith("inputs\t")][0]
assert inputs_line.rstrip("\n").split("\t")[1] == fresh
assert lab.publish_problem("cyrus-22") == ""
assert lab.publish_problem("zimbra-883") == ""
import shutil
changed_base = tempfile.mkdtemp()
for rel in (
    "seed/accounts.tsv",
    "seed/corpus-v1.sums",
    "seed/suite-fixture.tsv",
    "generated/corpus-v1/manifest.tsv",
    "generated/ca/server.crt",
    "servers.tsv",
):
    dest = os.path.join(changed_base, rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copy(os.path.join(HERE, rel), dest)
assert lab.publish_problem("cyrus-22", changed_base) == ""
with open(os.path.join(changed_base, "seed", "accounts.tsv"), "a", encoding="utf-8") as handle:
    handle.write("\n")
bumped = lab.fixture_inputs_hash("cyrus-22", changed_base)
assert bumped != fresh
bump = lab.publish_problem("cyrus-22", changed_base)
assert "inputs changed and the version was not bumped" in bump
assert bump.endswith("tools/test-servers/build-seed-image cyrus-22")
# The host-generated cert is not a fixture input (each lab's differs); the seed image's label records it instead.
os.remove(os.path.join(changed_base, "generated", "ca", "server.crt"))
assert lab.fixture_inputs_hash("cyrus-22", changed_base) == bumped
cert_now = lab._cert_fingerprint(os.path.join(HERE, "generated", "ca", "server.crt"))
assert lab.cert_label_problem("cyrus-22", {"liveimap.cert-sha256": cert_now}) == ""
stale = lab.cert_label_problem("cyrus-22", {"liveimap.cert-sha256": "0" * 64})
assert stale.endswith("tools/test-servers/build-seed-image cyrus-22") and "000000000000" in stale
assert "(none)" in lab.cert_label_problem("cyrus-22", {})
assert 'python3 "$DIR/lab_cert.py" check' in builder
assert builder.count('LABEL liveimap.cert-sha256=$cert_sha') == 2
assert "_cert_fingerprint" not in py_function(lab_py, "fixture_inputs_hash")
assert "fixture_inputs_hash" not in builder
assert builder.count("required_fixture_version") == 2
assert builder.count("publish_problem") == 2
assert builder.count("store_fixture_marker") == 2
close_mark = close.find("store_fixture_marker")
assert close.find("\n  seed_suite\n") < close_mark < close.find("docker stop")
assert close.find("publish_problem") < close.find("required_fixture_version") < close.find("docker commit")
tail_mark = builder.find("store_fixture_marker", tail_suite)
assert tail_suite < tail_mark < tail_stop
tail_commit = builder.find("docker commit", tail_stop)
assert builder.find("publish_problem", tail_stop) < builder.find("required_fixture_version", tail_stop) < tail_commit
assert "required_fixture_version" not in builder[stage:stage_close]
assert "publish_problem" not in builder[stage:stage_close]
assert "store_fixture_marker" not in builder[stage:stage_close]
seed_body = py_function(lab_py, "seed_problem")
assert "required_fixture_version" in seed_body
assert "got < required" in seed_body
assert "sys.exit" not in seed_body
assert lab.seed_problem("cyrus-22", {"Config": {"Labels": {"liveimap.seed-manifest": "abc"}}}) != ""
_cert = lab._cert_fingerprint(os.path.join(HERE, "generated", "ca", "server.crt"))
assert lab.seed_problem("cyrus-22", {"Config": {"Labels": {"liveimap.seed-manifest": "1", "liveimap.cert-sha256": _cert}}}) == ""
assert "build-seed-image cyrus-22" in lab.seed_problem("cyrus-22", {"Config": {"Labels": {"liveimap.seed-manifest": "1"}}})
assert lab.seed_problem("cyrus-22", {"Config": {"Labels": {}}}) != ""
assert "Config.Volumes" in lab.seed_problem(
    "cyrus-22", {"Config": {"Labels": {"liveimap.seed-manifest": "1"}, "Volumes": {"/mail": {}}}}
)
assert "docker start" not in dry_fn
assert "docker start" not in ready
for name, call in (
    ("acclab.py", 'am", "instrument"'),
    ("jmaplab.py", 'am", "instrument"'),
    ("jmaprun.py", "else derive_rcpt("),
    ("t4smtp.py", 'am", "instrument"'),
):
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    at = text.find(call)
    assert at != -1, name
    assert text.find("entry_version_problem") != -1 and text.find("entry_version_problem") < at, name
    assert "store_fixture_marker" not in text, name
    assert "fixture_inputs_hash" not in text, name
run_body = py_function(lab_py, "run_one")
assert run_body.find("entry_version_problem") < run_body.find("instrument(")
assert "store_fixture_marker" not in run_body
ver_at = regress.find("lab.entry_version_problem(")
assert ver_at != -1 and ver_at < fix_at
assert "store_fixture_marker" not in regress
assert "fixture_inputs_hash" not in regress
t1 = open(os.path.join(HERE, "t1run.sh"), encoding="utf-8").read()
assert t1.find("entry_version_problem") < t1.find("acclab.py")
assert "store_fixture_marker" not in t1
safety = open(os.path.join(HERE, "safety_run.py"), encoding="utf-8").read()
assert "entry_version_problem" in safety
assert "docker start" not in safety
assert "store_fixture_marker" not in safety
assert "fixture_inputs_hash" not in safety
# Seed build edges 1-4. Text only; this does not run docker or build-seed-image.
assert "ensure_buildx" in builder
pins_tsv = open(os.path.join(HERE, "upstream-pins.tsv"), encoding="utf-8").read()
assert "buildx-v0.38.0.linux-amd64" in pins_tsv
assert 'upstream_pins.py" fetch "docker-buildx-$asset" "$plugin_bin"' in builder
# Every upstream fetch is pinned: each docker build first pins its Dockerfile's FROM lines.
for n_line, line in enumerate(builder.splitlines()):
    if "docker build " in line:
        assert 'upstream_pins.py" dockerfile' in builder.splitlines()[n_line - 1], line
for line in builder.splitlines():
    assert "urllib.request" not in line, line
    assert not line.strip().startswith(("CY_SHA=", "DV_SHA=", "ST_SHA=")) or "upstream_pins.py" in line, line
assert "x86_64) asset=amd64 ;;" in builder
assert "aarch64|arm64) asset=arm64 ;;" in builder
build_lines = [line for line in builder.splitlines() if "docker build " in line]
assert build_lines
for line in build_lines:
    assert 'HOME="$plugin_home"' in line, line
    assert "--progress=plain" in line, line
for line in builder.splitlines():
    if "docker commit" in line or "docker create" in line:
        assert "HOME=" not in line, line
assert "trap 'remove_build_container; exit 1' EXIT" in builder
rm_body = function_body(builder, "remove_build_container")
assert rm_body.find("docker ps -a") < rm_body.find("docker rm")
assert "2>/dev/null" not in rm_body and "|| true" not in rm_body
assert 'usage: tools/test-servers/build-seed-image --all|<server>' in builder
assert '[ "$1" = "--all" ]' in builder
assert builder.find('[ "$1" = "--all" ]') < builder.find("trap 'remove_build_container; exit 1' EXIT")
assert 'sys.stdout.write("\\n".join(lab.SEED_SERVERS))' in builder
assert "SEED START" in builder
assert "SEED FAIL" in builder
assert '"$SCRIPT" "$server" || exit 1' not in builder
assert "\nensure_buildx\n" in builder
assert builder.find("\nensure_buildx\n") < builder.find('case "$server" in')
assert lab.SEED_SERVERS[0] == "dovecot-245"
assert list(lab.RECREATE_HINTS)[0] == "liveimap-dovecot-245"
assert not os.path.exists(os.path.join(HERE, "reseed.sh"))
# One start script, edges 5-12. Text only; this does not run docker, start.sh, or build-seed-image.
for gone in ("start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh", "zimbra-883.sh"):
    assert not os.path.exists(os.path.join(HERE, gone)), gone
assert "--recreate-all" in latest
assert 'sys.stdout.write("\\n".join(lab.SEED_SERVERS))' in latest
assert '"$SCRIPT" --recreate "$server" || exit 1' in latest
assert "belongs to" not in latest
for value in lab.RECREATE_HINTS.values():
    assert value.startswith("tools/test-servers/start.sh --recreate "), value
for gone in ("start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh", "zimbra-883.sh"):
    assert gone not in regress, gone
assert "ADMIN_PW" not in latest and "LDAP_ROOT_PW" not in latest
for tag in (
    "docker build ${SEED_NO_CACHE:+--no-cache} -t liveimap-cyrus-3124:phase1",
    "docker build ${SEED_NO_CACHE:+--no-cache} -t liveimap-courier-606:phase1",
    "docker build ${SEED_NO_CACHE:+--no-cache} -t liveimap-cyrus-3137:phase2",
    "docker build ${SEED_NO_CACHE:+--no-cache} -t liveimap-dovecot-dev:phase1",
    "liveimap-stalwart-dev:phase1",
):
    assert tag in builder, tag
assert 'echo "missing $phase"' not in builder
assert "missing liveimap-zimbra-883:lan" in builder
assert "docker build" not in latest
assert "draw_ports 10" not in latest
assert "|| true" not in latest and "2>/dev/null" not in latest
assert "|| true" not in builder and "2>/dev/null" not in builder
# Seed census edges 1-8. Text only; this does not run docker or build-seed-image.
assert 'b" ".join' not in builder
assert 'b" ".join' not in snap
assert builder.count("isinstance(item, bytes)") == 2
assert snap.count("isinstance(item, bytes)") == 1
cyrus_fn = function_body(builder, "setup_cyrus")
assert "id -gn cyrus" in cyrus_fn
assert "chown -R cyrus:cyrus" not in cyrus_fn
mail_at = builder.find("if [ -x /usr/local/bin/stalwart-mail ]; then")
assert mail_at != -1
mail_end = builder.find("\nEOF\n", mail_at)
mail_ep = builder[mail_at:mail_end]
assert "--config /etc/stalwart/config.json" in mail_ep
assert "STALWART_RECOVERY_MODE=1" in mail_ep
assert "/etc/lab/stalwart-provision" in mail_ep
assert "--init" not in mail_ep
assert "config.toml" not in mail_ep
assert '{"@type":"RocksDb","path":"/var/lib/stalwart/"}' in builder
health_at = builder.find('while [ "$i" -lt 120 ]; do')
assert health_at != -1
health_end = builder.find("\ndone\n", health_at)
health = builder[health_at:health_end]
assert "STEP" in health and "stalwart health" in health
dev_fn = function_body(builder, "setup_dovecot_dev")
assert "/etc/dovecot/conf.d/auth.conf" in dev_fn
assert "ssl_server {" in dev_fn
assert "ssl_server_cert_file" not in dev_fn
assert "chown root:vmail /etc/dovecot/passwd" in dev_fn
weak_at = dev_fn.find("auth_allow_weak_schemes = yes")
passdb_at = dev_fn.find("passdb passwd-file")
assert weak_at != -1 and passdb_at != -1 and weak_at < passdb_at
courier_fn = function_body(builder, "setup_courier")
assert courier_fn.find("mkdir -p /etc/courier") < courier_fn.find("each_base courier_one")
assert "useradd -M -u 1000" in courier_fn
assert 'chown -R "$user:$user"' not in courier_fn
authd = "/usr/lib/courier-imap/sbin/authdaemond"
co_at = builder.find('cat > "$CO_BUILD/entrypoint.sh"')
co_end = builder.find("\nEOF\n", co_at)
assert authd in builder[co_at:co_end]
seed_courier = builder.find("  courier)\n    cat > \"$work/entrypoint.sh\"")
seed_end = builder.find("\nEOF\n", seed_courier)
assert authd in builder[seed_courier:seed_end]
cert_sh = open(os.path.join(HERE, "client-cert-install.sh"), encoding="utf-8").read()
case_at = cert_sh.find('case "$c" in')
case_end = cert_sh.find("esac", case_at)
case_body = cert_sh[case_at:case_end]
assert "liveimap-dovecot-245" in case_body and "liveimap-build-dovecot-245" in case_body
s15_at = builder.find("    stalwart-0155)\n      cat > \"$work/Dockerfile\"")
s15_end = builder.find("\n      ;;\n", s15_at)
s15 = builder[s15_at:s15_end]
assert "command -v curl" in s15
assert "apt-get install -y --no-install-recommends curl" in s15
assert 'bind = "0.0.0.0:{ports.container_port(server, service)}"' in open(os.path.join(HERE, "stalwart_015_toml.py"), encoding="utf-8").read()
pkg = builder.split("FROM $base_image\n", 1)[1].split("COPY certs/", 1)[0]
assert "timeout 120" not in pkg
assert "Acquire::ForceIPv4=true" in pkg
assert "/proc/" in pkg and "600" in pkg
assert "Acquire::http::Timeout=30" in pkg
assert "apt-get install" in pkg
all_at = builder.find('if [ $# -eq 1 ] && [ "$1" = "--all" ]; then')
all_end = builder.find("\nfi\n", all_at)
all_body = builder[all_at:all_end]
assert "SEED START" in all_body and "SEED FAIL" in all_body
assert '"$SCRIPT" "$server" || exit 1' not in all_body
assert "exec /usr/bin/tini" not in builder  # dovecot-245 keeps the upstream ENTRYPOINT/CMD (upstream_config.py)
for line in builder.splitlines():
    if line.strip().startswith("COPY"):
        assert "accounts.tsv" not in line, line
assert "type=secret,id=accounts" in builder
stamp_name = "check_" + "stamp"
for dirpath, dirnames, filenames in os.walk(HERE):
    dirnames[:] = [d for d in dirnames if d not in ("generated", "recordings", "__pycache__")]
    for fn in filenames:
        path = os.path.join(dirpath, fn)
        try:
            text = open(path, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        assert stamp_name not in text, path
# Remaining seed failures. Text only; this does not run docker or build-seed-image.
assert "-u root" in cert_sh and "LAB_TLS_MAX" in cert_sh
assert "ulimit -n 1024" not in pkg
jessie_gate = builder[builder.find("  dol='$'"):builder.find("FROM $base_image\n")]
assert '[ "$server" = dovecot-22 ]' in jessie_gate and "ulimit -n 1024" in jessie_gate
new_auth = "/usr/lib/courier/courier-authlib/authdaemond"
assert authd in builder[co_at:co_end] and new_auth in builder[co_at:co_end]
assert authd in builder[seed_courier:seed_end] and new_auth in builder[seed_courier:seed_end]
assert 'set_key /etc/imapd.conf servername " localhost" ":"' in cyrus_fn
assert "saslpasswd2 -p -c -u localhost cyrus" in cyrus_fn
assert 'saslpasswd2 -p -c -u localhost "$user"' in cyrus_fn
assert '"-u", domain, local' in builder
pkg_host = builder.find("seed_hostname=localhost\n")
assert builder.count('docker create --hostname "$seed_hostname" --name "liveimap-build-$server"') == 2
assert builder.count('LABEL liveimap.hostname=${seed_hostname:-}') == 2
assert pkg_host != -1 and pkg_host > builder.find("FROM $base_image\n")
assert "seed_hostname=zimbra.zcs-foss.test" in open_body
assert '@type":"Stdout"' in builder and '"buffered":false' in builder
assert "[::]:" not in open(os.path.join(HERE, "stalwart_015_toml.py"), encoding="utf-8").read() and "[::]:143" not in builder
assert "fallback-admin" in builder and "config.toml" in s15
assert "config.toml" not in mail_ep
seed_src = open(os.path.join(HERE, "seed_corpus.py"), encoding="utf-8").read()
imap_src = open(os.path.join(HERE, "lab_imap.py"), encoding="utf-8").read()
assert "lab._lab_ssl()" in seed_src
connect_src = py_function(imap_src, "connect")
assert "\n    import lab\n" in connect_src and "lab._lab_ssl()" in connect_src
cy_ep = builder.find("  cyrus)\n    cat > \"$work/entrypoint.sh\"")
cy_end = builder.find("\nEOF\n", cy_ep)
assert cy_ep != -1 and "/run/cyrus" in builder[cy_ep:cy_end]
assert builder.count("docker logs --tail 80") == 9
print("ok")
# Seed audit: build-seed-image audits live before each commit and the image after; lab-ready audits each image.
audit_src = open(os.path.join(HERE, "seed_audit.py"), encoding="utf-8").read()
assert builder.count('python3 "$DIR/seed_audit.py" live "$server" "$lab_host" "$host_port"') == 2
assert builder.count('if ! python3 "$DIR/seed_audit.py" image "$server"; then') == 2
assert builder.count('LABEL liveimap.audit=$audit') == 2
assert '"seed_audit.py"), "image", server]' in open(os.path.join(HERE, "lab-ready"), encoding="utf-8").read()
assert 'g["verify_one"](s, True)' in audit_src and "getpeercert(binary_form=True)" in audit_src
assert "/etc/lab/" in audit_src and "liveimap.audit" in audit_src

# Every suite starts from the seed: the container is dropped and recreated before each suite on each server,
# so the imap suite's appends never reach imap-idle's fixture precheck. Text only; no docker.
reset_fn = py_function(regress, "reset_for_suite")
assert "reset_lab_server(s)" in reset_fn and 'lab.only_one("")' in reset_fn
for runner, before in (("run_imap", '"acclab.py"'), ("run_lab_suite", '"lab.py"), "run"'),
                       ("run_jmap", '"jmaprun.py"'), ("run_smtp", '"t4smtp.py"')):
    body = py_function(regress, runner)
    assert "reset_or_fail(" in body, runner
    assert body.find("reset_or_fail(") < body.find(before), runner
assert "fixture_problem" not in py_function(regress, "reset_or_fail")

# Progress on stderr: n/m over planned server x suite units, a heartbeat while one runs, --quiet keeps only FAILs.
# Fakes only: no docker, lab, emulator or regress run.
import io
rg = {"__file__": os.path.join(HERE, "regress"), "__name__": "regress_progress_test"}
exec(compile(regress, "regress", "exec"), rg)
units = rg["plan_units"](["imap", "imap-idle", "jmap", "smtp", "sieve", "contacts"],
                         ["courier-50", "dovecot-245", "stalwart"], sieve=["dovecot-245"])
assert units == [("courier-50", "imap"), ("dovecot-245", "imap"), ("stalwart", "imap"),
                 ("courier-50", "imap-idle"), ("dovecot-245", "imap-idle"), ("stalwart", "imap-idle"),
                 ("stalwart", "jmap"), ("dovecot-dev", "smtp"), ("dovecot-245", "sieve"), ("dovecot-245", "contacts")], units
rg["plan"] = units[:3]
rg["run_t0"] = rg["time"].time()
err, saved = io.StringIO(), sys.stderr
sys.stderr = err
try:
    rg["add_row"]("courier-50", "imap", "plain", {"pass": 13}, "PASS", "", "", 83)
    rg["add_row"]("courier-50", "imap", "no SORT", {"pass": 12}, "PASS", "", "", 83)
    rg["quiet"] = True
    rg["add_row"]("dovecot-245", "imap", "plain", {"pass": 14}, "PASS", "", "", 5)
    rg["add_row"]("stalwart", "imap", "plain", {"fail": 1}, "FAIL", "x", "boom", 125)
    rg["quiet"] = False
    rg["HEARTBEAT_S"] = 0.2
    rg["run_log"] = tempfile.mkdtemp()
    rc, text = rg["run_cmd"]([sys.executable, "-c", "import time; time.sleep(0.5); print('hi')"], "imap-idle.log", None)
finally:
    sys.stderr = saved
out = err.getvalue().splitlines()
assert rc == 0 and text.strip() == "hi", (rc, text)
assert out[0].startswith("[regress] 1/3 courier-50 imap plain PASS 1m23s (total "), out
# n counts result lines (it moves within a unit); m is the expected line count, one per open unit by default.
assert out[1].startswith("[regress] 2/4 courier-50 imap no SORT PASS 1m23s"), out
assert out[2].startswith("[regress] 4/4 stalwart imap plain FAIL 2m05s"), out  # quiet: the PASS line is suppressed
assert len(out) >= 4 and all(l.startswith("[regress] ... still running imap-idle ") for l in out[3:]), out
assert len(rg["rows"]) == 4
main_src = regress[regress.find("\ndef main():"):regress.find("\nif __name__ == ")]
assert 'add_argument("--quiet"' in main_src and "plan = plan_units(suites, names)" in main_src
assert 'print(f"VERDICT {verdict}")' in main_src  # stdout VERDICT/summary lines unchanged
assert "file=sys.stderr" in py_function(regress, "progress")


# tlsUntrusted: a plaintext variant that failed another step must not turn the TLS-only step into an empty FAIL.
_ss = rg["step_status"]
assert _ss({"idle": ("ok", ""), "idleRenew": ("fail", "x")}, "tlsUntrusted", "fail") == ("skip", "step not run")
assert _ss({}, "tlsUntrusted", "fail") == ("fail", "lab run failed before any step ran")
assert _ss({"tlsUntrusted": ("ok", "certificate untrusted confirms=1")}, "tlsUntrusted", "fail")[0] == "ok"
_n = len(rg["rows"])
rg["quiet"] = True
rg["add_row"]("courier-50", "imap-idle", "tlsUntrusted", {}, "FAIL", "tlsUntrusted", "", 1)
assert rg["rows"][_n]["first_error"].startswith("no detail reported for tlsUntrusted"), rg["rows"][_n]
rg["add_row"]("courier-50", "imap-idle", "idleRenew", {}, "FAIL", "idleRenew", "no EXISTS after 28 renewals", 1)
assert rg["rows"][_n + 1]["first_error"] == "no EXISTS after 28 renewals"
print("ok step_status/empty-detail")

# Startup and seed/start phase progress: a start line before anything slow, setup k/K per server step with
# elapsed, a heartbeat inside one long step, per-suite reset lines; --quiet keeps only FAIL. Fakes only.
_by = {"courier-50": {"name": "courier-50", "container": "c50"}, "ext": {"name": "ext"}}
_steps = rg["setup_steps"](["courier-50", "ext"], _by, ["imap-idle"])
assert _steps == [("courier-50", "start"), ("courier-50", "check_published"), ("courier-50", "probe"),
                  ("courier-50", "login"), ("courier-50", "entry-version"), ("courier-50", "verify-seed"),
                  ("ext", "probe"), ("ext", "login"), ("ext", "entry-version"), ("ext", "verify-seed")], _steps
assert len(rg["setup_steps"](["ext"], _by, ["imap"])) == 5  # imap adds the fixture check
rg["setup_done"][0], rg["setup_total"][0] = 0, 4
rg["plan"], rg["done_units"] = [("courier-50", "imap"), ("courier-50", "imap-idle")], {("courier-50", "imap")}
rg["HEARTBEAT_S"] = 0.2
rg["quiet"] = False
err = io.StringIO(); sys.stderr = err
try:
    assert rg["timed_step"]("courier-50 start", lambda: (rg["time"].sleep(0.5), "")[1]) == ""
    assert rg["timed_step"]("courier-50 verify-seed", lambda: ("", ["d"]), failed=lambda r: bool(r[0])) == ("", ["d"])
    rg["timed_step"]("courier-50 reset for imap-idle", lambda: "", counter="reset (1/2 done)")
    rg["quiet"] = True
    rg["timed_step"]("ext probe", lambda: "")
    rg["timed_step"]("ext login", lambda: "login refused")
finally:
    sys.stderr = saved
    rg["quiet"] = False
out = err.getvalue().splitlines()
beats = [l for l in out if "still running courier-50 start" in l]
assert beats and beats[0].startswith("[regress] ... still running courier-50 start 0s ("), out
assert beats[0].endswith(" results, 1/2 server x suite units done)"), out
rest = [l for l in out if "still running" not in l]
assert rest[0].startswith("[regress] setup 1/4 courier-50 start ok 0s (total "), rest
assert rest[1].startswith("[regress] setup 2/4 courier-50 verify-seed ok "), rest
assert rest[2].startswith("[regress] reset (1/2 done) courier-50 reset for imap-idle ok "), rest
assert rest[3].startswith("[regress] setup 4/4 ext login FAIL ") and rest[3].endswith("login refused"), rest
assert len(rest) == 4, rest  # quiet: ext probe ok suppressed
main_src = regress[regress.find("\ndef main():"):regress.find("\nif __name__ == ")]
assert main_src.find('progress(f"start: ') < main_src.find("acquire_lock()") < main_src.find('write_lock(f"seed {name}")')
_setup_src = py_function(regress, "setup_server")
for st in ("start", "check_published", "probe", "login", "entry-version", "fixture", "verify-seed"):
    assert f'timed_step(f"{{name}} {st}"' in _setup_src, st
assert "timed_step(" in py_function(regress, "reset_for_suite")
print("ok setup/reset progress")

# Per-server order: servers outer, suites inner; setup once per server, a reset only between that server's
# suites, teardown (stop + rm) before the next server; a setup failure blocks one server, not the run. Fakes only.
_units = rg["plan_units"](["imap", "imap-idle", "smtp"], ["courier-50", "dovecot-12"], sieve=[])
_per = rg["server_major"](_units, ["courier-50", "dovecot-12"])
assert _per == [("courier-50", ["imap", "imap-idle"]), ("dovecot-12", ["imap", "imap-idle"]),
                ("dovecot-dev", ["smtp"])], _per
assert "plan = [(name, suite) for name, mine in per_server for suite in mine]" in main_src
assert main_src.rfind("for i, (name, mine) in enumerate(per_server, 1):") > main_src.find("cache.update(load_cache())")
assert "for name in used:" not in main_src  # the up-front setup pass over all servers is gone
_loop = main_src[main_src.rfind("for i, (name, mine) in enumerate(per_server, 1):"):]
assert _loop.find("setup_server(") < _loop.find("fresh.add(name)") < _loop.rfind("run_unit(suite, name") \
    < _loop.find("finally:") < _loop.find("teardown_server(s)")
assert "block_server(name, mine, reason)" in _loop and "continue" in _loop[_loop.find("block_server("):_loop.find("fresh.add(")]
_td = py_function(regress, "teardown_server")
assert '["docker", "stop", "-t", "20", cont]' in _td and '["docker", "rm", cont]' in _td
_rs = py_function(regress, "reset_for_suite")
assert _rs.find("if name in fresh:") < _rs.find("timed_step(")
rg["fresh"].add("courier-50")
rg["lab_servers_by_name"] = lambda: {"courier-50": {"name": "courier-50", "container": "liveimap-courier-50"}}
assert rg["reset_for_suite"]("imap", "courier-50") == "" and "courier-50" not in rg["fresh"]  # first suite: no reset
_n = len(rg["rows"])
rg["quiet"] = True
err = io.StringIO(); sys.stderr = err
try:
    rg["block_server"]("courier-50", ["imap", "imap-idle"], "courier-50: no IMAP greeting after 300s")
finally:
    sys.stderr = saved
    rg["quiet"] = False
_bl = err.getvalue().splitlines()
assert _bl[0].startswith("[regress] BLOCKED courier-50 setup: courier-50: no IMAP greeting after 300s"), _bl  # loud even with --quiet
assert len(_bl) == 3 and all(" BLOCKED " in l for l in _bl[1:]), _bl
assert [r["result"] for r in rg["rows"][_n:]] == ["BLOCKED", "BLOCKED"]
assert [r["suite"] for r in rg["rows"][_n:]] == ["imap", "imap-idle"]  # the suite name is recorded as given
assert "courier-50 imap-idle setup" in rg["failing_names"](rg["rows"][_n:])
assert 'row["result"] in ("FAIL", "BLOCKED")' in main_src
assert 'f"{suite}-{todo[0]}.log"' in py_function(regress, "run_lab_suite")  # one server per lab.py run: own log
print("ok per-server order")
for l in out:
    print("  sample:", l)

# Full-regress fixes 2026-10-22. Pure helpers and source text. This does not run docker, regress, or a device.
assert lab.POLICY_REFUSED.search("login failed: Cleartext authentication disallowed on non-secure (SSL/TLS) connections.")
with tempfile.TemporaryDirectory() as _d:
    _j = os.path.join(_d, "zimbra-883-None.jsonl")
    with open(_j, "w") as _h:
        _h.write('{"conn":"main","dir":"C","line":"2 LOGIN x cleartext logins disabled"}\n')
        _h.write('not json\n')
    _rows = [["open", "fail", "login failed (server rejected LOGIN)"]]
    assert not lab.policy_refused(_rows, [_j])  # client lines do not count
    with open(_j, "a") as _h:
        _h.write('{"conn":"main","dir":"S","line":"2 NO cleartext logins disabled"}\n')
    assert lab.policy_refused(_rows, [_j])
assert lab.policy_refused([["open", "fail", "LOGIN is disabled on the clear-text port"]], [])
_ss = {}
exec(py_function(regress, "step_status"), _ss)
assert _ss["step_status"]({"open": ("fail", "x")}, "open", "expected")[0] == "skip"
assert _ss["step_status"]({"open": ("fail", "x")}, "open", "fail")[0] == "fail"
assert _ss["step_status"]({"idle": ("fail", "x")}, "idle", "expected")[0] == "fail"  # KNOWN via known-limits
_kl = open(os.path.join(HERE, "known-limits.tsv")).read()
assert "stalwart-0155\timap-idle\tidle" not in _kl and "uwimap\timap-idle\tidleRenew" in _kl
assert "\tjmap\tsubmit\t" not in _kl and "\tjmap\tdelivered\t" not in _kl  # cyrus JMAP submission now has a sendmail sink
import lab_ports
assert lab_ports.env_flags("dovecot-245", {}) == []
_start = open(os.path.join(HERE, "start.sh")).read()
assert '$port_flags $env_flags "$image"' in _start
print("full-regress fixes ok")
