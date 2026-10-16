#!/usr/bin/env python3
"""Edges for the lab runners beside the other test-server tools.
Run from the repo root: python3 tools/test-servers/test_lab_config.py"""
import os, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab

lab_py = open(os.path.join(HERE, "lab.py"), encoding="utf-8").read()
assert "assembleX86_64DebugAndroidTest" in lab_py
assert "app-x86_64-debug-androidTest.apk" in lab_py
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
jmap = open(os.path.join(HERE, "jmaprun.py"), encoding="utf-8").read()
assert "Config.Hostname" not in jmap
assert jmap.count('sys.exit("needs LAB_MAIL_DOMAIN")') >= 2
assert "JMAP_DOMAIN_USER" in jmap
cyrus = open(os.path.join(HERE, "cyrus_domain_user.py"), encoding="utf-8").read()
assert 'lab.HOSTENV.get("LAB_MAIL_DOMAIN")' in cyrus
assert 'sys.exit("needs LAB_MAIL_DOMAIN")' in cyrus
print("ok")
