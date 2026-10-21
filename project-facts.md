# project-facts.md — liveimap

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Repository: `@@ORCH_ROOT@@/master`, branch `master`. `@@ORCH_ROOT@@` is not a git checkout.
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)
- `./build_app` compiles and moves `builds`. `./deploy` installs the per-ABI APK (the APKs the last `./build_app` recorded from Gradle's output-metadata.json; `--test`: the debug x86_64 app plus its androidTest APK) and moves `deployed`. Agents do not deploy.
- Package `com.davidlang.liveimap`.
- App versionName comes from git describe (annotated tag v0.1 on master; <branch>-start when that tag exists). versionCode stays 1. About shows that version. Support mail is david+liveimap@lang.hm.
- The launcher icon is `@mipmap/ic_launcher` (adaptive, minSdk 26, no PNG mipmaps); the Play 512 is `docs/store/icon.png`; the generator is `docs/store/gen.py` and its `OUT` is `/workspace/iconbuild/icon`.

## Build and commit
- **Standard build:** debug
- **Test build:** instrumentation:x86_64Debug
- Build: `./build_app --standard --test` (never `--play`: it needs David at a terminal)
- Commit: `./build_app --standard --test "<message>"|@<msg_file> <paths...>` (builds, then commits exactly the named paths and tags builds)

## Review after implement
- Record HEAD before the executor starts. Each phase: `git diff` that phase's paths against that phase's observable, then commit and build green before the next phase. Before handoff, re-read the plan once.
- A plan line `Review: diff` starts one reviewer. Prompt is the plan path and `<recorded>..HEAD`. Diff that range only. Check the plan's Critical Files and Acceptance lines. Reply in chat. Do not diff `master..<branch>`.
- A deletion, a doc edit, or a pin bump omits `Review: diff`.
- A reviewer `STATUS: FAIL` names a bug: the executor fixes that bug, then the reviewer runs again on the new range.
