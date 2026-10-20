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

# Edge 2: generate generated/corpus-v1, and a failed seed stops the matrix.
seed = open(os.path.join(HERE, "seed_corpus.py"), encoding="utf-8").read()
assert "generated" in seed and "corpus-v1" in seed
assert "make_corpus.load_tables" in seed and "make_corpus.generate" in seed
assert "manifest.tsv" in seed
acc = open(os.path.join(HERE, "acclab.py"), encoding="utf-8").read()
retry = acc.find('seed("--starttls")')
assert retry != -1
after = acc[retry:]
assert after.find("if rc != 0:") != -1
assert after.find("sys.exit") != -1 and after.find("sys.exit") < after.find("acceptanceMatrix")
t4 = open(os.path.join(HERE, "t4smtp.py"), encoding="utf-8").read()
seed_at = t4.find("seed_corpus.py")
exit_at = t4.find("sys.exit", seed_at)
smtp_at = t4.find("smtpAccepts", seed_at)
assert seed_at != -1 and exit_at != -1 and smtp_at != -1 and exit_at < smtp_at
assert "returncode" in t4[seed_at:exit_at]
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
assert 'tools/test-servers/seed-snapshot.sh $server' in seed_body
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
    assert name in rows, name
    assert not rows[name].lstrip().startswith("{"), name
assert "cyrmaster" in rows["liveimap-cyrus-22"]
assert "cyrmaster" in rows["liveimap-cyrus-24"]
assert "cyrmaster" in rows["liveimap-cyrus-25"]
assert "cyrmaster" in rows["liveimap-cyrus-36"]
assert "setsid dovecot" in rows["liveimap-dovecot-10"]
assert "setsid dovecot" in rows["liveimap-dovecot-12"]
assert "setsid dovecot" in rows["liveimap-dovecot-22"]
assert "courier-imap" in rows["liveimap-courier-50"]
assert "inetd" in rows["liveimap-uwimap"]
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
assert 'docker cp "$GEN/dovecot-245/relay.conf" "liveimap-dovecot-245:/etc/dovecot/conf.d/relay.conf"' in latest
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
print("ok")
