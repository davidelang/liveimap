# project-facts.md — liveimap

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `sandbox/` (absolute: /home/dlang/git/liveimap/sandbox/)
- Repository: `/home/dlang/git/liveimap/master`, branch `master`. `/home/dlang/git/liveimap` is not a git checkout.
- git_home: /home/dlang/git
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)
- `./build_app` compiles and moves `builds`. `./deploy` installs `app/build/outputs/apk/debug/app-debug.apk` and moves `deployed`. Agents do not deploy.
- Package `org.dlang.liveimap`.
- IMAP under test: `moon.lang.hm` Cyrus 3.12.4, port 143, plaintext, no `STARTTLS`. Cyrus 2.2 is the less capable server. Connection still requires `NAMESPACE`, `UIDPLUS`, `LITERAL+`, `CHILDREN`, `UNSELECT`, `SORT`, `THREAD=REFERENCES`, and `IDLE`. A missing newer capability degrades. It does not close the connection.
- App versionName comes from git describe (annotated tag v0.1 on master; <branch>-start when that tag exists). versionCode stays 1. About shows that version. Support mail is david+liveimap@lang.hm.
