# project-facts.md — liveimap

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Repository: `@@ORCH_ROOT@@/master`, branch `master`. `@@ORCH_ROOT@@` is not a git checkout.
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)
- `./build_app` compiles and moves `builds`. `./deploy` installs `app/build/outputs/apk/debug/app-debug.apk` and moves `deployed`. Agents do not deploy.
- Package `org.@@PRIMARY_USER@@.liveimap`.
- App versionName comes from git describe (annotated tag v0.1 on master; <branch>-start when that tag exists). versionCode stays 1. About shows that version. Support mail is david+liveimap@lang.hm.

## Build and commit
- Build: `./build_app`
- Commit: `./build_app "<message>"|@<msg_file> <paths...>` (builds, then commits exactly the named paths and tags builds)
