#!/bin/sh
# libFuzzer build (clang with compiler-rt; LLVM=path to a dir with clang/clang++ wrappers) of fuzz_imap against a
# fuzzer-instrumented libetpan ($ETP_FUZZ, configured with CC=clang CFLAGS="-fsanitize=fuzzer-no-link,address,undefined -fno-sanitize=function,shift-base").
set -eu
cd "$(dirname "$0")"
R=$(cd ../../.. && pwd); J=${JAVA_INC:-/usr/lib/jvm/java-17-openjdk-amd64/include}; ETP=${ETP_FUZZ:?}; LL=${LLVM:?}
# -fno-sanitize=function: libetpan calls its parsers through cast function pointers by design.
F="-g -O1 -fno-omit-frame-pointer -fsanitize=fuzzer-no-link,address,undefined -fno-sanitize=function,shift-base"
# stubs/jni.h is the NDK jni.h (AttachCurrentThread takes JNIEnv**, as on Android).
CX="$LL/clang++ -std=c++17 -w $F -Istubs -I$R/app/src/main/cpp -I$ETP/include"
O=${OUT:-/tmp}/liveimap-fuzz; mkdir -p "$O"
$CX -c imap_harness.cpp -o "$O/h.o"
$CX -c "$R/app/src/main/cpp/imap_bursts.cpp" -o "$O/b.o"
$CX -c "$R/app/src/main/cpp/keychain_key.cpp" -o "$O/k.o"
$LL/clang -c sasl_stubs.c -o "$O/s.o"
$CX -c fuzz_imap.cpp -o "$O/f.o"
$LL/clang++ -fsanitize=fuzzer,address,undefined -fno-sanitize=function,shift-base -o "$O/fuzz_imap" "$O/f.o" "$O/h.o" "$O/b.o" "$O/k.o" "$O/s.o" "$ETP/src/.libs/libetpan.a" -lssl -lcrypto -lz -lpthread
echo "$O/fuzz_imap"
