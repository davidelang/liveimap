#!/usr/bin/env python3
"""Seed build only: create the base lab accounts in a running Stalwart 0.15 build container through its REST
management API (/api/principal on 127.0.0.1:8080, fallback admin from the container env). Fails on any error.
Prints names only, never passwords. Usage: stalwart_015_accounts.py CONTAINER"""
import csv
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lab  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DOMAIN = "localhost.local"  # same lab mail domain as stalwart-accounts.sh (0.16)
# JMAP send (jmap.tsv stalwart-0155): 0.15.5 refuses Identity and envelope addresses in .local/.test domains ("Invalid
# e-mail address"), and drops a delivery whose Message-ID the account already has outside INBOX (the Sent copy). So the
# lab user also gets an address in SEND_DOMAIN, and a separate USER-send account (same password) sends to it.
SEND_DOMAIN = "liveimap.example.com"
container = sys.argv[1]


def post(obj):
    r = subprocess.run(["docker", "exec", "-i", container, "/bin/sh", "-c",
                        'curl -sS -f -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" '
                        '--data-binary @- http://127.0.0.1:8080/api/principal'],
                       input=json.dumps(obj), capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"stalwart 0.15 principal {obj['name']} failed ({r.returncode}): {r.stderr[-300:]}")
    body = json.loads(r.stdout)
    if "error" in body:
        sys.exit(f"stalwart 0.15 principal {obj['name']}: {json.dumps(body)[:300]}")
    print("stalwart 0.15 principal", obj["type"], obj["name"])


post({"type": "domain", "name": DOMAIN})
post({"type": "domain", "name": SEND_DOMAIN})
user = lab.USER.split("@")[0]
sender_secret = ""
for row in csv.DictReader(open(os.path.join(HERE, "seed", "accounts.tsv"), newline="", encoding="utf-8"), delimiter="\t"):
    if row.get("profile") == "base":
        emails = [row["user"] + "@" + DOMAIN] + ([user + "@" + SEND_DOMAIN] if row["user"] == user else [])
        post({"type": "individual", "name": row["user"], "secrets": [row["password"]], "emails": emails, "roles": ["user"]})
        if row["user"] == user:
            sender_secret = row["password"]
if not sender_secret:
    sys.exit(f"stalwart 0.15: no base account {user} in seed/accounts.tsv")
post({"type": "individual", "name": user + "-send", "secrets": [sender_secret],
      "emails": [user + "-send@" + SEND_DOMAIN], "roles": ["user"]})


# login-policy.tsv cleartext=yes: accept plain-text logins on the clear-text IMAP listener, then reload the config.
def api(method, path, body=None):
    r = subprocess.run(["docker", "exec", "-i", container, "/bin/sh", "-c",
                        'curl -sS -f -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" '
                        f'-X {method} ' + ('--data-binary @- ' if body is not None else '') +
                        f'http://127.0.0.1:8080/api/{path}'],
                       input=json.dumps(body) if body is not None else "", capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"stalwart 0.15 {method} /api/{path} failed ({r.returncode}): {r.stderr[-300:]}")
    out = json.loads(r.stdout)
    if "error" in out:
        sys.exit(f"stalwart 0.15 {method} /api/{path}: {json.dumps(out)[:300]}")
    return out


api("POST", "settings", [{"type": "insert", "prefix": None, "assert_empty": False,
                          "values": [["imap.auth.allow-plain-text", "true"]]}])
api("GET", "reload")
print("stalwart 0.15 imap.auth.allow-plain-text = true")
