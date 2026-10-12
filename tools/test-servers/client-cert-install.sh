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
    # LAB_SUBMISSION_RELAY=host:port: relay submission to that sink (submission-proxy.py), for AUTH EXTERNAL
    # on SMTP. Without a relay Dovecot's submission refuses every session.
    if [ -n "${LAB_SUBMISSION_RELAY:-}" ]; then
      sed -i '/^# end lab-clientcert/i submission_relay_host = '"${LAB_SUBMISSION_RELAY%:*}"'\nsubmission_relay_port = '"${LAB_SUBMISSION_RELAY##*:}"'\nsubmission_relay_trusted = yes' "$tmp"
    fi
    docker cp "$tmp" "$c:$conf" >/dev/null
    # LAB_TLS_MAX=1.2 caps the server at TLS 1.2 (Dovecot has no max-protocol setting, so it goes into the
    # container's system OpenSSL config, which Dovecot loads); unset restores the original (TLS 1.3).
    # /usr/lib/ssl/openssl.cnf is a symlink to /etc/ssl/openssl.cnf; always write the real file. The pristine
    # copy comes from the image (the container has no cp), so re-runs never stack edits.
    ssl=/etc/ssl/openssl.cnf
    t=$(docker create "$(docker inspect -f '{{.Config.Image}}' "$c")")
    docker cp -L "$t:$ssl" - | tar xO > "$tmp"
    docker rm "$t" >/dev/null
    if [ "${LAB_TLS_MAX:-}" = 1.2 ]; then
      sed -i '/^\[openssl_init\]/a ssl_conf = lab_ssl_sect' "$tmp"
      printf '\n[lab_ssl_sect]\nsystem_default = lab_tls_sect\n[lab_tls_sect]\nMaxProtocol = TLSv1.2\n' >> "$tmp"
    fi
    docker cp "$tmp" "$c:$ssl" >/dev/null
    rm -f "$tmp"
    docker exec "$c" doveconf -n >/dev/null
    docker restart "$c" >/dev/null ;;
  *) echo "no client-cert recipe for $c" >&2; exit 2 ;;
esac
echo "client certs requested by $c"
