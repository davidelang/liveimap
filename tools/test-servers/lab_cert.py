#!/usr/bin/env python3
"""The host-generated lab server certificate (lab-ca.sh -> generated/ca, not checked in).
  lab_cert.py check        exit 1 unless server.crt chains to ca.crt and names LAB_BIND, LAB_NAME and every fixed
                           servers.tsv login_domain (e.g. Zimbra's host name)
  lab_cert.py fingerprint  print the sha256 fingerprint (lower hex) recorded in each seed image's label"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CA = os.path.join(HERE, "generated", "ca")
sys.path.insert(0, HERE)


def fail(text):
    sys.stderr.write("lab_cert: %s; run tools/test-servers/lab-ca.sh\n" % text)
    sys.exit(1)


def openssl(*args):
    r = subprocess.run(["openssl", *args], capture_output=True, text=True, errors="replace")
    if r.returncode != 0:
        fail("openssl %s: %s" % (" ".join(args), (r.stderr or r.stdout).strip()[-200:]))
    return r.stdout


def needed_names():
    import lab
    names = ["IP Address:" + lab.LAB_HOST, "DNS:" + lab.HOSTENV["LAB_NAME"]]
    with open(os.path.join(HERE, "servers.tsv"), encoding="utf-8") as handle:
        for line in handle:
            f = line.rstrip("\n").split("\t")
            if line.startswith("#") or len(f) < 4 or f[3] in ("", "LAB_MAIL_DOMAIN"):
                continue
            if "DNS:" + f[3] not in names:
                names.append("DNS:" + f[3])
    return names


def main():
    crt = os.path.join(CA, "server.crt")
    for name in ("server.crt", "server.key", "ca.crt", "chain.crt"):
        if not os.path.isfile(os.path.join(CA, name)):
            fail("generated/ca/%s missing" % name)
    if sys.argv[1:] == ["check"]:
        openssl("verify", "-CAfile", os.path.join(CA, "ca.crt"), crt)
        san = [s.strip() for s in openssl("x509", "-in", crt, "-noout", "-ext", "subjectAltName").splitlines()[-1].split(",")]
        missing = [n for n in needed_names() if n not in san]
        if missing:
            fail("generated/ca/server.crt does not name %s" % ", ".join(missing))
    elif sys.argv[1:] == ["fingerprint"]:
        print(openssl("x509", "-in", crt, "-noout", "-fingerprint", "-sha256").strip().split("=", 1)[1].replace(":", "").lower())
    else:
        fail("usage: lab_cert.py check|fingerprint")


if __name__ == "__main__":
    main()
