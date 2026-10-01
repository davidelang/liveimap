# ENGINEERING_LOG


## 2026-08-21 - clone-orchestration-project execution start

- Approved plan: sandbox/plans/clone-orchestration-project-20260821-1819-plan.md
- Work: add ./clone-orchestration-project to clone this example into git_home/<name> with orchestration + master worktrees
- First action per STANDARD BLOCK; host has no ./build_app (phase gate: bash -n + forensic read)

## 2026-08-21 - clone-orchestration-project landed (staged)

- Added ./clone-orchestration-project (discovery, clone --no-hardlinks as primary, orchestration+master worktrees, fix-perms --skip-sudoers)
- README.md + project-facts.md document the helper
- Verified: bash -n; --dry-run SomeName from this tree, git_home, and --source (same source/dest)
- git commit blocked: ai-orchestrator has no user.name/email (changes remain staged)

## 2026-08-21 - clone-seed-worktree-config execution start

- Approved plan: sandbox/plans/clone-seed-worktree-config-20260821-1840-plan.md
- Work: seed dest+master project.config via ve_seed_worktree_project_config; dest-root DAC 2775; --repair; repair torque-ford-gas-tracking
- First action per STANDARD BLOCK; host has no ./build_app

## 2026-08-21 - clone-seed-worktree-config script landed; live --repair blocked

- clone-orchestration-project now seeds dest+master project.config via ve_seed_worktree_project_config, dest-root chown 2775, and --repair
- Live --repair of torque-ford-gas-tracking failed in this session: dest is 770 dlang:dlang and sudo is blocked (Landlock no-new-privileges)
- Human: run as dlang ./clone-orchestration-project --repair /home/dlang/git/torque-ford-gas-tracking

## 2026-08-21 - clone-landlock-exec-dac execution start

- Approved plan: sandbox/plans/clone-landlock-exec-dac-20260821-1901-plan.md
- Work: fix-perms lists agent-landlock+env; clone do_fix_perms uses this tree fix-perms with explicit DEST and DEST/master; force landlock 775
- First action per STANDARD BLOCK; host has no ./build_app

## 2026-08-21 - clone-landlock-exec-dac landed

- fix-perms: agent-landlock and env in executable-scripts list (2775/775 o+x)
- clone-orchestration-project: this-tree fix-perms --skip-sudoers DEST and DEST/master; force landlock 775
- Live dest not repaired from this session (sudo/Landlock nnp). Human: --repair torque-ford-gas-tracking as dlang

## 2026-09-30 - app shell and contracts execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/app-shell-and-contracts-20260930-1403-plan.md
- Work: Android app shell, ./build_app gateway, frozen Kotlin contracts, persisted settings, mail screen stubs
- First action per STANDARD BLOCK; commits stay on master

## 2026-09-30 - app shell phase 1 blocked before compile

- ./build_app committed phase 1 (4ca23cd) then refused uncommitted tracked dirt before Gradle
- Dirt left unstaged: AGENT_CONTEXT.md, project-facts.md, sandbox/research/plan-style-guide.md
- No APK and no builds tag. Phases 2 and 3 not started. Plan status BLOCKED — needs replan

## 2026-09-30 - rewrite-untrack-sandbox blocked before rewrite

- Plan: sandbox/plans/rewrite-untrack-sandbox-20260930-1534-plan.md
- Status set to BLOCKED — needs replan. No stash, no backup ref, no mirror, no filter-repo, no reset, no push.
- Phase 2 `git log --all --full-history -- sandbox/` and phase 3 `git rev-list --all --objects` cannot succeed while `origin/master` stays `a15f423077abdf0f9103263e425c0b73db01adaf`. That commit adds `sandbox/research/plan-style-guide.md`, and `--all` walks `refs/remotes/origin/master`.

## 2026-09-30 - finish app shell contracts execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/finish-app-shell-contracts-20260930-1723-plan.md
- Work: finish settings persistence, the MailSession contract, and stub screens on efa08fc
- First action per standard-plan-compliance-block.md; commits stay on master

## 2026-09-30 - settings form password field compile fix

- SettingsScreen passed a local function after a named argument; kotlinc rejected it as Unit
- Password field now uses a trailing lambda that calls persistPassword
- Rebuild of the settings phase after 1a8c886

