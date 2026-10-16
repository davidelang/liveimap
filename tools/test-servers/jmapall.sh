#!/bin/bash
HERE=$(cd "$(dirname "$0")" && pwd)
while pgrep -f accfull.sh >/dev/null || pgrep -f acclab.py >/dev/null; do sleep 10; done
for n in cyrus-3124 cyrus-3137 stalwart stalwart-01625 stalwart-dev stalwart-0155; do timeout 1200 python3 "$HERE/jmaprun.py" $n jmap10-$n 2>&1 | grep -v -i pass | head -14; done; echo JMAPDONE
