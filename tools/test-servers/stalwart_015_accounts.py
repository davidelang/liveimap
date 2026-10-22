#!/usr/bin/env python3
"""Seed build only: create the base lab accounts in a running Stalwart 0.15 build container through its REST
management API (/api/principal on 127.0.0.1:8080, fallback admin from the container env). Fails on any error.
Prints names only, never passwords. Usage: stalwart_015_accounts.py CONTAINER"""
import csv
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DOMAIN = "localhost.local"  # same lab mail domain as stalwart-accounts.sh (0.16)
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
for row in csv.DictReader(open(os.path.join(HERE, "seed", "accounts.tsv"), newline="", encoding="utf-8"), delimiter="\t"):
    if row.get("profile") == "base":
        post({"type": "individual", "name": row["user"], "secrets": [row["password"]],
              "emails": [row["user"] + "@" + DOMAIN], "roles": ["user"]})
