# fakeimap

A local IMAP (plus SMTP submission, HTTP JMAP, and ManageSieve) server for
tests. Run `artifact/fakeimap <config.json>`; it writes one JSON object of
bound addresses to stdout and flushes it. Config and the control-socket API
are in the upstream `docs/guide.md`.

| | |
|--|--|
| **Upstream** | `davidelang/fakeimap` (`git_https` / `git_ssh` in `libpin.toml`) |
| **Host clone** | `/home/dlang/git/fakeimap` (worktree `master`) |
| **Profile** | Source plus one build step: `go build` of `fakeimap` and `cyrusprobe` (static, stdlib only, reproducible). Personalities and datasets stay in materialized `src/` (not artifact rows). |
| **Version** | Locked to `8cb6482580da3b78465fbc7ac442d979501134b5` (`git_describe` `8cb6482`). |

This pin is in the default fetch-deps name set (`default = true`). While
`git_sha` was UNSELECTED it was omitted (hardcoded skip, and `default =
false`) so a bare `fetch-deps ro` would not fail. This checkout's
`fetch-deps` still does not honor `default = false`; including it is the
name-list change. Callers need the emerged binary, so it is emerged by
default.

To re-emerge after a later lock:

1. Replace `git_sha` and `git_describe` in `libpin.toml`. Leave the two
   artifact `sha256` values as 64 zeros for the first `--record` run.
2. `./third_party/fetch-deps ro fakeimap`
3. `./third_party/fetch-deps build --record fakeimap` — this fills the
   artifact `sha256` values. The pin `./build` refuses to run only if
   `git_sha` is the 40-zero placeholder. This checkout's `get-artifacts`
   does not yet implement `--record`; if `fetch-deps build --record` stops
   there, copy `src/fakeimap` and `src/cyrusprobe` to `artifact/`, write
   their sha256 values into `libpin.toml`, and run `./third_party/get-artifacts
   fakeimap` to verify.
4. Commit `libpin.toml`, `SOURCE.md`, `artifact/fakeimap`, and
   `artifact/cyrusprobe` with `./build_app --standard --test` (name those
   files). Do not push.

Callers need the emerged binary at `third_party/fakeimap/artifact/fakeimap`
and the personality files at `third_party/fakeimap/src/personalities/` (from
step 2). There is no fallback to a host checkout or a `PATH` binary.

The upstream `Makefile` also runs `go test` and may use `/tmp` for `GOCACHE`.
The pin `./build` does not: it uses `src/build` for `GOCACHE`/`GOTMPDIR` and
does not run the library tests.
