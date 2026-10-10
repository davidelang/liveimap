#!/bin/sh
# Deploy the lab certificate as Zimbra's commercial certificate (container must be running and up), then restart.
set -eu
DIR=$(dirname "$(readlink -f "$0")")
CA="$DIR/generated/ca"
c=${1:-liveimap-zimbra-883}
. "$DIR/host.env"
# Zimbra's LDAP checks its own host name on the same certificate, so the Zimbra certificate also names the container host.
zh=$(docker inspect -f '{{.Config.Hostname}}' "$c")
Z="$CA/zimbra"; mkdir -p "$Z"
openssl req -newkey rsa:2048 -nodes -keyout "$Z/server.key" -out "$Z/server.csr" -subj "/CN=$zh" 2>/dev/null
printf 'subjectAltName=IP:%s,DNS:%s,DNS:%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n' "$LAB_BIND" "$LAB_NAME" "$zh" > "$Z/server.ext"
openssl x509 -req -in "$Z/server.csr" -CA "$CA/ca.crt" -CAkey "$CA/ca.key" -CAcreateserial -out "$Z/server.crt" -days 825 -extfile "$Z/server.ext" 2>/dev/null
chmod 644 "$Z"/*
docker cp "$Z/server.key" "$c:/opt/zimbra/ssl/zimbra/commercial/commercial.key"
docker cp "$Z/server.crt" "$c:/tmp/lab.crt"
docker cp "$CA/ca.crt" "$c:/tmp/lab-ca.crt"
docker exec "$c" sh -c 'chown zimbra:zimbra /opt/zimbra/ssl/zimbra/commercial/commercial.key /tmp/lab.crt /tmp/lab-ca.crt'
docker exec -u zimbra "$c" sh -c '/opt/zimbra/bin/zmcertmgr verifycrt comm /opt/zimbra/ssl/zimbra/commercial/commercial.key /tmp/lab.crt /tmp/lab-ca.crt && /opt/zimbra/bin/zmcertmgr deploycrt comm /tmp/lab.crt /tmp/lab-ca.crt' 2>&1 | tail -3
docker exec -u zimbra "$c" sh -c '/opt/zimbra/bin/zmcontrol restart' >/dev/null 2>&1
echo "zimbra certificate deployed"
