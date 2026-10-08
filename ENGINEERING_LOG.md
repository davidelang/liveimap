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

## 2026-10-01 - Cyrus LIST return spacing execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/fix-cyrus-list-return-spacing-20261001-1217-plan.md
- Work: tight parentheses on extended LIST, ESEARCH, and ESORT so Cyrus getword does not see an empty word; plain LIST stays mailimap_list
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 17d76e5

## 2026-10-01 - Cyrus LIST return spacing phase 1

- listMailboxes extended sends RETURN (CHILDREN SPECIAL-USE), or RETURN (CHILDREN SPECIAL-USE STATUS (MESSAGES UNSEEN)) when status is on. The first word inside each parenthesis has no lead space. Each closing parenthesis has none. RETURN and STATUS still have a space before the opening parenthesis
- sendUidEsearch sends RETURN (ALL). sendUidSortChoice sends (KEY) or (REVERSE KEY), and RETURN (ALL) when esort is on
- Plain listMailboxes still calls mailimap_list. sendWord, mailimap_mailbox_send(""), and mailimap_list_mailbox_send are unchanged

## 2026-10-01 - swipe delete JNI ref execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/fix-swipe-delete-jni-ref-20261001-1226-plan.md
- Work: end JChars before DeleteLocalRef in the flag and SMTP recipient loops; LIST reference is the mailbox context and the pattern is %; thread, sort, and ENABLE send fixed literals; only the five system flags are stored
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 66aa06a

## 2026-10-01 - swipe delete JNI ref phase 1

- flagListFromArray and nativeSmtp destroy JChars before DeleteLocalRef. flagFromName returns only the five system-flag constructors. Any other name fails in nativeStoreFlags before mailimap_uid_store, and the failure names those five flags. \Deleted still stores
- LIST reference is the parent plus its delimiter, the parent, or the namespace prefix. The pattern is %. Extended LIST sends the reference with mailimap_mailbox_send and % with mailimap_list_mailbox_send. RETURN, CHILDREN, SPECIAL-USE, and STATUS leadSpace calls are unchanged
- THREAD sends the literal REFERENCES or ORDEREDSUBJECT and throws before a command otherwise. SORT sends DATE, FROM, SUBJECT, TO, CC, SIZE, or DISPLAY and does not pass keyName to sendWord. ENABLE stores QRESYNC or CONDSTORE and returns without mailimap_enable otherwise

## 2026-10-01 - fix compile so builds can move execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/fix-compile-so-builds-can-move-20261001-1334-plan.md
- Work: orderedLevel uses compareBy with a case-insensitive leaf comparator then mailbox; the index sort control uses Icons.AutoMirrored.Filled.List
- First action per standard-plan-compliance-block.md; commits stay on master

## 2026-10-01 - index marks and compose close execution start

- Approved plan: sandbox/plans/index-marks-and-compose-close-20261001-1352-plan.md
- Work: drop the flag-name line; seen and deleted text appearance; left mark circles; to-me and attachment on the index row; Close leaves compose; store the literal $Forwarded after a forward send

## 2026-10-01 - move tabs below status bar execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/move-tabs-below-status-bar-20261001-1327-plan.md
- Work: contentWindowInsets is statusBars union navigationBars union displayCutout; enableEdgeToEdge stays; no TopAppBar and no second tab-row padding
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 30e3aca

## 2026-10-01 - unread counts and qresync select execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/unread-counts-and-qresync-select-20261001-1707-plan.md
- Work: showUnreadCounts defaults off and gates LIST-STATUS; a repeat QRESYNC select sends the modifier list
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 690b8ef

## 2026-10-01 - folder message count columns execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/folder-message-count-columns-20261001-2054-plan.md
- Work: LIST-STATUS returns MESSAGES when unread counts are off; folder rows reserve end-aligned count columns and a faint divider
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 94870b1

## 2026-10-01 - index date sequence dots execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/index-date-sequence-dots-20261001-2054-plan.md
- Work: date format setting and pure index date formatter; sequence slot and reserved mark gutter; reader shows Message N
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 5207104

## 2026-10-01 - reader delete move spam execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/reader-delete-move-spam-20261001-2054-plan.md
- Work: spam mailbox stays empty until chosen; reader Delete stores \Deleted and stays open; Move and Spam use copyThenDelete, and Spam is hidden until that mailbox is set
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 4c2e510

## 2026-10-01 - Message view pulldown

- Started message-body-view-20261001-2054. BodyView replaces Prefer HTML. The reader pulldown does not save settings. HEADER and raw use BODY.PEEK. htmlAsText quotes HTML only for Plain or HTML and Plain or text.

## 2026-10-01 - thread newest first execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/thread-newest-first-20261001-2054-plan.md
- Work: pass newestFirst into fetchThread; order thread roots by the largest uid in each thread; nested message order stays as returned
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD d920479

## 2026-10-01 - index filter narrow widen execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/index-filter-narrow-widen-20261001-2054-plan.md
- Work: whitelist IMAP searchCriterion, index filter stack (narrow, widen, all), filter control between Search and Sort
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD b0ac8a8

## 2026-10-01 - swipe left and right fields execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/swipe-left-right-fields-20261001-2054-plan.md
- Work: label the trailing and leading editors Swipe left and Swipe right from LocalLayoutDirection; show the move mailbox only for Move and the flag only for SetFlag or ClearFlag
- Stored keys stay swipeTrailing and swipeLeading. Defaults stay delete on trailing and reply-all on leading. Hiding a line does not clear the stored mailbox or flag
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 09fdbd4

## 2026-10-01 - fix thread order tests execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/fix-thread-order-tests-20261001-2228-plan.md
- Work: rename the two IndexWindowTest thread tests; oldest first expects 3, 5, 1, 9, 4, 2 and newest first expects 8, 1, 2
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 0b02a95

## 2026-10-02 - shared mail session execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/shared-mail-session-20261002-0032-plan.md
- Work: one process-wide SerialMailSession on thread liveimap-imap; reuse an open handle when the IMAP identity matches; screens and Test server do not close the session
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 6ea6854

## 2026-10-02 - shared mail session overlap thread name

- SerialMailSessionTest saw liveimap-imap @coroutine#N because unit tests enable coroutines debug naming. The factory name is still liveimap-imap. The test compares the name before that suffix.

## 2026-10-02 - APPEND tagged OK execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/append-tagged-ok-20261002-0046-plan.md
- Work: tagged APPEND OK including APPENDUID is success; send stores Answered or Forwarded after SMTP then appends the sent copy; postpone appends with Draft
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD b1f575c

## 2026-10-02 - UNSELECT optional execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/unselect-optional-20261002-0114-plan.md
- Work: drop UNSELECT from required capabilities; send UNSELECT only when advertised, and only before SELECT of a different mailbox; never send CLOSE
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD c84225b

## 2026-10-02 - RFC 2047 display execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/rfc2047-display-20261002-0124-plan.md
- Work: decode RFC 2047 words for index from and subject, and for parsed header text used by reply and forward; leave a failed word unchanged; Headers and Raw stay peeked bytes
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD 6ae05ec

## 2026-10-02 - app shell navigation execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/app-shell-navigation-20261002-0140-plan.md
- Work: navigation-compose stack and drawer replace the tab row; theme flow recolors live; system Back closes overlays before pop; search and compose fields are saveable
- Phase 1 edits the plan critical files, then ./build_app runs testDebugUnitTest and assembleDebug

## 2026-10-02 - index row status thread execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/index-row-status-thread-20261002-0207-plan.md
- Work: three-slot index status, on-screen sequence width, filter chips, root-plus-summary threads, confirm before threading a folder larger than 5000
- First action per standard-plan-compliance-block.md; commits stay on master

## 2026-10-02 - reader-advance execution start

- Approved plan: sandbox/plans/reader-advance-20261002-0235-plan.md
- Work: icon reply bar, Bounce only in the overflow, advance to the next published index uid after a successful delete, move, or spam
- Phase 1 of 1

## 2026-10-02 - folder counts and favorites execution start

- Approved plan: sandbox/plans/folder-counts-favorites-20261002-0255-plan.md
- Work: on-screen message totals from one pipelined STATUS (MESSAGES) burst when LIST-STATUS is absent, and folder favorites in the drawer
- Phase 1 of 1

## 2026-10-02 - compose chrome execution start

- Approved plan: sandbox/plans/compose-chrome-20261002-0325-plan.md
- Work: Compose from the index and folder list, discard confirmation, and forward attachment chips
- Phase 1 of 1

## 2026-10-02 - settings labels and expunge execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/settings-labels-expunge-20261002-0344-plan.md
- Work: human labels and switch rows in Settings, ask-before-expunge default on, expunge confirms when that setting is on or UIDPLUS is absent
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start HEAD f87c2d9

## 2026-10-02 - loading empty error execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/loading-empty-error-20261002-0351-plan.md
- Work: progress, empty, banner, and snackbar states on the index, folder list, and reader
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 73d2b42

## 2026-10-02 - loading empty error phase 1

- Index, folder list, and reader use a 4 dp progress bar, an errorContainer banner with Retry, and a bottom snackbar for later failures
- Empty index uses emptyIndexText; empty folder list says No folders; the top notice line is gone
- Expunge confirm is unchanged

## 2026-10-02 - swipe background execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/swipe-background-20261002-0423-plan.md
- Work: show swipe color, icon, and label from the first pixel; commit still requires 40 percent
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag e40698c

## 2026-10-02 - swipe background phase 1

- A non-zero offset shows the action container, 24 dp icon, and human label; alpha stays 0.45 until 40 percent
- Crossing 40 percent uses full color and one long-press haptic, then the existing confirm still runs the swipe
- Offset 0 stays blank, and releasing early still does nothing

## 2026-10-02 - Selection bar and undo

- Executing sandbox/plans/selection-undo-20261002-0443-plan.md.
- Selection uses a labeled icon row. Select all uses the filter UID list or one 1:* command. Delete and move snackbars can undo, and Expunge with UIDPLUS expunges only those UIDs.

## 2026-10-02 - thread expand execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/thread-expand-20261002-0513-plan.md
- Work: a thread summary toggles the hidden messages already fetched for that root; expanding does not send UID THREAD
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 1e88da1

## 2026-10-02 - thread expand phase 1

- A summary row toggles the hidden messages already fetched for that root and does not send UID THREAD
- Expanded roots stay in a mailbox-keyed saveable uid list, so another mailbox starts collapsed
- threadMessageOrder inserts a root's hidden uids only while that root is expanded

## 2026-10-02 - resume postponed execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/resume-postponed-20261002-0533-plan.md
- Work: a set postponed mailbox is a drawer row that opens that mailbox; resuming a message there removes the old copy with UID EXPUNGE only after send or postpone succeeds
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag c6fbae4

## 2026-10-02 - resume postponed phase 1

- The drawer shows the stored postponed mailbox between INBOX and All folders, and hides that row when the setting is empty
- Opening a message there sets the resume uid; a successful send or postpone deletes and UID EXPUNGEs that uid
- A failed send or append leaves the old copy, and a removal failure only sets a notice

## 2026-10-02 - unsent list execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/unsent-list-20261002-0544-plan.md
- Work: list failed sends from the folder screen; Open and Retry use the existing send path; Discard deletes only the local copy after a confirm
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 509b1a7

## 2026-10-02 - unsent list phase 1

- The folder list shows Unsent and the count only when a local copy exists, and that row opens the unsent route
- Each row shows the subject, recipients, Not sent or Sent, copy not saved, and Open, Retry, or confirmed Discard
- Compose drops the per-copy retry buttons and opens the list from an Unsent button after a failed send

## 2026-10-02 - folder row icons execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/folder-row-icons-20261002-0609-plan.md
- Work: 48 dp folder open target and expander, function icons from settings or SPECIAL-USE, TalkBack description without raw special-use text
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 7e68661

## 2026-10-02 - folder row icons phase 1

- Each folder row reserves a 48 dp expander and a separate open target at least 48 dp tall, indented 24 dp per depth
- Function icons come from a settings mailbox, then Sent, Drafts, Trash, or Junk special-use, then literal INBOX; other rows use the folder icon
- TalkBack on the open target reads the leaf, counts, and collapsed or expanded; the expander reads Expand or Collapse

## 2026-10-02 - two pane mail execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/two-pane-mail-20261002-0628-plan.md
- Work: at 840 dp and above, keep the message list beside the open message and use a permanent drawer; narrower windows keep a single screen and a modal drawer
- Phase 1 of 1
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag fa3611e

## 2026-10-02 - two pane mail phase 1

- At 840 dp and above the index keeps the list beside the open message and the drawer stays open without a menu button
- Narrower windows still push the reader route and keep the modal drawer; IDLE on the index stops while the message pane is open
- A reader route folds back into the index when the window becomes wide and the mailbox matches

## 2026-10-02 - pinerc import execution start

- Approved plan: sandbox/plans/pinerc-import-20261002-0653-plan.md
- Work: Settings imports a user-picked .pinerc, previews changes, and Apply writes only existing account fields
- Status set to APPROVED

## 2026-10-02 - preview fetch pipeline execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/preview-fetch-pipeline-20261002-0723-plan.md
- Work: send one index window of body previews as a single UID FETCH burst and read every tag before the next IMAP command
- Phase 1 of 1
- Status set to APPROVED
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 0c5a29e

## 2026-10-02 - settings pipeline and IMAP log execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/settings-pipeline-and-imap-log-20261002-1231-plan.md
- Work: Settings can turn IMAP pipelining off and can log IMAP traffic to logcat without the password
- Phase 1 of 1
- Status set to APPROVED
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 2f3ef77

## 2026-10-02 - settings pipeline and IMAP log phase 1

- Settings stores pipelineCommands (default on) and logImapTraffic (default off); both switches save immediately
- Pipeline off reads each STATUS or preview tag before the next send; log on writes IMAP traffic to logcat tag LiveIMAP and skips COMPRESS
- A LOGIN buffer is logged as C <private>; SMTP is not logged

## 2026-10-02 - Execution start: burst desync and IMAP log

- Plan: /home/dlang/git/liveimap/sandbox/plans/burst-desync-and-imap-log-20261002-1333-plan.md
- Status set to APPROVED. Phase 1: move STATUS and preview bursts so a failed pipeline restores the high IMAP tag and drops the session, and keep the redacted IMAP transcript in the app cache log.
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not edit libetpan.

## 2026-10-02 - index show fetched rows execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/index-show-fetched-rows-20261002-1445-plan.md
- Work: reassemble split IMAP log lines, throw when an index FETCH parses no rows for a non-empty mailbox, and show that notice instead of "No messages"
- Phase 1 of 1
- Status set to APPROVED
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 37cd4ec
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not edit libetpan.

