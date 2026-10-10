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
  liveimap-cyrus-*)
    # Advertise every mechanism whose plugin is installed (sasldb holds plain passwords, so all can verify).
    docker exec "$c" sh -c '
      m=""
      for d in /usr/lib/sasl2 /usr/lib/*/sasl2 /usr/local/lib/sasl2 /usr/lib64/sasl2; do
        [ -d "$d" ] || continue
        for f in "$d"/lib*.so*; do
          [ -f "$f" ] || continue
          case "${f##*/}" in
            libplain.so*) m="$m PLAIN" ;;
            liblogin.so*) m="$m LOGIN" ;;
            libcrammd5.so*) m="$m CRAM-MD5" ;;
            libdigestmd5.so*) m="$m DIGEST-MD5" ;;
            libntlm.so*) m="$m NTLM" ;;
            libscram.so*) m="$m SCRAM-SHA-1"; grep -q SCRAM-SHA-256 "$f" && m="$m SCRAM-SHA-256" ;;
          esac
        done
      done
      m=$(echo $m | tr " " "\n" | sort -u | tr "\n" " ")
      sed -i "/^sasl_mech_list:/d" /etc/imapd.conf
      echo "sasl_mech_list: $m" >> /etc/imapd.conf
      grep "^sasl_mech_list" /etc/imapd.conf' ;;
  liveimap-stalwart*)
    # Stalwart's IMAP mechanisms are built in (PLAIN, OAUTHBEARER, XOAUTH2); nothing to enable.
    : ;;
  *) echo "no SASL recipe for $c" >&2; exit 2 ;;
esac
