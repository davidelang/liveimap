#!/bin/sh
# Lab-only TLS client certificate for the lab test account, issued by the lab CA (lab-ca.sh).
# Output (gitignored): generated/ca/client.{key,crt,p12}; CN = the lab user, EKU clientAuth.
# The .p12 (password "liveimap") is what gets installed in the emulator's KeyChain. Re-runnable.
set -eu
DIR=$(dirname "$(readlink -f "$0")")
OUT="$DIR/generated/ca"
USER_CN="${1:-liveimap}"
cd "$OUT"
[ -f ca.key ] || { echo "run lab-ca.sh first" >&2; exit 1; }
if [ ! -f client.crt ] || ! openssl x509 -in client.crt -noout -subject | grep -q "CN *= *$USER_CN"; then
  openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr -subj "/CN=$USER_CN" 2>/dev/null
  printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n' > client.ext
  openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -extfile client.ext -out client.crt 2>/dev/null
  openssl pkcs12 -export -inkey client.key -in client.crt -certfile ca.crt -name "LiveIMAP lab client" \
    -passout pass:liveimap -out client.p12 2>/dev/null
fi
openssl x509 -in client.crt -noout -subject -fingerprint -sha256