## 2026-10-02 - index show fetched rows phase 1

- Split socket reads stay one C or S disk line; a private send still logs only C <private> and is not appended to the tail
- The tail is flushed when the logger is turned off and when the session is freed; the 4 MiB rotation and INFO/DEBUG logcat lines stay
- An index FETCH logs parsed <count> from rowsFromList before the deleted-row filter
- When EXISTS is greater than 0, the requested range is non-empty, and that count is 0, nativeFetchIndex throws fetch returned no rows instead of an empty array
- pull copies model.notice, and the index shows that text instead of No messages
- Host /tmp/fetch_row_test exited 0: one item, att_number 16, uid 16

## 2026-10-02 - index pull invokes refresh execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/index-pull-invokes-refresh-20261002-1510-plan.md
- Work: change MessageIndexScreen.pull so it calls sync.block instead of returning the function
- Phase 1 of 1
- Status set to APPROVED
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 9519fa4
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge.

## 2026-10-02 - index pull invokes refresh phase 1

- MessageIndexScreen.pull now calls sync.block() so the compose list copies model.rows after load, watch, and the other pull sites
- Fetch, parser, and the empty-state sentence are unchanged

## 2026-10-02 - reader menu and favorite edit execution start

- Approved plan: sandbox/plans/reader-menu-and-favorite-edit-20261002-1527-plan.md
- Work: message menu closes on the same message; the bar keeps at most four saved actions; long-press edits a drawer favorite
- Phase 1 of 1. build_app runs testDebugUnitTest and assembleDebug. No deploy

## 2026-10-02 - reader menu and favorite edit phase 1

- Message bar shows Back and at most four saved actions, then More. Close, Back, and outside tap leave the message open
- More lists the other actions, body views, folder sort keys, and newest or oldest. Choosing one stays on this message
- A drawer favorite long-press renames, reorders, or deletes. A blank name falls back to favoriteLabel
- TODO: more than one account, and keep the account and password in the Android secure store

## 2026-10-02 - libetpan pipeline helpers execution start

- Approved plan: sandbox/plans/libetpan-pipeline-helpers-20261002-1552-plan.md
- Work: pin libetpan fea126faf0c53cf5b7f56ada04ddd55491f679da and send pipelined STATUS and UID FETCH through mailimap_status_multiple and mailimap_uid_fetch_multiple
- Phase 1 of 1. build_app runs testDebugUnitTest and assembleDebug. No deploy
- Start builds tag 59bf418
- Host: /home/dlang/git/liveimap/master. Do not edit /home/dlang/git/libetpan. Do not send, append, or expunge.

## 2026-10-02 - libetpan pipeline helpers phase 1

- Pin is fea126faf0c53cf5b7f56ada04ddd55491f679da. Both Android libetpan.a archives export mailimap_status_multiple and mailimap_uid_fetch_multiple
- Pipelined STATUS and UID FETCH use those helpers. Stream, fatal, and a desynchronized session return MAILIMAP_ERROR_STREAM. One command at a time is unchanged
- Host burst tests exit 0 against /home/dlang/git/libetpan/src/.libs/libetpan.so at that sha

## 2026-10-02 - sort display keys and menu execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/sort-display-keys-and-menu-20261002-1742-plan.md
- Work: From and To use DISPLAYFROM and DISPLAYTO when SORT=DISPLAY is advertised; the sort menu checks the current key and disables missing capabilities
- Phase 1 of 1
- Status set to APPROVED
- First action per standard-plan-compliance-block.md; commits stay on master
- Start builds tag 5caa096
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge. Do not edit libetpan.
- Icons.Filled.Sort is in material-icons-extended already on the classpath

## 2026-10-02 - sort display keys and menu phase 1

- sortKind returns Esort or UidSort. imapSortKey maps FROM to DISPLAYFROM and TO to DISPLAYTO only when SORT=DISPLAY is advertised
- sort sends that key. nativeSort and sendUidSortChoice accept those two keys, reject DISPLAY, and hand-send a display-name key even when ESORT is off. Parentheses stay tight
- The sort menu checks the active key, puts a divider before the thread keys, and disables a missing capability with Not advertised. The button uses Icons.Filled.Sort. Newest first is a checkbox
- menuKeys keeps every SortKey, including ordered subject when that capability is absent
- IndexWindowTest.orderedSubjectHiddenUnlessAdvertised still expects ordered subject to be omitted. That file is not a Critical File, so it was not edited

## 2026-10-02 - screen top bars execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/screen-top-bars-20261002-1810-plan.md
- Work: top bars on the folder list, index, reader, and compose screens. The outer scaffold stops padding content. Index title is the mailbox leaf with the parent under it. Settings, About, Unsent, and the drawer keep their own insets.
- Phase 1 of 1. build_app runs testDebugUnitTest and assembleDebug. No deploy
- Status set to APPROVED
- First action per standard-plan-compliance-block.md
- Start builds tag 914f451
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge.

## 2026-10-02 - screen top bars phase 1

- LiveImapScaffold content insets are empty and the padded box is gone, so the status inset is not applied twice
- Folder list, index, reader, and compose each use a Scaffold top bar. The bar owns the top inset and the page clears the navigation bar and cutout
- Folder list title is Folders. Menu is shown only for the modal drawer and opens it. The menu button above the NavHost is gone. The permanent drawer passes a null callback
- Index title is the leaf after the personal namespace delimiter, with the parent on the next line when that parent is not empty. Search, filter, and sort, with their menus, are bar actions. The sort label stays beside the sort icon. Expunge stays under the bar
- Reader bar holds Back, the same actions, and More. The menus are unchanged. The title uses the same leaf and parent
- Compose title is Compose. Back closes through the existing dirty check. Send and Postpone, where those buttons already exist, are bar actions
- The delimiter is the personal namespace whose prefix is a prefix of the mailbox, longest prefix first, otherwise the first personal namespace. A failed namespaces call leaves the mailbox as the title with no parent line
- Settings, About, and Unsent are not Critical Files. Their status, navigation, and cutout insets are applied at the NavHost call sites. The drawer sheets use the same union
- The remembered nav graph is unchanged, so an open back stack entry stays open
- No deploy. No send, append, or expunge

## 2026-10-02 - pull to refresh execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/pull-to-refresh-20261002-1831-plan.md
- Work: PullToRefreshBox and a Refresh bar button on the folder list, index, and reader. Each increments loadToken. No second load while loading. Compose is unchanged.
- Phase 1 of 1. build_app runs testDebugUnitTest and assembleDebug. No deploy
- Status set to APPROVED
- First action per standard-plan-compliance-block.md
- Start builds tag 003dd24
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge.
- androidx.compose.material3.pulltorefresh.PullToRefreshBox is in material3 1.3.0 already on the classpath

## 2026-10-02 - pull to refresh phase 1

- Folder list, index, and reader wrap their scrolling content in PullToRefreshBox. isRefreshing is that screen's loading flag. onRefresh increments loadToken only when loading is false, so a pull during a load does not start another
- Each of those three TopAppBars has a Refresh icon button that increments the same loadToken and stays visible. Search, filter, sort, reader actions, and More are unchanged. Compose has no refresh control
- A failure still uses the existing banner. The pull indicator is the Material3 default, which hides when loading is false and the control is not pulled
- No deploy. No send, append, or expunge

## 2026-10-02 - index row details execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/index-row-details-20261002-1856-plan.md
- Work: date column width from the widest formatIndexDate on screen, one status description with theme colors, and an active filter row showing N of M
- Phase 1 of 1. build_app runs testDebugUnitTest and assembleDebug. No deploy
- Status set to APPROVED
- First action per standard-plan-compliance-block.md
- Start builds tag 49ecd48
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge.

## 2026-10-02 - index row details phase 1

- The date column uses one measured width, the widest formatIndexDate among the index rows listed, with the same format, pattern, zone, and instant. Tabular figures are fontFeatureSettings tnum because Compose BOM 2024.10.00 has no FontFeature type. Empty dates keep that width. TextAlign.End. Sequence width is unchanged
- IndexStatusColumn takes indexStatusDescription. An empty string sets no description. Flagged is colorScheme.error and to-me is colorScheme.primary. The three slots and sizes stay. Icons stay unlabeled
- An active filter row under the bar shows the filter chips and N of M. N is the index message rows listed. M is EXISTS already received by this screen, otherwise N. All messages clears the filters and removes the row. No new count request
- No deploy. No send, append, or expunge

## 2026-10-02 - selection contextual bar execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/selection-contextual-bar-20261002-2307-plan.md
- Work: while messages are selected, the index TopAppBar is the selection bar and the extra row is not composed
- Phase 1 of 1. build_app runs assembleDebug on this tree. No deploy
- Status set to APPROVED
- First action per standard-plan-compliance-block.md
- Start builds tag 13cd3f9
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge.

## 2026-10-02 - selection contextual bar phase 1

- While messages are selected, the index TopAppBar title is selectionTitle, navigation is Close and clears the selection, and the actions are mark read or unread, flag or unflag, move, delete, and More. The extra selection row is not composed
- The flag action says Unflag when every loaded selected row is flagged, otherwise Flag. The read action still says Mark unread or Mark read
- More still dismisses without changing the selection. Its items are Select all, Mark answered, Mark unanswered, Undelete when a selected row is deleted or the whole folder is selected, Bounce, and Clear selection. The extra Mark unread item is gone. Item text has no raw flag names
- Select all, undo, and the store and copy calls are unchanged. With nothing selected, the normal top bar returns
- No deploy. No send, append, or expunge

## 2026-10-02 - folder count columns execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/folder-count-columns-20261002-2312-plan.md
- Work: folder rows show an aligned total, unread only when nonzero, a favorite star, faint dividers, and a search of names already loaded
- Phase 1 of 1. This tree's ./build_app runs assembleDebug. No deploy
- Status set to APPROVED
- First action per standard-plan-compliance-block.md
- Start builds tag 4d4def2
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge.

## 2026-10-02 - folder count columns phase 1

- Each folder row ends with a total and an unread column. Both are right-aligned, tabular (fontFeatureSettings tnum), and one measured width for every row on screen. Unread is blank when the setting is off or the count is zero, not 0. The faint row divider stays
- A star in front of the counts toggles the same favorite as a long-press: a leaf uses the name record, a collapsed node uses the node record, and an expanded row uses the mailbox record. A filled star is that favorite. The star does not open the folder. Long-press still works
- The top bar search field filters loaded display names and does not send LIST. Clearing it shows the loaded level again
- No deploy. No send, append, or expunge

## 2026-10-02 - Reader body chrome execution start

- Plan: sandbox/plans/reader-body-chrome-20261002-2312-plan.md
- Status set to APPROVED
- builds before edit: 2040cbc
- Phase 1: header card, plain-text links and quote color, theme HTML, attachment chips, no-text card in MessageReaderScreen.kt

## 2026-10-02 - compose reply chrome execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/compose-reply-chrome-20261002-2312-plan.md
- Status set to APPROVED
- builds before edit: cac0e0b
- Phase 1 of 1: leave prompt, labeled recipients, reply cursor, reply-above-quote setting, forward header block
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. No deploy. Do not send, append, or expunge.

## 2026-10-02 - compose reply chrome phase 1

- A changed body, subject, or recipient asks Discard, Postpone, or Keep editing. Postpone in that dialog stays disabled when no postponed mailbox is set. An unchanged compose leaves without the dialog
- To, Cc, and Bcc stay separate labeled fields. Empty Cc and Bcc are omitted from the message
- A reply keeps one attribution line and `> ` quotes. The cursor starts on the line below the quote. AccountSettings.replyAboveQuote defaults to false; a missing key stays false. Settings switch "Reply above the quote" persists immediately and puts the cursor on the line above the quote
- A forward shows From, Date, and Subject, then the quoted body. Attachments still follow includeForwardAttachments and can be removed before send
- AccountSettingsTest key list includes replyAboveQuote so the encode round-trip stays true. No deploy. No send, append, or expunge

## 2026-10-02 - settings categories and about execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/settings-categories-and-about-20261002-2312-plan.md
- Status set to APPROVED
- builds before edit: 424db1c
- Phase 1 of 1: five settings groups, dynamicColor default true, theme uses dynamic color only on API 31 when the switch is on, About already shows LiveIMAP and versionName
- Theme path is MainActivity.kt. About path is app/src/main/kotlin/org/dlang/liveimap/ui/about/AboutScreen.kt
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. No deploy. Do not send, append, or expunge.

## 2026-10-02 - settings categories and about phase 1

- Settings controls sit under Account, Mailboxes, Display, Message bar, and Debug. Labels and save calls are unchanged. Reply above the quote stays in Display
- AccountSettings.dynamicColor defaults to true. A missing key decodes to true. Display shows Dynamic color only on API 31 and above
- The activity theme uses the system dynamic scheme only when that switch is on and the device is API 31 or newer. Otherwise it keeps the static scheme
- About already shows LiveIMAP and BuildConfig.VERSION_NAME at ui/about/AboutScreen.kt. No license text added
- No deploy. No send, append, or expunge

## 2026-10-03 - compose brief gaps execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/compose-brief-gaps-20261003-0018-plan.md
- Status set to APPROVED
- builds before edit: b8b44a6
- Phase 1 of 1: collapse empty Cc and Bcc, forwardAsAttachment default false, one message/rfc822 part, overflow one-shot
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. No deploy. Do not send, append, or expunge.

## 2026-10-03 - compose brief gaps phase 1

- Empty Cc and Bcc stay hidden until the To row Cc/Bcc control is on, or that field is non-empty. From stays the read-only text line
- AccountSettings.forwardAsAttachment defaults to false. A missing key decodes to false. Settings switch "Forward as attachment" persists
- When that mode is on, forward attaches forwarded.eml as message/rfc822 and does not insert the inline header block. The setting stays the default
- Reader overflow, and the index overflow when one message is selected, offer the other mode for that open only and do not change the setting
- No plaintext-only recipient rule exists on a compose address. The AssistChip was not added. Do not invent the rule
- AccountSettingsTest.defaultsRoundTrip lists encode keys exactly and already omits dynamicColor. forwardAsAttachment would fail that test. The test file was not edited. This tree's ./build_app runs assembleDebug only
- No deploy. No send, append, or expunge

## 2026-10-03 - folder overflow a11y execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/folder-overflow-a11y-20261003-0018-plan.md
- Status set to APPROVED
- builds before edit: 5021493
- Phase 1 of 1: folder bar More with Collapse all, Unsent when copies exist, empty-prefix announcement
- Host: /home/dlang/git/liveimap/master. Critical file: FolderListScreen.kt. No deploy. Do not send, append, or expunge.

