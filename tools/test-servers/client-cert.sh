#!/bin/sh
# Lab-only TLS client certificate for the lab test account, issued by the lab CA (lab-ca.sh).
# Output (gitignored): generated/ca/client.{key,crt,p12}; CN = the lab user, EKU clientAuth.
# The .p12 (password "liveimap") is what gets installed in the emulator's KeyChain. Re-runnable.
set -eu
DIR=$(dirname "$(readlink -f "$0")")
OUT="$DIR/generated/ca"
# client-cert.sh [CN]       RSA 2048 -> client.{key,crt,p12}
# client-cert.sh ec [CN]    EC P-256 -> client-ec.{key,crt,p12}
KIND=rsa
[ "${1:-}" = ec ] && { KIND=ec; shift; }
USER_CN="${1:-liveimap}"
cd "$OUT"
[ -f ca.key ] || { echo "run lab-ca.sh first" >&2; exit 1; }
B=client; NEWKEY=rsa:2048
[ "$KIND" = ec ] && { B=client-ec; openssl ecparam -name prime256v1 -out ec.param 2>/dev/null; NEWKEY=ec:ec.param; }
if [ ! -f $B.crt ] || ! openssl x509 -in $B.crt -noout -subject | grep -q "CN *= *$USER_CN"; then
  openssl req -newkey $NEWKEY -nodes -keyout $B.key -out $B.csr -subj "/CN=$USER_CN" 2>/dev/null
  printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n' > client.ext
  openssl x509 -req -in $B.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -extfile client.ext -out $B.crt 2>/dev/null
  openssl pkcs12 -export -inkey $B.key -in $B.crt -certfile ca.crt -name "LiveIMAP lab client" \
    -passout pass:liveimap -out $B.p12 2>/dev/null
fi
openssl x509 -in $B.crt -noout -subject -fingerprint -sha256
