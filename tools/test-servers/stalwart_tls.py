#!/usr/bin/env python3
"""Install the lab certificate in a RUNNING Stalwart 0.16 container (JMAP admin on :8080) and make it the
default certificate (the app connects by IP, so there is no SNI to match). Usage: stalwart_tls.py CONTAINER"""
import json, os, subprocess, sys
c = sys.argv[1]
ca = os.path.join(os.path.dirname(os.path.abspath(__file__)), "generated", "ca")
cert = open(os.path.join(ca, "chain.crt")).read()
key = open(os.path.join(ca, "server.key")).read()


def jmap(calls):
    body = json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"], "methodCalls": calls})
    r = subprocess.run(["docker", "exec", "-i", c, "/bin/sh", "-c",
        'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap'],
        input=body, capture_output=True, text=True)
    try:
        return json.loads(r.stdout)
    except ValueError:
        return {"raw": (r.stdout + r.stderr)[:300]}


r = jmap([["x:Certificate/set", {"create": {"lab": {"certificate": {"@type": "Text", "value": cert},
          "privateKey": {"@type": "Text", "secret": key}}}}, "c1"]])
created = (r.get("methodResponses") or [[None, {}]])[0][1].get("created") or {}
if "lab" not in created:
    sys.exit("certificate not created: " + json.dumps(r)[:300])
cid = created["lab"]["id"]
r = jmap([["x:SystemSettings/set", {"update": {"singleton": {"defaultCertificateId": cid}}}, "c2"]])
print("cert", cid, json.dumps((r.get("methodResponses") or [[None, r]])[0][1])[:200])
