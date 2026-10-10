#!/bin/sh
# Deploy the lab certificate as Zimbra's commercial certificate (container must be running and up), then restart.
set -eu
DIR=$(dirname "$(readlink -f "$0")")
CA="$DIR/generated/ca"
c=${1:-liveimap-zimbra-883}
docker cp "$CA/server.key" "$c:/opt/zimbra/ssl/zimbra/commercial/commercial.key"
docker cp "$CA/server.crt" "$c:/tmp/lab.crt"
docker cp "$CA/ca.crt" "$c:/tmp/lab-ca.crt"
docker exec "$c" sh -c 'chown zimbra:zimbra /opt/zimbra/ssl/zimbra/commercial/commercial.key /tmp/lab.crt /tmp/lab-ca.crt'
docker exec -u zimbra "$c" sh -c '/opt/zimbra/bin/zmcertmgr verifycrt comm /opt/zimbra/ssl/zimbra/commercial/commercial.key /tmp/lab.crt /tmp/lab-ca.crt && /opt/zimbra/bin/zmcertmgr deploycrt comm /tmp/lab.crt /tmp/lab-ca.crt' 2>&1 | tail -3
docker exec -u zimbra "$c" sh -c '/opt/zimbra/bin/zmcontrol restart' >/dev/null 2>&1
echo "zimbra certificate deployed"