## 2026-09-30 - finish app shell contracts blocked on scaffold opt-in

- compileDebugKotlin fails only at app/src/main/kotlin/org/dlang/liveimap/ui/LiveImapScaffold.kt:25
- TopAppBar is ExperimentalMaterial3Api and that file has no OptIn
- The file is already present and is not in this plan's Critical Files, so it was not edited
- No builds tag. Phase 2 not started. Settings commits 1a8c886 and 3f38f5f are on master

## 2026-09-30 - scaffold topappbar opt-in execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/scaffold-topappbar-optin-20260930-1801-plan.md
- Work: add ExperimentalMaterial3Api opt-in on LiveImapScaffold so the settings screen compiles
- Settings files from 1a8c886 and 3f38f5f stay unchanged
- Kept the existing scaffold block note in ENGINEERING_LOG.md

## 2026-09-30 - mail session contract execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/mail-session-contract-20260930-1801-plan.md
- Work: add frozen MailSession types and DisconnectedMailSession; no screens, network, or native code
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 050b9be with builds tag

## 2026-09-30 - stub mail screens execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/shell-stub-screens-20260930-1801-plan.md
- Work: add FlagMarks, five stub screens, and NavHost routes
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 1393021 with builds tag

## 2026-09-30 - Pin LibEtPan and libfastjson start

- Plan: sandbox/plans/pin-libetpan-libfastjson-20260930-1414-plan.md
- Status set to APPROVED. Phase 1 writes the pins, build scripts, and Android patch, then fetch-deps --no-bwrap so both src trees sit on the pinned shas. No app/ or ./build_app edits. Upstream checkouts are not committed.

## 2026-09-30 - Pin phase 1: pins and Android patch

- Wrote third_party/libfastjson and third_party/libetpan libpin.toml files, both build scripts, and patches/android-full-features.patch.
- third_party/fetch-deps --no-bwrap libfastjson checked out c2329f89006600703711271c6c39fe5181286264.
- third_party/fetch-deps --no-bwrap libetpan checked out 8c9d5e06e49feb4d4d834bd01cefe7ed77acd899 and applied the patch. Android.mk lists unselect.c. src trees are not committed.

## 2026-09-30 - Pin phase 2: Android static archives

- third_party/fetch-deps --no-bwrap build libfastjson and libetpan produced arm64-v8a and x86_64 archives.
- file reports a current ar archive for each .a. nm on both libetpan.a files lists mailimap_unselect, a mailjmap_ symbol, and an fjson_ symbol. None of the archives is LIBETPAN_STUB.
- Upstream /home/dlang/git/libetpan and /home/dlang/git/libfastjson were not committed. src trees are not in this commit.

## 2026-09-30 - IMAP SMTP engine execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/imap-smtp-engine-20260930-1415-plan.md
- Work: link LibEtPan into libliveimap.so, then LibetpanMailSession behind mailSession()
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD d3a392c with builds tag. Both ABI archive sets are present. No socket parser and no stand-in library.

## 2026-09-30 - IMAP SMTP engine phase 1

- Added app/src/main/cpp/CMakeLists.txt and liveimap_jni.cpp. CMake links libetpan.a, libsasl2.a, libssl.a, libcrypto.a, libfastjson.a, libiconv.a, then libz and libdl from third_party/libetpan/artifact for the ABI.
- app/build.gradle.kts gains ndk abiFilters arm64-v8a and x86_64 and externalNativeBuild cmake 3.22.1. Dependencies are unchanged.
- liveimap_jni.cpp exports Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeOpen and the other JNI entry points. nativeUnselect calls mailimap_unselect. Connect is mailimap_socket_connect.
- Host preflight linked both ABIs. llvm-nm shows mailimap_unselect and nativeOpen on each shared library.

## 2026-09-30 - IMAP SMTP engine phase 2

- LibetpanMailSession implements MailSession. open reads the keystore password and capabilityGate. A missing required token, including IDLE, returns OpenResult.Rejected and closes without LIST.
- mailSession() returns LibetpanMailSession. DisconnectedMailSession.kt stays in the tree.
- CapabilityGateTest uses a fake capability line that omits IDLE.
- System.loadLibrary("liveimap") runs from the LibetpanMailSession companion init.
