#!/bin/bash
# Lab chunk T1: deploy (no build), test APK install, full IMAP acceptance matrix on every server.
# The commit is the first argument. It is not checked against HEAD.
set -u
COMMIT="${1:?usage: t1run.sh COMMIT}"
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
LOGDIR=${LAB_LOG:-.}
mkdir -p "$LOGDIR"
cd "$REPO"
export LAB_BIND=$(ip -4 route get 1.1.1.1 | grep -o 'src [0-9.]*' | cut -d' ' -f2)
echo "LAB_BIND=$LAB_BIND commit=$COMMIT start $(date +%H:%M:%S)"
./deploy emulator 2>&1 | tail -2
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/x86_64/debug/app-x86_64-debug-androidTest.apk 2>&1 | tail -1
for n in $(PYTHONPATH="$HERE" python3 -c "import lab; print(' '.join(s['name'] for s in lab.servers()))"); do
  echo "=== $n $(date +%H:%M:%S)"
  timeout 2400 python3 "$HERE/acclab.py" $n auto t1-$n > "$LOGDIR/t1-$n.log" 2>&1
  head -5 "$LOGDIR/t1-$n.log"
  grep -m1 "# summary" "$LOGDIR/t1-$n.log" || true
done
echo "T1DONE $(date +%H:%M:%S) $COMMIT"
