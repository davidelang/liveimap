#!/bin/bash
# Lab chunk T3: JMAP suite on every JMAP server. Do not rebuild. Do not redeploy.
# The commit is the first argument. It is not checked against HEAD.
set -u
COMMIT="${1:?usage: t3run.sh COMMIT}"
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
LOGDIR=$(mkdir -p "${LAB_LOG:-.}" && cd "${LAB_LOG:-.}" && pwd)
REC=$(mkdir -p "${LAB_REC:-$HERE/recordings}" && cd "${LAB_REC:-$HERE/recordings}" && pwd)
RUNID="t3-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOGDIR" "$REC"
cd "$REPO"
echo "T3 RUNID=$RUNID commit=$COMMIT start $(date +%H:%M:%S) emu=$(adb -s emulator-5554 shell dumpsys package org.dlang.liveimap | grep versionName | head -1)" | tee "$LOGDIR/t3-master.log"
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/x86_64/debug/app-x86_64-debug-androidTest.apk 2>&1 | tee -a "$LOGDIR/t3-master.log" | tail -2
SUM="$REC/${RUNID}-${COMMIT}-jmap-summary.tsv"
echo -e "server\tresult\tnote" > "$SUM"
for n in cyrus-3124 cyrus-3137 stalwart stalwart-01625 stalwart-dev stalwart-0155; do
  echo "=== $n $(date +%H:%M:%S)" | tee -a "$LOGDIR/t3-master.log"
  echo "T3 progress $(date '+%H:%M:%S') running $n" > "$LOGDIR/lab-run.lock"
  timeout 1200 python3 "$HERE/jmaprun.py" $n ${RUNID}-$n > "$LOGDIR/t3-$n.log" 2>&1
  rc=$?
  head -20 "$LOGDIR/t3-$n.log" | tee -a "$LOGDIR/t3-master.log"
  if grep -q '^OK$' "$LOGDIR/t3-$n.log"; then
    echo -e "$n\tok\trc$rc" >> "$SUM"
  elif grep -q '^FAIL$' "$LOGDIR/t3-$n.log"; then
    echo -e "$n\tfail\trc$rc" >> "$SUM"
  else
    echo -e "$n\tunknown\trc$rc" >> "$SUM"
  fi
  tail -5 "$LOGDIR/t3-$n.log" | tee -a "$LOGDIR/t3-master.log"
done
echo "T3DONE $(date +%H:%M:%S) $SUM" | tee -a "$LOGDIR/t3-master.log"
echo "T3DONE $(date '+%Y-%m-%d %H:%M %Z') $SUM" > "$LOGDIR/lab-run.lock"
