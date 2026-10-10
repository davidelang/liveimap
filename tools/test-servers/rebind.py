#!/usr/bin/env python3
"""Recreate one stopped lab container with its ports published on LAB_BIND (host.env).
Keeps its filesystem (docker commit), volumes, env, hostname, entrypoint and command. Usage: rebind.py CONTAINER"""
import json, os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lab
c = sys.argv[1]
i = json.loads(subprocess.check_output(["docker", "inspect", c]))[0]
if i["State"]["Running"]:
    sys.exit("stop it first")
hc, cf = i["HostConfig"], i["Config"]
img = f"{c}:lan"
subprocess.check_call(["docker", "commit", c, img], stdout=subprocess.DEVNULL)
args = ["docker", "run", "-d", "--name", c, "--hostname", cf["Hostname"]]
for e in cf.get("Env") or []:
    args += ["-e", e]
for m in i["Mounts"]:
    src = m["Name"] if m["Type"] == "volume" else m["Source"]
    args += ["-v", f"{src}:{m['Destination']}" + ("" if m.get("RW", True) else ":ro")]
for cport, binds in (hc.get("PortBindings") or {}).items():
    for b in binds:
        args += ["-p", f"{lab.LAB_HOST}:{b['HostPort']}:{cport}"]
for cap in hc.get("CapAdd") or []:
    args += ["--cap-add", cap]
if hc.get("ShmSize"):
    args += ["--shm-size", str(hc["ShmSize"])]
if cf.get("Entrypoint"):
    args += ["--entrypoint", cf["Entrypoint"][0]]
args += [img] + (cf["Entrypoint"][1:] if cf.get("Entrypoint") else []) + (cf.get("Cmd") or [])
subprocess.check_call(["docker", "rename", c, c + "-old"])
r = subprocess.run(args, capture_output=True, text=True)
if r.returncode:
    subprocess.run(["docker", "rm", "-f", c], capture_output=True)
    subprocess.check_call(["docker", "rename", c + "-old", c])
    sys.exit("run failed: " + r.stderr[-300:])
subprocess.check_call(["docker", "stop", "-t", "60", c], stdout=subprocess.DEVNULL)
subprocess.check_call(["docker", "rm", c + "-old"], stdout=subprocess.DEVNULL)
print(f"{c} rebound to {lab.LAB_HOST}")
