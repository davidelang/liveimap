#!/bin/sh
# Enable as many SASL mechanisms as each lab server supports (lab accounts store {PLAIN} passwords, so
# challenge-response mechanisms work). Usage: sasl-install.sh <container>; the container must be running.
# Restart the container afterwards (lab.py run does that via its start recipe).
set -e
c="$1"
case "$c" in
  liveimap-dovecot-245)
    # Minimal image (no sh tools): write the drop-in on the host and copy it in.
    t=$(mktemp)
    printf 'auth_mechanisms = plain login cram-md5 scram-sha-1 scram-sha-1-plus scram-sha-256 scram-sha-256-plus digest-md5\n' > "$t"
    docker cp "$t" "$c:/etc/dovecot/conf.d/lab-sasl.conf"
    rm -f "$t" ;;
  liveimap-stalwart*)
    # Stalwart's IMAP mechanisms are built in (PLAIN, OAUTHBEARER, XOAUTH2); nothing to enable.
    : ;;
  *) echo "no SASL recipe for $c" >&2; exit 2 ;;
esac
