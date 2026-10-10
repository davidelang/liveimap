#!/bin/sh
# Lab CA and one server certificate for the lab host (SAN: LAB_BIND IP and LAB_NAME).
# Output (gitignored): generated/ca/{ca.key,ca.crt,server.key,server.crt,server.pem,server.p12,ca.der}
# Re-runnable: keeps an existing CA; reissues the server certificate when its SANs differ.
set -eu
DIR=$(dirname "$(readlink -f "$0")")
. "$DIR/host.env"
OUT="$DIR/generated/ca"
mkdir -p "$OUT"
cd "$OUT"
if [ ! -f ca.key ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days 3650 \
    -subj "/CN=LiveIMAP lab CA" -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
fi
want="IP Address:$LAB_BIND, DNS:$LAB_NAME"
have=$(openssl x509 -in server.crt -noout -ext subjectAltName 2>/dev/null | tail -1 | sed 's/^ *//' || true)
if [ "$have" != "$want" ] && [ "$have" != "DNS:$LAB_NAME, IP Address:$LAB_BIND" ]; then
  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$LAB_NAME" 2>/dev/null
  printf 'subjectAltName=IP:%s,DNS:%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n' "$LAB_BIND" "$LAB_NAME" > server.ext
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 825 -extfile server.ext 2>/dev/null
fi
cat server.crt server.key > server.pem
cat server.crt ca.crt > chain.crt
openssl pkcs12 -export -in server.crt -inkey server.key -certfile ca.crt -name lab -passout pass:labpass -out server.p12 2>/dev/null
openssl x509 -in ca.crt -outform der -out ca.der
chmod 644 ./*
echo "$OUT"
