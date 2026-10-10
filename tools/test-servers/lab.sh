#!/bin/sh
# Thin wrapper: tools/test-servers/lab.sh run [--server NAME ...]
exec python3 "$(dirname "$(readlink -f "$0")")/lab.py" "$@"
