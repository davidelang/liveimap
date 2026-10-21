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
saved_user = lab.HOSTENV.get("LAB_ZIMBRA_USER")
lab.HOSTENV["LAB_ZIMBRA_USER"] = "zimbra-user@example.test"
try:
    assert lab.user_for({"name": "zimbra"}) == "zimbra-user@example.test"
    assert lab.user_for({"name": "cyrus"}) == lab.USER
    del lab.HOSTENV["LAB_ZIMBRA_USER"]
    try:
        lab.user_for({"name": "zimbra"})
        raise AssertionError("missing LAB_ZIMBRA_USER should exit")
    except SystemExit as e:
        assert e.code == "needs LAB_ZIMBRA_USER"
finally:
    if saved_user is None:
        lab.HOSTENV.pop("LAB_ZIMBRA_USER", None)
    else:
        lab.HOSTENV["LAB_ZIMBRA_USER"] = saved_user
example = open(os.path.join(HERE, "host.env.example"), encoding="utf-8").read()
assert "requires" in example
for key in ("LAB_MAIL_DOMAIN", "LAB_ZIMBRA_USER"):
    values = [line.split("=", 1)[1] for line in example.splitlines() if line.startswith(key + "=")]
    assert values and values[0].strip().strip('"') == ""

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
    "start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh",
    "reseed.sh", "regress", "seed-snapshot.sh", "zimbra-883.sh",
    "tls-install.sh", "lab-ca.sh", "smtp.tsv", "smtp-extra.tsv", "t4smtp.py",
)
for name in no_greenmail:
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    assert "greenmail" not in text.lower(), name

jmap = open(os.path.join(HERE, "jmaprun.py"), encoding="utf-8").read()
assert "Config.Hostname" not in jmap
assert jmap.count('sys.exit("needs LAB_MAIL_DOMAIN")') >= 2
assert "JMAP_DOMAIN_USER" in jmap
cyrus = open(os.path.join(HERE, "cyrus_domain_user.py"), encoding="utf-8").read()
assert 'lab.HOSTENV.get("LAB_MAIL_DOMAIN")' in cyrus
assert 'sys.exit("needs LAB_MAIL_DOMAIN")' in cyrus

# Seed-image edges 1-4. Text only; this does not run docker.
start_names = (
    "start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh", "zimbra-883.sh",
)
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

latest = open(os.path.join(HERE, "start-latest.sh"), encoding="utf-8").read()
reset_body = function_body(latest, "reset_from_seed")
seed_body = function_body(latest, "require_seed_image")
assert "liveimap-seed-$server" in reset_body
assert "local-ports.tsv" in reset_body
assert "require_seed_image" in reset_body
assert "pick_ports" not in reset_body
assert "stalwartlabs/stalwart" not in reset_body
assert 'tools/test-servers/build-seed-image $server' in seed_body
assert "seed-snapshot.sh" not in seed_body
assert 'reset_from_seed "$2"' in latest
assert "stalwart-01625)" in latest or "stalwart-01625|" in latest

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
assert "draw_ports 10" in latest
assert "draw_ports 11" not in latest
assert 'docker cp "$GEN/dovecot-245/relay.conf" "liveimap-dovecot-245:/etc/dovecot/conf.d/relay.conf"' not in latest
assert "fake-smtp-sink:*:host" not in latest

# Follow-up 2 edges. Text only; this does not run docker or a start script.
sink_body = function_body(latest, "sink_port")
zero_at = sink_body.find('[ "$count" -eq 0 ]')
draw_at = sink_body.find("draw_ports 1)")
write_at = sink_body.find("printf 'fake-smtp-sink\\t%s\\t%s\\thost\\n'")
other_at = sink_body.find('[ "$count" -ne 1 ]')
assert zero_at != -1 and draw_at != -1 and write_at != -1 and other_at != -1
assert zero_at < draw_at < write_at < other_at
assert "draw_ports" not in sink_body[other_at:]
assert "exit 1" in sink_body[other_at:]
assert "--recreate dovecot-245" in sink_body
assert sink_body.count("draw_ports") == 1
assert latest.count("draw_ports 1)") == 1
assert latest.count("draw_ports 10") == 1
pick_body = function_body(latest, "pick_ports")
assert "draw_ports" not in pick_body
assert "fake-smtp-sink" not in pick_body
tail = latest[latest.find('echo "drawing ports"'):]
assert "draw_ports 10" in tail
assert "draw_ports 1)" not in tail
assert "fake-smtp-sink" not in tail
sink_at = latest.find("sink_port >/dev/null")
draw10_at = latest.find("PORTS=$(draw_ports 10)")
assert sink_at != -1 and draw10_at != -1 and sink_at < draw10_at
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
checked = regress.find('err = lab.check_published(s["container"])')
assert checked != -1
window = regress[checked:checked + 220]
assert 'if err:' in window
stop_line = 'stop(f"VERDICT BLOCKED {name}: {err}", suites, names, [])'
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

for name in ("start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh"):
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    saw = False
    for line in text.splitlines():
        if "$(cport" not in line:
            continue
        saw = True
        assert whole_cport(line), (name, line)
        for bad in ("-p", "printf", "pick_ports", "expected_ports", "published_port"):
            assert bad not in line, (name, line)
    assert saw, name

# Seed-image build edges 1-7. Text and pure functions. This does not run docker, adb, or build-seed-image.
assert "MOUNT_ROOTS" not in regress
vol = {"Type": "volume", "Name": "mail", "Source": "/var/lib/docker/volumes/mail/_data", "Destination": "/srv/vmail"}
one = lab.mount_problems(["liveimap-dovecot-245"], {"liveimap-dovecot-245": [vol]})
assert one and "\n" not in one
assert "volume" in one and "mail" in one and "/srv/vmail" in one
assert "tools/test-servers/start-latest.sh --recreate dovecot-245" in one
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

