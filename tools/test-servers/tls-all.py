#!/usr/bin/env python3
"""Install lab TLS on each STARTTLS-family container, one at a time, and verify it. TSV to stdout."""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lab
verify_only = "--verify-only" in sys.argv
args = [a for a in sys.argv[1:] if a != "--verify-only"]
names = args or ["dovecot-10","dovecot-12","dovecot-22","dovecot-245","dovecot-dev","cyrus-22","cyrus-24","cyrus-25","cyrus-36","cyrus-3124","cyrus-3137","courier-50","courier-606","uwimap"]
ss = {s["name"]: s for s in lab.servers()}
ca = os.path.join(lab.GEN, "ca", "ca.crt")
print("server\tinstall\tstarttls_verify")
for n in names:
    s = ss[n]; c = s["container"]
    lab.only_one(c)
    lab.sh(["docker", "start", c])
    up = lab.up_commands().get(c)
    if up: lab.sh(["docker", "exec", c, "sh", "-c", up])
    lab.greeting(s["imap"], False, 240)
    if verify_only:
        inst = "-"
    else:
        r = lab.sh([os.path.join(lab.HERE, "tls-install.sh"), c])
        inst = "ok" if r.returncode == 0 else "fail:" + (r.stderr.strip().splitlines() or ["?"])[-1][:80]
        # Restart cleanly so the daemon reads the new configuration.
        lab.sh(["docker", "restart", "-t", "10", c])
        if up: lab.sh(["docker", "exec", c, "sh", "-c", up])
    lab.greeting(s["imap"], False, 240)
    time.sleep(3)
    port, extra = (s["imaps"], []) if "imaps" in s else (s["imap"], ["-starttls", "imap"])
    try:
      v = subprocess.run(["openssl", "s_client", "-connect", f"{lab.LAB_HOST}:{port}", *extra,
                        "-CAfile", ca, "-verify_return_error", "-verify_ip", lab.LAB_HOST, "-brief"],
                       input="", capture_output=True, text=True, timeout=30)
      out = (v.stdout + v.stderr); rc = v.returncode
    except subprocess.TimeoutExpired:
      out, rc = "timeout", 1
    ver = "old-tls" if "unsupported protocol" in out else "ok" if "Verification: OK" in out or ("Protocol version" in out and rc == 0) else "fail:" + " ".join(out.split())[:100]
    print(f"{n}\t{inst}\t{ver}", flush=True)
    lab.sh(["docker", "stop", "-t", "20", c])
