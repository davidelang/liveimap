#!/bin/sh
# Install the lab server certificate (generated/ca, from lab-ca.sh) into one RUNNING lab container
# and restart its IMAP daemon. Usage: tls-install.sh CONTAINER. Re-runnable.
set -eu
DIR=$(dirname "$(readlink -f "$0")")
CA="$DIR/generated/ca"
c=$1
# docker cp of a directory works even in images without mkdir.
stage=$(mktemp -d)
for f in server.crt server.key server.pem chain.crt ca.crt; do cp "$CA/$f" "$stage/$f"; done
chmod 755 "$stage"; chmod 644 "$stage"/*
docker cp "$stage" "$c:/etc/lab-new" >/dev/null
docker exec "$c" sh -c 'rm -rf /etc/lab 2>/dev/null; mv /etc/lab-new /etc/lab 2>/dev/null' || docker cp "$stage/." "$c:/etc/lab" >/dev/null
rm -rf "$stage"
docker exec "$c" sh -c 'chmod 755 /etc/lab; chmod 644 /etc/lab/*' 2>/dev/null || true
up=$(awk -F '\t' -v c="$c" '$1 == c { print $2 }' "$DIR/up.tsv")
setkv() { # file key value separator: replace or append "key<sep>value"
  docker exec "$c" sh -c "f=$1; k='$2'; v='$3'; s='$4'; if grep -q \"^[#[:space:]]*\$k[[:space:]]*\$s\" \$f; then sed -i \"s|^[#[:space:]]*\$k[[:space:]]*\$s.*|\$k\$s\$v|\" \$f; else echo \"\$k\$s\$v\" >> \$f; fi"
}
case "$c" in
  liveimap-dovecot-10)
    setkv /etc/dovecot/dovecot.conf ssl_disable ' no' ' ='
    setkv /etc/dovecot/dovecot.conf ssl_cert_file ' /etc/lab/chain.crt' ' ='
    setkv /etc/dovecot/dovecot.conf ssl_key_file ' /etc/lab/server.key' ' =' ;;
  liveimap-dovecot-12)
    setkv /etc/dovecot/dovecot.conf ssl ' yes' ' ='
    setkv /etc/dovecot/dovecot.conf ssl_cert_file ' /etc/lab/chain.crt' ' ='
    setkv /etc/dovecot/dovecot.conf ssl_key_file ' /etc/lab/server.key' ' =' ;;
  liveimap-dovecot-22)
    docker exec "$c" sh -c 'printf "ssl = yes\nssl_cert = </etc/lab/chain.crt\nssl_key = </etc/lab/server.key\n" > /etc/dovecot/conf.d/10-ssl.conf; sed -i "s/^ssl = no/ssl = yes/" /etc/dovecot/dovecot.conf' ;;
  liveimap-dovecot-245|liveimap-dovecot-dev)
    # 2.4: the main dovecot.conf sets ssl last, so the lab settings go at its end (last value wins).
    # Edit on the host: the 2.4 image has no sed or grep.
    conf=$(docker exec "$c" sh -c 'for f in /usr/local/etc/dovecot/dovecot.conf /etc/dovecot/dovecot.conf; do [ -f $f ] && echo $f && break; done')
    tmp=$(mktemp)
    docker cp "$c:$conf" "$tmp" >/dev/null
    sed -i '/^# lab-tls/,$d' "$tmp"
    printf '# lab-tls\nssl = yes\nssl_server_cert_file = /etc/lab/chain.crt\nssl_server_key_file = /etc/lab/server.key\n' >> "$tmp"
    docker cp "$tmp" "$c:$conf" >/dev/null
    rm -f "$tmp" ;;
  liveimap-cyrus-22|liveimap-cyrus-24|liveimap-cyrus-25)
    setkv /etc/imapd.conf tls_cert_file ' /etc/lab/chain.crt' ':'
    setkv /etc/imapd.conf tls_key_file ' /etc/lab/server.key' ':' ;;
  liveimap-cyrus-36|liveimap-cyrus-3124|liveimap-cyrus-3137)
    setkv /etc/imapd.conf tls_server_cert ' /etc/lab/chain.crt' ':'
    setkv /etc/imapd.conf tls_server_key ' /etc/lab/server.key' ':'
    # Cyrus 3.12+ defaults allowstarttls to no (cyr_info conf-all); without it STARTTLS is "BAD Unrecognized command".
    setkv /etc/imapd.conf allowstarttls ' yes' ':' ;;
  liveimap-courier-50|liveimap-courier-606)
    docker exec "$c" sh -c 'for f in $(find / -xdev -name imapd-ssl -path "*etc*" 2>/dev/null); do sed -i "s|^TLS_CERTFILE=.*|TLS_CERTFILE=/etc/lab/courier.pem|; /^IMAP_STARTTLS=/d" $f; grep -q "^TLS_CERTFILE=" $f || echo "TLS_CERTFILE=/etc/lab/courier.pem" >> $f; echo "IMAP_STARTTLS=YES" >> $f; done; cat /etc/lab/server.key /etc/lab/server.crt /etc/lab/ca.crt > /etc/lab/courier.pem; chmod 600 /etc/lab/courier.pem; if id courier >/dev/null 2>&1; then chown courier:courier /etc/lab/courier.pem; elif id daemon >/dev/null 2>&1; then chown daemon /etc/lab/courier.pem; fi' ;;
  liveimap-uwimap)
    docker exec "$c" sh -c 'cat /etc/lab/server.key /etc/lab/server.crt /etc/lab/ca.crt > /etc/ssl/certs/imapd.pem; mkdir -p /etc/ssl/private; cp /etc/ssl/certs/imapd.pem /etc/ssl/private/imapd.pem; printf "I accept the risk\\nset disable-plaintext nil\\n" > /etc/c-client.cf' ;;
  liveimap-greenmail|liveimap-greenmail-214|liveimap-greenmail-dev|liveimap-greenmail-16)
    docker cp "$CA/greenmail.p12" "$c:/home/greenmail/greenmail.p12" >/dev/null ;;
  liveimap-stalwart-0155)
    # 0.15 reads local TOML keys; the lab certificate is the default (the app connects by IP, no SNI).
    docker exec "$c" sh -c 'for f in /opt/stalwart/etc/config.toml /etc/stalwart/config.toml; do [ -f $f ] || continue; sed -i "/^# lab-tls/,/^# end lab-tls/d" $f; printf "# lab-tls\n[certificate.lab]\ncert = \"%%{file:/etc/lab/chain.crt}%%\"\nprivate-key = \"%%{file:/etc/lab/server.key}%%\"\ndefault = true\n# end lab-tls\n" >> $f; done' ;;
  *) echo "no TLS recipe for $c" >&2; exit 2 ;;
esac
# Restart: stop the daemon the up.tsv command starts, then start it again.
docker exec "$c" sh -c 'pkill -x dovecot 2>/dev/null; pkill -x cyrmaster 2>/dev/null; pkill -f couriertcpd 2>/dev/null; pkill -x inetd 2>/dev/null; pkill -f openbsd-inetd 2>/dev/null; sleep 2' || true
case "$c" in
  liveimap-dovecot-245) docker restart "$c" >/dev/null ;;
  liveimap-greenmail|liveimap-greenmail-214|liveimap-greenmail-dev|liveimap-greenmail-16) docker restart "$c" >/dev/null ;;
  liveimap-stalwart-0155) docker restart "$c" >/dev/null ;;
  liveimap-dovecot-dev|liveimap-cyrus-3124|liveimap-cyrus-3137|liveimap-courier-606) docker restart "$c" >/dev/null ;;
  *) [ -n "$up" ] && docker exec "$c" sh -c "$up" ;;
esac
echo "installed $c"
