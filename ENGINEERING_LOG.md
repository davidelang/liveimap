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

## 2026-09-30 - folder list execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/folder-list-20260930-1416-plan.md
- Work: replace the folder stub with one server level; INBOX.sent-mail stays under INBOX; namespace labels stay the server prefix
- First action per standard-plan-compliance-block.md; commits stay on master

## 2026-09-30 - folder list phase 1

- FolderListScreen keeps onOpenMailbox and lists one server level from FolderListModel
- loadLevel and toggleExpanded call namespaces and listLevel only; expansion is saved on the account
- INBOX.sent-mail stays a child of INBOX; other and shared labels stay the server prefix
- FolderTreeTest covers that tree

## 2026-09-30 - message index execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/message-index-20260930-1417-plan.md
- Work: replace the index stub with the folder window, IndexModel, and IndexWindowTest
- Arrival does not call sort; thread keeps server order; commands go through MailSession
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD b02e466 with builds tag

## 2026-09-30 - message index phase 1

- MessageIndexScreen keeps its signature and now shows the folder window, swipe, and multi-select
- IndexModel loadWindow and applyView: arrival uses ArrivalNewest or ArrivalOldest and does not call sort; thread keeps server order
- IndexWindowTest: 18 tests, 0 failures. Compact sets includePreview false. Delete adds \Deleted and does not copy
- Fake MailSession stays inside IndexWindowTest.kt. NavHost, MailSession, and settings files were not edited

## 2026-09-30 - reader and composer execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/reader-compose-20260930-1418-plan.md
- Work: replace the reader and composer stubs; add Rfc822 buildPlain and buildBounce; keep both screen signatures
- Compose sends text/plain; Bcc stays off the header block; bounce prepends Resent-* and keeps the original Message-ID
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 6e3a380 with builds tag

## 2026-09-30 - reader and composer phase 1

- MessageReaderScreen keeps its signature, selects the mailbox, and peeks the preferred part in 4096-byte steps
- HTML is a WebView with JavaScript off and network loads blocked; a missing part shows No text/plain part or No text/html part and does not fetch the raw message
- The first successful peek stores \Seen when markSeenOnOpen is set; attachment bytes are fetched only after a tap, 65536 wire bytes at a time, on a multiple of 4
- ComposeScreen keeps its signature, calls AddressBookPicker, and sends text/plain
- Bcc stays off the header block; bounce prepends Resent-* and keeps the original Message-ID; a failed SMTP send does not append
- NavHost, MailSession, the index, the folder screen, and settings files were not edited

## 2026-09-30 - address book reader execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/addressbook-reader-20260930-1419-plan.md
- Work: replace the address book stub; read the Alpine mailbox; do not write it and do not read Android contacts
- AddressBookPicker keeps its signature; ComposeScreen, NavHost, and MailSession stay unchanged
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 4592947 with builds tag

## 2026-09-30 - address book reader phase 1

- AddressBookPicker keeps its signature, selects the configured mailbox, and does not call append, storeFlags, or smtpSend
- An empty addressBookMailbox shows Address book mailbox is not set and does not select
- The first message must contain x-pine-addrbook; the book is the last message body with no MIME decoding
- parseAlpineBook keeps tab fields, space continuations, empty nicknames, and [plaintext] in the comment
- AlpineBookTest: 9 tests, 0 failures. A headerless body is rejected and a normal mail folder is not parsed
- Fake MailSession stays inside AlpineBookTest.kt. ComposeScreen, NavHost, and MailSession were not edited

## 2026-10-01 - add deploy script execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/add-deploy-script-20261001-0631-plan.md
- Work: add executable ./deploy that installs app-debug.apk and moves the deployed tag on success
- First action per standard-plan-compliance-block.md; commits stay on master

## 2026-10-01 - Cyrus 3 capability degradation execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/cyrus3-capability-degradation-20261001-0705-plan.md
- Work: keep the eight v1 names as a hard refusal; define one behavior for each v2 capability when it is absent
- A missing v2 capability does not close the connection. Do not call UTF8=ACCEPT. Do not invent mailbox names
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 54e8322 with builds tag

## 2026-10-01 - Cyrus 3 capability degradation phase 1

