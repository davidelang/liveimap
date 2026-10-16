import json, subprocess, sys, time, os
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); import lab
def sj(c, calls):
    return json.loads(subprocess.run([sys.executable, os.path.join(HERE, "sj2.py"), json.dumps(calls), c], capture_output=True, text=True).stdout)
for c in sys.argv[1:]:
    lab.only_one(c); subprocess.run(["docker", "start", c], capture_output=True); time.sleep(20)
    r = sj(c, [["x:BlockedIp/query", {}, "0"], ["x:BlockedIp/get", {"#ids": {"resultOf": "0", "name": "x:BlockedIp/query", "path": "/ids"}}, "1"],
               ["x:AllowedIp/query", {}, "2"], ["x:AllowedIp/get", {"#ids": {"resultOf": "2", "name": "x:AllowedIp/query", "path": "/ids"}}, "3"],
               ["x:Tracer/query", {}, "4"], ["x:Tracer/get", {"#ids": {"resultOf": "4", "name": "x:Tracer/query", "path": "/ids"}}, "5"]])
    m = {x[2]: x[1] for x in r.get("methodResponses", [])}
    blocked = m.get("1", {}).get("list", []); allowed = m.get("3", {}).get("list", []); tracers = m.get("5", {}).get("list", [])
    print(c, "blocked:", [(b["address"], b["reason"], b["createdAt"]) for b in blocked], "allowed:", [a["address"] for a in allowed], "tracers:", [t["@type"] for t in tracers])
    calls = []
    if blocked: calls.append(["x:BlockedIp/set", {"destroy": [b["id"] for b in blocked]}, "a"])
    if not any(a["address"].startswith(lab.LAB_HOST) for a in allowed):
        calls.append(["x:AllowedIp/set", {"create": {"n": {"address": lab.LAB_HOST, "reason": "LiveIMAP lab host (tests connect often)"}}}, "b"])
    calls.append(["x:Security/set", {"update": {"singleton": {"authBanPeriod": 3600000, "loiterBanPeriod": 3600000, "scanBanPeriod": 3600000}}}, "c"])
    if not any(t["@type"] == "Stdout" for t in tracers):
        calls.append(["x:Tracer/set", {"create": {"t": {"@type": "Stdout", "enable": True, "level": "info", "ansi": False, "multiline": False, "buffered": False}}}, "d"])
    out = sj(c, calls)
    print(c, "set:", json.dumps(out.get("methodResponses", out))[:900])
    subprocess.run(["docker", "restart", "-t", "10", c], capture_output=True); time.sleep(20)
    s = [x for x in lab.servers() if x["container"] == c][0]
    t = subprocess.run(f"timeout 8 openssl s_client -connect {lab.LAB_HOST}:{s['imaps']} </dev/null 2>&1 | grep -E 'subject=|Cipher is' | head -2", shell=True, capture_output=True, text=True).stdout
    print(c, "imaps", s["imaps"], t.strip().replace("\n", " | "))
    subprocess.run(["docker", "stop", "-t", "20", c], capture_output=True)
