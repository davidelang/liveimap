#!/bin/sh
# Write generated/<server>/seed-snapshot.tar and seed-snapshot.stamp.
# GreenMail has no tar. This script is not run by the reseed commit.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GEN="$DIR/generated"
if [ $# -ne 1 ] || [ -z "${1:-}" ]; then
  echo "usage: tools/test-servers/seed-snapshot.sh <server>" >&2
  exit 1
fi
server=$1

case "$server" in
  greenmail|greenmail-16|greenmail-214|greenmail-dev)
    echo "no seed-snapshot.tar for $server" >&2
    exit 0
    ;;
esac

case "$server" in
  cyrus-22|cyrus-25|cyrus-24|cyrus-36)
    paths="/var/spool/cyrus/mail /var/lib/cyrus /var/spool/sieve" ;;
  cyrus-3124|cyrus-3137)
    paths="/var/spool/cyrus/mail /var/lib/cyrus /var/spool/cyrus/sieve" ;;
  dovecot-10|dovecot-12|dovecot-22|dovecot-245|dovecot-dev)
    paths="/srv/vmail" ;;
  courier-50)
    paths="/var/vmail" ;;
  courier-606|uwimap)
    paths="/var/mail" ;;
  stalwart-0155)
    paths="/opt/stalwart/data" ;;
  stalwart|stalwart-01625|stalwart-dev)
    paths="/var/lib/stalwart" ;;
  zimbra-883)
    paths="/opt/zimbra/store /opt/zimbra/index /opt/zimbra/db/data /opt/zimbra/data/ldap/mdb/db /opt/zimbra/redolog" ;;
  *)
    echo "unknown server $server" >&2
    exit 1
    ;;
esac
if [ "$server" = "uwimap" ]; then
  paths="/var/mail /home"
fi

name=liveimap-$server
if ! docker inspect "$name" >/dev/null 2>&1; then
  echo "missing container $name" >&2
  exit 1
fi

running=$(docker inspect -f '{{.State.Running}}' "$name")
if [ "$running" = "true" ]; then
  if ! docker stop -t 60 "$name" >/dev/null; then
    exit 1
  fi
  running=$(docker inspect -f '{{.State.Running}}' "$name")
  if [ "$running" = "true" ]; then
    echo "stop failed $name" >&2
    exit 1
  fi
fi

stage=$(mktemp -d)
# shellcheck disable=SC2086
for p in $paths; do
  rel=${p#/}
  mkdir -p "$stage/$(dirname "$rel")"
  rm -rf "$stage/$rel"
  docker cp "$name:$p" "$stage/$rel"
done
mkdir -p "$GEN/$server"
out="$GEN/$server/seed-snapshot.tar"
rels=
# shellcheck disable=SC2086
for p in $paths; do
  rels="$rels ${p#/}"
done
# shellcheck disable=SC2086
tar -C "$stage" -cf "$out" $rels
rm -rf "$stage"
if [ "$server" = "zimbra-883" ]; then
  if tar -tf "$out" | grep -E '(^|/)opt/zimbra/bin(/|$)' >/dev/null 2>&1; then
    rm -f "$out"
    echo "zimbra tar includes /opt/zimbra/bin" >&2
    exit 1
  fi
fi
image=$(docker inspect -f '{{.Image}}' "$name")
stamp="$GEN/$server/seed-snapshot.stamp"
{
  printf 'image %s\n' "$image"
  for spec in \
    "tools/test-servers/generated/corpus-v1/manifest.tsv|$GEN/corpus-v1/manifest.tsv" \
    "tools/test-servers/seed/accounts.tsv|$DIR/seed/accounts.tsv" \
    "tools/test-servers/seed/folders.tsv|$DIR/seed/folders.tsv" \
    "tools/test-servers/seed/corpus-v1.sums|$DIR/seed/corpus-v1.sums"
  do
    key=${spec%%|*}
    path=${spec#*|}
    sum=$(sha256sum "$path" | awk 'NR==1 { print $1; exit }')
    printf '%s %s\n' "$key" "$sum"
  done
} > "$stamp"
chmod a+r "$out" "$stamp"
chmod a+rx "$GEN/$server"
flag=$(mktemp)
printf 'yes\n' > "$flag"
docker cp "$flag" "$name":/.liveimap-no-reseed
rm -f "$flag"
