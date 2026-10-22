#!/usr/bin/env python3
"""Audit a seed image's contents. Every problem is printed (AUDIT FAIL ...) and the exit status is 1.

  seed_audit.py live SERVER HOST PORT   against a running build or scratch container on HOST:PORT (IMAP):
        every seed/accounts.tsv account with folders.tsv rows logs in; every folders.tsv folder (UTF-8 and nested
        included) has its exact count, flags and corpus-v1.sums digest; the suite boxes (suite-fixture.tsv:
        LiveIMAP-Test, Sub, Trash, Tëst-Ünï, Deep, Spoof) have their exact counts; the server presents
        generated/ca/server.crt. build-seed-image runs this before it commits the image.
  seed_audit.py image SERVER            offline (no server started): labels liveimap.seed-manifest,
        liveimap.cert-sha256 and liveimap.audit (the live audit passed for the current fixture inputs);
        the certificate and CA files inside the image (CERT_FILES) equal generated/ca; no volumes.
        lab-ready runs this for every server. (The IMAP port is proved by the live audit logging in on the
        servers.tsv container port; start.sh checks every published port.)
  seed_audit.py scratch SERVER          live audit of the image in a throwaway container on a temporary port
        (liveimap-audit-SERVER, removed afterwards; the lab container is not touched), then the image audit.
  seed_audit.py inputs                  print the value the liveimap.audit label must carry
"""
import hashlib
import json
import os
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab  # noqa: E402

problems = []
# Where each image holds the lab certificate and CA (generated/ca name, path inside the image).
CERT_FILES = {
    "": (("server.crt", "/etc/lab/server.crt"), ("ca.crt", "/etc/lab/ca.crt")),
    "zimbra-883": (("server.crt", "/opt/zimbra/ssl/zimbra/commercial/commercial.crt"),
                   ("ca.crt", "/opt/zimbra/ssl/zimbra/commercial/commercial_ca.crt")),  # deployed by zimbra-tls.sh
}


def bad(text):
    problems.append(text)
    print("AUDIT FAIL " + text)


def regress_module():
    path = os.path.join(HERE, "regress")
    g = {"__file__": path, "__name__": "seed_audit_regress"}
    exec(compile(open(path, encoding="utf-8").read(), path, "exec"), g)
    return g


def audit_inputs():
    """The fixture inputs hash plus this file and the seeding code: a change to any of them needs a new audit."""
    digest = hashlib.sha256(lab.fixture_inputs_hash("audit").encode())
    for name in ("seed_audit.py", "seed_corpus.py", "lab_imap.py", os.path.join("seed", "folders.tsv")):
        with open(os.path.join(HERE, name), "rb") as handle:
            digest.update(handle.read())
    return digest.hexdigest()


def fingerprint_pem(pem):
    r = subprocess.run(["openssl", "x509", "-noout", "-fingerprint", "-sha256"], input=pem,
                       capture_output=True, text=True, errors="replace")
    if r.returncode != 0 or "=" not in r.stdout:
        return ""
    return r.stdout.strip().split("=", 1)[1].replace(":", "").lower()


def generated_fp(name):
    with open(os.path.join(lab.GEN, "ca", name), encoding="utf-8") as handle:
        return fingerprint_pem(handle.read())


def server_by_name(server):
    by = {s["name"]: s for s in lab.servers()}
    if server not in by:
        sys.exit(f"AUDIT FAIL {server}: not a lab server")
    return dict(by[server])


def live(server, host, port):
    g = regress_module()
    g["secrets"] = g["load_secrets"]()
    s = server_by_name(server)
    s["imap"] = int(port)
    if host != lab.LAB_HOST:
        sys.exit(f"AUDIT FAIL {server}: host {host} is not the lab host")
    _blocked, drift = g["verify_one"](s, True)
    for line in drift:
        bad(f"{server} {line[len('drift: '):] if line.startswith('drift: ') else line}")
    import imaplib
    ctx = lab._lab_ssl()  # unverified on purpose: the served certificate is compared to generated/ca below
    try:
        mail = imaplib.IMAP4(host, int(port), timeout=30)
        mail.starttls(ssl_context=ctx)
        der = mail.sock.getpeercert(binary_form=True)
    except (imaplib.IMAP4.error, OSError) as exc:
        bad(f"{server} cert: STARTTLS on {host}:{port} failed: {exc}")
        der = None
    else:
        try:
            mail.logout()
        except (imaplib.IMAP4.abort, OSError):
            pass  # Dovecot 1.2 drops the TLS session on LOGOUT; the certificate is already in hand
    if der and hashlib.sha256(der).hexdigest() != generated_fp("server.crt"):
        bad(f"{server} cert: served certificate is not generated/ca/server.crt")


