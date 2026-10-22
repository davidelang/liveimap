#!/usr/bin/env python3
"""Keep an upstream image's runtime config when its filesystem is flattened to drop VOLUME.

upstream_config.py tail UPSTREAM        print Dockerfile lines (ENV, WORKDIR, EXPOSE, USER, ENTRYPOINT, CMD) to end a
                                        `FROM <base>` + `COPY --from=upstream / /` build with the upstream config
upstream_config.py check UPSTREAM STAGE exit 1 naming every field that differs (Volumes must be empty on STAGE)
"""
import json
import subprocess
import sys

FIELDS = ["Env", "Entrypoint", "Cmd", "User", "WorkingDir", "ExposedPorts", "StopSignal"]


def config(image):
    out = subprocess.run(["docker", "image", "inspect", "-f", "{{json .Config}}", image],
                         capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"docker image inspect {image}: {out.stderr.strip()}")
    return json.loads(out.stdout)


def tail(image):
    c = config(image)
    lines = []
    for e in c.get("Env") or []:
        k, _, v = e.partition("=")
        lines.append("ENV %s=%s" % (k, json.dumps(v)))
    if c.get("WorkingDir"):
        lines.append("WORKDIR %s" % c["WorkingDir"])
    for p in sorted(c.get("ExposedPorts") or {}):
        lines.append("EXPOSE %s" % p)
    if c.get("StopSignal"):
        lines.append("STOPSIGNAL %s" % c["StopSignal"])
    if c.get("User"):
        lines.append("USER %s" % c["User"])
    lines.append("ENTRYPOINT %s" % json.dumps(c.get("Entrypoint") or []))
    lines.append("CMD %s" % json.dumps(c.get("Cmd") or []))
    print("\n".join(lines))


def check(up, stage):
    a, b = config(up), config(stage)
    bad = []
    for f in FIELDS:
        if (a.get(f) or None) != (b.get(f) or None):
            bad.append("%s: upstream %s stage %s" % (f, json.dumps(a.get(f)), json.dumps(b.get(f))))
    if b.get("Volumes"):
        bad.append("Volumes: stage still declares %s" % json.dumps(b["Volumes"]))
    if bad:
        sys.stderr.write("config differs from %s:\n  %s\n" % (up, "\n  ".join(bad)))
        sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "tail":
        tail(sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "check":
        check(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
