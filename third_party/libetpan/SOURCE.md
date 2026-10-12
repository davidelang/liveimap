# libetpan pin

- **Upstream:** fork `davidelang/libetpan` at the `git_sha` in `libpin.toml`. Produces Android static archives
  (arm64-v8a, x86_64): libetpan, OpenSSL 3, Cyrus SASL 2.1.28, libfastjson (separate pin), iconv.
- **Profile:** patch + options. `patches/` holds code diffs only (candidates for upstream PRs); compile flags live
  in `scripts/build-flags.sh`, sourced by `build`.

## Patches (code, upstreamable)

| Patch | What |
|---|---|
| `imap-literal8.patch` | RFC 3516 literal8 (`~{n}`) in `mailimap_literal_parse_progress` (Stalwart BINARY fetch), with a unit test from a recorded Stalwart response. |
| `imap-greeting-bye-leak.patch` | `parse_greeting()` returned on a `* BYE` greeting (and on its out-of-memory paths) without freeing the parsed greeting, leaking it on every refused connection. Found by the host ASan boundary run (`tools/test-servers/fuzz`). |
| `imap-section-null-free.patch` | `mailimap_section_parse()` freed a NULL section spec when `[` was not followed by a section (`BODY[x`): a server-triggerable SIGSEGV. Found by the libFuzzer run (`tools/test-servers/fuzz`). Upstream candidate. |
| `imap-nesting-depth.patch` | Deep-nesting safety for the BODYSTRUCTURE parser. A sender controls the MIME tree, and `mailimap_body_parse` recursed once per level, so a few thousand levels overflowed the stack. Parts deeper than `MAILIMAP_BODY_MAX_DEPTH` (128) are skipped iteratively (quoted strings and literals honoured) and returned as one `application/x-libetpan-too-deep` part, so the rest of the FETCH (UID, other messages) still parses and section numbers stay right. Nested body-extension lists (server data) deeper than `MAILIMAP_BODY_EXTENSION_MAX_DEPTH` (32) are a parse error. Adds `body_depth`/`body_extension_depth` at the end of `struct mailimap_parser_context` (allocated only by the library). Unit test `unittest/imap/body_depth_test.c` (10, 127, 128, 5000 and 200000 levels, parens in quoted strings/literals, 100000-deep extension list). A call-graph scan of `src/low-level/imap/*.c` finds only two recursive parser cycles (body ↔ 1part/mpart/msg, and body-extension ↔ body-ext-list); both are bounded by this patch. **Upstream candidate; pending Grok Build review; to move to the libpin repo.** Applies after `imap-literal8.patch` (alphabetical order). |
| `stream-line-length.patch` | Caps one line read by `mailstream_read_line*()`: `struct mailstream` gets `max_line_length` (default `MAILSTREAM_DEFAULT_MAX_LINE_LENGTH`, 64 MiB; 0 = no limit) with `mailstream_set/get_max_line_length()`, and a longer line fails the read instead of growing the buffer until memory runs out (hostile server, or MITM on a plaintext account). Literal sizes were already capped in the fork itself (commit 7bb634c, `MAILIMAP_LITERAL_SIZE_MAX` 64 MiB, parse error), so this patch only adds the line cap. Unit test `unittest/imap/line_length_test.c`. Applies after `imap-nesting-depth.patch` (its unittest hunks sit next to that patch's lines). **Upstream candidate; pending Grok Build review; to move to the libpin repo.** |
| `android-full-features.patch` | Android build-script changes (ABIs arm64-v8a/x86_64, OpenSSL 3.5.9, autogen/configure with libfastjson, `set -eu`). Mostly build configuration, not library code: a candidate to move into `scripts/` (see below). |

## Build flags (`scripts/build-flags.sh`)

- `-DUSE_SASL=1`: host `configure` cannot see the Android Cyrus SASL headers, so without it SASL AUTHENTICATE
  and SMTP AUTH are stubs ("SASL ... failed locally; client mechanisms: none").
- `-DHAVE_SHA512=1`: Cyrus SASL builds SCRAM-SHA-256/-512 and their -PLUS forms only with it.

## Gotchas

- Host check of the unit tests: the checked-in `unittest/Makefile` links `-lcurl -lsasl2`; on a host without them,
  build a scratch copy (`./configure --without-sasl --without-curl --disable-iconv --disable-shared && make`) and link
  `unittest/imap/*.c` against `src/.libs/libetpan.a -lz -lssl -lcrypto -lpthread`.

- ndk-build ignores `APP_CFLAGS` from the environment (no Application.mk). `build` passes it through `MAKEFLAGS`
  (spaces escaped), which make treats like a command-line variable, so every nested ndk-build sees it.
- The upstream build scripts cache archives by version number (`cyrus-sasl-android-4.zip`, ...). `build` keeps a
  stamp of the flags in `src/build-android/.liveimap-build-options` and deletes the cached libetpan and Cyrus SASL
  archives when the flags change; no version bump is needed.
- Untracked files created by a patch (new test files) survive `fetch-deps` re-materialize and make the patch fail
  ("already exists"); delete them from `src/` first.
- Check after a build: `nm -A artifact/<abi>/libetpan.a | grep 'U sasl_client_start'` lists mailimap.o and
  mailsmtp.o, and `strings artifact/<abi>/libsasl2.a | grep -x SCRAM-SHA-256` matches.
