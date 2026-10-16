import json, subprocess, sys
C = sys.argv[2] if len(sys.argv) > 2 else "liveimap-stalwart"
def jmap(calls):
    body = json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"], "methodCalls": calls})
    r = subprocess.run(["docker", "exec", "-i", C, "/bin/sh", "-c",
        'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap'],
        input=body, capture_output=True, text=True)
    try: return json.loads(r.stdout)
    except ValueError: return {"raw": (r.stdout + r.stderr)[:300]}
print(json.dumps(jmap(json.loads(sys.argv[1]))))
