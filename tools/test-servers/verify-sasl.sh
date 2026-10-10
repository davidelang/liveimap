#!/bin/sh
# Check that the libetpan archives were built with SASL (USE_SASL): mailimap.o and mailsmtp.o must reference sasl_client_*.
set -eu
ROOT=$(dirname "$(readlink -f "$0")")/../../third_party/libetpan/artifact
fail=0
for abi in arm64-v8a x86_64; do
  t=$(mktemp -d)
  (cd "$t" && ar x "$ROOT/$abi/libetpan.a" mailimap.o mailsmtp.o)
  for o in mailimap.o mailsmtp.o; do
    n=$(nm "$t/$o" | grep -c ' U sasl_client_' || true)
    printf '%s\t%s\t%s\n' "$abi" "$o" "$n"
    [ "$n" -gt 0 ] || fail=1
  done
  rm -rf "$t"
done
exit $fail
