# usage: s155.sh METHOD PATH [json-body]   (lab admin credential piped from the container, never printed or saved)
C=liveimap-stalwart-0155
docker exec $C printenv STALWART_RECOVERY_ADMIN | docker run --rm -i --network container:$C alpine sh -c 'read -r A; apk add -q curl >/dev/null 2>&1; curl -s -u "$A" -X '"$1"' -H "Content-Type: application/json" '"${3:+--data '$3'}"' http://127.0.0.1:8080'"$2"