## 2026-10-03 - folder overflow a11y phase 1

- Folder top bar More menu has Collapse all. It clears expandedFolders and reloads the top level
- Unsent is in that menu only when unsentCount is greater than zero, and it opens the existing Unsent screen. The existing list row is unchanged
- An empty namespace prefix keeps the visible server label. TalkBack on that row is "Namespace, empty prefix". Other namespace rows still use folderRowDescription
- No deploy. No send, append, or expunge. This tree's ./build_app runs assembleDebug

## 2026-10-03 - folder overflow a11y collapse does not list

- Collapse all clears expandedFolders and shows the already loaded top-level rows. It does not call loadLevel and does not send LIST
- No deploy. No send, append, or expunge

## 2026-10-03 - index refresh and thread marks execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/index-refresh-and-thread-marks-20261003-0018-plan.md
- Work: pull-to-refresh and the Refresh button update on-screen flags and add a grown tail without reloading the window. A collapsed thread shows its count and unread count. A selected row shows a check.
- Phase 1 of 1
- Status set to APPROVED
- First action per standard-plan-compliance-block.md
- Start builds tag 66d858a
- Host: /home/dlang/git/liveimap/master. Do not deploy. Do not send, append, or expunge.

## 2026-10-03 - index refresh and thread marks phase 1

- Pull and Refresh call refreshShown. It fetches flags for the on-screen UIDs and copies those flags onto the loaded rows. A larger EXISTS adds the newest tail. An unchanged EXISTS does not replace the rows. Failure uses the existing banner.
- MailSession has no NOOP, and that file is not a Critical File, so this phase does not send one. The count is selectedExists or the folder EXISTS already on screen. Refresh does not SELECT and does not reload the window.
- A collapsed thread mark is the message count and the unread count, such as 7 · 2 unread. Expanded replies indent 16 dp per level, capped at 6, and a deeper row shows its depth number.
- A selected row keeps secondaryContainer and shows check_circle in the status column, tinted primary. The one-shot forward choice is unchanged.

## 2026-10-03 - plaintext recipient chip execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/plaintext-recipient-chip-20261003-0018-gap-plan.md
- Status set to APPROVED
- builds before edit: bfff03d
- Phase 1 of 1: show Plain text only when a To, Cc, or Bcc address matches an Alpine entry marked [plaintext]
- Host: /home/dlang/git/liveimap/master. Critical files: ComposeScreen.kt and AlpineBook.kt. No deploy. Do not send, append, or expunge.

## 2026-10-03 - plaintext recipient chip phase 1

- A To, Cc, or Bcc address that matches an Alpine book entry whose address or comments contain [plaintext] shows an AssistChip labeled Plain text only. The chip does not remove the recipient
- Matching strips [plaintext] from the address and uses that mark only. An entry without the mark does not show the chip
- An unset address-book mailbox or a failed book load shows no chip and does not fail compose. Bounce is unchanged
- Empty Cc and Bcc still collapse. Forward as message/rfc822 and the leave prompt are unchanged
- No deploy. No send, append, or expunge. This tree's ./build_app runs assembleDebug

## 2026-10-03 - settings sub-screens execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/settings-subscreens-and-about-20261003-0018-plan.md
- Status set to APPROVED
- builds before edit: d10e68e
- Phase 1 of 1: five settings groups each with a back stack entry, switch rows stay one ListItem, About plaintext notice and library names
- Host: /home/dlang/git/liveimap/master. Critical files: SettingsScreen.kt and AboutScreen.kt. No deploy. Do not send, append, or expunge.

## 2026-10-03 - critical UI fixes execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/critical-ui-fixes-20261003-1505-plan.md
- Status: APPROVED
- builds before edit: d10e68e
- Stash ref: stash@{0} On master: pre-critical-ui-fixes 2026-10-03: settings-subscreens-and-about-20261003-0018 partial work (unused SettingsScreen imports)
- settings-subscreens-and-about-20261003-0018: stopped at start, SettingsScreen.kt imports stashed, not resumed
- coder-next.txt was idle (nonce 0), not status run for reader-brief-gaps. This dispatch names the approved plan. No other execute child.
- Phase 1: newest message at the newest end in every time-ordered view; arrival pages by sequence range; newest-first sort sends REVERSE; new mail tails instead of reloading the window
- Host: /home/dlang/git/liveimap/master. No deploy. Do not send, append, or expunge.

## 2026-10-03 - up navigation execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/up-navigation-20261003-1630-plan.md
- Status: APPROVED
- builds before edit: 810d9db
- settings-subscreens-and-about-20261003-0018: stash dropped (10 unused imports), nothing of that plan remains
- Phase 0 done: stash list empty, no tracked changes, SettingsScreen.kt has no NavHost import
- Phase 1: Up bar on Settings, About and Unsent; drawer edge swipe only on the folder list; remove Unsent Close
- Host: /home/dlang/git/liveimap/master. No deploy. Do not send, append, or expunge.

## 2026-10-03 - up navigation phase 1

- Settings, About and Unsent use UpPage and UpTopAppBar. The Up icon content description is Navigate up. Settings and About call navigateUp. Unsent calls popBackStack and still passes onBack
- Drawer edge swipe is enabled only when the route is folders
- Unsent no longer shows a Close button. Open, Retry and Discard stay
- No new dependency. No deploy. No send, append, or expunge

## 2026-10-03 - connection recovery execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/connection-recovery-20261003-1645-plan.md
- Status: APPROVED
- builds before edit: 8921769
- Phase 0: no tracked changes, stash empty, up-navigation already landed on that builds tag
- Phase 1: real IMAP error text, keepalive, IDLE renewal and lost-watch notification
- Host: /home/dlang/git/liveimap/master. No deploy. Do not send, append, or expunge except as the plan's manual checks describe.

## 2026-10-03 - connection recovery phase 3 landed

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/connection-recovery-20261003-1645-plan.md
- Phase 3: ConnectionStatusStrip on the index, folder list, and reader. Index Retry calls resume then refreshIndex, or bumps loadToken when not yet connected. Reconnected refreshes the index. WatchLost resumes after 2 s, then 10 s, then 60 s, and then stops. LifecycleStartEffect resumes and refreshes on every start after the first. MainActivity onStop suspends the session unless a configuration change is in progress.
- Manual checks on 10.0.0.1 stay with the human. No deploy.

## 2026-10-05 - index start position execution start

- Approved plan: sandbox/plans/index-startup-position-20261003-1625-plan.md
- Worktree: /home/dlang/git/liveimap/master branch master
- Phase 0: no tracked modifications. builds tag 13430ae
- Known failures not fixed here: IndexWindowTest.orderedSubjectHiddenUnlessAdvertised, AccountSettingsTest.defaultsRoundTrip
- Phase 1: StartRule settings, pinerc mapping, searchStart through native, parseEsearch MIN/MAX

## 2026-10-05 - index start position phase 2

- Phase 2: locateStart, startIndex, and the window anchor around that row
- scrollToStart after open, sort, direction, filter, and search; Show uses scrollToNewestEnd
- Keep-top records the visible uid; Arrival locates it with an uncached sequence search
- New mail shows the snackbar and does not scroll; undo and expunge keep the anchor

## 2026-10-05 - Index new-mail pill execution start

- Approved plan: sandbox/plans/index-new-mail-pill-20261005-2130-plan.md
- Status: APPROVED
- Worktree: /home/dlang/git/liveimap/master branch master
- builds before edit: f45cbba
- Phase 1: Arrival growth keeps pendingNew; a sort, filter, or search sets newMailUnnumbered and does not fetch
- Phase 2: replace the new-mail snackbar with the pill; hold the first visible row across a prepend
- Known failures not fixed here: IndexWindowTest.orderedSubjectHiddenUnlessAdvertised, AccountSettingsTest.defaultsRoundTrip
- No deploy. Do not install. Do not send, append, expunge, or edit the address book

## 2026-10-05 - Index new-mail pill phase 1

- applyArrivalGrowth keeps pendingNew as the running growth, including when the new tail is inserted
- Exists during a search, a filter, or a non-Arrival sort sets newMailUnnumbered, leaves pendingNew at 0, and does not fetch
- Flags and Expunge do not set that flag
- IndexWindowTest: one known failure, orderedSubjectHiddenUnlessAdvertised. The new-mail tests passed

## 2026-10-05 - Index new-mail pill phase 2

- The new-mail snackbar is gone. New mail does not call loadWindow
- The pill reads "N new messages" with an arrow, or "New messages" with no number. TalkBack uses one polite live region per change
- A newest-first prepend keeps the first visible uid and offset. Tap and a scroll onto the newest rows clear the pill
- IndexWindowTest: 54 completed, 1 known failure (orderedSubjectHiddenUnlessAdvertised). No deploy

## 2026-10-05 - Traffic log execution start

- Execute sandbox/plans/traffic-log-20261005-2145-plan.md on master (Status APPROVED). Phase 1: TrafficLog file format and native chunk handoff. Phase 2: share, debug report review, user-name switch, debug status. No install. Device checks stay with David.

## 2026-10-05 - Traffic log phase 2

- Share the current traffic file through TrafficFileProvider. The debug report can drop a line, then Copy or Share. The user name is <user> unless "Show user name in the debug report" is on.
- DebugConnectionStatus sits under the reconnect strip on the folder list, the index, and the reader. The strip text is unchanged.
- TrafficLogTest checks the report. No install. Device checks stay with David.

## 2026-10-05 - Settings screens execution start

- Execute sandbox/plans/settings-screens-20261005-2248-plan.md on master (Status APPROVED). Phase 1: commit text and port on Done, focus loss, or leave. Phase 2: settings groups, sub-screens, licenses. No install. Device and rotation checks stay with David.

## 2026-10-05 - Settings screens phase 1

- commitText and commitPort. Equal text and an invalid or unchanged port return null.
- A text field keeps its draft and writes on IME Done, focus loss, or leaving. Rotation and a screen that is not ready skip the leave write. Add-row drafts do not insert a row on leave.
- LineCommitTest covers those rules. No install.

## 2026-10-05 - Settings screens phase 1 compiles

- One settings state, and mailbox lines pass onCommit. The field commit now compiles.

## 2026-10-05 - Settings screens phase 2

- Settings opens a group list, then one screen per group. The first section starts open. Up returns to the list, and the folder lists are their own screens.
- About shows the plaintext card when an IMAP host is set, and Open-source licenses reads the notices. No new setting and no session restart. No install.

## 2026-10-05 - Help page execution start

- Execute sandbox/plans/help-page-20261005-2251-plan.md on master (Status APPROVED). Phase 1: help assets, Help screen, shared debug-report review, drawer item, route, and folder-list Help button. No install. Device and TalkBack checks stay with David.

## 2026-10-05 - Help page phase 1

- Help route and drawer item sit above About. Up is navigateUp. About is unchanged.
- The folder list shows Help under the connection strip only while the loaded IMAP host is blank.
- Help reads the two bundled assets. Report a bug opens the issues URL. Copy debug report uses the shared review. No install.

## 2026-10-05 - Folder list execution start

- Execute sandbox/plans/folder-list-20261005-2319-plan.md on master (Status APPROVED).
- Phase 1: session expansion, level cache, overflow items, long-press menu, empty-prefix row, and FolderListModelTest.
- Phase 2: Expanded folders and Folder views use the mailbox picker and a close icon.
- No install. Device and lab-server checks stay with David.

## 2026-10-05 - Folder list phase 1

- Session expansion is copied once. Expand, collapse, show collapsed, and collapse all do not write settings.
- Each list level is cached until refreshLevels. Pull to refresh and the toolbar refresh clear that cache before they list.
- Overflow adds Save as default view and Reset to default view. Long-press keeps favorite and adds Always expand and Don't always expand.
- The empty-prefix row is italic, uses FolderSpecial, and search always keeps it without matching its label. No install.

## 2026-10-05 - Folder list phase 1 test doubles

- Renamed the FolderListModelTest doubles so they do not clash with FolderTreeTest's private helpers. FolderTreeTest is unchanged.

## 2026-10-05 - Folder list phase 2

- Expanded folders and Folder views add through the mailbox picker. Remove is a close icon. Sort, Newest first, and the stored strings are unchanged. No install.

## 2026-10-06 - Folder list phase 3 execution resume

- Resume revision 2 of sandbox/plans/folder-list-20261005-2319-plan.md on master. Phases 1 and 2 stay DONE.
- Phase 3 updates FolderTreeTest only: inboxDotPrefixDoesNotPromoteChildren and toggleExpandedLeavesStoredExpandedFolders.
- No production edits. No install.

## 2026-10-06 - Folder list phase 3

- FolderTreeTest constructs the INBOX prefix case already expanded and expects one ListCall("INBOX.", null). Toggling INBOX and Archive leaves stored expanded folders empty and does not save.
- No production edits. No install.

## 2026-10-06 - Reader polish execution start

- Execute sandbox/plans/reader-polish-20261005-2318-plan.md on master (Status APPROVED).
- Phase 1: pinned header, view labels, view switch, link dialog, quote rows, Show HTML, and the monospace switch.
- Phase 2: attachment chip, Open, Share, Save, attachment provider, cache deletion on leave, and Show images.
- No install. Device checks stay with David.

## 2026-10-06 - Reader polish phase 1

- The header card stays above the scrolling body. From uses the HEADER From when it contains @, otherwise the index From. The date is the envelope date when it is not blank, otherwise the formatted index date. To and Cc start as one ellipsized line and wrap after a tap. Subject is not ellipsized. Message N is gone.
- A Deleted chip and Undelete show when \Deleted is set. Undelete removes that flag and does not expunge.
- Body view labels are Plain text, HTML, HTML as text, Headers, and Raw source. The Close menu item is gone. Choosing a view shows that view even when a plain part exists. The no-text card has Show HTML when an HTML part exists, and that button does not write the setting.
- A link tap, in plain text or the WebView, shows the full URL. Open uses ACTION_VIEW. Cancel dismisses. If no app can open it, the snack says No app found.
- A line that starts with > is a row with a 2 dp outlineVariant box and onSurfaceVariant text. Other lines keep the current color.
- Reading, Opening saves Plain text in monospace immediately. The default is false, a missing key stays false, and the plain body uses monospace only when it is on. No install.

## 2026-10-06 - Reader polish phase 1 test import

- ReaderTextTest imports settings.encode so the monospace round-trip compiles. The phase 1 reader behavior is unchanged. No install.

## 2026-10-06 - Reader polish phase 2

