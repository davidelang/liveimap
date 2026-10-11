#!/bin/sh
# Lab only: add a STARTTLS submission listener on 587 to a RUNNING Stalwart 0.16 container (JMAP admin API;
# the image only listens on 25 and 465 while docker maps 587). Idempotent; restart the container afterwards.
c="$1"
api() { docker exec -i "$c" /bin/sh -c 'curl -sS -u "$STALWART_RECOVERY_ADMIN" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/jmap'; }
if echo '{"using":["urn:ietf:params:jmap:core","urn:stalwart:jmap"],"methodCalls":[["x:NetworkListener/get",{"ids":null,"properties":["bind"]},"c"]]}' | api | grep -q '587'; then
  echo "$c: 587 already present"; exit 0
fi
echo '{"using":["urn:ietf:params:jmap:core","urn:stalwart:jmap"],"methodCalls":[["x:NetworkListener/set",{"create":{"s1":{"name":"submission","bind":{"[::]:587":true},"protocol":"smtp","useTls":true,"tlsImplicit":false}}},"c"]]}' | api