for name in ("start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh"):
    body = function_body(open(os.path.join(HERE, name), encoding="utf-8").read(), "reset_from_seed")
    assert body.find("require_seed_image") < body.find("docker rm"), name
    seed_fn = function_body(open(os.path.join(HERE, name), encoding="utf-8").read(), "require_seed_image")
    assert "build-seed-image" in seed_fn and "seed-snapshot.sh" not in seed_fn
zimbra = open(os.path.join(HERE, "zimbra-883.sh"), encoding="utf-8").read()
reset_z = py_function(zimbra, "reset_zimbra")
assert reset_z.find("require_seed_image") < reset_z.find('"docker", "rm"')
assert "build-seed-image zimbra-883" in py_function(zimbra, "require_seed_image")
builder = open(os.path.join(HERE, "build-seed-image"), encoding="utf-8").read()
assert "unknown server" in builder
for server in lab.SEED_SERVERS:
    assert server in builder, server
assert len(lab.SEED_SERVERS) == 19
for line in builder.splitlines():
    if "docker rm" in line:
        assert "liveimap-build-" in line, line
    if "$(" in line and not line.strip().startswith("#"):
        code = line.strip()
        assert "=" in code, line
        lhs, rhs = code.split("=", 1)
        assert lhs.replace("_", "").isalnum(), line
        assert rhs.startswith("$(") and rhs.endswith(")") and rhs.count("$(") == 1, line
for name in ("build-seed-image", "start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh"):
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
for name in ("start-latest.sh", "start-dev.sh", "start-older.sh"):
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    assert "/run/liveimap/accounts.tsv" not in text, name
older = open(os.path.join(HERE, "start-older.sh"), encoding="utf-8").read()
for fn in ("start_cyrus", "start_dovecot", "start_courier", "start_uw"):
    body = function_body(older, fn)
    assert "apt-get" not in body, fn
    assert "--mount" not in body and " -v " not in body, fn
    assert "docker cp" not in body, fn
for path, marker in (
    ("start-latest.sh", 'cat > "$CY_BUILD/entrypoint.sh"'),
    ("start-latest.sh", 'cat > "$CO_BUILD/entrypoint.sh"'),
    ("start-dev.sh", 'cat > "$CY_SRC/entrypoint.sh"'),
):
    text = open(os.path.join(HERE, path), encoding="utf-8").read()
    at = text.find(marker)
    assert at != -1, marker
    end = text.find("\nEOF\n", at)
    body = text[at:end]
    assert "/run/liveimap/accounts.tsv" not in body, marker
    assert "apt-get" not in body, marker

# Phase 3 edges 8-10. Text only; this does not run docker or build-seed-image.
dove = builder.split("# dovecot-245-build", 1)[1].split("# end dovecot-245-build", 1)[0]
assert dove.find("fake-smtp-sink") < dove.find("open_build"), "sink row before another port"
assert "VOLUME" not in builder
assert "FROM dovecot/dovecot:2.4.5 AS upstream" in dove
assert "\nFROM dovecot/dovecot:2.4.5\n" not in dove
assert "FROM debian:bookworm-slim" in dove
assert "submission_relay_host = %s" in dove
assert "submission_relay_port = %s" in dove
for line in dove.splitlines():
    if "submission_relay_host = " in line and "%s" not in line:
        raise AssertionError(line)
assert "client-cert-install.sh" in dove
for name in ("start-latest.sh", "start-dev.sh", "start-local.sh", "start-older.sh"):
    text = open(os.path.join(HERE, name), encoding="utf-8").read()
    assert "STALWART_RECOVERY_ADMIN=admin" not in text, name
assert "upstream=stalwartlabs/stalwart:v0.16\n" in builder
assert "upstream=stalwartlabs/stalwart:v0.16.25\n" in builder
assert "upstream=stalwartlabs/stalwart:v0.15.5\n" in builder
assert "FROM $upstream AS upstream" in builder
assert "liveimap-stalwart-dev:phase1" in builder
assert "stalwart-accounts.sh" in builder
zcreate = py_function(zimbra, "zimbra_create")
assert "-e" not in zcreate
assert "--hostname" not in zcreate
assert "127.0.0.1" not in zcreate
assert "sandbox" not in zcreate
assert "443" not in zcreate
assert "tools/test-servers/generated" in zimbra
assert "tools/test-servers/seed/accounts.tsv" in zimbra
assert "missing liveimap-zimbra-883:lan" in builder
assert "FROM liveimap-zimbra-883:lan AS upstream" in builder
assert "zimbra-tls.sh" in builder
stop_fn = function_body(zimbra, "stop_zimbra")
assert "|| true" not in stop_fn
assert "2>/dev/null" not in stop_fn
assert "docker stop liveimap-zimbra-883" in stop_fn

# No-test-time-seed edges 1-4. Text only; this does not run docker, regress, or build-seed-image.
assert "seed_corpus.py" not in regress
assert "seed_one(" not in regress
verify_at = regress.find("blocked, drifted = verify_one(")
assert verify_at != -1
stop_at = regress.find("stop(", verify_at)
assert stop_at != -1
verify_fail = regress[verify_at:regress.find("\n", stop_at)]
assert "build-seed-image" in verify_fail
assert "stop(" in verify_fail
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
assert "--config /opt/stalwart/etc/config.toml" in ep
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
assert "user_for(s)" in resolve_src
assert "resolve_auto(s)" in acc_auto
assert "seed_corpus.py" not in acc_auto
print("ok")
