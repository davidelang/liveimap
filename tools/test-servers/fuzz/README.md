# Server-response boundary tests and fuzzing

Host builds of the app's IMAP response handling: libetpan's parser plus the app's own extension parsers and
post-processing in `app/src/main/cpp/liveimap_jni.cpp` (ESEARCH/PARTIAL, THREAD, PREVIEW, BINARY, scope hits,
COPYUID expansion). `imap_harness.cpp` includes `liveimap_jni.cpp` directly (host stubs: `stubs/` holds the NDK
`jni.h` and an `android/log.h` no-op; `sasl_stubs.c` stands in for Cyrus SASL, never initialized here) and feeds
bytes through a socketpair, as a server would send them, with the tagged reply `1 ...`.

## libetpan builds (outside the repo)

Copy `third_party/libetpan/src` (patched, after `third_party/fetch-deps ro libetpan`), excluding nothing, then:

    ./configure --without-sasl --without-curl --disable-iconv --disable-shared --without-gnutls && make
    # ASan (boundary/replay, gcc):
    CFLAGS="-g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer"
    # libFuzzer (clang with compiler-rt):
    CC=clang CFLAGS="-g -O1 -fsanitize=fuzzer-no-link,address,undefined -fno-sanitize=function,shift-base -fno-omit-frame-pointer"

`-fno-sanitize=function`: libetpan calls its parsers through cast function pointers by design.
`-fno-sanitize=shift-base`: `mail_mkgmtime()` counts time_t bits by shifting a signed value until it overflows
(input-independent; flagged on every INTERNALDATE). Without root, clang can come from
`apt-get download clang-21 libclang-cpp21 libllvm21 libclang-rt-21-dev libclang-common-21-dev llvm-21-linker-tools`
and `dpkg -x` into a directory, with small `clang`/`clang++` wrappers setting `LD_LIBRARY_PATH` (`LLVM=` that dir).

## Run

    ETP=<asan libetpan> OUT=<dir> ./build-host.sh            # -> boundary, replay
    (ulimit -s 1024; <dir>/liveimap-fuzz-host/boundary)      # 35+ cases, must print "0 failure(s)", no leaks
    ETP_FUZZ=<fuzz libetpan> LLVM=<clang dir> OUT=<dir> ./build-fuzz.sh
    ./make_seeds.py ../recordings <corpus>                    # server replies from lab recordings
    ASAN_OPTIONS=detect_leaks=0 <dir>/liveimap-fuzz/fuzz_imap <corpus> -max_len=65536 -timeout=10 \
        -rss_limit_mb=2048 -max_total_time=600 -fork=8 -ignore_crashes=1 -artifact_prefix=<artifacts>/
    <dir>/liveimap-fuzz-host/replay <artifacts>/crash-*      # symbolized ASan/UBSan report per input

Input format: byte 0 bit 0 = starts with a greeting (connect path); the rest is the server stream.
Leak detection is off while fuzzing (reports from the per-input writer thread did not reproduce on replay);
the boundary run checks leaks.

## Found so far (2026-10-11)

- libetpan `mailimap_section_parse()` NULL free on `BODY[x` (SIGSEGV): `imap-section-null-free.patch`.
- libetpan greeting leak on `* BYE`: `imap-greeting-bye-leak.patch`.
- Leak in our nesting-depth placeholder subtype: fixed in `imap-nesting-depth.patch`.
- App: COPYUID destination expansion and many-range sequence sets could allocate billions of entries; capped.
