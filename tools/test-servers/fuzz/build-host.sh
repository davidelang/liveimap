#!/bin/sh
# Host ASan/UBSan build of boundary from the app's liveimap_jni.cpp + an ASan libetpan ($ETP, see README).
set -eu
cd "$(dirname "$0")"
R=$(cd ../../.. && pwd); J=${JAVA_INC:-/usr/lib/jvm/java-17-openjdk-amd64/include}; ETP=${ETP:?ASan libetpan build dir}
CX="g++ -std=c++17 -g -O1 -w -fsanitize=address,undefined -fno-omit-frame-pointer -Istubs -I$R/app/src/main/cpp -I$J -I$J/linux -I$ETP/include"
O=${OUT:-/tmp}/liveimap-fuzz-host; mkdir -p "$O"
$CX -c imap_harness.cpp -o "$O/h.o"
$CX -c "$R/app/src/main/cpp/imap_bursts.cpp" -o "$O/b.o"
$CX -c "$R/app/src/main/cpp/keychain_key.cpp" -o "$O/k.o"
gcc -c sasl_stubs.c -o "$O/s.o"
$CX -c boundary.cpp -o "$O/boundary.o"
g++ -fsanitize=address,undefined -o "$O/boundary" "$O/boundary.o" "$O/h.o" "$O/b.o" "$O/k.o" "$O/s.o" "$ETP/src/.libs/libetpan.a" -lssl -lcrypto -lz -lpthread
$CX -c replay.cpp -o "$O/replay.o"
g++ -fsanitize=address,undefined -o "$O/replay" "$O/replay.o" "$O/h.o" "$O/b.o" "$O/k.o" "$O/s.o" "$ETP/src/.libs/libetpan.a" -lssl -lcrypto -lz -lpthread
echo "$O/boundary $O/replay"
