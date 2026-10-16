#!/usr/bin/env python3
"""Give a lab Cyrus a domain-qualified account so its JMAP Identity has a real address (Cyrus uses the userid
when it contains '@'). Domain: LAB_MAIL_DOMAIN (host.env / env). Usage: cyrus_domain_user.py <name>.
Turns on virtdomains: userid, adds <USER>@<domain> to sasldb, creates its INBOX as the admin, keeps the old user."""
import imaplib, os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import lab
name = sys.argv[1]; c = "liveimap-" + name
domain = lab.HOSTENV.get("LAB_MAIL_DOMAIN") or sys.exit("needs LAB_MAIL_DOMAIN")
user = lab.USER.split("@")[0] + "@" + domain
def ex(cmd, data=None):
    return subprocess.run(["docker", "exec", "-i", c, "sh", "-c", cmd], input=data, capture_output=True, text=True)
ex("sed -i 's/^virtdomains: off/virtdomains: userid/' /etc/imapd.conf")
for u, realm in ((lab.USER.split("@")[0], domain),):
    flag = f"-u {realm} " if realm else ""
    r = ex(f"saslpasswd2 -p -c {flag}{u}", lab.PASS + "\n")  # password on stdin, never on a command line
    if r.returncode: sys.exit(f"saslpasswd2 {u}: rc {r.returncode}")
# The lab user is admin only for the CREATE (the "cyrus" admin's password is not the lab's), then not again.
def restart():
    subprocess.run(["docker", "restart", c], capture_output=True); time.sleep(12)
ex(f"grep -q '^admins: cyrus {lab.USER}$' /etc/imapd.conf || sed -i 's/^admins: cyrus$/admins: cyrus {lab.USER}/' /etc/imapd.conf")
restart()
port = int(subprocess.check_output(["docker", "port", c, "143"], text=True).split(":")[-1])
m = imaplib.IMAP4(lab.LAB_HOST, port); m.login(lab.USER, lab.PASS)
typ, _ = m.create(f"user/{user}")
print(name, "create", f"user/{user}", typ); m.logout()
ex(f"sed -i 's/^admins: cyrus {lab.USER}$/admins: cyrus/' /etc/imapd.conf")
restart()
m = imaplib.IMAP4(lab.LAB_HOST, port); m.login(user, lab.PASS); print(name, "login", user, m.select("INBOX")[0]); m.logout()
