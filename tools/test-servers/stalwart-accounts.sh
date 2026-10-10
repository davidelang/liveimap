#!/bin/sh
# Create the seed's base lab accounts in a running Stalwart 0.16 container (x:Domain/x:Account via JMAP on the
# management port, with the container's recovery admin). Idempotent: an existing domain or account is reported, not fatal.
# Usage: stalwart-accounts.sh <container>. Prints only the created/notCreated keys, never the passwords.
set -e
c="$1"
DIR=$(cd "$(dirname "$0")" && pwd)
python3 - "$DIR/seed/accounts.tsv" <<'PY' | docker exec -i "$c" /bin/sh -c 'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap' | python3 -c '
import json, sys
r = json.load(sys.stdin)
for name, args, _ in r.get("methodResponses", []):
    print(name, "created=" + ",".join(sorted((args.get("created") or {}).keys())),
          "notCreated=" + ",".join("%s:%s" % (k, v.get("type")) for k, v in sorted((args.get("notCreated") or {}).items())))
'
import csv, json, sys
users = [(r["user"], r["password"]) for r in csv.DictReader(open(sys.argv[1], newline=""), delimiter="\t")
         if r.get("profile") == "base"]
accounts = {"a%d" % i: {"name": u, "@type": "User", "domainId": "#d1",
                        "credentials": {"0": {"@type": "Password", "secret": p}}} for i, (u, p) in enumerate(users)}
sys.stdout.write(json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"], "methodCalls": [
    ["x:Domain/set", {"create": {"d1": {"name": "localhost.local"}}}, "c1"],
    ["x:Account/set", {"create": accounts}, "c2"]]}))
PY
