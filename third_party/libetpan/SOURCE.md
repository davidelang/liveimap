# libetpan pin

- **Upstream:** fork `davidelang/libetpan` at the `git_sha` in `libpin.toml`. Produces Android static archives
  (arm64-v8a, x86_64): libetpan, OpenSSL 3, Cyrus SASL 2.1.28, libfastjson (separate pin), iconv.
- **Profile:** patch + options. `patches/` holds code diffs only (candidates for upstream PRs); compile flags live
  in `scripts/build-flags.sh`, sourced by `build`.

## Patches (code, upstreamable)

| Patch | What |
|---|---|
| `imap-literal8.patch` | RFC 3516 literal8 (`~{n}`) in `mailimap_literal_parse_progress` (Stalwart BINARY fetch), with a unit test from a recorded Stalwart response. |
| `android-full-features.patch` | Android build-script changes (ABIs arm64-v8a/x86_64, OpenSSL 3.5.9, autogen/configure with libfastjson, `set -eu`). Mostly build configuration, not library code: a candidate to move into `scripts/` (see below). |

## Build flags (`scripts/build-flags.sh`)

- `-DUSE_SASL=1`: host `configure` cannot see the Android Cyrus SASL headers, so without it SASL AUTHENTICATE
  and SMTP AUTH are stubs ("SASL ... failed locally; client mechanisms: none").
- `-DHAVE_SHA512=1`: Cyrus SASL builds SCRAM-SHA-256/-512 and their -PLUS forms only with it.

## Gotchas

- ndk-build ignores `APP_CFLAGS` from the environment (no Application.mk). `build` passes it through `MAKEFLAGS`
  (spaces escaped), which make treats like a command-line variable, so every nested ndk-build sees it.
- The upstream build scripts cache archives by version number (`cyrus-sasl-android-4.zip`, ...). `build` keeps a
  stamp of the flags in `src/build-android/.liveimap-build-options` and deletes the cached libetpan and Cyrus SASL
  archives when the flags change; no version bump is needed.
- Untracked files created by a patch (new test files) survive `fetch-deps` re-materialize and make the patch fail
  ("already exists"); delete them from `src/` first.
- Check after a build: `nm -A artifact/<abi>/libetpan.a | grep 'U sasl_client_start'` lists mailimap.o and
  mailsmtp.o, and `strings artifact/<abi>/libsasl2.a | grep -x SCRAM-SHA-256` matches.
