#!/usr/bin/env python3
"""The only packaged-asset list. Packaging copies these paths; the APK check expects them.

A later runtime asset is written under build/, never under src/, and its path is added here.
"""

import sys
import shutil
import zipfile
from pathlib import Path

ALLOWLIST = (
    "help/getting-started.txt",
    "help/reporting-a-bug.txt",
    "licenses/NOTICES.txt",
)


def rel_ok(rel):
    parts = Path(rel).parts
    return rel == Path(rel).as_posix() and not Path(rel).is_absolute() and ".." not in parts and rel != ""


def packaged_names(src):
    names = []
    for rel in ALLOWLIST:
        if not rel_ok(rel):
            sys.stderr.write("bad allowlist path: %s\n" % rel)
            raise SystemExit(1)
        if (src / rel).is_file():
            names.append(rel)
    return names


def stage(src, dest):
    dest.mkdir(parents=True, exist_ok=True)
    for child in list(dest.iterdir()):
        if child.is_dir() and not child.is_symlink():
            shutil.rmtree(child)
        else:
            child.unlink()
    missing = [rel for rel in ALLOWLIST if not (src / rel).is_file()]
    if missing:
        sys.stderr.write("missing allowlist asset: %s\n" % ", ".join(missing))
        raise SystemExit(1)
    for rel in packaged_names(src):
        target = dest / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src / rel, target)


def mismatch(expected, found):
    extra = sorted(set(found) - set(expected))
    missing = sorted(set(expected) - set(found))
    return extra, missing


def emit(extra, missing):
    if not extra and not missing:
        return 0
    if extra:
        for name in extra:
            print("extra: %s" % name)
    else:
        print("extra:")
    if missing:
        for name in missing:
            print("missing: %s" % name)
    else:
        print("missing:")
    return 1


def apk_assets(apk):
    names = []
    with zipfile.ZipFile(apk) as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/").lstrip("/")
            if info.is_dir() or name.endswith("/"):
                continue
            if not name.startswith("assets/"):
                continue
            rel = name[len("assets/"):]
            if rel:
                names.append(rel)
    return names


def read_names(text):
    return [line.strip() for line in text.splitlines() if line.strip()]


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        sys.stderr.write(
            "usage: packaged-assets.py list | select SRC | stage SRC DEST | diff | check-apk app|instrumentation APK\n"
        )
        return 2
    cmd = argv[0]
    if cmd == "list":
        if len(argv) != 1:
            return 2
        for rel in ALLOWLIST:
            print(rel)
        return 0
    if cmd == "select":
        if len(argv) != 2:
            return 2
        for rel in packaged_names(Path(argv[1])):
            print(rel)
        return 0
    if cmd == "stage":
        if len(argv) != 3:
            return 2
        stage(Path(argv[1]), Path(argv[2]))
        return 0
    if cmd == "diff":
        if len(argv) != 1:
            return 2
        return emit(*mismatch(ALLOWLIST, read_names(sys.stdin.read())))
    if cmd == "check-apk":
        if len(argv) != 3 or argv[1] not in ("app", "instrumentation"):
            return 2
        apk = Path(argv[2])
        if not apk.is_file():
            sys.stderr.write("missing apk: %s\n" % apk)
            return 1
        expected = ALLOWLIST if argv[1] == "app" else ()
        code = emit(*mismatch(expected, apk_assets(apk)))
        if code != 0:
            print("apk assets mismatch: %s" % apk)
        return code
    sys.stderr.write("unknown command: %s\n" % cmd)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
