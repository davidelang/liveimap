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
| **Version** | **UNSELECTED.** `git_sha` is the 40-zero placeholder. Do not run `fetch-deps ro` or `fetch-deps build` for this pin until a commit is locked. |

This pin is omitted from the default fetch-deps name set (this checkout skips
it by name, same as `example`; `default = false` is also set for a newer
fetch-deps). Name it explicitly when the version is locked.

## Lock and emerge (later, once fakeimap has settled)

1. Choose the commit, for example `git -C /home/dlang/git/fakeimap/master rev-parse HEAD`.
2. Replace `git_sha` and `git_describe` in `libpin.toml` (the 40-zero
   placeholder must not remain). Leave the two artifact `sha256` values as 64
   zeros for the first `--record` run.
3. `./third_party/fetch-deps ro fakeimap`
4. `./third_party/fetch-deps build --record fakeimap` — this fills the artifact
   `sha256` values. The pin `./build` refuses to run while `git_sha` is still
   the placeholder.
5. Commit `libpin.toml`, `SOURCE.md`, `artifact/fakeimap`, and
   `artifact/cyrusprobe` with `./build_app --standard --test` (name those
   files). Do not push.

Callers need the emerged binary at `third_party/fakeimap/artifact/fakeimap`
and the personality files at `third_party/fakeimap/src/personalities/` (from
step 3). There is no fallback to a host checkout or a `PATH` binary.

The upstream `Makefile` also runs `go test` and may use `/tmp` for `GOCACHE`.
The pin `./build` does not: it uses `src/build` for `GOCACHE`/`GOTMPDIR` and
does not run the library tests.