- capabilityGate still rejects a line missing any of NAMESPACE, UIDPLUS, LITERAL+, CHILDREN, UNSELECT, SORT, THREAD=REFERENCES, IDLE
- moveKind, listKind, resyncKind, searchKind, sortKind, previewKind, and fetchKind follow the degradation table. A missing v2 name does not reject the line
- FolderEntry and FolderRow carry specialUse, messages, and unseen, defaulting to null. loadLevel copies those three fields through
- No JNI in this phase

## 2026-10-01 - Cyrus 3 capability degradation phase 1 blocked

- Phase 1 commit 1e0655c is on master. ./build_app did not move the builds tag
- CapabilityGateTest passed: missingIdleIsRejected, cyrus22Connects, moonLineConnects
- testDebugUnitTest failed in DisconnectedSessionTest.openFailedTextIsNotConnected
- mailSession() constructs LibetpanMailSession, and that class loads libliveimap.so. The host unit test has no liveimap library, so the test throws UnsatisfiedLinkError
- That test file is not a Critical File. Phase 2 was not started

## 2026-10-01 - JNI watch callback crash fix execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/fix-jni-watch-callback-20261001-0732-plan.md
- Work: drop internal from onNativeWatch so the JVM name stays onNativeWatch; look up the seven-arg FolderEntry constructor and pass null for specialUse, messages, and unseen
- ensureJni returns false unless folderInit and onWatch are both non-null. Do not fill those three fields from the server
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 1e0655c with builds tag 54e8322

## 2026-10-01 - JNI watch callback crash fix phase 1

