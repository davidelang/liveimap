#!/usr/bin/env python3
"""Layout and git-attribute checks for the settings-tree clean filter and collapse script. Does not load libliveimap."""
import json
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TREE = ROOT / "app" / "src" / "main" / "assets" / "settings-tree.json"
FILTER = ROOT / "settings-tree-filter"
COLLAPSE = ROOT / "settings-tree-collapse"


def fail(message):
    raise SystemExit(message)


def run_filter(mode, text):
    proc = subprocess.run(
        [str(FILTER), mode],
        input=text.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if proc.returncode != 0:
        fail("%s failed: %s" % (mode, proc.stderr.decode()))
    return proc.stdout


def run_collapse(text):
    with tempfile.TemporaryDirectory(prefix="settings-tree-collapse-") as directory:
        path = Path(directory) / "tree.json"
        path.write_bytes(text.encode("utf-8"))
        names = set(Path(directory).iterdir())
        proc = subprocess.run(
            [str(COLLAPSE), str(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        got = path.read_bytes()
        if set(Path(directory).iterdir()) != names:
            fail("collapse wrote a second file")
        if proc.stdout:
            fail("collapse wrote stdout")
        if proc.returncode != 0:
            fail("collapse failed: %s" % proc.stderr.decode())
        return got


def check_collapse_parse_failure():
    with tempfile.TemporaryDirectory(prefix="settings-tree-collapse-") as directory:
        path = Path(directory) / "tree.json"
        bad = b"{ not json\n"
        path.write_bytes(bad)
        proc = subprocess.run(
            [str(COLLAPSE), str(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if proc.returncode == 0:
            fail("bad tree exited 0")
        if path.read_bytes() != bad:
            fail("bad tree was rewritten")
        if proc.stdout:
            fail("collapse wrote stdout on failure")


def check_filter_usage():
    proc = subprocess.run(
        [str(FILTER), "collapse"],
        input=b"{}\n",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    err = proc.stderr.decode()
    if proc.returncode == 0 or proc.stdout or "collapse" in err or "clean" not in err:
        fail("filter accepted collapse: %s" % err)
    missing = subprocess.run(
        [str(FILTER)],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    usage = missing.stderr.decode()
    if missing.returncode == 0 or "collapse" in usage:
        fail("usage with no args: %s" % usage)


def ugly_text(pretty):
    # Same tree: 4-space indent, every leaf expanded, a newline in every list, trailing commas.
    doc = json.loads(pretty)

    def dump(value, indent):
        pad = " " * indent
        if isinstance(value, dict):
            if not value:
                return "{}"
            lines = ["{"]
            for key, item in value.items():
                lines.append(pad + "    " + json.dumps(key) + ": " + dump(item, indent + 4) + ",")
            lines.append(pad + "}")
            return "\n".join(lines)
        if isinstance(value, list):
            if not value:
                return "[]"
            lines = ["["]
            for item in value:
                lines.append(pad + "    " + dump(item, indent + 4) + ",")
            lines.append(pad + "]")
            return "\n".join(lines)
        return json.dumps(value)

    return dump(doc, 0) + "\n"


def check_layout(collapsed):
    lines = collapsed.splitlines()
    if not lines or lines[-1] != "}":
        fail("root closing line is %r" % (lines[-1] if lines else ""))
    for index, line in enumerate(lines):
        stripped = line.strip()
        if (len(line) - len(line.lstrip(" "))) % 2 or "\t" in line:
            fail("indent: %s" % line)
        if ", }" in line or ", ]" in line:
            fail("extra comma: %s" % line)
        if stripped in ("{", "[") or stripped.endswith("{") or stripped.endswith("["):
            if stripped.endswith(","):
                fail("comma on opener: %s" % line)
            continue
        if index == len(lines) - 1:
            continue
        if not stripped.endswith(","):
            fail("missing comma: %s" % line)
    zoom = [
        line for line in lines
        if '"setting": "appTextZoom"' in line
    ]
    if zoom != [
        '            { "setting": "appTextZoom", "type": "Int", "scope": "ui", '
        '"kind": "number", "default": 100, "title": "Font size", '
        '"min": 50, "max": 300 },'
    ]:
        fail("appTextZoom: %r" % zoom)
    tls_at = next(i for i, line in enumerate(lines) if line.strip() == '"setting": "tlsMode",')
    window = lines[tls_at:tls_at + 12]
    for needle in (
        '"choices": [ "None", "StartTls", "Implicit" ],',
        '"choiceTitles": { "None": "None", "StartTls": "STARTTLS", '
        '"Implicit": "Implicit TLS" },',
        '"visibleWhen": { "setting": "jmapFolders", "equals": false },',
    ):
        if not any(needle in line for line in window):
            fail("tlsMode missing %s" % needle)
    if any('"setting": "tlsMode"' in line and '"choices"' in line for line in lines):
        fail("tlsMode collapsed onto one line")
    chain = '"summary": [ { "value": { "choiceTitle": "bodyView" } } ],'
    if not any(line.strip() == chain for line in lines):
        fail("summary chain not one line")
    display = next(
        i for i, line in enumerate(lines)
        if line.strip() == '"text": "%1$s, %2$s",'
        and lines[i + 1].strip()
        == '"args": [ { "choiceTitle": "density" }, { "choiceTitle": "dateFormat" } ],'
    )
    if lines[display - 2].strip() != '"summary": [':
        fail("display summary not expanded: %s" % lines[display - 2])
    args = lines[display + 1].strip()
    if args != '"args": [ { "choiceTitle": "density" }, { "choiceTitle": "dateFormat" } ],':
        fail("display args: %s" % args)
    delays = [line for line in lines if '"default": [ 2, 10, 60 ]' in line]
    if len(delays) != 1:
        fail("reconnect delays: %r" % delays)


def check_attributes():
    lines = [
        line.strip() for line in (ROOT / ".gitattributes").read_text().splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    tree = [line for line in lines if "settings-tree.json" in line]
    if tree != ["app/src/main/assets/settings-tree.json filter=settings-tree merge=binary"]:
        fail("tree attributes: %r" % tree)
    if sum("filter=settings-tree" in line for line in lines) != 1:
        fail("filter=settings-tree must be on that path only")
    if sum("merge=binary" in line for line in lines) != 1:
        fail("merge=binary must be on that path only")
    if any("merge=union" in line or "merge=text" in line for line in lines):
        fail("union or text merge attribute set")
    if "diff" in tree[0]:
        fail("diff attribute set on the tree")
    if "settings-tree-filter !filter" not in lines:
        fail("settings-tree-filter is not !filter")
    if "settings-tree-collapse !filter" not in lines:
        fail("settings-tree-collapse is not !filter")
    script = (ROOT / "install-merge-drivers.sh").read_text()
    if 'git config filter.settings-tree.clean "./settings-tree-filter clean"' not in script:
        fail("clean filter is not registered")
    for banned in (
        "filter.settings-tree.smudge",
        "filter.settings-tree.required",
        "merge.settings-tree",
    ):
        if banned in script:
            fail("installer sets %s" % banned)


def git(repo, *args, check=True):
    proc = subprocess.run(["git", "-C", str(repo), *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and proc.returncode != 0:
        fail("%s\n%s\n%s" % (args, proc.stdout.decode(), proc.stderr.decode()))
    return proc


def check_merge_driver():
    repo = Path(tempfile.mkdtemp(prefix="settings-tree-merge-"))
    git(repo, "init", "-q", "-b", "master")
    git(repo, "config", "user.email", "t@example.com")
    git(repo, "config", "user.name", "t")
    git(repo, "config", "filter.settings-tree.clean", "%s clean" % FILTER)
    (repo / ".gitattributes").write_text("tree.json filter=settings-tree merge=binary\n")
    tree = repo / "tree.json"
    pretty = '{\n  "a": 1\n}\n'
    tree.write_text(pretty)
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", "base")
    blob = git(repo, "rev-parse", "HEAD:tree.json").stdout.decode().strip()
    tree.write_text('{\n  "a": 1,\n}\n')
    git(repo, "add", "tree.json")
    if git(repo, "diff", "--cached", "--quiet", "--", "tree.json", check=False).returncode != 0:
        fail("layout-only add changed the blob")
    if git(repo, "rev-parse", ":tree.json").stdout.decode().strip() != blob:
        fail("index blob changed")
    tree.write_text('{\n  "a": 9\n}\n')
    git(repo, "checkout", "HEAD", "--", "tree.json")
    if tree.read_text() != pretty:
        fail("checkout did not write the blob: %r" % tree.read_text())
    # One side (theirs) keeps that side.
    git(repo, "branch", "other")
    git(repo, "checkout", "-q", "other")
    tree.write_text('{\n  "a": 3\n}\n')
    git(repo, "commit", "-q", "-am", "theirs")
    git(repo, "checkout", "-q", "master")
    merged = git(repo, "merge", "--no-edit", "other", check=False)
    if merged.returncode != 0 or tree.read_text() != '{\n  "a": 3\n}\n':
        fail("one-side merge: %s %r" % (merged.returncode, tree.read_text()))
    if "<<<<<<<" in tree.read_text():
        fail("one-side merge wrote markers")
    git(repo, "reset", "--hard", "HEAD~1")
    # Both sides: keep ours, conflict, no markers.
    git(repo, "branch", "-f", "other")
    tree.write_text('{\n  "a": 2\n}\n')
    git(repo, "commit", "-q", "-am", "ours")
    git(repo, "checkout", "-q", "other")
    tree.write_text('{\n  "a": 3\n}\n')
    git(repo, "commit", "-q", "-am", "theirs2")
    git(repo, "checkout", "-q", "master")
    both = git(repo, "merge", "--no-edit", "other", check=False)
    if both.returncode == 0 or tree.read_text() != '{\n  "a": 2\n}\n' or "<<<<<<<" in tree.read_text():
        fail("both-sides merge: %s %r" % (both.returncode, tree.read_text()))
    if not git(repo, "ls-files", "-u", "--", "tree.json").stdout:
        fail("both-sides merge did not leave the path conflicted")
    git(repo, "merge", "--abort")
    # Revert: only the reverted commit changed the file, so that side (the parent) stays.
    reverted = git(repo, "revert", "--no-edit", "HEAD", check=False)
    if reverted.returncode != 0 or tree.read_text() != pretty or "<<<<<<<" in tree.read_text():
        fail("one-side revert: %s %r" % (reverted.returncode, tree.read_text()))
    # Both sides of a revert: a later edit plus an older change. Keep the current text.
    tree.write_text('{\n  "a": 2\n}\n')
    git(repo, "commit", "-q", "-am", "middle")
    middle = git(repo, "rev-parse", "HEAD").stdout.decode().strip()
    tree.write_text('{\n  "a": 4\n}\n')
    git(repo, "commit", "-q", "-am", "later")
    rev = git(repo, "revert", "--no-edit", middle, check=False)
    if rev.returncode == 0 or tree.read_text() != '{\n  "a": 4\n}\n' or "<<<<<<<" in tree.read_text():
        fail("both-sides revert: %s %r" % (rev.returncode, tree.read_text()))
    if not git(repo, "ls-files", "-u", "--", "tree.json").stdout:
        fail("revert did not leave the path conflicted")
    git(repo, "revert", "--abort")
    # Cherry-pick one side keeps the picked side; both sides keep the current branch.
    git(repo, "checkout", "-q", "-b", "pick", "HEAD~1")
    tree.write_text('{\n  "a": 5\n}\n')
    git(repo, "commit", "-q", "-am", "pickme")
    pick = git(repo, "rev-parse", "HEAD").stdout.decode().strip()
    git(repo, "checkout", "-q", "master")
    # master is "later" (a=4), which also changed the file from the parent of pick? 
    # pick branched from middle (a=2). master is later (a=4). Both differ from a=2.
    both_pick = git(repo, "cherry-pick", "--no-edit", pick, check=False)
    if both_pick.returncode == 0 or tree.read_text() != '{\n  "a": 4\n}\n' or "<<<<<<<" in tree.read_text():
        fail("both-sides cherry-pick: %s %r" % (both_pick.returncode, tree.read_text()))
    if not git(repo, "ls-files", "-u", "--", "tree.json").stdout:
        fail("cherry-pick did not leave the path conflicted")
    git(repo, "cherry-pick", "--abort")
    # pick's parent matches this tree, so only the picked side changed.
    git(repo, "checkout", "-q", "-B", "plain", "pick~1")
    one_pick = git(repo, "cherry-pick", "--no-edit", pick, check=False)
    if one_pick.returncode != 0 or tree.read_text() != '{\n  "a": 5\n}\n' or "<<<<<<<" in tree.read_text():
        fail("one-side cherry-pick: %s %r" % (one_pick.returncode, tree.read_text()))


def check_worktree_diff(collapsed, ugly):
    original = TREE.read_bytes()
    try:
        for body in (original, collapsed.encode("utf-8"), ugly.encode("utf-8")):
            TREE.write_bytes(body)
            diff = subprocess.run(
                ["git", "diff", "--", "app/src/main/assets/settings-tree.json"],
                cwd=ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if diff.returncode != 0 or diff.stdout:
                fail("layout diff not empty: %s" % diff.stdout[:400])
        edited = collapsed.replace(
            '"setting": "appTextZoom", "type": "Int", "scope": "ui", "kind": "number", "default": 100,',
            '"setting": "appTextZoom", "type": "Int", "scope": "ui", "kind": "number", "default": 101,',
            1,
        )
        if edited == collapsed:
            fail("could not edit appTextZoom")
        TREE.write_text(edited)
        diff = subprocess.run(
            ["git", "diff", "--", "app/src/main/assets/settings-tree.json"],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        text = diff.stdout.decode()
        if "Binary files" in text or "<<<<<<<" in text:
            fail(text[:400])
        if '-              "default": 100,' not in text or '+              "default": 101,' not in text:
            fail("pretty line diff missing:\n%s" % text[:800])
    finally:
        TREE.write_bytes(original)


def drop_generator_bytecode():
    cache = ROOT / "app" / "__pycache__"
    if not cache.is_dir():
        return
    for path in cache.glob("gen-settings-tree.cpython-*.pyc"):
        path.unlink()


def bytecode_files():
    found = []
    for path in ROOT.rglob("*.pyc"):
        found.append(path.relative_to(ROOT).as_posix())
    return sorted(found)


def pretty_blob():
    # Checkout writes the pretty blob. A layout-only worktree edit still cleans to it.
    proc = subprocess.run(
        ["git", "-C", str(ROOT), "show", "HEAD:app/src/main/assets/settings-tree.json"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if proc.returncode != 0:
        fail(proc.stderr.decode())
    return proc.stdout


def main():
    drop_generator_bytecode()
    before = bytecode_files()
    pretty = pretty_blob()
    if run_filter("clean", pretty.decode("utf-8")) != pretty:
        fail("clean of the blob is not those bytes")
    if run_filter("clean", TREE.read_bytes().decode("utf-8")) != pretty:
        fail("clean of the current tree is not the blob")
    collapsed = run_collapse(pretty.decode("utf-8")).decode("utf-8")
    if run_filter("clean", collapsed) != pretty:
        fail("clean of collapse is not the current tree")
    check_layout(collapsed)
    ugly = ugly_text(pretty.decode("utf-8"))
    if "\n" not in ugly.split("[", 1)[-1][:80]:
        fail("ugly form did not break a list")
    if run_collapse(ugly).decode("utf-8") != collapsed:
        fail("ugly collapse differs")
    if run_filter("clean", ugly) != pretty:
        fail("ugly clean differs")
    doc = json.loads(pretty)
    again = json.loads(run_filter("clean", ugly))
    if again != doc:
        fail("key order, strings, numbers, or bools changed")
    check_attributes()
    installed = subprocess.run(
        [str(ROOT / "install-merge-drivers.sh")],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if installed.returncode != 0:
        fail(installed.stderr.decode())
    clean_cfg = subprocess.run(
        ["git", "config", "--get", "filter.settings-tree.clean"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        text=True,
        check=False,
    )
    if clean_cfg.stdout.strip() != "./settings-tree-filter clean":
        fail("filter clean config: %r" % clean_cfg.stdout)
    for key in ("filter.settings-tree.smudge", "filter.settings-tree.required"):
        got = subprocess.run(
            ["git", "config", "--get", key],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            check=False,
        )
        if got.returncode == 0:
            fail("%s is set" % key)
    named = subprocess.run(
        ["git", "config", "--get-regexp", "merge.settings-tree"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        check=False,
    )
    if named.returncode == 0:
        fail("merge.settings-tree is set")
    attrs = subprocess.run(
        ["git", "check-attr", "filter", "merge", "diff", "text", "--",
         "app/src/main/assets/settings-tree.json", "settings-tree-filter",
         "settings-tree-collapse"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        text=True,
        check=False,
    )
    expect = {
        "app/src/main/assets/settings-tree.json: filter: settings-tree",
        "app/src/main/assets/settings-tree.json: merge: binary",
        "app/src/main/assets/settings-tree.json: diff: unspecified",
        "app/src/main/assets/settings-tree.json: text: unspecified",
        "settings-tree-filter: filter: unspecified",
        "settings-tree-collapse: filter: unspecified",
    }
    got_attrs = {line for line in attrs.stdout.splitlines() if line}
    if not expect <= got_attrs:
        fail("check-attr:\n%s" % attrs.stdout)
    check_worktree_diff(collapsed, ugly)
    check_merge_driver()
    check_filter_usage()
    check_collapse_parse_failure()
    run_filter("clean", '{ "a": 1 }\n')
    run_collapse('{ "a": 1 }\n')
    made = [path for path in bytecode_files() if path not in before]
    if made or list((ROOT / "app" / "__pycache__").glob("gen-settings-tree.cpython-*.pyc")):
        fail("filter wrote bytecode: %s" % made)
    print("settings-tree-filter-test: ok")


if __name__ == "__main__":
    main()