- An attachment chip shows the filename, the BODYSTRUCTURE size, and an image, PDF, or generic icon. Nothing is fetched until the first tap. While the 65536-byte fetch runs, the chip shows the fetched count. When it is done, Open, Share, and Save use the cache file and do not fetch again.
- AttachmentFileProvider serves only a read-only cache file named liveimap-<uid>-<safe>. It does not serve the traffic log. Leaving the message deletes that UID's cache files and leaves imap-traffic.log.
- Show images is shown while HTML is on screen. It turns off blockNetworkLoads for this message only. The next message starts blocked. JavaScript stays off. No install.

## 2026-10-06 - compose-polish execution start

- Approved plan: sandbox/plans/compose-polish-20261006-0040-plan.md
- Worktree: /home/dlang/git/liveimap/master (master). Builds tag at dispatch: 575db68
- Phases: chips and Send/Postpone chrome; alt-addresses, Reply-To chip, sent name, to-me; background draft hook and APPENDUID
- Do not install. Device checks stay David's

## 2026-10-06 - compose-polish phase 4 execution resume

- Approved plan: sandbox/plans/compose-polish-20261006-0040-plan.md
- Resume revision 2 from phase 4. Phases 1–3 stay DONE (b30f3a1, 13a7308, d3d5f35).
- Worktree: /home/dlang/git/liveimap/master (master). Builds tag at dispatch: d3d5f35
- SerialMailSession.appendReturningUid returns the inner UID on the liveimap-imap lane. Do not install.

## 2026-10-06 - compose-polish phase 4

- SerialMailSession.appendReturningUid calls inner.appendReturningUid on the liveimap-imap lane and returns that long. It does not call append.
- SerialMailSessionTest.appendReturningUidReturnsInnerUidOnTheLane: inner returns 3955, append is not called, and the recorded thread is liveimap-imap. No install.

## 2026-10-06 - compose-polish phase 4 test double

- AppendUidInner is its own MailSession so the lane test compiles. OverlapInner stays unchanged. The test still expects UID 3955, no append call, and thread liveimap-imap. No install.

## 2026-10-06 - reader body decoding execution start

- Approved plan: sandbox/plans/reader-body-decoding-20261006-0117-plan.md
- Worktree: /home/dlang/git/liveimap/master (master). Builds tag at dispatch: b7dc9e3
- Phase 1: MimePart charset and encoding, decodePart, WireTextDecoder, reader body paths, charsetOf, compose quoting
- Phase 2: jsoup, desugaring, htmlAsText, preview path, NOTICES.txt
- Do not install. Device checks stay David's

## 2026-10-06 - reader body decoding phase 1

- MimePart carries charset and encoding, both default empty. The JNI constructor passes the BODYSTRUCTURE charset and base64 or quoted-printable, or empty for 7bit, 8bit, binary, and multipart.
- decodePart undoes that transfer encoding, then java.nio.charset. An empty or unknown charset is ISO-8859-1 with unknownCharset set. WireTextDecoder holds a split base64 quantum, a quoted-printable tail, and an incomplete character until finish.
- Plain bodies stream through that decoder. HTML and HTML-as-text read the whole part, then decode. The WebView page starts with a UTF-8 meta. The reader shows "Unknown charset; shown as ISO-8859-1." above the body. Compose quoting uses the same decode and sets that sentence when the notice is empty. No install.

## 2026-10-06 - reader body decoding phase 2

- htmlAsText uses jsoup 1.21.2. script and style are omitted. Block tags break the line. Link text stays in the body and each href is listed as [1] after a blank line. A blockquote line starts with "> ". Ordered items are numbered. Table cells in a row are separated by one space. pre keeps its spaces.
- A client preview takes the first text/plain part, otherwise the first text/html part, and does not follow preferHtml. Peeked bytes go through PartText.previewText. A failed base64 or quoted-printable decode leaves the preview empty. A server-supplied preview is unchanged. The unknown-charset note is not inserted into a preview.
- minSdk stays 26. Core library desugaring uses desugar_jdk_libs_nio 2.1.5. NOTICES lists jsoup as MIT and the desugar library as GPL-2.0 with the Classpath Exception. No install.

## 2026-10-06 - contacts completion execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/contacts-completion-20261006-0150-plan.md
- Worktree: /home/dlang/git/liveimap/master (master). Builds tag at dispatch: a7dcba9
- Phase 1: completeAddress and completionSources. Phase 2: settings section, READ_CONTACTS, in-memory load, AddressChips suggestions
- Do not install. Do not copy contacts or write the pine address book. Device checks stay David's

## 2026-10-06 - contacts completion phase 1

- completeAddress ranks an exact nickname, ignoring case, ahead of a display name or email that contains the typed text. Within one rank the earlier source stays first. A parenthesized distribution list is one suggestion whose members come from pickedAddresses.
- completionSources is missing as pine and empty as no sources. The default pine-only list is not written. Other lists are percent-encoded and comma-separated. An Android id is android, the encoded account type, and the encoded account name.
- loadAlpineBook is unchanged. No install.

## 2026-10-06 - contacts completion phase 1 test import

- CompletionTest imports settings.encode so the completionSources round-trip compiles. The phase 1 ranking behavior is unchanged. No install.

## 2026-10-06 - contacts completion phase 2

- Compose, Address completion lists the pine row when the address book mailbox is set, and asks for READ_CONTACTS only from Show Android contact sets. A denial shows Contacts permission was denied. and enables nothing. After a grant, each RawContacts account is an off switch labeled with the owning app or the account name. Move up and Move down reorder the enabled list.
- AddressChips shows suggestions while the buffer is not empty. A tap commits through appendAddress and clears the buffer. A distribution list commits each pickedAddresses member. The address book button and picker stay.
- The pine book is loaded with loadAlpineBook at most once per process when pine is enabled. Android contact rows are queried at most once per process when an Android source is enabled. Neither is written to disk. No WRITE_CONTACTS. No install.

## 2026-10-06 - contacts copy execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/contacts-copy-20261006-0213-plan.md
- Worktree: /home/dlang/git/liveimap/master (master). Builds tag at dispatch: 8c0d572
- Phase 1: CopyMap both ways and preview. Phase 2: formatAlpineBook, revisionsToExpunge, history settings. Phase 3: copy screen, WRITE_CONTACTS, pine append and uidExpunge of those UIDs only
- Do not install. Do not expunge without UIDPLUS. Device checks stay David's

## 2026-10-06 - contacts copy phase 1

- copyContacts maps nickname, full name, email, distribution lists, fcc, comments, and plaintext both ways. The same call builds the preview lines and the destination entries. The source list is not modified.
- A matching email with merge off is Skipped, already there. Nested list nicknames expand. No notes drops fcc. Dropped phone, postal, organization, birthday, photo, website, IM, and custom label are named, and can be appended to comments. Starred, ringtone, and linked contacts are not copied.
- No install.

## 2026-10-06 - contacts copy phase 1 name

- isPineListAddress avoids the private isDistributionAddress already in Completion.kt. The mapping is unchanged. No install.

## 2026-10-06 - contacts copy phase 2

- formatAlpineBook writes five tab fields per entry. An empty nickname stays empty. A tab or newline in a field becomes a space. [plaintext] stays in the comment.
- revisionsToExpunge keeps the header out. History 3 on UIDs 1 through 6 returns 2. Never trim and a missing UIDPLUS return nothing.
- addressBookHistory defaults to 3 and addressBookNeverTrim to false. Both keys are omitted at those defaults. Pinerc remote-abook-history sets the number only when it is all digits, and does not turn on never-trim.
- loadAlpineBook is unchanged. No install.

## 2026-10-06 - contacts copy phase 2 test import

- AlpineBookWriteTest imports settings.encode so the history round-trip compiles. The history behavior is unchanged. No install.

## 2026-10-06 - contacts copy phase 3

- Compose, Address book has the history number, Never trim, and Copy contacts. Up on the contacts route opens the copy screen. The user selects contacts, taps Copy to…, and confirms once. The copy is not started from a background load.
- Copying into Android asks for WRITE_CONTACTS at confirm. Denial shows Contacts permission was denied. and writes nothing. Copying into pine does not ask for that permission.
- A pine write appends one message using the last message header and the new body. The first header message is left as it is. Revisions outside the history window are marked Deleted and removed with uidExpunge of those UIDs only. Never trim and a missing UIDPLUS append and expunge nothing. A changed last UID shows the preview again and does not append on that pass.
- No install.

## 2026-10-06 - account storage execution start

- Approved plan: sandbox/plans/account-storage-20261006-0246-plan.md.
- Phase 1: migrateAccount and AccountMigrationTest. Old DataStore and liveimap_secret stay until the readback matches.
- Phase 2: shared AccountStore, authenticator, manifest, and backup rules. No install.

## 2026-10-06 - account storage phase 2

- Shared AccountStore. The AccountManager password slot is AES-GCM ciphertext, not plaintext.
- DataStoreSettingsStore load, save, and password delegate to that one store.
- Authenticator type org.dlang.liveimap. Add account opens settings and does not add a second account.
- Removing the account deletes its key, DataStore entry, unsent, and imap-traffic.log.
- Backup stays allowed and excludes liveimap_secret. No install.

## 2026-10-06 - fake IMAP execution start

- Approved plan: sandbox/plans/fake-imap-20261006-0313-plan.md
- Phase 1: loopback FakeImapServer and a line client. Both profiles greet, CAPABILITY is that string, LOGIN, LIST INBOX, SELECT UIDVALIDITY 17, FETCH \Seen.
- Phase 2: Close, Silent, Bad, No, Bye, NewUidValidity, and ExpungeDuringFetch.
- Test sources only. No app/src/main edits. No install.

## 2026-10-06 - fake IMAP phase 1

- FakeImapServer binds 127.0.0.1 only. minimal is IMAP4rev1. cyrus22 is the recorded Cyrus 2.2 token line. Greeting, CAPABILITY, LOGIN, LIST INBOX, SELECT UIDVALIDITY 17, FETCH \Seen, and LOGOUT.
- FakeImapTest drives both profiles with a CRLF line client, including an empty password NO. No install.

## 2026-10-06 - fake IMAP phase 2

- Script steps Close, Silent, Bad, No, Bye, NewUidValidity, and ExpungeDuringFetch replace the next reply.
- Close ends the read. Silent stays unanswered. Bad and No are tagged. Bye is untagged and the socket closes. The next SELECT reports UIDVALIDITY 99. EXPUNGE is sent before FETCH.
- No install.

## 2026-10-06 - About strings execution start

- Approved plan: sandbox/plans/about-strings-20261006-0325-plan.md
- Phase 1: About words move into strings.xml. Wording stays the same. The about route title uses about_title. No other screen. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - About strings phase 1

- About title, version, plaintext warning, Support, licenses, license notice, and the no-email toast come from strings.xml. app_name is unchanged and is reused for the name line and the mail subject.
- The about route Up title is about_title. Other NavHost titles are unchanged. The support address stays david+liveimap@lang.hm. No install.

## 2026-10-06 - Help strings execution start

- Approved plan: sandbox/plans/help-strings-20261006-0330-plan.md
- Phase 1: Help headings, buttons, and the missing-file line move into strings.xml. Wording stays the same. The Help Up title and the Help drawer label use help_title. Articles and the issues URL stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Help strings phase 1

- Help title, Getting started, Reporting a bug, Report a bug, Copy debug report, and the missing-file line come from strings.xml. Existing string values are unchanged.
- HelpScreen uses those resources except the title. The Help route Up title and the Help drawer label are help_title. The About drawer label stays About. HELP_ISSUES_URL and the help asset files stay. No install.

## 2026-10-06 - Licenses strings execution start

- Approved plan: sandbox/plans/licenses-strings-20261006-0335-plan.md
- Phase 1: the licenses missing-file line comes from strings.xml. Wording stays the same. The about/licenses Up title uses about_licenses. NOTICES.txt stays. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Licenses strings phase 1

- licenses_unreadable is The license text could not be read. Existing string values are unchanged.
- LicensesScreen uses that resource for the missing-file line. The about/licenses Up title is about_licenses. The About drawer label stays About. NOTICES.txt stays. No install.

## 2026-10-06 - Drawer strings execution start

- Approved plan: sandbox/plans/drawer-strings-20261006-0338-plan.md
- Phase 1: drawer labels, the postponed description, and the favorite dialog come from strings.xml. Wording stays the same. About uses about_title. No other screen. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Drawer strings phase 1

