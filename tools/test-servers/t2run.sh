#!/bin/bash
# Lab chunk T2: deploy (no build), full IMAP acceptance matrix on every server.
# The commit is the first argument. It is not checked against HEAD.
set -u
COMMIT="${1:?usage: t2run.sh COMMIT}"
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
LOGDIR=${LAB_LOG:-.}
REC=${LAB_REC:-$HERE/recordings}
mkdir -p "$LOGDIR" "$REC"
cd "$REPO"
export LAB_BIND=$(ip -4 route get 1.1.1.1 | grep -o 'src [0-9.]*' | cut -d' ' -f2)
RUNID="t2-$(date +%Y%m%d-%H%M%S)"
echo "LAB_BIND=$LAB_BIND RUNID=$RUNID commit=$COMMIT start $(date +%H:%M:%S)" | tee "$LOGDIR/t2-master.log"
./deploy emulator 2>&1 | tee -a "$LOGDIR/t2-master.log" | tail -5
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/x86_64/debug/app-x86_64-debug-androidTest.apk 2>&1 | tee -a "$LOGDIR/t2-master.log" | tail -2
adb -s emulator-5554 shell dumpsys package org.dlang.liveimap | grep -E 'versionName|lastUpdateTime' | head -4 | tee -a "$LOGDIR/t2-master.log"
SUM="$REC/${RUNID}-${COMMIT}-summary.tsv"
echo -e "server\tpass\tfail\tskip\tstatus" > "$SUM"
for n in $(PYTHONPATH="$HERE" python3 -c "import lab; print(' '.join(s['name'] for s in lab.servers()))"); do
  echo "=== $n $(date +%H:%M:%S)" | tee -a "$LOGDIR/t2-master.log"
  timeout 2400 python3 "$HERE/acclab.py" $n auto ${RUNID}-$n > "$LOGDIR/t2-$n.log" 2>&1
  rc=$?
  head -8 "$LOGDIR/t2-$n.log" | tee -a "$LOGDIR/t2-master.log"
  summ=$(grep -m1 "# summary" "$LOGDIR/t2-$n.log" || true)
  echo "$summ" | tee -a "$LOGDIR/t2-master.log"
  if [[ "$summ" =~ ([0-9]+)\ pass,\ ([0-9]+)\ fail,\ ([0-9]+)\ skip ]]; then
    echo -e "$n\t${BASH_REMATCH[1]}\t${BASH_REMATCH[2]}\t${BASH_REMATCH[3]}\t$([ $rc -eq 0 ] && echo ok || echo fail-rc$rc)" >> "$SUM"
  else
    echo -e "$n\t?\t?\t?\trc$rc" >> "$SUM"
  fi
  echo "T2 progress $(date '+%H:%M:%S') finished $n; see $SUM" > "$LOGDIR/lab-run.lock"
done
echo "T2DONE $(date +%H:%M:%S) $SUM" | tee -a "$LOGDIR/t2-master.log"
echo "T2DONE $(date '+%Y-%m-%d %H:%M %Z') $SUM" > "$LOGDIR/lab-run.lock"
