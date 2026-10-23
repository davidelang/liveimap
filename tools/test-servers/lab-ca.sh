#!/bin/sh
# Lab CA and one server certificate shared by every lab server (SAN: LAB_BIND IP, LAB_NAME, and each fixed
# login_domain host name in servers.tsv, e.g. Zimbra's, which its LDAP checks against its own host name).
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
extra_names=$(awk -F '\t' '!/^#/ && $4 != "" && $4 != "LAB_MAIL_DOMAIN" { print $4 }' "$DIR/servers.tsv" | sort -u)
want="IP Address:$LAB_BIND, DNS:$LAB_NAME"
ext_san="IP:$LAB_BIND,DNS:$LAB_NAME"
for name in $extra_names; do
  want="$want, DNS:$name"
  ext_san="$ext_san,DNS:$name"
done
# No server.crt yet: issue one. An existing one openssl cannot read is an error, not a silent reissue.
have=""
if [ -e server.crt ]; then
  if ! san=$(openssl x509 -in server.crt -noout -ext subjectAltName); then
    echo "lab-ca.sh: openssl cannot read the subjectAltName of $OUT/server.crt" >&2
    exit 1
  fi
  have=$(printf '%s\n' "$san" | tail -1 | sed 's/^ *//')
fi
if [ "$have" != "$want" ]; then
  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$LAB_NAME" 2>/dev/null
  printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n' "$ext_san" > server.ext
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 825 -extfile server.ext
fi
cat server.crt server.key > server.pem
cat server.crt ca.crt > chain.crt
openssl pkcs12 -export -in server.crt -inkey server.key -certfile ca.crt -name lab -passout pass:labpass -out server.p12 2>/dev/null
openssl x509 -in ca.crt -outform der -out ca.der
chmod 644 ./*
echo "$OUT"
