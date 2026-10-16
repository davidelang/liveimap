#!/usr/bin/env python3
"""Recreate a lab Stalwart container so its HTTPS (443) is published on LAB_HOST:<port chosen at run time>
(LAB_JMAP_PORT, default 443: Stalwart advertises https://<hostname>/ with no port), keeping every volume and
the other port mappings. Usage: stalwart_republish.py <name> [port]. Nothing lab-specific is hard-coded."""
import json, os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import lab
name = sys.argv[1]; c = "liveimap-" + name
port = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("LAB_JMAP_PORT", "443")
i = json.loads(subprocess.check_output(["docker", "inspect", c]))[0]
# Stalwart builds its advertised URL from its hostname, so the container gets the detected lab host.
args = ["docker", "run", "-d", "--name", c, "--hostname", lab.LAB_HOST]
for e in i["Config"]["Env"] or []: args += ["-e", e]
for m in i["Mounts"]:
    if m["Destination"] == "/etc/lab": continue
    args += ["-v", f'{m.get("Name") or m["Source"]}:{m["Destination"]}']
# The lab cert (server.crt/key from lab.GEN) at /etc/lab, where the "# lab-tls" config block reads it.
args += ["-v", f"{lab.GEN}/ca:/etc/lab:ro"]
for k, binds in (i["HostConfig"]["PortBindings"] or {}).items():
    if k == "443/tcp": args += ["-p", f"{lab.LAB_HOST}:{port}:443"]
    else:
        for b in binds: args += ["-p", f'{b["HostPort"]}:{k.split("/")[0]}']
for k, v in (i["Config"]["Labels"] or {}).items(): args += ["--label", f"{k}={v}"]
if i["Config"]["Entrypoint"]: args += ["--entrypoint", i["Config"]["Entrypoint"][0]]
args += [i["Config"]["Image"]] + (i["Config"]["Entrypoint"] or [])[1:] + (i["Config"]["Cmd"] or [])
subprocess.run(["docker", "stop", c], capture_output=True)
subprocess.check_call(["docker", "rename", c, c + "-old"])
r = subprocess.run(args, capture_output=True, text=True)
if r.returncode:
    subprocess.run(["docker", "rename", c + "-old", c]); sys.exit("recreate failed: " + r.stderr[-300:])
subprocess.run(["docker", "rm", c + "-old"], capture_output=True)
print(name, "443 ->", f"{lab.LAB_HOST}:{port}")

# Advertise the detected host: 0.16 builds its URLs from SystemSettings.defaultHostname (it needs the
# defaultDomainId alongside), set over the admin JMAP API; pre-0.16 images use the container hostname set above.
import time
def admin(calls):
    body = json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"], "methodCalls": calls})
    r = subprocess.run(["docker", "exec", "-i", c, "/bin/sh", "-c",
        'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap'],
        input=body, capture_output=True, text=True)
    try: return json.loads(r.stdout)["methodResponses"]
    except (ValueError, KeyError): return None
time.sleep(8)
domains = admin([["x:Domain/query", {}, "0"]])
if domains and domains[0][0] == "x:Domain/query" and domains[0][1].get("ids"):
    res = admin([["x:SystemSettings/set", {"update": {"singleton": {"defaultHostname": lab.LAB_HOST,
                  "defaultDomainId": domains[0][1]["ids"][0]}}}, "0"]])
    ok = bool(res and res[0][1].get("updated"))
    print(name, "defaultHostname ->", lab.LAB_HOST, "ok" if ok else "FAILED")
    subprocess.run(["docker", "restart", c], capture_output=True)
else:
    print(name, "no 0.16 admin API; container hostname", lab.LAB_HOST)
