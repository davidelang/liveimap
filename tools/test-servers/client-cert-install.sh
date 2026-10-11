#!/bin/sh
# Lab-only: make a lab server request TLS client certificates signed by the lab CA (lab-ca.sh) and offer
# SASL EXTERNAL with the username taken from the cert CN. Client certs come from client-cert.sh.
# Usage: client-cert-install.sh liveimap-dovecot-245. Re-runnable (replaces its own block).
set -eu
DIR=$(dirname "$(readlink -f "$0")")
c="$1"
docker cp "$DIR/generated/ca/ca.crt" "$c:/etc/lab/ca.crt" >/dev/null
case "$c" in
  liveimap-dovecot-245)
    # Dovecot 2.4 names: ssl_server_request_client_cert + ssl_server_ca_file (2.3's ssl_verify_client_cert /
    # ssl_ca are gone), auth_ssl_username_from_cert + ssl_server_cert_username_field. The client cert is
    # requested but not required, so password logins keep working on the same ports.
    conf=$(docker exec "$c" sh -c 'for f in /usr/local/etc/dovecot/dovecot.conf /etc/dovecot/dovecot.conf; do [ -f $f ] && echo $f && break; done')
    tmp=$(mktemp)
    docker cp "$c:$conf" "$tmp" >/dev/null
    sed -i '/^# lab-clientcert/,/^# end lab-clientcert/d' "$tmp"
    cat >> "$tmp" <<'CONF'
# lab-clientcert
ssl_server_request_client_cert = yes
ssl_server_ca_file = /etc/lab/ca.crt
ssl_server_cert_username_field = commonName
# 2.4 defaults ssl_server_require_crl=yes; the lab CA publishes no CRL, so every cert would count as invalid.
ssl_server_require_crl = no
auth_ssl_username_from_cert = yes
auth_mechanisms {
  external = yes
}
# EXTERNAL does a plain passdb verification; the verified cert is the credential, so a static
# no-password passdb restricted to EXTERNAL answers it (the cert CN still has to be a known user).
passdb lab-cert {
  driver = static
  mechanisms_filter = external
  fields {
    nopassword = yes
  }
}
# end lab-clientcert
CONF
    docker cp "$tmp" "$c:$conf" >/dev/null
    rm -f "$tmp"
    docker exec "$c" doveconf -n >/dev/null
    docker restart "$c" >/dev/null ;;
  *) echo "no client-cert recipe for $c" >&2; exit 2 ;;
esac
echo "client certs requested by $c"
