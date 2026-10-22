#!/usr/bin/env python3
"""Check every upstream fetch of build-seed-image against upstream-pins.tsv. Any mismatch exits 1.

  upstream_pins.py image REF          make REF@digest present locally (pull by digest), print REF@digest
  upstream_pins.py dockerfile FILE    rewrite each pinned FROM to REF@digest in place; an unpinned FROM fails
  upstream_pins.py fetch NAME DEST    download a tarball or tool to DEST (kept if it already matches), check sha256
  upstream_pins.py git NAME           print the pinned commit
  upstream_pins.py url NAME           print the source URL
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PINS = os.path.join(HERE, "upstream-pins.tsv")


def fail(text):
    sys.stderr.write("upstream_pins: " + text + "\n")
    sys.exit(1)


def pins():
    out = {}
    with open(PINS, encoding="utf-8") as handle:
        for n, line in enumerate(handle, 1):
            if not line.strip() or line.startswith("#"):
                continue
            f = line.rstrip("\n").split("\t")
            if len(f) != 4:
                fail(f"{PINS}:{n}: want 4 tab-separated fields")
            kind, name, pin, source = f
            if name in out:
                fail(f"{PINS}:{n}: duplicate {name}")
            out[name] = (kind, pin, source)
    return out


def one(name, kinds):
    got = pins().get(name)
    if got is None or got[0] not in kinds:
        fail(f"{name}: no {'/'.join(kinds)} row in upstream-pins.tsv")
    return got


def docker(*args):
    return subprocess.run(["docker", *args], capture_output=True, text=True, errors="replace")


def ensure_local(ref, pin):
    """Return the ref to use in FROM / docker inspect, after checking the local copy against the pin."""
    kind = pin[0]
    if kind == "local":
        r = docker("image", "inspect", "-f", "{{.Id}}", ref)
        if r.returncode != 0:
            fail(f"{ref}: not present (built outside build-seed-image): {r.stderr.strip()}")
        if r.stdout.strip() != pin[1]:
            fail(f"{ref}: image Id {r.stdout.strip()} != pinned {pin[1]}")
        return ref
    bare = ref.split("@", 1)[0]
    repo = bare.rsplit(":", 1)[0] if ":" in bare.rsplit("/", 1)[-1] else bare
    pinned = f"{bare}@{pin[1]}"
    r = docker("image", "inspect", "-f", "{{json .RepoDigests}}", pinned)
    if r.returncode != 0:
        p = docker("pull", pinned)
        if p.returncode != 0:
            fail(f"docker pull {pinned}: {(p.stderr or p.stdout).strip()[-400:]}")
        r = docker("image", "inspect", "-f", "{{json .RepoDigests}}", pinned)
        if r.returncode != 0:
            fail(f"{pinned}: not present after pull")
    digests = json.loads(r.stdout or "[]") or []
    short = repo[len("docker.io/"):] if repo.startswith("docker.io/") else repo
    if not any(d.split("@", 1)[1] == pin[1] and d.split("@", 1)[0] in (repo, short, "library/" + short) for d in digests):
        fail(f"{pinned}: local RepoDigests {digests} do not include the pin")
    return pinned


def image(ref):
    print(ensure_local(ref, one(ref.split("@", 1)[0], ("image", "local"))))


FROM = re.compile(r"^(FROM\s+(?:--platform=\S+\s+)?)(\S+)(.*)$")


def dockerfile(path):
    table = pins()
    text = open(path, encoding="utf-8").read().split("\n")
    stages = set()
    out = []
    for line in text:
        m = FROM.match(line)
        if not m:
            out.append(line)
            continue
        ref, rest = m.group(2), m.group(3)
        st = re.search(r"\s+AS\s+(\S+)", rest, re.I)
        bare = ref.split("@", 1)[0]
        if ref in stages or ref == "scratch" or ref.startswith("$") or bare.startswith("liveimap-") and bare not in table:
            pass  # a stage of this file, an ARG, or an image these scripts build (each built from pinned inputs)
        elif bare in table and table[bare][0] in ("image", "local"):
            if "@" in ref and ref.split("@", 1)[1] != table[bare][1]:
                fail(f"{path}: FROM {ref}: digest != pinned {table[bare][1]}")
            ref = ensure_local(bare, table[bare])
        else:
            fail(f"{path}: FROM {ref} has no row in upstream-pins.tsv")
        if st:
            stages.add(st.group(1))
        out.append(m.group(1) + ref + rest)
    if out != text:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(out))


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(name, dest):
    _kind, want, url = one(name, ("tarball", "tool"))
    if os.path.isfile(dest) and sha256(dest) == want:
        return
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "liveimap-lab"})
    with urllib.request.urlopen(req, timeout=300) as resp, open(tmp, "wb") as out:
        while True:
            chunk = resp.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    got = sha256(tmp)
    if got != want:
        os.remove(tmp)
        fail(f"{name}: sha256 {got} != pinned {want} ({url})")
    os.replace(tmp, dest)


def main():
    a = sys.argv[1:]
    if len(a) == 2 and a[0] == "image":
        image(a[1])
    elif len(a) == 2 and a[0] == "dockerfile":
        dockerfile(a[1])
    elif len(a) == 3 and a[0] == "fetch":
        fetch(a[1], a[2])
    elif len(a) == 2 and a[0] == "git":
        print(one(a[1], ("git",))[1])
    elif len(a) == 2 and a[0] == "url":
        print(one(a[1], ("git", "tarball", "tool"))[2])
    else:
        fail(__doc__.strip())


if __name__ == "__main__":
    main()