- onNativeWatch is a public instance method. The JVM name is onNativeWatch and the signature stays (IIJ[Ljava/lang/String;)V
- FolderEntry <init> is (Ljava/lang/String;Ljava/lang/String;ZCLjava/lang/String;Ljava/lang/Integer;Ljava/lang/Integer;)V
- nativeListLevel passes null for specialUse, messages, and unseen and does not fill them from the server
- ensureJni returns false unless folderInit and onWatch are both non-null

## 2026-10-01 - Continue Cyrus 3 degradation

- Start. Phase 1 makes openFailedTextIsNotConnected construct DisconnectedMailSession directly so the host JVM does not call mailSession() or load libliveimap.

## 2026-10-01 - Cyrus 3 command degradation

- Phase 2. Open enables QRESYNC or CONDSTORE from resyncKind and sends COMPRESS DEFLATE when that capability is advertised. A NO leaves the feature off. Extended LIST fills special-use, messages, and unseen. Plain LIST leaves those null. MOVE, copy-then-delete plus UID EXPUNGE, ESEARCH, ESORT, DISPLAY, PREVIEW, and BINARY.PEEK follow the same capability functions. The folder row shows specialUse, messages, and unseen only when they are non-null.

## 2026-10-01 - git describe version execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/git-describe-version-20261001-0822-plan.md
- Work: versionName from the VehicleExpenses git describe command; About shows that version; Support mail is david+liveimap@lang.hm
- versionCode stays 1. buildConfig is true. Configuration writes no file. AndroidManifest and SettingsScreen stay unchanged
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 2d60941

## 2026-10-01 - settings sections and theme execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/settings-sections-and-theme-20261001-1042-plan.md
- Work: split Settings into Server, Account, and Display; friendly name and theme after bounceFcc; older blobs missing those keys still decode; password stays out of encode and sits under Username; choices are one pulldown; email defaults only when Username loses focus and email is blank; MainActivity applies Dark, Light, or Follow system
- Sent, postponed, and address book stay text fields. No Choose buttons
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD ff91d07

## 2026-10-01 - settings sections and theme phase 1

- AccountSettings appends friendlyName and theme after bounceFcc. A blob missing either or both decodes; any other missing key still throws. Password stays out of encode
- looksLikeEmail accepts dlang@lang.hm and rejects dlang and a@b. emailDefaultedFromUsername fills a blank email from an email-shaped username
- SettingsScreen sections are Server, Account, and Display. Password sits under Username on the keystore path. ChoiceField is one ExposedDropdownMenuBox. Email changes only when Username loses focus and email is blank
- MainActivity uses darkColorScheme, lightColorScheme, or isSystemInDarkTheme from the stored theme, and FollowSystem until that load returns
- Sent, postponed, and address book stay text fields. No Choose buttons

## 2026-10-01 - settings folder picker execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/settings-folder-picker-20261001-1043-plan.md
- Work: Choose beside sent, postponed, address book, and both swipe move-mailbox fields. The dialog opens the stored account, lists FolderListModel rows, writes the tapped row mailbox, and shows failure text with no names
- Expander only expands. Dismiss leaves the field unchanged. No second folder lister and no invented mailbox names
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD da3ece2

## 2026-10-01 - settings folder picker phase 1

- SettingsScreen puts Choose beside Sent mailbox, Postponed mailbox, Address book mailbox, and both swipe Move mailbox fields. Typed text still saves
- The dialog calls mailSession().open with the stored account. Connected uses FolderListModel.loadLevel and toggleExpanded. Tapping the row name writes that row's mailbox, saves, and closes. The expander only expands
- OpenResult.Failed shows text. OpenResult.Rejected shows capabilities. MailFailure shows text. Those cases list no mailboxes. Dismiss leaves the field unchanged

## 2026-10-01 - git describe version navigation execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/git-describe-version-20261001-0822-plan.md
- Work: drop the title bar; always show Folders, Settings, and About; highlight the current one with secondaryContainer; Index, Reader, and Compose highlight Folders and Folders from those routes opens the folder list
- versionName, AboutScreen, and the project-facts version bullet already match the plan and stay unchanged
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 31b9194

## 2026-10-01 - show connect target execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/show-connect-target-20261001-1125-plan.md
- Work: IMAP and SMTP connect errors name the host, port, numeric address, and strerror or timed out; lookup uses gai_strerror; login names the user and the server reply
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 3a5efe7

## 2026-10-01 - folder back search sort execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/folder-back-search-sort-20261001-1208-plan.md
- Work: back from the message list to Folders and from a message to that mailbox; search icon reveals the field; sort icon plus a short label opens the menu; Expunge stays on the row; each folder level sorts by leaf with INBOX first
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 034f1c7

## 2026-10-01 - folder back search sort phase 1

- MessageIndexScreen back returns to Folders. The mailbox name is beside it. Search is an icon that shows the field; clearing it to empty calls applySearch("") and hides it. Sort is an icon plus Arrival, Date, From, Subject, To, Cc, Size, Thread, or Ordered and opens one menu of menuKeys plus newest/oldest. Choosing one calls applyView and clears the search text. Expunge stays on the row
- MessageReaderScreen back is the first action and returns to MailRoute.Index for that mailbox. Folders, Settings, and About stay. Compose still uses its done path
- FolderListModel orders each level by case-insensitive leaf, then mailbox. INBOX stays the first root. Other-namespace roots stay after personal folders, and shared-namespace roots stay after those
- FolderTreeTest: zeta, alpha, INBOX shows INBOX, alpha, zeta; children b, a show a, b
- material-icons-core is next to the Material3 dependency

## 2026-10-01 - settings test server execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/settings-test-server-20261001-1218-plan.md
- Work: Test server after the address book mailbox logs in with the saved account and runs read-only commands for advertised capabilities. Each line is OK, FAIL, or SKIP. A failed command does not stop later checks. The password is not shown. A divider separates that block from Display
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 39b7fcd

## 2026-10-01 - settings test server phase 1

- ServerProbe.probeServer opens the saved account. Failed or Rejected is one FAIL login line and returns. Connected runs the read-only checks. MailFailure is caught per check. The password is not read or printed
- A LIST failure is also reported for advertised CHILDREN, LIST-EXTENDED, LIST-STATUS, and SPECIAL-USE. SELECT INBOX failure skips the selected checks. sort is one From newest call. searchText("x") reports the UID count. IDLE calls stopWatch in finally. CONDSTORE, QRESYNC, and COMPRESS=DEFLATE skip because open sends them. Other leftover capabilities skip as no read-only command
- SettingsScreen puts Test server after Address book mailbox. The monospace report is under the button. HorizontalDivider with 8.dp vertical padding sits before Display. The label is Testing… and the button is disabled while the screen scope runs mailSession, probeServer, and close in finally
- ServerProbeTest uses a fake MailSession and does not call mailSession. A list syntax failure reports FAIL LIST and FAIL LIST-EXTENDED. A failed open is one FAIL login line and does not call listLevel
