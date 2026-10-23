#!/usr/bin/env python3
"""Seed build only: in a Stalwart 0.16 build container still in recovery mode (admin on 127.0.0.1:8080), install
the lab certificate as the default certificate and create one listener per servers.tsv row for SERVER (container
ports come from servers.tsv, never from here). Fails on any refused call. Usage: stalwart_lab_config.py CONTAINER SERVER"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ports  # noqa: E402

container, server = sys.argv[1:3]
# service -> (protocol, implicit TLS). Plain-port services offer STARTTLS (useTls).
KINDS = {"imap": ("imap", False), "imaps": ("imap", True), "managesieve": ("manageSieve", False),
         "https": ("http", True), "submission": ("smtp", False)}


def jmap(calls):
    body = json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"], "methodCalls": calls})
    r = subprocess.run(["docker", "exec", "-i", container, "/bin/sh", "-c",
                        'curl -sS -f -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" '
                        '--data-binary @- http://127.0.0.1:8080/jmap'], input=body, capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"stalwart admin call failed ({r.returncode}): {r.stderr[-300:]}")
    return json.loads(r.stdout)["methodResponses"]


def created(resp, key, what):
    body = resp[0][1]
    if resp[0][0] == "error" or key not in (body.get("created") or {}):
        sys.exit(f"{what} not created: {json.dumps(body)[:400]}")
    return body["created"][key]["id"]


ca = os.path.join(HERE, "generated", "ca")
cert = open(os.path.join(ca, "chain.crt"), encoding="utf-8").read()
key = open(os.path.join(ca, "server.key"), encoding="utf-8").read()
cid = created(jmap([["x:Certificate/set", {"create": {"lab": {
    "certificate": {"@type": "Text", "value": cert}, "privateKey": {"@type": "Text", "secret": key}}}}, "c"]]),
    "lab", "certificate")
# Run after stalwart-accounts.sh: the settings need the lab domain, which becomes the default hostname too.
q = jmap([["x:Domain/query", {}, "q"], ["x:Domain/get", {"#ids": {"resultOf": "q", "name": "x:Domain/query", "path": "/ids"},
          "properties": ["name"]}, "g"]])
doms = q[1][1].get("list") or []
if len(doms) != 1:
    sys.exit(f"want exactly one Stalwart domain, got {json.dumps(q)[:300]}")
r = jmap([["x:SystemSettings/set", {"update": {"singleton": {"defaultCertificateId": cid,
          "defaultHostname": doms[0]["name"], "defaultDomainId": doms[0]["id"]}}}, "c"]])
if "singleton" not in (r[0][1].get("updated") or {}):
    sys.exit("defaultCertificateId not set: " + json.dumps(r[0][1])[:400])
# login-policy.tsv cleartext=yes: accept LOGIN/AUTHENTICATE PLAIN on the clear-text IMAP listener (applies after the restart).
r = jmap([["x:Imap/set", {"update": {"singleton": {"allowPlainTextAuth": True}}}, "c"]])
if "singleton" not in (r[0][1].get("updated") or {}):
    sys.exit("allowPlainTextAuth not set: " + json.dumps(r[0][1])[:400])
print("stalwart certificate", cid, "default; hostname", doms[0]["name"])

rows = [line.rstrip("\n").split("\t") for line in open(os.path.join(HERE, "servers.tsv"), encoding="utf-8")
        if line.startswith(server + "\t")]
if not rows:
    sys.exit(f"no servers.tsv rows for {server}")
create = {}
for row in rows:
    service = row[1]
    if service not in KINDS:
        sys.exit(f"servers.tsv {server} {service}: no Stalwart listener kind")
    protocol, implicit = KINDS[service]
    cport = ports.container_port(server, service)
    create[service] = {"name": service, "protocol": protocol, "bind": {f"[::]:{cport}": True},
                       "tlsImplicit": implicit, "useTls": True}
r = jmap([["x:NetworkListener/set", {"create": create}, "c"]])
made = r[0][1].get("created") or {}
missing = sorted(set(create) - set(made))
if missing:
    sys.exit(f"listeners not created {missing}: {json.dumps(r[0][1].get('notCreated'))[:400]}")
for service in create:
    print("stalwart listener", service, create[service]["protocol"], list(create[service]["bind"])[0])
