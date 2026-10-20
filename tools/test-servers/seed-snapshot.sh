#!/bin/sh
# Stop the lab container and docker commit it to liveimap-seed-<server>.
# A volume or bind mount exits 1. No tar, no stamp, and the container stays stopped.
# This script is not run by the reseed commit.
set -eu

if [ $# -ne 1 ] || [ -z "${1:-}" ]; then
  echo "usage: tools/test-servers/seed-snapshot.sh <server>" >&2
  exit 1
fi
server=$1

case "$server" in
  cyrus-22|cyrus-25|cyrus-24|cyrus-36)
    ;;
  cyrus-3124|cyrus-3137)
    ;;
  dovecot-10|dovecot-12|dovecot-22|dovecot-245|dovecot-dev)
    ;;
  courier-50)
    ;;
  courier-606|uwimap)
    ;;
  stalwart-0155)
    ;;
  stalwart|stalwart-01625|stalwart-dev)
    ;;
  zimbra-883)
    ;;
  *)
    echo "unknown server $server" >&2
    exit 1
    ;;
esac

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

bad=$(docker inspect -f '{{range .Mounts}}{{if or (eq .Type "volume") (eq .Type "bind")}}{{println .Destination}}{{end}}{{end}}' "$name")
if [ -n "$bad" ]; then
  printf '%s\n' "$bad" >&2
  exit 1
fi

docker commit "$name" "liveimap-seed-$server" >/dev/null
