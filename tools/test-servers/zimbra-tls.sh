#!/bin/sh
# Deploy the lab certificate as Zimbra's commercial certificate (container must be running and up), then restart.
set -eu
set -o pipefail
DIR=$(dirname "$(readlink -f "$0")")
CA="$DIR/generated/ca"
c=${1:-liveimap-zimbra-883}
. "$DIR/host.env"
# Zimbra's LDAP checks its own host name on the certificate: the shared lab server certificate names it (lab-ca.sh
# adds each fixed servers.tsv login_domain), so Zimbra gets the same certificate as every other lab server.
zh=$(docker inspect -f '{{.Config.Hostname}}' "$c")
san=$(openssl x509 -in "$CA/server.crt" -noout -ext subjectAltName)
case "$san" in
  *"DNS:$zh,"*|*"DNS:$zh") ;;
  *) echo "zimbra-tls: $CA/server.crt does not name $zh; run tools/test-servers/lab-ca.sh" >&2; exit 1 ;;
esac
Z="$CA"
docker cp "$Z/server.key" "$c:/opt/zimbra/ssl/zimbra/commercial/commercial.key"
docker cp "$Z/server.crt" "$c:/tmp/lab.crt"
docker cp "$CA/ca.crt" "$c:/tmp/lab-ca.crt"
docker exec "$c" sh -c 'chown zimbra:zimbra /opt/zimbra/ssl/zimbra/commercial/commercial.key /tmp/lab.crt /tmp/lab-ca.crt'
docker exec -u zimbra "$c" sh -c '/opt/zimbra/bin/zmcertmgr verifycrt comm /opt/zimbra/ssl/zimbra/commercial/commercial.key /tmp/lab.crt /tmp/lab-ca.crt && /opt/zimbra/bin/zmcertmgr deploycrt comm /tmp/lab.crt /tmp/lab-ca.crt' 2>&1 | tail -3
docker exec -u zimbra "$c" sh -c '/opt/zimbra/bin/zmcontrol restart'
echo "zimbra certificate deployed"
