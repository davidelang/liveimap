# project-facts.md — liveimap

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Repository: `@@ORCH_ROOT@@/master`, branch `master`. `@@ORCH_ROOT@@` is not a git checkout.
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock` (Grok and cursor). Planner writes `work-history/` (root `TODO.md` and `ENGINEERING_LOG.md` are symlinks).
- Groups: source `./env` (refresh-shell setuid)
- `./build_app` compiles and moves `builds`. `./deploy` installs the per-ABI APK (the APKs the last `./build_app` recorded from Gradle's output-metadata.json; `--test`: the debug x86_64 app plus its androidTest APK) and moves `deployed`. Agents do not deploy.
- Package `com.davidlang.liveimap`.
- App versionName comes from git describe (annotated tag v0.1 on master; <branch>-start when that tag exists). versionCode stays 1. About shows that version. Support mail is david+liveimap@lang.hm.
- The launcher icon is `@mipmap/ic_launcher` (adaptive, minSdk 26, no PNG mipmaps); the Play 512 is `docs/store/icon.png`; the generator is `docs/store/gen.py` and its `OUT` is `/workspace/iconbuild/icon`.

## Build and commit
- **Standard build:** debug
- **Test build:** instrumentation:x86_64Debug
- Build: `./build_app --standard --test`. Agents never pass `--play`: only David runs the Play build, by hand.
- Play-only code (`app/src/play`) compiles without signing: `./build_app --standard --test -- compilePlayReleaseKotlin`.
- Commit: `./build_app --standard --test "<message>"|@<msg_file> <paths...>` (builds, then commits exactly the named paths and tags builds)
- Translation rules: `docs/facts/translations.md`. A normal build packages the committed catalog and does not call a translator. Call sites use `S` or the plural helper next to it. A settings-tree sentence stays in `settings-tree.json`. The build writes en-US `strings.xml` from those definitions.
- A missing group title is created as `settings_group_<id>_title` with the JSON title. An existing `settings_group_<id>_title` whose text is not that title still fails. `--check` names the missing resource and writes nothing.
- Settings-tree check (the build's display and structure checks, no writes and no translation): `python3 app/gen-settings-tree.py --check`.
- A display string in `app/src/main/assets/settings-tree.json` (`title`, `label`, `description`, each `choiceTitles` value, and summary `text`, `plural`, and `titles`) is the en-US sentence. The Android resource name stays the name that sentence already has. One sentence stays one resource until its English texts differ; a shared sentence that changes on one use gets a new name, and an `<locale>.override.json` entry stays on the old name.

## Review after implement
- Record HEAD before the executor starts. Phases are internal steps: `git diff` each phase's paths against its observable; no per-phase commit. Before handoff, re-read the plan once.
- A plan lands as one commit at the end, once `./build_app --standard --test` is green, every test passes and the plan's checks hold. Landing never waits on a lab run, the Play build or a manual check.
- Lab suites (`tools/test-servers/regress`) run after a plan lands, as the reviewer's regression run, never a coder land gate. A lab failure is fixed in a new plan.
- A plan line `Review: diff` starts one reviewer. Prompt is the plan path and `<recorded>..HEAD`. Diff that range only. Check the plan's Critical Files and Acceptance lines. Reply in chat. Do not diff `master..<branch>`.
- A plan that only deletes, edits docs or bumps a pin omits `Review: diff`. Any other plan keeps it, and the reviewer diffs the whole range, deletion phases included.
- A reviewer `STATUS: FAIL` names a bug: the executor fixes that bug, then the reviewer runs again on the new range.