def image(server):
    ref = "liveimap-seed-" + server
    r = subprocess.run(["docker", "image", "inspect", ref], capture_output=True, text=True, errors="replace")
    if r.returncode != 0:
        bad(f"{server} image: missing {ref} tools/test-servers/build-seed-image {server}")
        return
    cfg = json.loads(r.stdout)[0].get("Config") or {}
    labels = cfg.get("Labels") or {}
    err = lab.seed_problem(server, json.loads(r.stdout)[0])
    if err:
        bad(f"{server} image: {err}")
    if labels.get("liveimap.audit") != audit_inputs():
        bad(f"{server} image: label liveimap.audit is not the current audit inputs (live audit not passed for "
            f"these inputs) tools/test-servers/build-seed-image {server}")
    if cfg.get("Volumes"):
        bad(f"{server} image: Config.Volumes {sorted(cfg['Volumes'])}")
    for name, inside in CERT_FILES.get(server, CERT_FILES[""]):
        c = subprocess.run(["docker", "run", "--rm", "-u", "0", "--entrypoint", "cat", ref, inside],
                           capture_output=True, text=True, errors="replace")
        if c.returncode != 0:
            bad(f"{server} image: {inside} missing ({c.stderr.strip()[-120:]})")
        elif fingerprint_pem(c.stdout) != generated_fp(name):
            bad(f"{server} image: {inside} differs from generated/ca/{name} tools/test-servers/build-seed-image {server}")


def wait_imap(port, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            with socket.create_connection((lab.LAB_HOST, port), timeout=5) as sock:
                sock.settimeout(10)
                if sock.recv(200).startswith(b"* OK"):
                    return True
        except OSError:
            pass
        time.sleep(3)
    return False


def scratch(server):
    ref = "liveimap-seed-" + server
    name = "liveimap-audit-" + server
    import ports
    cport = ports.container_port(server, "imap")
    hport = int(subprocess.check_output(["python3", os.path.join(HERE, "build_host_port.py"),
                                       os.path.join(lab.GEN, "local-ports.tsv")], text=True).strip())
    host_label = subprocess.check_output(["docker", "image", "inspect", "-f",
                                          '{{index .Config.Labels "liveimap.hostname"}}', ref], text=True).strip()
    exists = subprocess.run(["docker", "container", "inspect", name], capture_output=True)
    if exists.returncode == 0:
        subprocess.run(["docker", "rm", "-f", name], check=True, capture_output=True)
    cmd = ["docker", "run", "-d", "--name", name] + (["--hostname", host_label] if host_label else []) + \
          ["-p", f"{lab.LAB_HOST}:{hport}:{cport}", ref]
    subprocess.run(cmd, check=True, capture_output=True)
    try:
        if not wait_imap(hport, 600):
            bad(f"{server} live: no IMAP greeting on the scratch container in 600s")
        else:
            time.sleep(5 if server != "zimbra-883" else 30)
            live(server, lab.LAB_HOST, hport)
    finally:
        subprocess.run(["docker", "rm", "-f", name], check=True, capture_output=True)
    image(server)


def main():
    a = sys.argv[1:]
    if len(a) == 4 and a[0] == "live":
        live(a[1], a[2], a[3])
    elif len(a) == 2 and a[0] == "image":
        image(a[1])
    elif len(a) == 2 and a[0] == "scratch":
        scratch(a[1])
    elif a == ["inputs"]:
        print(audit_inputs())
        return
    else:
        sys.exit(__doc__.strip())
    if problems:
        sys.exit(1)
    print("AUDIT OK " + a[1])


if __name__ == "__main__":
    main()