- Drawer labels INBOX, All folders, and Settings come from strings.xml. The About drawer item uses about_title. Existing string values are unchanged.
- The postponed row still shows postponedDrawerMailbox. Its spoken label is drawer_postponed. Favorite names stay the stored label. Favorite, Name, Move up, Move down, Save, Delete, and Cancel come from strings.xml.
- NavHost.kt has no Text(". No other screen. No install.

## 2026-10-06 - Unsent strings execution start

- Approved plan: sandbox/plans/unsent-strings-20261006-0343-plan.md
- Phase 1: Unsent buttons, dialog, Up title, and status lines come from strings.xml. Wording stays the same. unsentSubject takes the missing-subject fallback. unsentLabel is removed. No other screen. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Unsent strings phase 1

- Open, Retry, Discard, Cancel, and Discard this unsent message? come from strings.xml. The unsent route Up title is unsent_title. Existing string values are unchanged.
- unsentSubject takes the missing-subject text and the screen passes unsent_no_subject. unsentLabel is removed. The screen uses unsent_sent_copy or unsent_not_sent. A present Subject header is still shown.
- ComposeChromeTest.unsentSubjectAndLabel expects Hello and the passed fallback No subject. It does not call unsentLabel. The folder list Unsent item is unchanged. UnsentScreen.kt has no Text(". No install.

## 2026-10-06 - Folder strings execution start

- Approved plan: sandbox/plans/folder-strings-20261006-0350-plan.md
- Phase 1: folder list titles, menus, empty states, and spoken row text come from strings.xml. Wording stays the same, except one versus many for the spoken counts. Mailbox names stay mailbox names. Help uses help_title. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Folder strings phase 1

- Folder list title, menu, search, empty states, row menu, and icon descriptions come from strings.xml. Existing string values are unchanged. Help uses help_title.
- Spoken counts use folders_messages and folders_unread. One count uses the one-form. Other counts use the other-form. An empty prefix shows (empty prefix) and is spoken as Namespace, empty prefix.
- folderDisplayName and folderRowsMatchingName take the empty-prefix label. folderRowDescription joins the leaf and the non-null phrases. FolderListScreen.kt has no Text(". No install.

## 2026-10-06 - Debug report strings execution start

- Approved plan: sandbox/plans/debug-report-strings-20261006-0358-plan.md
- Phase 1: debug-report review title, buttons, clipboard label, and share title come from strings.xml. Wording stays the same. Report lines stay generated text. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Debug report strings phase 1

- Debug report, Remove line, Copy, Share, and Dismiss come from strings.xml. The clipboard label is LiveIMAP debug report. The share chooser title is Share debug report. Existing string values are unchanged.
- An empty report line still shows a space. Report lines stay generated text. DebugReportReview.kt has no Text(". No install.

## 2026-10-06 - Index strings execution start

- Approved plan: sandbox/plans/index-strings-20261006-0408-plan.md
- Phase 1: index bar, menus, dialogs, empty text, and the new-mail pill come from strings.xml. Wording stays the same, except one versus many for the pill and the thread warning. Filter kinds, start-rule labels, and relative dates stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Index strings phase 1

- Index bar, menus, dialogs, empty text, and the new-mail pill come from strings.xml. Wording stays the same except one versus many for the pill and the thread warning.
- emptyIndexText takes the empty sentence and the query format. A blank query returns the sentence. Any other query uses the format with the query and the mailbox. Filter kinds, start-rule labels, and relative dates stay.
- MessageIndexScreen.kt has no Text(". No install.

## 2026-10-06 - Index strings phase 1 test gate

- IndexWindowTest at 2026-10-06 04:20:45 MST: 54 tests, 1 failed, 0 errors, 0 skipped. The failure is the known orderedSubjectHiddenUnlessAdvertised at IndexWindowTest.kt:175. emptyIndexTextBlankAndQuery passed. No install.
- assembleDebug follows so the builds tag can move. The earlier testDebugUnitTest exit left the tag unmoved.

## 2026-10-06 - Known test failures execution start

- Approved plan: sandbox/plans/known-test-failures-20261006-1144-plan.md
- Phase 1: sortKeyAdvertised becomes internal; rename orderedSubjectStaysListedWhenNotAdvertised; defaultsRoundTrip matches a default encode(); omit comment only. Menu behavior and encoded defaults stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Known test failures phase 1

- sortKeyAdvertised is internal. The menu still lists every SortKey and disables a missing capability. orderedSubjectStaysListedWhenNotAdvertised checks listing and advertisement separately.
- defaultsRoundTrip lists the keys a default encode writes. completionSources, addressBookHistory, and addressBookNeverTrim stay omitted. The omit comment names those three defaults. Encoded defaults are unchanged. No install.

## 2026-10-06 - Reader strings execution start

- Approved plan: sandbox/plans/reader-strings-20261006-1150-plan.md
- Phase 1: reader buttons, snacks, dialogs, and header labels come from strings.xml. Wording stays the same. Action, body-view, sort, and opposite-forward labels stay. The charset note stays. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Reader strings phase 1

- Reader buttons, snacks, dialogs, and header labels come from strings.xml. Wording stays the same.
- Without UIDPLUS, the expunge dialog is reader_expunge_body, a space, then reader_expunge_others. recipientLine uses reader_to, reader_cc, or reader_to_cc. The share chooser title is reader_share. The five not-connected snacks and banners use reader_not_connected. No app found uses reader_no_app.
- Action, body-view, sort, and opposite-forward labels stay. Snack modes retry and undo stay. The charset note stays. MessageReaderScreen.kt has no Text(", no No app found, and no not connected literal. No install.

## 2026-10-06 - ESEARCH filter results execution start

- Approved plan: sandbox/plans/esearch-filter-results-20261006-1245-plan.md
- Phase 1: takeEsearch returns the libetpan ESEARCH ids when the LiveIMAP list is absent. Steal msg_list before the result is freed. MIN and MAX stay one id. An empty match stays empty. sendEsearch and libetpan stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - ESEARCH filter results phase 1

- takeEsearch still returns a detached LIVE_ESEARCH list when that slot is filled.
- Otherwise, when the extension is mailimap_extension_esearch, it steals msg_list and sets that field to null before freeExtensionList. A null msg_list starts empty. An empty list appends min when has_min and max when has_max.
- The list uidArray receives is still uint32_t values. sendEsearch is unchanged. libetpan is unchanged. No install.

## 2026-10-06 - Compose strings execution start

- Approved plan: sandbox/plans/compose-strings-20261006-1255-plan.md
- Phase 1: compose buttons, field labels, titles, and notices come from strings.xml. Wording stays the same. Existing reader and unsent names are reused. oppositeForwardLabel stays. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Compose strings phase 1

- Compose buttons, field labels, titles, and notices come from strings.xml. Wording stays the same.
- composeTitle takes Reply, Reply all, Forward, and Compose. Bounce, a new message, and a resumed draft use Compose. The send icon is compose_send, or reader_retry when the held copy is append-only.
- The Unsent button compares the notice and status with compose_accepted and unsent_not_sent. forwardHeaderBody takes the header already formatted with compose_quote_header. The leading newline stays at the call. oppositeForwardLabel is unchanged.
- ComposeScreen.kt has no Text(", no contentDescription = ", no notice = ", and no status = ". No install.

## 2026-10-06 - Settings strings execution start

- Approved plan: sandbox/plans/settings-strings-20261006-1310-plan.md
- Phase 1: settings screen titles, fields, buttons, and notices come from strings.xml. Wording stays the same except settings_expanded, settings_omitted, and settings_sources. Shared label functions stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Settings strings phase 1

- Settings screen titles, fields, buttons, and notices come from strings.xml. Wording stays the same except settings_expanded, settings_omitted, and settings_sources.
- SettingsGroup.title reads that group's resource. Compose uses compose_new. Debug uses settings_debug. Shared label results are still passed into the summaries. One omitted pinerc line uses the singular settings_omitted.
- SettingsScreen.kt has no Text(" and no contentDescription = ". No install.

## 2026-10-06 - Shared labels execution start

- Approved plan: sandbox/plans/shared-labels-20261006-1323-plan.md
- Phase 1: the index, the reader, and settings show sort, start-rule, swipe, theme, date, density, body-view, reader-action, and forward-style names from strings.xml. Wording stays the same. Existing compose, delete, and forward-attachment names are reused. Pure label functions stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Shared labels phase 1

- The index, the reader, and settings show sort, start-rule, swipe, theme, date, density, body-view, reader-action, and forward-style names from strings.xml. Wording stays the same.
- Reused compose_reply, compose_reply_all, compose_forward, compose_bounce, compose_to, compose_cc, compose_subject, drawer_delete, and settings_forward_attachment. label_recent_note keeps the leading backslash. label_alpine_default keeps the apostrophe. label_open_at keeps the ellipsis and replaces the two index sentences that already said that.
- ChoiceField's name callback is composable. The pure label functions, recentRuleNote, openAtMenuText, and oppositeForwardLabel stay. PinercImport, AccountSettings label bodies, ComposeScreen, and the tests were not edited. The index sort menu still uses the shorter index_sort names. No install.

## 2026-10-06 - Contact copy strings execution start

- Approved plan: sandbox/plans/contact-copy-strings-20261006-1331-plan.md
- Phase 1: the contact-copy screen’s buttons, notices, and field labels come from strings.xml. Wording stays the same. Existing back, confirm, Pine, contacts-denied, and not-connected names are reused. Query clauses and the photo marker stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Contact copy strings phase 1

- The contact-copy screen buttons, notices, and field labels come from strings.xml. Wording stays the same.
- Reused reader_back, settings_confirm, settings_pine, settings_contacts_denied, and reader_not_connected. copy_reading and copy_to keep the ellipsis. copy_name_email is one space between the two values.
- contactLabel and phoneLabel are not composable and read the words with context.getString. connectAccount receives the not-connected words. Query clauses and the photo marker stay. ContactCopyScreen.kt has no Text(". No install.

## 2026-10-06 - Chrome strings execution start

- Approved plan: sandbox/plans/chrome-strings-20261006-1505-plan.md
- Phase 1: the mailbox chooser, address-book picker, connection status, and up button read their words from strings.xml. Wording stays the same. reader_retry, reader_not_connected, and compose_close are reused. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Chrome strings phase 1

- The mailbox chooser, address-book picker, connection status, and up button read their words from strings.xml. Wording stays the same.
- Added chooser_title, picker_missing, status_reconnecting, and scaffold_up at the end. status_reconnecting keeps the ellipsis. Reused reader_retry, reader_not_connected, and compose_close.
- The four Kotlin files have no Text(" and no contentDescription = ". Expander marks and the address label template stay. No install.

## 2026-10-06 - Index date strings execution start

- Approved plan: sandbox/plans/index-date-strings-20261006-1512-plan.md
- Phase 1: relative index dates and the bad custom pattern come from strings.xml. The sentences stay the same, including min for one minute and for several. The 45-second, 7-day, and hour boundaries stay. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Index date strings phase 1

- Relative index dates and the bad custom pattern come from strings.xml. The sentences stay the same, including min for one minute and for several.
- formatIndexDate takes the nine words after its current arguments. One minute and several minutes both use index_min. One hour uses index_hour. Several hours use index_hours. Days follow the same split. A past time uses index_ago. A future time uses index_ahead. An empty or illegal custom pattern uses index_bad_pattern.
- The 45-second, 7-day, and hour boundaries stay. Local and short patterns stay in the formatter. The index and the reader pass the resource values. DateFormatTest passes the same English words. Expected sentences stay. No install.

## 2026-10-06 - Pinerc preview strings execution start

- Approved plan: sandbox/plans/pinerc-strings-20261006-1523-plan.md
- Phase 1: the pinerc preview labels and skip sentences come from strings.xml. The wording stays the same. Pinerc keys stay. The sort and start-rule functions still supply the values. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Pinerc preview strings phase 1

- The pinerc preview labels and skip sentences come from strings.xml. The wording stays the same.
- pinercPreview takes the phrases. sortKeyLabel and startRuleLabel still produce the values. The settings screen passes the resource strings. PinercImportTest and AlpineBookWriteTest pass the same English through previewPinerc. Expected sentences stay, including Display name: Old → Ada. Pinerc keys stay.
- PinercImport.kt has no TLS is not supported literal. No install.

## 2026-10-06 - Rotation state execution start

- Approved plan: sandbox/plans/rotation-state-20261006-1624-plan.md
- Phase 1: hold the folder list, the index, the reader, and the mailbox chooser in navigation-entry viewModel() instances so rotation reuses them and does not refetch. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Rotation state phase 1

- Folder list, index, reader, and mailbox chooser keep their models in viewModel() instances owned by the navigation entry.
- The index holds the model, rows, selected UIDs, and the first visible UID with its pixel offset. The folder list and the chooser hold the folder model and rows. The reader holds the fetched body for the open UID and that body's scroll offset.
- Those load effects skip loadLevel, the index window fetch, and the body fetch when that held state is already loaded. A new process still starts empty. rememberSaveable fields stay. No configChanges, no orientation lock, no install.

## 2026-10-06 - Session select and examine execution start

- Approved plan: sandbox/plans/session-select-examine-20261006-1653-plan.md
- Phase 1: a read sends EXAMINE. SELECT is only the read-write upgrade or a stale resync of a mailbox that is already read-write. The watch connection examines, then idles. NAMESPACE is once per connection. A password change logs in again. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Session select and examine phase 1

- A read sends EXAMINE. SELECT is the read-write upgrade, or a stale resync of a mailbox that is already read-write. The same mailbox with fresh sequences and a sufficient mode sends neither.
- The watch connection sends EXAMINE, then IDLE, and not QRESYNC. NAMESPACE stays on the session. login and close clear it. A kept session does not.
- sameImapIdentity takes both passwords. A different password logs in again. A different display name does not. The password is not stored on AccountSettings and is not written to the traffic log.
- Leaving a mailbox sends no CLOSE. UNSELECT clears the open mailbox. Without UNSELECT, unselect examines the same mailbox and leaves it read-only. No install.

## 2026-10-06 - Capability flags execution start

- Approved plan: sandbox/plans/capability-flags-20261006-1711-plan.md
- Phase 1: one Capabilities object from the login line. IMAP4rev1 or IMAP4rev2 connects. A missing extension skips that command. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Capability flags phase 1

- IMAP4rev1 or IMAP4rev2 connects. A line with neither is rejected. Missing IDLE, SORT, NAMESPACE, or UIDPLUS still connects.
- Capabilities.parse is the capability-line split. The session stores that object. Sort, thread, idle, and unselect read it. Without NAMESPACE, LIST "" "" supplies one personal delimiter and does not list %.
- An unadvertised saved sort or thread opens at Arrival and does not ask the thread question. The menu still lists the key as not advertised. UIDPLUS, MOVE, and ESEARCH in the index, reader, and address book read featureCaps.
- requiredCapabilities stays so ServerProbe can compile. The gate does not read it. No install.

## 2026-10-06 - Slower fallbacks execution start

- Approved plan: sandbox/plans/slower-fallbacks-20261006-1732-plan.md. Mailbox SEQ 38 IMPLEMENT is the approval. Status set to APPROVED.
- Phase 1: settings, the hide set, polling, the STATUS gate, and the help paragraph. Phase 2: client sort, client thread, and the 5000 dialog. No install.
- First action per standard-plan-compliance-block.md. Base builds tag a8630ff. Commits stay on master.

## 2026-10-06 - Slower fallbacks phase 2

- Client sort sends one UID FETCH of UID plus one field and sorts in Kotlin. Client thread sends one UID FETCH of Message-ID, References, In-Reply-To, and Subject with BODY.PEEK.
- A folder over 5000 messages asks before that download. Continue runs the client call. Cancel leaves the view at Arrival.
- SerialMailSession forwards clientOrder and clientThread so the open session reaches LibetpanMailSession. No install.

## 2026-10-06 - Delete and expunge execution start

- Approved plan: sandbox/plans/delete-and-expunge-20261006-1819-plan.md. Mailbox SEQ 39 IMPLEMENT is the approval. Status set to APPROVED.
- Phase 1: settings, moveCommandKind, CLOSE on leave and background close, trash resolution, and the help paragraph. Phase 2: delete policy on the selection bar, swipe, reader, both menus, Expunge, and Folder info. No install.
- First action per standard-plan-compliance-block.md. Base builds tag 0436609. Commits stay on master.

## 2026-10-06 - Delete and expunge phase 1

- Settings omit autoExpunge, deletePolicy, moveMethod, and trashMailbox at their defaults. Missing lines decode as off, Mark as deleted, Copy then mark deleted, and empty.
- moveCommandKind returns Move only when the method is IMAP MOVE and MOVE is advertised. Both copy methods pass that. A null account stays CopyThenDelete.
- CLOSE is sent only for Auto-expunge on a read-write selected mailbox, on folder change, unselect, and background close. A failed background CLOSE still closes the socket. No plain EXPUNGE.
- knownTrash uses the saved Trash mailbox, or one LIST of each personal namespace when LIST-EXTENDED is advertised. It caches that result, including empty, until close. It does not guess a name.
- Confirm before expunge, Auto-expunge, Delete button, Move method, and Trash are in Settings. The help paragraph is last. No install.

## 2026-10-06 - Delete and expunge phase 2

- The selection trash icon, reader Delete, and swipe Delete use effectiveDeletePolicy. The other two policies are on the selection menu and the reader more menu when they are possible. A fallen-back policy is not listed again.
- Delete permanently asks before STORE when Confirm is on, then STORE \Deleted and UID EXPUNGE. All-mailbox uses uidExpungeDeleted. Without UIDPLUS neither expunge method runs.
- Expunge… is on the sort menu after the sort items, then Select all, then Folder info. The list-column button is gone. Forward as attachment stays on the selection menu. Index Back calls expungeOnLeave. Reader Back does not. No install.

## 2026-10-06 - Index layout and sort execution start

- Approved plan: sandbox/plans/index-layout-and-sort-20261006-1855-plan.md
- SEQ 40. Phase 1: fixed status slots, EXISTS sequence width, date-format width. Phase 2: session sort, menu, subtitle, reader items, thread count, null-parent siblings, thread index setting, help paragraph.
- No install. Builds tip at start is 66cc2b2.

## 2026-10-06 - Index layout and sort phase 1

- Status slots stay 16 dp, 10 dp, and 16 dp. A selected row has no check icon. The row background and selected semantics stay.
- Sequence width uses existsColumnChars(folderExists). 999 is 3 digits and 1000 is 4. sequenceColumnChars stays. Opening copies selected EXISTS when the ask path did not.
- Date width uses dateColumnSamples for the format. Loaded row dates are not measured.

## 2026-10-06 - Index layout and sort phase 2

Session sort stays in memory. The index menu is Newest, Oldest, then the criteria, with a quiet sort line under the mailbox title. The reader more menu no longer changes the sort. A thread count sits on the root row. A null-uid parent is siblings at that level. Thread index is Expanded or Collapsed and is omitted from encode when Expanded.

## 2026-10-06 - Simple search bar execution start

- Approved plan: sandbox/plans/search-simple-bar-20261006-1924-plan.md. Mailbox SEQ 41 IMPLEMENT is the approval. Status set to APPROVED.
- Phase 1: the search bar field name, SimpleSearchField, searchCriterion from the simple search, charset only when a code point is above 127, the Participating string, and the help paragraph. No install.
- First action per standard-plan-compliance-block.md. Base builds tag dfd461f. Commits stay on master.

## 2026-10-06 - Simple search bar phase 1

- The open bar is the field name and the text field. The names are Subject, From, To, Cc, and Participating. Choosing a name does not search. IME Search passes the held field. There is no Advanced button and no scope control. The filter menu stays.
- searchField is on IndexScreenHeld. It is not rememberSaveable. bind and dropLoaded leave it. A new process starts at Subject. It is not written to settings.
- applySearch stores the query and the field. fetchSearch calls searchCriterion with that field name. Filter save and restore keeps the field. jumpToNewest repeats it. The simple search does not call searchText.
- searchNeedsCharset is false for ASCII and true when a code point is above 127. completeSearch sends CHARSET UTF-8 only when that flag is true. nativeSearchText still builds TEXT and still sends CHARSET UTF-8.
- The visible word is Participating. The help paragraph is last. No install.

## 2026-10-06 - Advanced search page execution start

- Approved plan: sandbox/plans/search-advanced-page-20261006-1942-plan.md. Mailbox SEQ 42 IMPLEMENT revision 1 is the approval. Status set to APPROVED.
- Phase 1: the Advanced page for the current folder, one combined search, Body, sort and select-all, the help paragraph, and the tests. No install.
- First action per standard-plan-compliance-block.md. Base builds tag d6ad37c. Commits stay on master.

## 2026-10-06 - Advanced search page phase 1

- The open bar gains Advanced after the text field. It opens search/{mailbox} for the current folder. The filter menu stays. There is no scope control.
- The page starts on And, with one Subject row and Not off. Add term adds a Subject row. Body and Full text show the cost sentence and are not the starting field. Search sends one criterion of kind Advanced. A blank valued row is dropped. The form is not saved.
- A sort keeps that search. A simple search clears it. Select all during a search selects the search uids. Body uses the BODY key. One folded key is sent. No install.

## 2026-10-06 - Search scope and count execution start

- Approved plan: sandbox/plans/search-scope-and-count-20261006-2001-plan.md
- Phase 1: scope choice, per-folder search, Count without FETCH, folder name on the row, cancel, and the tests. No ESEARCH IN. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-06 - Search scope and count phase 1

- Scope is This folder, This folder and below, Subscribed, or All folders. This folder starts selected and is not saved. Search stores advancedScope with the encoded text.
- A wider search walks one folder at a time, can cancel, reports Folder N of M, names the folder on the row, and selects the index mailbox again. Current stays one search and does not list children.
- Count uses searchCount and does not fetch. ESEARCH sends RETURN (COUNT). A failed select is skipped. No ESEARCH IN. No install.

## 2026-10-06 - Search scope and count phase 1 layout

- The scope chips use FlowRow with the same ExperimentalLayoutApi opt-in as the other chip rows. No install.

## 2026-10-06 - ESEARCH IN execution start

- Approved plan: sandbox/plans/search-esearch-in-20261006-2037-plan.md
- Mailbox SEQ 44 IMPLEMENT revision 1 is the approval. Status set to APPROVED.
- Phase 1: MULTISEARCH flag, ESEARCH IN for Subtree, Subscribed, and Personal All, correlator parser, count sum, and the tests. The per-folder loop stays when MULTISEARCH is absent and when All includes a namespace that is not Personal. No install.
- First action per standard-plan-compliance-block.md. Base tip d5891cc. Commits stay on master.

## 2026-10-06 - ESEARCH IN phase 1

- MULTISEARCH is a capability flag. Cyrus is false. Moon is true. The fixture lines are unchanged.
- A wider search with MULTISEARCH sends one ESEARCH IN. Subtree names the home mailbox. Subscribed uses subscribed. All uses personal only when every namespace is Personal. Any other namespace stays on the folder loop. Current stays one search.
- Count sends RETURN (COUNT) and sums the per-mailbox counts. The result list sends RETURN (ALL). There is no SELECT before that command. CapabilityGateTest 7 and IndexWindowTest 75 passed. Native assembleDebug compiled. No install.

## 2026-10-06 - index toolbar customize execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-index-bar-20261006-2102-plan.md
- Status set to APPROVED
- builds before edit: 40bf7a0
- Phase 1 of 1: index bar layout for Refresh, Search, and Filter. Sort stays pinned. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - index toolbar customize phase 1

- IndexBarLayout keeps Refresh, Search, and Filter in exactly one section. Sort is not an action and stays pinned on the bar
- A default encode omits indexBar. defaultsRoundTrip keeps its key list. A repeated, unknown, or missing action throws bad indexBar
- The Sort menu ends with Customize toolbar…. That screen moves the three onto the bar, into the menu, or out of sight, and saves indexBar with the account
- No install

## 2026-10-06 - selection toolbar customize execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-selection-bar-20261006-2123-plan.md
- Status set to APPROVED
- Mailbox SEQ 46 IMPLEMENT revision 1. builds before edit: cf2a6da
- Phase 1 of 1: selection bar for Mark read, Flag, Move, and Delete. More stays pinned. The index bar stays. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - selection toolbar customize phase 1

- SelectionBarLayout keeps Seen, Flag, Move, and Delete in exactly one section. More is not an action and stays pinned
- A default encode omits selectionBar. defaultsRoundTrip keeps its key list. A repeated, unknown, or missing action throws bad selectionBar
- The selection More menu ends with Customize toolbar…. That screen moves the four onto the bar, into the menu, or out of sight, and saves selectionBar. toolbar/index still saves indexBar only
- No install

## 2026-10-06 - folder toolbar customize execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-folder-bar-20261006-2135-plan.md
- Status set to APPROVED
- Mailbox SEQ 47 IMPLEMENT revision 1. builds before edit: d899a40
- Phase 1 of 1: folder bar for Refresh, Collapse all, Save as default view, and Reset to default view. More stays pinned. The index and selection bars stay. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - folder toolbar customize phase 1

- FolderBarLayout keeps Refresh, Collapse all, Save as default view, and Reset to default view in exactly one section. Unsent is not an action. More stays pinned
- A default encode omits folderBar. defaultsRoundTrip keeps its key list. A repeated, unknown, or missing action throws bad folderBar
- The folder list More menu ends with Customize toolbar…. That screen moves the four onto the bar, into the menu, or out of sight, and saves folderBar. toolbar/index still saves indexBar only. toolbar/selection still saves selectionBar only
- No install

## 2026-10-06 - reader toolbar customize execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-reader-bar-20261006-2149-plan.md
- Status set to APPROVED
- Mailbox SEQ 48 IMPLEMENT revision 1. builds before edit: bed945c
- Phase 1 of 1: reader bar for Refresh and the message actions. Until a layout is saved it follows the Message bar list. More stays pinned. readerBar encoding stays. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - reader toolbar customize phase 1

- ReaderToolbarLayout keeps Refresh and the message actions in exactly one section. Until readerToolbar is saved it follows readerBar. Spam is only a display filter. More stays pinned
- A default encode omits readerToolbar. defaultsRoundTrip keeps its key list. readerBar stays. A repeated, unknown, or missing action throws bad readerToolbar
- The reader More menu ends with Customize toolbar…. That screen moves Refresh and the message actions onto the bar, into that menu, or out of sight, and saves readerToolbar. The other toolbar routes stay on their own layouts
- No install

## 2026-10-06 - compose toolbar customize execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-compose-bar-20261006-2202-plan.md
- Status set to APPROVED
- Mailbox SEQ 49 IMPLEMENT revision 1. builds before edit: cd5f298
- Phase 1 of 1: compose bar for Postpone. Send and More stay pinned. Bounce keeps an empty action row. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - compose toolbar customize phase 1

- ComposeBarLayout keeps Postpone in exactly one section. The default leaves the toolbar empty and puts Postpone in the menu. Send and More stay pinned. Bounce keeps an empty action row
- A default encode omits composeBar. defaultsRoundTrip keeps its key list. A repeated, unknown, missing, or badly shaped value throws bad composeBar
- The compose More menu ends with Customize toolbar…. That screen moves Postpone onto the bar, into that menu, or out of sight, and saves composeBar. The other toolbar routes stay on their own layouts
- No install

## 2026-10-06 - toolbar drag execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-drag-20261006-2219-plan.md
- Status set to APPROVED
- Mailbox SEQ 50 IMPLEMENT revision 1. builds before edit: e20659b
- Phase 1 of 1: drag a row in the shared toolbar editor. Move up, Move down, and Move to stay. No wrap and no TalkBack custom actions. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - toolbar drag phase 1

- Customize toolbar… can drag a row within and between Toolbar, Overflow, and Hidden. The drag saves the same layout the Move buttons already save. Reset still restores that screen's default
- Move up, Move down, and Move to stay. Reader drag reads effectiveReaderToolbar and saves readerToolbar. A drag on one screen does not change another screen's layout
- reorderable 3.1.0 is the only new dependency. NOTICES.txt says 115 artifacts and 113 under Apache-2.0, and lists that artifact once. No install

## 2026-10-06 - toolbar wrap execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-wrap-20261006-2235-plan.md
- Status set to APPROVED
- Mailbox SEQ 51 IMPLEMENT revision 1. builds before edit: f8de69f
- Phase 1 of 1: wrap toolbar icons onto another row. Sort, More, and Send stay on the bar. No TalkBack custom actions. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - toolbar wrap phase 1

- A full toolbar wraps onto another row. The last row scrolls when the chosen cap is too small. No action is dropped into the overflow
- Toolbar rows defaults to 2 and is omitted from a default encode. A value outside 1..4 throws bad toolbarRows. The editor stepper saves toolbarRows only and warns with a 3-slot budget
- Sort, More, and Send stay on the bar. The disconnected index Refresh stays one icon. Bounce still draws neither Send nor More. No TalkBack custom actions. No install

## 2026-10-06 - toolbar talkback execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-talkback-20261006-2254-plan.md
- Status set to APPROVED
- Mailbox SEQ 52 IMPLEMENT revision 1. builds before edit: bb85b3b
- Phase 1 of 1: TalkBack custom actions on the shared toolbar editor. Move up, Move down, and Move to stay. A move is announced. No Material default change. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - toolbar talkback phase 1

- TalkBack moves a Customize toolbar… row with Move up, Move down, and Move to. The drag handle stays out of the focus order. The Move buttons stay, at least 48.dp, and the Toolbar rows steppers do too
- A move is announced in a polite live region. Move up says Moved Search up. Move to Overflow says Moved Search to Overflow. A drag that lands Filter in Hidden says Moved Filter to Hidden. A header drag does not change the announcement
- dragLanding of the index default from 2 to 3 is Search in Toolbar, from 3 to 5 is Filter in Hidden, and from 0 to 3 is null. No bar default, toolbarRows, or pinned control changed. No install

## 2026-10-06 - toolbar material defaults execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/toolbar-material-defaults-20261006-2310-plan.md
- Status set to APPROVED
- Mailbox SEQ 53 IMPLEMENT revision 1. builds before edit: d9fb7d6
- Phase 1 of 1: index default is Search, selection default is Delete, reader default is Reply and Delete. Bounce starts in the reader overflow. Folder and compose defaults stay. No new index action. No install
- Host: /home/dlang/git/liveimap/master. This tree's ./build_app runs assembleDebug. Do not deploy
- First action per standard-plan-compliance-block.md

## 2026-10-06 - toolbar material defaults phase 1

- An untouched index bar shows Search, with Refresh and Filter in the overflow. An untouched selection shows Delete, with Seen, Flag, and Move in the overflow. An untouched reader shows Reply and Delete, with Refresh, Reply all, Forward, Move, Spam, and Bounce in the overflow
- resetReaderToolbar is that reader layout. A null readerToolbar with the default readerBar uses it. Any other null readerBar still follows readerToolbarFrom. A saved readerToolbar still wins. readerBar encoding is unchanged
- An omitted indexBar or selectionBar decodes to the new default. T:Refresh,Search,Filter|O:|H: and T:Seen,Flag,Move,Delete|O:|H: still decode to those toolbars. defaultsRoundTrip still ends at altAddresses. Folder and compose defaults stay. No install

## 2026-10-06 - Jump to sequence execution start

- Approved plan: sandbox/plans/alpine-jump-20261006-2321-plan.md
- SEQ 54 REVISION 1. Phase 1: jumpToSequence, Sort menu dialog, strings, help sentence, and tests.
- No install. Builds tip at start is 0670e5a.

## 2026-10-06 - Next unread execution start

- Approved plan: sandbox/plans/alpine-next-unread-20261006-2333-plan.md
- SEQ 55 REVISION 1. Phase 1: nextUnread, Sort menu row, strings, help sentence, and tests.
- No install. Builds tip at start is 4673e1f.

## 2026-10-06 - Next unread phase 1

- nextUnread searches UNDELETED UNSEEN. Arrival chooses the next sequence in the current direction and calls jumpToSequence. Sorted, filtered, search, and thread views walk forward from the first visible row.
- The connected Sort menu lists Next unread after Jump to number…. No later unread says "No unread messages" and stays in this folder. A search failure keeps the rows and shows that notice.
- No install.

## 2026-10-06 - Next folder execution start

- Approved plan: sandbox/plans/alpine-next-folder-20261006-2355-plan.md
- SEQ 56 REVISION 1. Phase 1: nextUnseenFolder, unseenFolderOrder, confirm dialog, navigation callbacks, strings, help sentence, and tests.
- No install. Builds tip at start is 1db15ad.

## 2026-10-06 - Next folder phase 1

- Next unread with no later message in this folder asks before opening the next folder that has unread mail.
- Go opens that index. Cancel stays. No unread folder still says "No unread messages".
- No install.

## 2026-10-07 - Save copy execution start

- Approved plan: sandbox/plans/alpine-save-copy-20261007-0009-plan.md
- SEQ 57 REVISION 1. Phase 1: Copy kind, copyUids, reader Save row, snack string, help sentence, disconnected test.
- No install. Builds tip at start is b4e35d9.

## 2026-10-07 - Save copy phase 1

- Kind Copy is UID COPY and does not store Deleted. Move still UID MOVEs. Other kinds still copy and then store Deleted.
- Reader More Save copies the open message into the chosen folder and stays. Saved is the success text. A copy failure shows that failure text.
- No install.

## 2026-10-07 - Saved mailbox execution start

- Approved plan: sandbox/plans/alpine-save-folder-20261007-0017-plan.md
- SEQ 58 REVISION 1. Phase 1: savedMailbox next to trash, settings row after Trash, reader Save confirm, strings, help sentence, round-trip test.
- No name rule. No install. Builds tip at start is fc81319.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-07 - Saved mailbox phase 1

- savedMailbox sits next to trashMailbox. Empty is omitted from encode. A missing key decodes as "". Saved Mail round-trips as savedMailbox=Saved%20Mail. defaultsRoundTrip still ends at altAddresses.
- The special-use section shows Saved mailbox after Trash. Clearing the row stores "". mailboxSetCount is unchanged.
- When a saved mailbox is set, Save asks before copying there. Save uses copyUids and leaves the original. Choose opens the folder list. Cancel, dismiss, and Back do not copy. An empty setting still opens the folder list.
- No name rule. No install.

## 2026-10-07 - Save name rule execution start

- Approved plan: sandbox/plans/alpine-save-name-20261007-0026-plan.md. Mailbox SEQ 59 IMPLEMENT is the approval. Status set to APPROVED.
- Phase 1: SaveNameRule, saveFolderName, the settings choice, the reader offer, the last-folder store, the strings, the help sentence, and the tests. No install.
- First action per standard-plan-compliance-block.md. Base builds tag 4ba5f81. Commits stay on master.

## 2026-10-07 - Save name rule phase 1

- SaveNameRule is Default folder, By From, By Sender, By recipient, or Last folder used. Default folder and an empty last folder are omitted from encode. A missing key decodes as the default.
- saveFolderName offers the saved mailbox, the last folder, or the local part of From, Sender, or the recipient. Ada <Ada@Example.com> is ada. bob!ann%extra@host is ann.
- The choice sits after Saved mailbox. Save asks with that name, or opens the folder list when the name is empty. A successful copy stores the destination as the last folder. Cancel does not. No install.

## 2026-10-07 - Take address execution start

- Approved plan: sandbox/plans/alpine-take-address-20261007-0042-plan.md. Mailbox SEQ 60 REVISION 1 IMPLEMENT is the approval. Status set to APPROVED.
- Phase 1: takeAddresses, bookHasAddress, the reader Take address menu and confirm, the strings, the help sentence, and the tests. No install. Do not mark messages read.
- First action per standard-plan-compliance-block.md. Base builds tag 4007657. Commits stay on master.

## 2026-10-07 - alpine mark all read execution start

- Approved plan: sandbox/plans/alpine-mark-all-read-20261007-0053-plan.md
- Work: Mark all read in the connected index Sort menu, after confirm, stores \Seen on the whole folder
- Phase 1 only. Do not expunge. Do not install. Take address at 7a92dca stays underneath.

## 2026-10-07 - pinerc expunge row execution start

- Approved plan: sandbox/plans/alpine-pinerc-expunge-20261007-0101-plan.md
- Mailbox SEQ 62 REVISION 1 IMPLEMENT. Status set to APPROVED.
- Phase 1: offerAutoExpunge, pinercApplied, the import checkbox, settings_import_auto_expunge, and PinercImportTest.
- Do not import folder names. Do not install. Base builds tag ac1c2c7. Mark all read stays underneath.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-07 - pinerc expunge row phase 1

- offerAutoExpunge is true only when the feature list sets askBeforeExpunge false. pinercPreview does not change autoExpunge.
- pinercApplied turns autoExpunge on only when the row is offered and ticked. An untick leaves it unchanged. A tick when the row is not offered does nothing.
- The import dialog shows "Also turn on Auto-expunge?" under the change rows. It starts unticked for each new preview. Apply persists pinercApplied and is enabled when that result differs. Cancel discards the tick.
- The confirm row string stays "Confirm before expunge". No folder import. No install.

## 2026-10-07 - pinerc folder names execution start

- Approved plan: sandbox/plans/alpine-pinerc-folders-20261007-0114-plan.md. Mailbox SEQ 63 REVISION 1 IMPLEMENT. Status set to APPROVED.
- Phase 1: same-server folder-collections prefix, other-host skip, braced slash mailbox, inbox-path without braces, phrases, and tests.
- Do not LIST. Do not import incoming folders. Do not change smtp-server. Do not install. Base builds tag 37b2bcc. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - pinerc folder LIST execution start

- Approved plan: sandbox/plans/alpine-pinerc-list-20261007-0130-plan.md. Mailbox SEQ 64 REVISION 1 IMPLEMENT. Status set to APPROVED.
- Phase 1: LIST Sent, Postponed, and address-book names before the import dialog. A missing mailbox is dropped. A failed check drops those changes with one reason.
- Do not import incoming folders. Do not change smtp-server. Do not install. Base builds tag 6dad25d. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - pinerc folder LIST phase 1

- mailboxListed asks LIST for one exact name. An empty OK list is missing. \Noselect is missing. INBOX matches ignoring case. nativeListLevel stays %.
- A missing Sent, Postponed, or address-book name is put back and reported. A failed check drops those changes once, with no missing-folder line.
- The import dialog stays closed until that check finishes. A newer read discards the older result. pinercPreview does not open a session.
- No incoming-folder import. smtp-server is unchanged. No install.

## 2026-10-07 - Import pinerc incoming-folders as favorites

- Execute alpine-pinerc-incoming-20261007-0151-plan.md from step 1 on master at 7d224d1.
- incoming-folders become extra leaf favorites. LIST each new name. Do not import stay-open-folders. Do not install.

## 2026-10-07 - SMTP EHLO and quoted-printable execution start

- Approved plan: sandbox/plans/smtp-ehlo-20261007-0212-plan.md. Mailbox SEQ 66 REVISION 1 IMPLEMENT. Status set to APPROVED.
- Phase 1: EHLO, HELO only for the three refusal codes, 8bitmime before MAIL, quoted-printable when 8-bit is off or a line exceeds 998, and one ComposeScreen retry.
- No STARTTLS. No AUTH. No install. Base builds tag 38fec60. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - SMTP EHLO and quoted-printable phase 1

- nativeSmtp sends EHLO. HELO runs only after NOT_IMPLEMENTED, UNEXPECTED_CODE, or ACTION_NOT_TAKEN. Any other EHLO result fails with the host prefix and does not send HELO.
- When 8BITMIME is absent, a Content-Transfer-Encoding: 8bit header throws 8bitmime before MAIL. ComposeScreen retries that failure once with withoutEightBit and does not keep a device copy for the first failure.
- A text part stays 8bit or 7bit only when 8-bit is allowed and no line exceeds 998 octets. Otherwise it is quoted-printable, with lines of at most 76 octets including CRLF. Attachments stay base64. Bcc stays off the message.
- No STARTTLS. No AUTH. No install.

## 2026-10-07 - SMTP wrap column execution start

- Approved plan: sandbox/plans/smtp-wrap-column-20261007-0236-plan.md. Mailbox SEQ 67 REVISION 1 IMPLEMENT. Status set to APPROVED.
- Phase 1: composerWrapColumn default 74, Sending wrap-column row, wrapPlain before textPart, assemble passes the setting, tests, and the help sentence.
- No format=flowed. Do not import composer-wrap-column. No STARTTLS. No AUTH. No install. Base builds tag 55bd748. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - SMTP wrap column phase 1

- composerWrapColumn defaults to 74 and is omitted from encode. A stored value is coerced into 0..998. The constructor rejects anything outside that range.
- Settings, Sending has a Wrap column row after the SMTP port. Empty text, a non-digit, or a value outside 0..998 restores the stored number and does not persist. The section summary stays the SMTP host and port.
- wrapPlain breaks plain text on spaces and tabs at the column, repeats a > quote prefix, and leaves a long word whole. Column 0 does not insert breaks. buildPlain wraps before the existing 998 rule. No format=flowed. Bounce is unchanged.
- No composer-wrap-column import. No STARTTLS. No AUTH. No install.

## 2026-10-07 - ReaderHeld visibility execution start

- Approved plan: sandbox/plans/reader-held-visible-20261007-0256-plan.md. Mailbox SEQ 68 REVISION 1 IMPLEMENT. Status set to APPROVED.
- Phase 1: change private class ReaderHeld to internal class ReaderHeld. No-arg constructor, properties, dropLoaded, and the viewModel key stay.
- Do not edit Index, folder, or mailbox-chooser holders. Do not install. Base builds tag 20bce7d. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - ReaderHeld visibility phase 1

- ReaderHeld is internal class ReaderHeld : ViewModel(). The no-arg constructor, properties, dropLoaded, and viewModel key reader:$mailbox:$uid are unchanged.
- IndexScreenHeld, FolderScreenHeld, and MailboxChooserHeld were not edited.
- No install.

## 2026-10-07 - ReaderHeld helper types visible in the module

- The internal ReaderHeld class cannot expose file-private Utf8Carry or AttachmentRow. Those two types in MessageReaderScreen.kt are internal. ReaderHeld properties, dropLoaded, and the viewModel key are unchanged.
- First compile of the visibility change failed on those two exposures. No install.

## 2026-10-07 - inbound rules sieve emitter execution start

- Approved plan: sandbox/plans/inbound-rules-emitter-20261007-0305-plan.md
- Phase 1: emit Sieve script text from inbound rules. No connect, no upload, no install.
- Host worktree: /home/dlang/git/liveimap/master at c37e941

## 2026-10-07 - inbound rules sieve emitter phase 1

- emitSieve writes script text only: require fileinto and imap4flags when used, allof tests, addflag fileinto redirect discard
- Empty and actionless rules return an empty script. CR, LF, and NUL are stripped before quoting
- Tests: filesAndMarksRead, redirectsAndDiscards, quotesAndSkipsEmptyRules. No install

## 2026-10-07 - inbound rules greeting execution start

- Approved plan: sandbox/plans/inbound-rules-greeting-20261007-0313-plan.md. Status set to APPROVED.
- Phase 1: parse a ManageSieve greeting into capabilities and send LOGOUT on an injected line transport.
- Do not open a socket. Do not authenticate. Do not install. Base builds tag 4fa5971. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - inbound rules greeting phase 1

- readGreeting collects IMPLEMENTATION, VERSION, SASL, SIEVE, and STARTTLS. OK returns them. NO and BYE throw SieveFailure with the quoted text, or the raw line.
- An empty line, an unquoted `{`, a bad line, or a missing close quote fails. logout writes LOGOUT and waits for OK. Capability lines during logout are ignored.
- No AUTHENTICATE, no PUTSCRIPT, no socket, and no screen. Tests: readsTheGreeting, splitsOneExtensionString, noTextIsTheFailure, logoutWritesLogout. No install.

## 2026-10-07 - inbound rules socket execution start

- Approved plan: sandbox/plans/inbound-rules-socket-20261007-0324-plan.md. Status set to APPROVED.
- Phase 1: plaintext ManageSieve TCP, read the greeting, send LOGOUT, and close. The only test peer is 127.0.0.1.
- Do not authenticate. Do not install. Base builds tag abf7ba6. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - inbound rules socket phase 1

- PlainSieveTransport connects on Dispatchers.IO. Connect timeout is 30 seconds and read timeout is 60 seconds. readLine, writeLine, and close also run on Dispatchers.IO.
- writeLine sends the line, then CR LF, as UTF-8, and flushes. readLine stops at LF, drops one trailing CR, and rejects a line longer than 8192 characters. A closed peer is SieveFailure("read"). A failed connect is SieveFailure("connect"). close is safe to call more than once.
- greetPlain reads the greeting, sends LOGOUT, and closes in a finally block. The localhost test peer is 127.0.0.1. No AUTHENTICATE, no PUTSCRIPT, and no install.

## 2026-10-07 - inbound rules commands execution start

- Approved plan: sandbox/plans/inbound-rules-commands-20261007-0339-plan.md. Status set to APPROVED.
- Phase 1: LISTSCRIPTS, GETSCRIPT, PUTSCRIPT, and CHECKSCRIPT on an injected transport, including script literals.
- Do not authenticate. Do not SETACTIVE. Do not install. Base builds tag b98cde1. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - inbound rules commands phase 1

- SieveLineTransport.readBytes and writeBytes default to SieveFailure("literal"). A negative count is "literal". PlainSieveTransport reads an exact count on Dispatchers.IO or throws SieveFailure("read"), and writeBytes writes those bytes and flushes.
- listScripts sends LISTSCRIPTS. A quoted name is inactive. A quoted name plus ACTIVE, compared without case, is active. OK ends the list and keeps that order. NO or BYE uses the greeting response text.
- getScript sends GETSCRIPT and the quoted name, reads a whole-line {count} or {count+} literal as UTF-8, and returns that text after OK. putScript and checkScript send {count+} and the script bytes with no extra CR LF. Plain OK returns "". OK (WARNINGS) returns the quoted warning text. NO {count} uses the literal bytes as the failure text.
- Names are wrapped in double quotes with \ and " escaped. No AUTHENTICATE, SETACTIVE, DELETESCRIPT, HAVESPACE, emitSieve, or greetPlain from these commands. No install.

## 2026-10-07 - Multi-pane resize execution start

- Approved plan: sandbox/plans/multi-pane-resize-20261007-1213-plan.md. Status set to APPROVED.
- Phase 1: Multi-pane Off or When wide (default Wide, omitted from encode), drag bars in NavHost, and the two pane tests.
- Do not bump Compose. Do not install. Base builds tag 20f502b. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - Multi-pane resize phase 1

- MultiPane is Off or Wide. Wide is the default and encode omits it. Off round-trips as multiPane=Off. An unknown value throws from enumValueOf. defaultsRoundTrip still ends at altAddresses.
- When wide on an expanded width, the drawer, index, and open message are panes with an 8 dp resize bar. Dragging a pane to the edge closes it. Off keeps today's single-pane drawer and reader route. Widths are rememberSaveable, not account settings.
- Appearance, Display offers Off and When wide. Did not install. Compose BOM stays 2024.10.00.

## 2026-10-07 - inbound rules editor execution start

- Approved plan: sandbox/plans/inbound-rules-editor-20261007-1255-plan.md. Status set to APPROVED.
- Phase 1: store inbound rules, seed criteria, and edit them on device. Show emitSieve text. No socket, no upload, no install.
- Host worktree: /home/dlang/git/liveimap/master at 6a5ff07. Do not commit the unstaged .gitignore edit.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - inbound rules editor phase 1

- Stored inboundRules on the account, omitted when empty. seedCriteria skips blanks. Filter list and editor show emitSieve and do not connect.
- Drawer Add filter and Edit filters, and the reader overflow Filter messages like this…, edit rules on device only.
- Unit tests: 324 run, 0 failed, including defaultsRoundTrip, inboundRulesRoundTrip, seedSkipsBlanks, and orderedSubjectStaysListedWhenNotAdvertised.

## 2026-10-07 - Cold index and drawer insets execution start

- Approved plan: sandbox/plans/cold-index-and-drawer-insets-20261007-1334-plan.md
- Status set to APPROVED. Phase 1: a mailbox that reports exists at or below 0 selects once, then a still-empty mailbox returns without fetchIndex. Phase 2: the split drawer pads vertical and start, and an open drawer consumes start for the trailing host.
- First action per standard-plan-compliance-block.md. Base builds tag b8f32da. No install.

## 2026-10-07 - Cold index phase 1

- fetchArrival still returns the filter list unchanged. When selected exists is not positive it selects that mailbox once. A still-empty mailbox clears the window and does not call fetchIndex. A positive exists does not select. MailFailure from select propagates.
- FakeMailSession.existsBeforeSelect is returned only while the mailbox name is empty, and that check is before exists. New tests cover a cold Inbox of 8 and an empty mailbox. Arrival fixtures that already expected a fetch set exists to 1 so they do not select.

## 2026-10-07 - Cold index phase 2

- The split drawer column and the modal sheet pad mail screen insets on the vertical and start sides only. The index and reader split is unchanged.
- When the resolved drawer width is above 0, the trailing NavHost consumes the start inset. Width 0 does not. Top, bottom, and end are not consumed. No install.

## 2026-10-07 - Reader pane first layout execution start

- Approved plan: sandbox/plans/reader-pane-first-layout-20261007-1505-plan.md. Status set to APPROVED.
- Phase 1: the split reader draws the header and body on the first layout. The body keeps half the pane. A wrapped header scrolls in the other half. A positive WebView size posts layout and invalidate without loading.
- Do not install. Base builds tag 2d1b800. Commits stay on master. Do not commit the unstaged .gitignore edit.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - Reader pane first layout phase 1

- Below the status line, one BoxWithConstraints holds the rest of the pane. readerBodyMinPx is 0 when the pane is not positive, otherwise half. readerHeaderMaxPx is 0 in that same case, otherwise the rest.
- When the header is ready it sits in a vertical scroll capped at that max, and the body slot keeps weight 1 with at least half the pane. Otherwise the body slot fills the pane. Plain text still scrolls inside the body. The HTML WebView stays in the body slot.
- onSizeChanged posts requestLayout and invalidate only when width and height are both positive and differ from the last size requested for that view. That callback does not load. The view.tag check still decides loadDataWithBaseURL.
- Test readerPaneGivesBodyHalf. No install.

## 2026-10-07 - Reader header collapse execution start

- Approved plan: sandbox/plans/reader-header-collapse-20261007-1517-plan.md. Status set to APPROVED.
- Phase 1: full screen setting, round-trip, Display switch, three strings, and hide or show the status and navigation bars. Cutout padding stays.
- Phase 2: collapse the reader header. Idle and Show images become toolbar icons. Remove the half-pane body cap. Keep the WebView size callback.
- Do not install. Base builds tag 9e09d6b. Leave the unstaged .gitignore edit unstaged.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - Reader header collapse phase 1

- AccountSettings.fullScreen defaults false, is omitted from the default encode, and is written as fullScreen=true only when true. A missing key decodes false. fullScreenRoundTrip covers that. defaultsRoundTrip keys still end at altAddresses.
- Display has a Full screen switch after the multi-pane choice. strings.xml appends collapse, expand, and full screen labels.
- MainActivity keeps fullScreen from the same settings load as theme. Full screen hides the status and navigation bars and shows them on a swipe. Off shows them. Light and dark icons stay. Cutout insets are unchanged.
- No install.

## 2026-10-07 - Reader header collapse phase 2

- DebugStatusIcon replaces DebugConnectionStatus. Empty text draws nothing. Other text draws a 24 dp info icon. Folder, index, and reader bars show it before the customizable icons. The index shows it on the normal bar and the multi-select bar. ConnectionStatusStrip stays in the column.
- The reader collapse button is fixed, not a toolbar action. Expanded and ready shows the header at its own height. Collapsed does not compose it. The body slot is weight 1 with no half-pane cap. readerBodyMinPx, readerHeaderMaxPx, and readerPaneGivesBodyHalf are gone.
- Show images is a fixed image icon when HTML is shown and images are still blocked. The body button is gone. The WebView size callback still posts layout and invalidate and does not load.
- No install.

## 2026-10-07 - Reader header collapse image toggle

- On an HTML body the picture button stays. It says Show images while images are blocked. While images are allowed it says Hide images and draws a red circle and a red diagonal line over the same picture. The tap toggles allowImages. Plain text has no button. The page and allowImages token still decides the load.
- reader_hide_images is appended with the other new reader strings. No install.

## 2026-10-07 - inbound rules activate execution start

- Approved plan: sandbox/plans/inbound-rules-activate-20261007-1532-plan.md. Status set to APPROVED.
- Phase 1: prepare the liveimap include line and SETACTIVE. Both stay behind consent. Upload only the script named liveimap.
- Do not open a socket. Do not install. Base builds tag c960912. Leave the unstaged .gitignore edit unstaged.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - inbound rules activate phase 1

- withLiveimapInclude appends include :personal "liveimap"; once. planLiveimapActivation chooses None, Include, or SetActive. Upload checks, then PUTSCRIPT names liveimap only.
- SETACTIVE and the include rewrite write nothing without consent. An empty SETACTIVE name writes nothing. No socket and no install.

## 2026-10-07 - ManageSieve SASL execution start

- Approved plan: sandbox/plans/sieve-sasl-20261007-1542-plan.md. Status set to APPROVED.
- Phase 1: choose CRAM-MD5, else PLAIN only when plaintext auth is allowed. AUTHENTICATE both on the injected transport. Fake transport only.
- No socket. No STARTTLS. No SCRAM. No account setting. No FilterScreen. No install. Base builds tag 368ddbd. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - ManageSieve SASL phase 1

- chooseSieveSasl returns CRAM-MD5 when that whole token is present, ignoring case. Otherwise PLAIN only when plaintext auth is allowed. SCRAM-SHA-256, LOGIN, DIGEST-MD5, and other tokens are not chosen.
- plainSaslInitial is standard Base64 of NUL, username, NUL, password. cramMd5Response HMACs the challenge bytes with HmacMD5 and returns standard Base64 of username, one space, and the lowercase hex digest.
- authenticatePlain and authenticateCramMd5 write on the injected transport. Empty username or password writes nothing. NO throws the server text. A CRAM response token writes no second line. Quoted and whole-line literal challenges are Base64.
- No socket. No STARTTLS. No SCRAM. No account setting. No FilterScreen. No install.

## 2026-10-07 - Sieve account setting execution start

- Approved plan: sandbox/plans/sieve-account-setting-20261007-1553-plan.md. Status set to APPROVED.
- Phase 1: store ManageSieve host and port on the account. A blank host means the IMAP host. Port defaults to 4190.
- No socket. No STARTTLS. No openPlainSieve. No ManageSieve.kt or FilterScreen edits. No install. Base builds tag 3e2da6d. Commits stay on master.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - Sieve account setting phase 1

- AccountSettings stores sieveHost (default empty) and sievePort (default 4190) after fullScreen. Encode omits an empty host and port 4190. A missing host decodes as empty. A missing port, or a port outside 1..65535, decodes as 4190.
- sieveEndpoint uses the sieve host when it is not blank, otherwise the IMAP host, with sievePort. Server fields follow the IMAP port. The server summary stays the IMAP host and port.
- No socket. No STARTTLS. No openPlainSieve. No ManageSieve.kt or FilterScreen edits. No install.

## 2026-10-07 - IMAP TLS connect execution start

- Approved plan: sandbox/plans/imap-tls-connect-20261007-1604-plan.md. Status set to APPROVED.
- Phase 1: save TlsMode on the account, compare it in the IMAP identity, and check the peer chain with PeerTrust before any password is sent.
- Phase 2: STARTTLS and implicit TLS in nativeOpen. None stays plaintext. No pin prompt, SCRAM, SMTP TLS, or ManageSieve TLS.
- Do not open a socket. Do not install. Base builds tag ab6dd98. Leave the unstaged .gitignore edit unstaged.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - IMAP TLS connect phase 1

- TlsMode is None, StartTls, or Implicit. AccountSettings.tlsMode defaults to None after sievePort. Encode omits None. A missing key decodes as None. StartTls and Implicit round-trip.
- sameImapIdentity is false when the TLS modes differ. The Server TLS choice follows the IMAP port and precedes the Sieve host.
- PeerTrust.check returns empty only for a non-empty chain the platform trust manager accepts and whose leaf matches the host. An empty chain returns a failure string and does not throw.
- No socket. No install.

## 2026-10-07 - IMAP TLS connect phase 2

- nativeOpen takes the TLS mode name. None stays on openPlain and does not call PeerTrust.
- StartTls uses the existing TCP connect, reads capabilities, and fails before login when STARTTLS is absent. It then calls mailimap_socket_starttls_with_server_name_callback with the account host.
- Implicit calls mailimap_ssl_connect_with_callback. The SSL callback sets TLS 1.2 as the minimum and records the peer chain, leaf first, while letting the handshake finish. No recorded certificate fails with certificate rejected before login.
- PeerTrust.check runs before mailimap_login. A failure closes the session and returns that text. The password is not sent. No socket from this turn. No install.

## 2026-10-07 - TODO backlog update execution start

- Approved plan: /home/dlang/git/liveimap/sandbox/plans/todo-backlog-update-20261007-1629-plan.md
- TODO: long-press icon help waits for the expanded manual. OpenSSL 3.x stays before a public release. IMAP TLS may ship on 1.1.1w.

## 2026-10-07 - IDLE watch uses the account TLS mode. None stays plaintext.

## 2026-10-07 - A certificate pin can accept one named, in-date leaf, and the login prompt asks before saving it.

## 2026-10-07 - About shows the plaintext warning only for TLS None.

## 2026-10-07 - The Server hint says IMAP is plaintext only when TLS is None.

## 2026-10-07 - ManageSieve STARTTLS execution start

ManageSieve STARTTLS checks the certificate before login. Nothing connects.
- Approved plan: /home/dlang/git/liveimap/sandbox/plans/sieve-starttls-20261007-1718-plan.md
- Status set to APPROVED.
- Phase 1: STARTTLS on the injected transport, then wrap the existing socket and check the certificate before login.
- No screen calls it. No upload. No install.
- First action per standard-plan-compliance-block.md.

## 2026-10-07 - Check ManageSieve reads the greeting with the account TLS mode and logs out.

## 2026-10-07 - IMAP auth choice prefers SCRAM, then CRAM-MD5, and leaves login on the LOGIN command.

## 2026-10-07 - Login uses mailimap_authenticate when an IMAP SASL mechanism is chosen.

## 2026-10-07 - A plaintext IMAP login asks before sending the password.

## 2026-10-07 - Ignore third_party source checkouts and local pin files.

## 2026-10-07 - SMTP TLS connect execution start

SMTP uses the account TLS mode and checks the certificate before MAIL.

- Approved plan: sandbox/plans/smtp-tls-connect-20261007-1824-plan.md. Status set to APPROVED.
- Phase 1: None stays the plaintext send. StartTls and Implicit encrypt and check the certificate before MAIL. No AUTH. No install.
- First action per standard-plan-compliance-block.md. Commits stay on master.

## 2026-10-07 - SMTP signs in with the chosen SASL mechanism before MAIL.

## 2026-10-07 - SMTP can sign in with a different username.

## 2026-10-07 - SMTP username phase 1

- A blank SMTP username still signs in as the IMAP user. A filled one is the SMTP AUTH login and auth name. The password stays the IMAP password.
- The setting is omitted when empty, round-trips "post master", and does not change IMAP identity. No install. No send.

## 2026-10-07 - SMTP can sign in with a different password.

## 2026-10-07 - Pinerc import applies TLS flags and a distinct SMTP user.

## 2026-10-07 - Plain text sends as format=flowed.

## 2026-10-07 - Deleting an open message skips messages that are already deleted.

## 2026-10-07 - The checked account is the one the app opens.

## 2026-10-07 - The drawer lists an Inbox for each account.

## 2026-10-07 - Each account keeps its own connection.
