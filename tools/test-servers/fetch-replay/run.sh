#!/bin/sh
# Host replay of a 32-command pipelined UID FETCH burst (indexNewest previews) through libetpan's
# mailimap_uid_fetch_multiple. Builds a throwaway host libetpan from third_party/libetpan/src in /tmp.
#   burst-no-literal-crlf.txt  old fake output ("{n}" without CRLF): expect a parse error
#   burst-fixed-fake.txt       fixed fake output: expect r=0 results=32 cmdOk=32
# Host link note: no -lsasl2/-lcurl dev libs needed; libetpan.so is linked without them.
set -eu
DIR=$(dirname "$(readlink -f "$0")")
SRC="$DIR/../../../third_party/libetpan/src"
B=${B:-/tmp/liveimap-etp-host}
[ -f "$B/src/.libs/libetpan.so" ] || { rm -rf "$B"; cp -a "$SRC" "$B"; make -C "$B" -j8 >/dev/null; }
cd "$B/unittest"
gcc -I. -Iimap -Icommon -I../include -I../src/data-types -I../src/low-level/imap -I.. -o "$B/replay" "$DIR/replay.c" imap/imap_test_utils.c common/*.c \
  -L../src/.libs -letpan -lz -lssl -lcrypto -lpthread
for f in "$DIR"/burst-*.txt; do LD_LIBRARY_PATH=../src/.libs "$B/replay" "$f"; done
