private val ignoredAccountToolbarKeys = setOf(
    "indexBar",
    "selectionBar",
    "folderBar",
    "readerToolbar",
    "composeBar",
    "toolbarRows",
)

data class AccountSettings(
    val imapHost: String = "",
    val imapPort: Int = SettingsDefaults.imapPort,
    val smtpHost: String = "",
    val smtpPort: Int = SettingsDefaults.smtpPort,
    val smtpUsername: String = "",
    /** F52 (David): use the IMAP password for SMTP only when this is ticked; never assumed. */
    val smtpSameAsImap: Boolean = false,
    /** Missing stored line copies [tlsMode]. Not Implicit by itself. */
    val smtpTlsMode: TlsMode = enumValueOf(SettingsDefaults.tlsMode),
    val smtpNoAuth: SmtpNoAuth = SmtpNoAuth.Auto,
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val sentMailbox: String = "",
    val postponedMailbox: String = "",
    val addressBookMailbox: String = "",
    val addressBookHistory: Int = 3,
    val addressBookNeverTrim: Boolean = false,
    val spamMailbox: String = "",
    val markSeenOnOpen: Boolean = true,
    val showDeleted: Boolean = true,
    val bodyView: BodyView = BodyView.PlainOrError,
    val density: Density = Density.Compact,
    val defaultView: FolderView = FolderView(SortKey.Arrival, newestFirst = true),
    val folderViews: Map<String, FolderView> = emptyMap(),
    val expandedFolders: Set<String> = emptySet(),
    val swipeTrailing: SwipeBinding = SwipeBinding(SwipeAction.Delete),
    val swipeLeading: SwipeBinding = SwipeBinding(SwipeAction.ReplyAll),
    val readerSwipeTrailing: SwipeBinding = SwipeBinding(SwipeAction.Next),
    val readerSwipeLeading: SwipeBinding = SwipeBinding(SwipeAction.Previous),
    val bounceFcc: Boolean = false,
    val friendlyName: String = "",
    val theme: ThemeMode = ThemeMode.FollowSystem,
    val dynamicColor: Boolean = true,
    val showUnreadCounts: Boolean = false,
    val dateFormat: DateFormat = DateFormat.Short,
    val datePattern: String = "",
    val favorites: List<FolderFavorite> = emptyList(),
    val includeForwardAttachments: Boolean = true,
    val forwardAsAttachment: Boolean = false,
    val replyAboveQuote: Boolean = false,
    val askBeforeExpunge: Boolean = true,
    val pipelineCommands: Boolean = true,
    val logImapTraffic: Boolean = false,
    val showUserInDebugReport: Boolean = false,
    val inboxStart: StartRule = StartRule.Newest,
    val folderStart: StartRule = StartRule.Newest,
    val folderStarts: Map<String, StartRule> = emptyMap(),
    val startAfterChange: StartAfterChange = StartAfterChange.RerunRule,
    val showRecentRules: Boolean = true,
    val openAtInIndexMenu: Boolean = false,
    val pinercStartDefault: PinercStartDefault = PinercStartDefault.LeaveUnchanged,
    val readerTextLayout: TextLayout = TextLayout.Text,
    /** Reader text size in percent (plain text and HTML); pinch-zoom adjusts it per message on top. */
    val readerTextZoom: Int = 100,
    /** F38: how often the IDLE wait wakes when nothing arrives (stop requests wake it at once). */
    val idleWakeSeconds: Int = 5,
    val altAddresses: List<String> = emptyList(),
    val completionSources: List<String> = listOf(pineSourceId),
    /** Further Alpine address books on this server (pinerc's later `address-book` entries); read and written. */
    val extraAddressBooks: List<String> = emptyList(),
    /** Alpine global address books on this server (`global-address-book`); read only. */
    val globalAddressBooks: List<String> = emptyList(),
    val clientSort: Boolean = false,
    val clientSortCollation: ClientSortCollation = ClientSortCollation.Locale,
    val unlabeledCharset: String = "ISO-8859-1",
    val clientThread: Boolean = false,
    val pollForNewMail: Boolean = false,
    val pollSeconds: Int = 60,
    val idleFallbackSeconds: Int = 60,
    val keepAliveMinutes: Int = 9,
    val idleRenewMinutes: Int = 9,
    val longSearchProgressSec: Int = 60,
    val longSearchQuietSec: Int = 600,
    val statusVisibleCounts: Boolean = false,
    val forceSlowerFallbacks: Boolean = false,
    val hiddenCapabilities: Set<String> = emptySet(),
    val autoExpunge: Boolean = false,
    val deletePolicy: DeletePolicy = DeletePolicy.MarkDeleted,
    val moveMethod: MoveMethod = MoveMethod.CopyThenMarkDeleted,
    val compression: Compression = Compression.On,
    /** "Move entire folder" also moves messages already marked \Deleted (default: skip them, UID SEARCH UNDELETED). */
    val moveAllIncludesDeleted: Boolean = false,
    val trashMailbox: String = "",
    val savedMailbox: String = "",
    val saveNameRule: SaveNameRule = SaveNameRule.DefaultFolder,
    val lastSaveMailbox: String = "",
    val threadIndexStyle: ThreadIndexStyle = ThreadIndexStyle.Expanded,
    val composerWrapColumn: Int = 74,
    val quellFlowed: Boolean = false,
    val multiPane: MultiPane = enumValueOf(SettingsDefaults.multiPane),
    val folderPane: LayoutChoice = enumValueOf(SettingsDefaults.folderPane),
    val foldPosture: LayoutChoice = LayoutChoice.Off,
    val fullScreen: Boolean = false,
    /** Auto = [planLayout]. */
    val paneArrangement: ArrangementChoice = enumValueOf(SettingsDefaults.paneArrangement),
    val previewSize: PreviewChoice = enumValueOf(SettingsDefaults.previewSize),
    val folderPosition: FolderPositionChoice = enumValueOf(SettingsDefaults.folderPosition),
    /** Custom panel layout (PaneLayout.kt text form); empty = Auto. */
    val customLayout: String = "",
    // Account-default sending fields (design-decisions.md "Sending rules and roles").
    val sendingRules: String = "",
    val signature: String = "",
    val sigDashes: Boolean = true,
    val sigAtBottom: Boolean = false,
    val quotePrefix: String = org.dlang.liveimap.ui.compose.DefaultQuotePrefix,
    val replyLeadIn: String = org.dlang.liveimap.ui.compose.DefaultLeadIn,
    val dsnMode: org.dlang.liveimap.ui.compose.DsnMode = org.dlang.liveimap.ui.compose.DsnMode.Off,
    val composerHeaders: String = "",
    val viewerHeaders: String = "",
    val nonAsciiDisplay: NonAsciiDisplay = NonAsciiDisplay.HighlightSuspicious,
    val sieveHost: String = "",
    val sievePort: Int = 4190,
    val sieveTlsMode: TlsMode = TlsMode.StartTls,
    val tlsMode: TlsMode = enumValueOf(SettingsDefaults.tlsMode),
    val certPin: String = "",
    val sieveCertPin: String = "",
    val sieveConnectSeconds: Int = 30,
    val sieveReadSeconds: Int = 60,
    /** F63: the JMAP (HTTPS) server's own pin; never the IMAP one. */
    val jmapCertPin: String = "",
    val allowPlaintextAuth: Boolean = false,
    /** Advanced, off by default: accept a certificate that fails validation (like pine /novalidate-cert). */
    val acceptInvalidCert: Boolean = false,
    /** Advanced, off by default: allow TLS 1.0/1.1, small DH keys and legacy ciphers for this account only. */
    val allowOldTls: Boolean = false,
    /** Advanced: force the IMAP login method. "" = automatic, "IMAP-LOGIN" = the LOGIN command, else a SASL name. */
    val authMechanism: String = "",
    /**
     * Advanced: TLS client certificate for IMAP and SMTP, the path of an imported PKCS#12 file in app-private
     * storage (see ClientCertStore; its password sits beside it, not in these settings). "" = none.
     */
    val clientCert: String = "",
    /**
     * Advanced: TLS client certificate from Android KeyChain (KeyChain.choosePrivateKeyAlias), the alias. The
     * key never leaves the system keystore; OpenSSL signs through it (JNI callback). Per account, exclusive
     * with [clientCert]: the UI sets one and clears the other. "" = none.
     */
    val clientCertAlias: String = "",
    val inboundRules: List<InboundRule> = emptyList(),
    val watchedFolders: List<String> = emptyList(),
    val extraIdleBudget: Int = 2,
    /** David's rule: most connections this account opens to its server (main, watch, search, extra IDLE). */
    val maxConnections: Int = org.dlang.liveimap.session.DefaultMaxConnections,
    val savedSearches: List<SavedSearch> = emptyList(),
    val savedAdvanced: List<SavedAdvanced> = emptyList(),
    val emailSubmission: Boolean = false,
    /** F68: browse folders over JMAP only when the user turned it on (discovery alone never switches routes). */
    val jmapFolders: Boolean = false,
    /** Largest JMAP response or download. A missing line is [JmapMaxBody]. */
    val maxJmapResponseBytes: Int = JmapMaxBody,
    /** Largest UID result. A missing line stays 2000000. */
    val maxResultUids: Int = 2000000,
    val askSort: Int = SettingsDefaults.askSort,
    val askThread: Int = SettingsDefaults.askThread,
    val askSearch: Int = SettingsDefaults.askSearch,
    val askFallback: Int = SettingsDefaults.askFallback,
    val sortAskSkip: Set<String> = emptySet(),
    val threadAskSkip: Set<String> = emptySet(),
    val searchAskSkip: Set<String> = emptySet(),
    val fallbackAskSkip: Set<String> = emptySet(),
    /** F69: lines with keys this version does not know (newer builds), kept verbatim so saving keeps them. */
    val folderCountFreshSeconds: Int = SettingsDefaults.folderCountFreshSeconds,
    val androidContactsCacheSeconds: Int = SettingsDefaults.androidContactsCacheSeconds,
    val unknownSettings: List<String> = emptyList(),
) {
    val preferHtml: Boolean
        get() = bodyView == BodyView.PlainOrHtml

    init {
        if (composerWrapColumn !in 0..998) throw IllegalArgumentException("bad composerWrapColumn")
        if (pollSeconds !in 15..600) throw IllegalArgumentException("pollSeconds")
        if (idleFallbackSeconds < 1) throw IllegalArgumentException("idleFallbackSeconds")
        if (keepAliveMinutes < 1) throw IllegalArgumentException("keepAliveMinutes")
        if (idleRenewMinutes < 1) throw IllegalArgumentException("idleRenewMinutes")
        if (longSearchProgressSec < 1) throw IllegalArgumentException("longSearchProgressSec")
        if (longSearchQuietSec < 1) throw IllegalArgumentException("longSearchQuietSec")
        if (maxJmapResponseBytes < 1) throw IllegalArgumentException("maxJmapResponseBytes")
        if (maxResultUids < 1) throw IllegalArgumentException("maxResultUids")
        if (sieveConnectSeconds < 1) throw IllegalArgumentException("sieveConnectSeconds")
        if (sieveReadSeconds < 1) throw IllegalArgumentException("sieveReadSeconds")
        if (!knownUnlabeledCharset(unlabeledCharset)) throw IllegalArgumentException("unlabeledCharset")
    }

    /** tlsMode, then deletePolicy, then moveMethod, when that raw line is still kept. */
    fun preservedSecurityKey(): String? {
        val present = HashSet<String>()
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0) present.add(first.substring(0, eq))
        }
        if ("tlsMode" in present) return "tlsMode"
        if ("deletePolicy" in present) return "deletePolicy"
        if ("moveMethod" in present) return "moveMethod"
        return null
    }

    /** The raw sieveTlsMode line, when it is still kept. Not a preservedSecurityKey. */
    fun preservedSieveTlsKey(): String? {
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0 && first.substring(0, eq) == "sieveTlsMode") return "sieveTlsMode"
        }
        return null
    }

    /** A chosen value, including the default, drops every kept line for [key]. */
    fun withoutPreserved(key: String): AccountSettings {
        val kept = ArrayList<String>()
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0 && first.substring(0, eq) == key) continue
            kept.add(entry)
        }
        return copy(unknownSettings = kept)
    }
}

private val fieldNames = listOf(
    "imapHost",
    "imapPort",
    "smtpHost",
    "smtpPort",
    "smtpTlsMode",
    "smtpUsername",
    "smtpSameAsImap",
    "smtpNoAuth",
    "username",
    "displayName",
    "email",
    "sentMailbox",
    "postponedMailbox",
    "addressBookMailbox",
    "spamMailbox",
    "markSeenOnOpen",
    "showDeleted",
    "preferHtml",
    "bodyView",
    "density",
    "defaultView",
    "folderViews",
    "expandedFolders",
    "swipeTrailing",
    "swipeLeading",
    "bounceFcc",
    "friendlyName",
    "theme",
    "showUnreadCounts",
    "dateFormat",
    "datePattern",
    "favorites",
    "includeForwardAttachments",
    "forwardAsAttachment",
    "replyAboveQuote",
    "askBeforeExpunge",
    "pipelineCommands",
    "logImapTraffic",
    "showUserInDebugReport",
    "dynamicColor",
    "inboxStart",
    "folderStart",
    "folderStarts",
    "startAfterChange",
    "showRecentRules",
    "openAtInIndexMenu",
    "pinercStartDefault",
    "readerTextLayout",
    "readerSwipeTrailing",
    "readerSwipeLeading",
    "readerTextZoom",
    "idleWakeSeconds",
    "altAddresses",
    "completionSources",
    "extraAddressBooks",
    "globalAddressBooks",
    "addressBookHistory",
    "addressBookNeverTrim",
    "clientSort",
    "clientSortCollation",
    "unlabeledCharset",
    "clientThread",
    "pollForNewMail",
    "pollSeconds",
    "idleFallbackSeconds",
    "keepAliveMinutes",
    "idleRenewMinutes",
    "longSearchProgressSec",
    "longSearchQuietSec",
    "statusVisibleCounts",
    "forceSlowerFallbacks",
    "hiddenCapabilities",
    "autoExpunge",
    "deletePolicy",
    "moveMethod",
    "compression",
    "moveAllIncludesDeleted",
    "trashMailbox",
    "savedMailbox",
    "saveNameRule",
    "lastSaveMailbox",
    "threadIndexStyle",
    "composerWrapColumn",
    "quellFlowed",
    "multiPane",
    "folderPane",
    "foldPosture",
    "fullScreen",
    "paneArrangement",
    "previewSize",
    "folderPosition",
    "customLayout",
    "sendingRules",
    "signature",
    "sigDashes",
    "sigAtBottom",
    "quotePrefix",
    "replyLeadIn",
    "dsnMode",
    "composerHeaders",
    "viewerHeaders",
    "nonAsciiDisplay",
    "sieveHost",
    "sievePort",
    "sieveTlsMode",
    "tlsMode",
    "certPin",
    "sieveCertPin",
    "sieveConnectSeconds",
    "sieveReadSeconds",
    "jmapCertPin",
    "allowPlaintextAuth",
    "acceptInvalidCert",
    "allowOldTls",
    "authMechanism",
    "clientCert",
    "clientCertAlias",
    "inboundRules",
    "watchedFolders",
    "extraIdleBudget",
    "maxConnections",
    "savedSearches",
    "savedAdvanced",
    "emailSubmission",
    "jmapFolders",
    "maxJmapResponseBytes",
    "maxResultUids",
    "askSort",
    "askThread",
    "askSearch",
    "askFallback",
    "sortAskSkip",
    "threadAskSkip",
    "searchAskSkip",
    "fallbackAskSkip",
    "folderCountFreshSeconds",
    "androidContactsCacheSeconds",
)

// Lines a fresh account writes. A preserved key still at that line stays omitted.
private fun failureEncodedLine(key: String): String? = if (key == "tlsMode") "tlsMode=None" else null

private val defaultEncodedLines: Map<String, String> by lazy {
    AccountSettings().encode().lineSequence().filter { it.isNotEmpty() }.associate { line ->
        val eq = line.indexOf('=')
        line.substring(0, eq) to line
    }
}

fun AccountSettings.encode(): String = buildString {
    val preserved = HashSet<String>()
    for (entry in unknownSettings) {
        val first = entry.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0) preserved.add(first.substring(0, eq))
    }
    val written = HashSet<String>()
    fun put(line: String) {
        val first = line.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0) {
            val key = first.substring(0, eq)
            // A later edit replaces the raw line. The failed-decode default does not.
            if (key in preserved && (defaultEncodedLines[key] == first || failureEncodedLine(key) == first)) return
            written.add(key)
        }
        appendLine(line)
    }
    put("imapHost=${percentEncode(imapHost)}")
    put("imapPort=$imapPort")
    put("smtpHost=${percentEncode(smtpHost)}")
    put("smtpPort=$smtpPort")
    put("smtpTlsMode=${smtpTlsMode.name}")
    put("smtpUsername=${percentEncode(smtpUsername)}")
    put("smtpSameAsImap=$smtpSameAsImap")
    put("smtpNoAuth=${smtpNoAuth.name}")
    put("username=${percentEncode(username)}")
    put("displayName=${percentEncode(displayName)}")
    put("email=${percentEncode(email)}")
    put("sentMailbox=${percentEncode(sentMailbox)}")
    put("postponedMailbox=${percentEncode(postponedMailbox)}")
    put("addressBookMailbox=${percentEncode(addressBookMailbox)}")
    put("spamMailbox=${percentEncode(spamMailbox)}")
    put("markSeenOnOpen=$markSeenOnOpen")
    put("showDeleted=$showDeleted")
    put("preferHtml=$preferHtml")
    put("bodyView=${bodyView.name}")
    put("density=${density.name}")
    put("defaultView=${encodeView(defaultView)}")
    put("folderViews=${encodeFolderViews(folderViews)}")
    put("expandedFolders=${expandedFolders.joinToString(";") { percentEncode(it) }}")
    put("swipeTrailing=${encodeSwipe(swipeTrailing)}")
    put("swipeLeading=${encodeSwipe(swipeLeading)}")
    put("bounceFcc=$bounceFcc")
    put("friendlyName=${percentEncode(friendlyName)}")
    put("theme=${theme.name}")
    put("showUnreadCounts=$showUnreadCounts")
    put("dateFormat=${dateFormat.name}")
    put("datePattern=${percentEncode(datePattern)}")
    put("favorites=${encodeFavorites(favorites)}")
    put("includeForwardAttachments=$includeForwardAttachments")
    put("forwardAsAttachment=$forwardAsAttachment")
    put("replyAboveQuote=$replyAboveQuote")
    put("askBeforeExpunge=$askBeforeExpunge")
    put("pipelineCommands=$pipelineCommands")
    put("logImapTraffic=$logImapTraffic")
    put("showUserInDebugReport=$showUserInDebugReport")
    put("dynamicColor=$dynamicColor")
    put("inboxStart=${inboxStart.name}")
    put("folderStart=${folderStart.name}")
    put("folderStarts=${encodeFolderStarts(folderStarts)}")
    put("startAfterChange=${startAfterChange.name}")
    put("showRecentRules=$showRecentRules")
    put("openAtInIndexMenu=$openAtInIndexMenu")
    put("pinercStartDefault=${pinercStartDefault.name}")
    put("readerTextLayout=${readerTextLayout.name}")
    put("readerSwipeTrailing=${encodeSwipe(readerSwipeTrailing)}")
    put("readerSwipeLeading=${encodeSwipe(readerSwipeLeading)}")
    put("readerTextZoom=$readerTextZoom")
    put("idleWakeSeconds=$idleWakeSeconds")
    put("nonAsciiDisplay=${nonAsciiDisplay.name}")
    put("altAddresses=${encodeAltAddresses(altAddresses)}")
    put("completionSources=${encodeCompletionSources(completionSources)}")
    put("extraAddressBooks=${encodeCompletionSources(extraAddressBooks)}")
    put("globalAddressBooks=${encodeCompletionSources(globalAddressBooks)}")
    put("addressBookHistory=$addressBookHistory")
    put("addressBookNeverTrim=$addressBookNeverTrim")
    put("clientSort=$clientSort")
    put("clientSortCollation=${clientSortCollationWord(clientSortCollation)}")
    put("unlabeledCharset=${percentEncode(unlabeledCharset)}")
    put("clientThread=$clientThread")
    put("pollForNewMail=$pollForNewMail")
    put("pollSeconds=$pollSeconds")
    put("idleFallbackSeconds=$idleFallbackSeconds")
    put("keepAliveMinutes=$keepAliveMinutes")
    put("idleRenewMinutes=$idleRenewMinutes")
    put("longSearchProgressSec=$longSearchProgressSec")
    put("longSearchQuietSec=$longSearchQuietSec")
    put("statusVisibleCounts=$statusVisibleCounts")
    put("forceSlowerFallbacks=$forceSlowerFallbacks")
    put("hiddenCapabilities=${encodeHiddenCapabilities(hiddenCapabilities)}")
    put("autoExpunge=$autoExpunge")
    put("deletePolicy=${deletePolicy.name}")
    put("moveMethod=${moveMethod.name}")
    put("compression=${compression.name}")
    put("moveAllIncludesDeleted=$moveAllIncludesDeleted")
    put("trashMailbox=${percentEncode(trashMailbox)}")
    put("savedMailbox=${percentEncode(savedMailbox)}")
    put("saveNameRule=${saveNameRule.name}")
    put("lastSaveMailbox=${percentEncode(lastSaveMailbox)}")
    put("threadIndexStyle=${threadIndexStyle.name}")
    put("composerWrapColumn=$composerWrapColumn")
    put("quellFlowed=$quellFlowed")
    put("multiPane=${multiPane.name}")
    put("folderPane=${folderPane.name}")
    put("foldPosture=${foldPosture.name}")
    put("fullScreen=$fullScreen")
    put("paneArrangement=${paneArrangement.name}")
    put("previewSize=${previewSize.name}")
    put("folderPosition=${folderPosition.name}")
    put("customLayout=${percentEncode(customLayout)}")
    put("sendingRules=${percentEncode(sendingRules)}")
    put("signature=${percentEncode(signature)}")
    put("sigDashes=$sigDashes")
    put("sigAtBottom=$sigAtBottom")
    put("quotePrefix=${percentEncode(quotePrefix)}")
    put("replyLeadIn=${percentEncode(replyLeadIn)}")
    put("dsnMode=${dsnMode.name}")
    put("composerHeaders=${percentEncode(composerHeaders)}")
    put("viewerHeaders=${percentEncode(viewerHeaders)}")
    put("sieveHost=${percentEncode(sieveHost)}")
    put("sievePort=$sievePort")
    put("sieveTlsMode=${sieveTlsMode.name}")
    put("tlsMode=${tlsMode.name}")
    put("certPin=${percentEncode(certPin)}")
    put("sieveCertPin=${percentEncode(sieveCertPin)}")
    put("sieveConnectSeconds=$sieveConnectSeconds")
    put("sieveReadSeconds=$sieveReadSeconds")
    put("jmapCertPin=${percentEncode(jmapCertPin)}")
    put("allowPlaintextAuth=$allowPlaintextAuth")
    put("acceptInvalidCert=$acceptInvalidCert")
    put("allowOldTls=$allowOldTls")
    put("authMechanism=${percentEncode(authMechanism)}")
    put("clientCert=${percentEncode(clientCert)}")
    put("clientCertAlias=${percentEncode(clientCertAlias)}")
    put("inboundRules=${encodeInboundRules(inboundRules)}")
    put("watchedFolders=${encodeWatchedFolders(watchedFolders)}")
    put("extraIdleBudget=$extraIdleBudget")
    put("maxConnections=$maxConnections")
    put("savedSearches=${encodeSavedSearches(savedSearches)}")
    put("savedAdvanced=${encodeSavedAdvanced(savedAdvanced)}")
    put("emailSubmission=$emailSubmission")
    put("jmapFolders=$jmapFolders")
    put("maxJmapResponseBytes=$maxJmapResponseBytes")
    put("maxResultUids=$maxResultUids")
    put("askSort=$askSort")
    put("askThread=$askThread")
    put("askSearch=$askSearch")
    put("askFallback=$askFallback")
    put("sortAskSkip=${encodeWatchedFolders(sortAskSkip.sorted())}")
    put("threadAskSkip=${encodeWatchedFolders(threadAskSkip.sorted())}")
    put("searchAskSkip=${encodeWatchedFolders(searchAskSkip.sorted())}")
    put("fallbackAskSkip=${encodeWatchedFolders(fallbackAskSkip.sorted())}")
    put("folderCountFreshSeconds=$folderCountFreshSeconds")
    put("androidContactsCacheSeconds=$androidContactsCacheSeconds")
    for (entry in unknownSettings) {
        val first = entry.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0 && first.substring(0, eq) in written) continue
        appendLine(entry)
    }
}

fun decodeAccountSettings(text: String): AccountSettings {
    val values = linkedMapOf<String, String>()
    val unknown = ArrayList<String>()
    var openKey: String? = null
    for (line in text.lines()) {
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        val key = if (eq > 0) line.substring(0, eq) else ""
        if (eq > 0 && key in fieldNames) {
            values[key] = line.substring(eq + 1) // F69: a duplicate key: the last one wins
            openKey = key
        } else if (eq > 0 && key in ignoredAccountToolbarKeys) {
            openKey = null
        } else if (openKey == "inboundRules" && line.contains('\t')) {
            values[openKey] = values.getValue(openKey) + "\n" + line // rule lines always carry a TAB
        } else if (eq > 0) {
            // F69: a newer build's key: kept for round-tripping, not an error. Never keep anything credential-like
            // (secrets live in the keystore; a stray one here must not be carried along and re-saved).
            val lower = key.lowercase()
            if ("password" !in lower && "secret" !in lower && "token" !in lower) unknown += line
            openKey = null
        } else {
            openKey = null // F69: a malformed line is skipped
        }
    }
    val d = AccountSettings()
    fun listDroppedAll(key: String, value: Any?): Boolean = when (key) {
        "inboundRules", "favorites" -> value is List<*> && value.isEmpty()
        "folderViews", "folderStarts" -> value is Map<*, *> && value.isEmpty()
        "expandedFolders" -> value is Set<*> && value.isEmpty()
        else -> false
    }
    // A throw keeps the raw line. A list that skips every element of a non-empty value does too.
    fun <T> field(key: String, default: T, read: () -> T): T {
        val raw = values[key]
        if (raw == null && key != "bodyView") return default
        return try {
            val value = read()
            if (raw != null && raw.isNotEmpty() && listDroppedAll(key, value)) unknown += "$key=$raw"
            value
        } catch (_: Exception) {
            if (raw != null) unknown += "$key=$raw"
            default
        }
    }
    val decodedTlsMode = field("tlsMode", TlsMode.None) { enumValueOf<TlsMode>(values.getValue("tlsMode")) }
    val decodedSmtpTls = if ("smtpTlsMode" !in values) {
        decodedTlsMode
    } else {
        field("smtpTlsMode", decodedTlsMode) { enumValueOf<TlsMode>(values.getValue("smtpTlsMode")) }
    }
    return AccountSettings(
        imapHost = field("imapHost", d.imapHost) { percentDecode(values.getValue("imapHost")) },
        imapPort = field("imapPort", d.imapPort) { parseIntField(values.getValue("imapPort")) },
        smtpHost = field("smtpHost", d.smtpHost) { percentDecode(values.getValue("smtpHost")) },
        smtpPort = field("smtpPort", d.smtpPort) { parseIntField(values.getValue("smtpPort")) },
        smtpUsername = field("smtpUsername", d.smtpUsername) { percentDecode(values.getValue("smtpUsername")) },
        smtpSameAsImap = field("smtpSameAsImap", d.smtpSameAsImap) { values["smtpSameAsImap"] == "true" },
        username = field("username", d.username) { percentDecode(values.getValue("username")) },
        displayName = field("displayName", d.displayName) { percentDecode(values.getValue("displayName")) },
        email = field("email", d.email) { percentDecode(values.getValue("email")) },
        sentMailbox = field("sentMailbox", d.sentMailbox) { percentDecode(values.getValue("sentMailbox")) },
        postponedMailbox = field("postponedMailbox", d.postponedMailbox) { percentDecode(values.getValue("postponedMailbox")) },
        addressBookMailbox = field("addressBookMailbox", d.addressBookMailbox) { percentDecode(values.getValue("addressBookMailbox")) },
        spamMailbox = field("spamMailbox", d.spamMailbox) { percentDecode(values.getValue("spamMailbox")) },
        markSeenOnOpen = field("markSeenOnOpen", d.markSeenOnOpen) { parseBoolean(values.getValue("markSeenOnOpen")) },
        showDeleted = field("showDeleted", d.showDeleted) { parseBoolean(values.getValue("showDeleted")) },
        bodyView = field("bodyView", d.bodyView) { decodeBodyView(values) },
        density = field("density", d.density) { enumValueOf(values.getValue("density")) },
        defaultView = field("defaultView", d.defaultView) { parseView(values.getValue("defaultView")) },
        folderViews = field("folderViews", d.folderViews) { parseFolderViews(values.getValue("folderViews")) },
        expandedFolders = field("expandedFolders", d.expandedFolders) { parseExpanded(values.getValue("expandedFolders")) },
        swipeTrailing = field("swipeTrailing", d.swipeTrailing) { parseSwipe(values.getValue("swipeTrailing")) },
        swipeLeading = field("swipeLeading", d.swipeLeading) { parseSwipe(values.getValue("swipeLeading")) },
        readerSwipeTrailing = field("readerSwipeTrailing", d.readerSwipeTrailing) { parseSwipe(values.getValue("readerSwipeTrailing")) },
        readerSwipeLeading = field("readerSwipeLeading", d.readerSwipeLeading) { parseSwipe(values.getValue("readerSwipeLeading")) },
        bounceFcc = field("bounceFcc", d.bounceFcc) { parseBoolean(values.getValue("bounceFcc")) },
        friendlyName = field("friendlyName", d.friendlyName) { percentDecode(values.getValue("friendlyName")) },
        theme = field("theme", d.theme) { enumValueOf<ThemeMode>(values.getValue("theme")) },
        showUnreadCounts = field("showUnreadCounts", d.showUnreadCounts) { parseBoolean(values.getValue("showUnreadCounts")) },
        dateFormat = field("dateFormat", d.dateFormat) { enumValueOf<DateFormat>(values.getValue("dateFormat")) },
        datePattern = field("datePattern", d.datePattern) { percentDecode(values.getValue("datePattern")) },
        favorites = field("favorites", d.favorites) { parseFavorites(values.getValue("favorites")) },
        includeForwardAttachments = field("includeForwardAttachments", d.includeForwardAttachments) { parseBoolean(values.getValue("includeForwardAttachments")) },
        forwardAsAttachment = field("forwardAsAttachment", d.forwardAsAttachment) { parseBoolean(values.getValue("forwardAsAttachment")) },
        replyAboveQuote = field("replyAboveQuote", d.replyAboveQuote) { parseBoolean(values.getValue("replyAboveQuote")) },
        askBeforeExpunge = field("askBeforeExpunge", d.askBeforeExpunge) { parseBoolean(values.getValue("askBeforeExpunge")) },
        pipelineCommands = field("pipelineCommands", d.pipelineCommands) { parseBoolean(values.getValue("pipelineCommands")) },
        logImapTraffic = field("logImapTraffic", d.logImapTraffic) { parseBoolean(values.getValue("logImapTraffic")) },
        showUserInDebugReport = field("showUserInDebugReport", d.showUserInDebugReport) { parseBoolean(values.getValue("showUserInDebugReport")) },
        dynamicColor = field("dynamicColor", d.dynamicColor) { parseBoolean(values.getValue("dynamicColor")) },
        inboxStart = field("inboxStart", d.inboxStart) { enumValueOf<StartRule>(values.getValue("inboxStart")) },
        folderStart = field("folderStart", d.folderStart) { enumValueOf<StartRule>(values.getValue("folderStart")) },
        folderStarts = field("folderStarts", d.folderStarts) { parseFolderStarts(values.getValue("folderStarts")) },
        startAfterChange = field("startAfterChange", d.startAfterChange) { enumValueOf<StartAfterChange>(values.getValue("startAfterChange")) },
        showRecentRules = field("showRecentRules", d.showRecentRules) { parseBoolean(values.getValue("showRecentRules")) },
        openAtInIndexMenu = field("openAtInIndexMenu", d.openAtInIndexMenu) { parseBoolean(values.getValue("openAtInIndexMenu")) },
        pinercStartDefault = field("pinercStartDefault", d.pinercStartDefault) { enumValueOf<PinercStartDefault>(values.getValue("pinercStartDefault")) },
        readerTextLayout = if ("readerTextLayout" !in values) d.readerTextLayout else enumValueOf<TextLayout>(values.getValue("readerTextLayout")),
        readerTextZoom = field("readerTextZoom", d.readerTextZoom) { clampTextZoom(parseIntField(values.getValue("readerTextZoom"))) },
        idleWakeSeconds = field("idleWakeSeconds", d.idleWakeSeconds) {
            val seconds = parseIntField(values.getValue("idleWakeSeconds"))
            if (seconds !in IdleWakeSeconds) throw IllegalArgumentException("bad idleWakeSeconds")
            seconds
        },
        altAddresses = field("altAddresses", d.altAddresses) { decodeAltAddresses(values.getValue("altAddresses")) },
        completionSources = field("completionSources", d.completionSources) { decodeCompletionSources(values["completionSources"]) },
        extraAddressBooks = field("extraAddressBooks", d.extraAddressBooks) { decodeCompletionSources(values.getValue("extraAddressBooks")) },
        globalAddressBooks = field("globalAddressBooks", d.globalAddressBooks) { decodeCompletionSources(values.getValue("globalAddressBooks")) },
        addressBookHistory = field("addressBookHistory", d.addressBookHistory) { parseIntField(values.getValue("addressBookHistory")) },
        addressBookNeverTrim = field("addressBookNeverTrim", d.addressBookNeverTrim) { parseBoolean(values.getValue("addressBookNeverTrim")) },
        clientSort = field("clientSort", d.clientSort) { parseBoolean(values.getValue("clientSort")) },
        clientSortCollation = if ("clientSortCollation" !in values) {
            d.clientSortCollation
        } else {
            val word = values.getValue("clientSortCollation")
            if (word != "locale" && word != "plain") throw IllegalArgumentException("clientSortCollation")
            field("clientSortCollation", d.clientSortCollation) { clientSortCollationOf(word) }
        },
        unlabeledCharset = if ("unlabeledCharset" !in values) {
            d.unlabeledCharset
        } else {
            val decoded = try {
                percentDecode(values.getValue("unlabeledCharset"))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("unlabeledCharset")
            }
            if (!knownUnlabeledCharset(decoded)) throw IllegalArgumentException("unlabeledCharset")
            field("unlabeledCharset", d.unlabeledCharset) { decoded }
        },
        clientThread = field("clientThread", d.clientThread) { parseBoolean(values.getValue("clientThread")) },
        pollForNewMail = field("pollForNewMail", d.pollForNewMail) { parseBoolean(values.getValue("pollForNewMail")) },
        pollSeconds = field("pollSeconds", d.pollSeconds) { parseIntField(values.getValue("pollSeconds")) },
        idleFallbackSeconds = field("idleFallbackSeconds", d.idleFallbackSeconds) { parseIntField(values.getValue("idleFallbackSeconds")) },
        keepAliveMinutes = field("keepAliveMinutes", d.keepAliveMinutes) { parseIntField(values.getValue("keepAliveMinutes")) },
        idleRenewMinutes = field("idleRenewMinutes", d.idleRenewMinutes) { parseIntField(values.getValue("idleRenewMinutes")) },
        longSearchProgressSec = field("longSearchProgressSec", d.longSearchProgressSec) { parseIntField(values.getValue("longSearchProgressSec")) },
        longSearchQuietSec = field("longSearchQuietSec", d.longSearchQuietSec) { parseIntField(values.getValue("longSearchQuietSec")) },
        statusVisibleCounts = field("statusVisibleCounts", d.statusVisibleCounts) { parseBoolean(values.getValue("statusVisibleCounts")) },
        forceSlowerFallbacks = field("forceSlowerFallbacks", d.forceSlowerFallbacks) { parseBoolean(values.getValue("forceSlowerFallbacks")) },
        hiddenCapabilities = field("hiddenCapabilities", d.hiddenCapabilities) { decodeHiddenCapabilities(values.getValue("hiddenCapabilities")) },
        autoExpunge = field("autoExpunge", d.autoExpunge) { parseBoolean(values.getValue("autoExpunge")) },
        deletePolicy = field("deletePolicy", d.deletePolicy) { enumValueOf<DeletePolicy>(values.getValue("deletePolicy")) },
        moveMethod = field("moveMethod", d.moveMethod) { enumValueOf<MoveMethod>(values.getValue("moveMethod")) },
        compression = if ("compression" !in values) d.compression else enumValueOf<Compression>(values.getValue("compression")),
        moveAllIncludesDeleted = field("moveAllIncludesDeleted", d.moveAllIncludesDeleted) { parseBoolean(values.getValue("moveAllIncludesDeleted")) },
        trashMailbox = field("trashMailbox", d.trashMailbox) { percentDecode(values.getValue("trashMailbox")) },
        savedMailbox = field("savedMailbox", d.savedMailbox) { percentDecode(values.getValue("savedMailbox")) },
        saveNameRule = field("saveNameRule", d.saveNameRule) { enumValueOf<SaveNameRule>(values.getValue("saveNameRule")) },
        lastSaveMailbox = field("lastSaveMailbox", d.lastSaveMailbox) { percentDecode(values.getValue("lastSaveMailbox")) },
        threadIndexStyle = field("threadIndexStyle", d.threadIndexStyle) { enumValueOf<ThreadIndexStyle>(values.getValue("threadIndexStyle")) },
        composerWrapColumn = field("composerWrapColumn", d.composerWrapColumn) { parseIntField(values.getValue("composerWrapColumn")).coerceIn(0, 998) },
        quellFlowed = field("quellFlowed", d.quellFlowed) { parseBoolean(values.getValue("quellFlowed")) },
        multiPane = field("multiPane", d.multiPane) { enumValueOf<MultiPane>(values.getValue("multiPane")) },
        folderPane = field("folderPane", d.folderPane) { enumValueOf<LayoutChoice>(values.getValue("folderPane")) },
        foldPosture = field("foldPosture", d.foldPosture) { enumValueOf<LayoutChoice>(values.getValue("foldPosture")) },
        fullScreen = field("fullScreen", d.fullScreen) { parseBoolean(values.getValue("fullScreen")) },
        paneArrangement = if ("paneArrangement" !in values) d.paneArrangement else enumValueOf<ArrangementChoice>(values.getValue("paneArrangement")),
        folderPosition = if ("folderPosition" !in values) d.folderPosition else enumValueOf<FolderPositionChoice>(values.getValue("folderPosition")),
        customLayout = field("customLayout", d.customLayout) { values["customLayout"]?.let { percentDecode(it) }?.takeIf { decodePaneLayout(it) != null }.orEmpty() },
        sendingRules = field("sendingRules", d.sendingRules) { percentDecode(values.getValue("sendingRules")) },
        signature = field("signature", d.signature) { percentDecode(values.getValue("signature")) },
        sigDashes = field("sigDashes", d.sigDashes) { values.getValue("sigDashes") == "true" },
        sigAtBottom = field("sigAtBottom", d.sigAtBottom) { values.getValue("sigAtBottom") == "true" },
        quotePrefix = field("quotePrefix", d.quotePrefix) { percentDecode(values.getValue("quotePrefix")) },
        replyLeadIn = field("replyLeadIn", d.replyLeadIn) { percentDecode(values.getValue("replyLeadIn")) },
        dsnMode = if ("dsnMode" !in values) d.dsnMode else enumValueOf<org.dlang.liveimap.ui.compose.DsnMode>(values.getValue("dsnMode")),
        composerHeaders = field("composerHeaders", d.composerHeaders) { percentDecode(values.getValue("composerHeaders")) },
        viewerHeaders = field("viewerHeaders", d.viewerHeaders) { percentDecode(values.getValue("viewerHeaders")) },
        nonAsciiDisplay = if ("nonAsciiDisplay" !in values) d.nonAsciiDisplay else enumValueOf<NonAsciiDisplay>(values.getValue("nonAsciiDisplay")),
        previewSize = if ("previewSize" !in values) d.previewSize else enumValueOf<PreviewChoice>(values.getValue("previewSize")),
        sieveHost = field("sieveHost", d.sieveHost) { percentDecode(values.getValue("sieveHost")) },
        sievePort = field("sievePort", d.sievePort) { parseSievePort(values.getValue("sievePort")) },
        sieveTlsMode = field("sieveTlsMode", d.sieveTlsMode) { enumValueOf<TlsMode>(values.getValue("sieveTlsMode")) },
        tlsMode = decodedTlsMode,
        smtpTlsMode = decodedSmtpTls,
        smtpNoAuth = field("smtpNoAuth", SmtpNoAuth.Auto) { enumValueOf<SmtpNoAuth>(values.getValue("smtpNoAuth")) },
        certPin = field("certPin", d.certPin) { percentDecode(values.getValue("certPin")) },
        sieveCertPin = field("sieveCertPin", d.sieveCertPin) { percentDecode(values.getValue("sieveCertPin")) },
        sieveConnectSeconds = field("sieveConnectSeconds", d.sieveConnectSeconds) { parseIntField(values.getValue("sieveConnectSeconds")) },
        sieveReadSeconds = field("sieveReadSeconds", d.sieveReadSeconds) { parseIntField(values.getValue("sieveReadSeconds")) },
        jmapCertPin = field("jmapCertPin", d.jmapCertPin) { percentDecode(values.getValue("jmapCertPin")) },
        allowPlaintextAuth = field("allowPlaintextAuth", d.allowPlaintextAuth) { parseBoolean(values.getValue("allowPlaintextAuth")) },
        acceptInvalidCert = field("acceptInvalidCert", d.acceptInvalidCert) { parseBoolean(values.getValue("acceptInvalidCert")) },
        allowOldTls = field("allowOldTls", d.allowOldTls) { parseBoolean(values.getValue("allowOldTls")) },
        authMechanism = field("authMechanism", d.authMechanism) { percentDecode(values.getValue("authMechanism")).trim().uppercase() },
        clientCert = field("clientCert", d.clientCert) { percentDecode(values.getValue("clientCert")) },
        clientCertAlias = field("clientCertAlias", d.clientCertAlias) { percentDecode(values.getValue("clientCertAlias")) },
        inboundRules = field("inboundRules", d.inboundRules) { decodeInboundRules(values.getValue("inboundRules")) },
        watchedFolders = field("watchedFolders", d.watchedFolders) { decodeWatchedFolders(values.getValue("watchedFolders")) },
        extraIdleBudget = field("extraIdleBudget", d.extraIdleBudget) { parseIntField(values.getValue("extraIdleBudget")) },
        // Never unlimited: a stored value outside the range is clamped to it.
        maxConnections = field("maxConnections", d.maxConnections) {
            org.dlang.liveimap.session.clampMaxConnections(parseIntField(values.getValue("maxConnections")))
        },
        savedSearches = field("savedSearches", d.savedSearches) { decodeSavedSearches(values.getValue("savedSearches")) },
        savedAdvanced = field("savedAdvanced", d.savedAdvanced) { decodeSavedAdvanced(values.getValue("savedAdvanced")) },
        emailSubmission = field("emailSubmission", d.emailSubmission) { parseBoolean(values.getValue("emailSubmission")) },
        jmapFolders = field("jmapFolders", d.jmapFolders) { parseBoolean(values.getValue("jmapFolders")) },
        maxJmapResponseBytes = field("maxJmapResponseBytes", d.maxJmapResponseBytes) { parseIntField(values.getValue("maxJmapResponseBytes")) },
        maxResultUids = field("maxResultUids", d.maxResultUids) { parseIntField(values.getValue("maxResultUids")) },
        askSort = field("askSort", d.askSort) { parseAskLimit(values.getValue("askSort")) },
        askThread = field("askThread", d.askThread) { parseAskLimit(values.getValue("askThread")) },
        askSearch = field("askSearch", d.askSearch) { parseAskLimit(values.getValue("askSearch")) },
        askFallback = field("askFallback", d.askFallback) { parseAskLimit(values.getValue("askFallback")) },
        sortAskSkip = field("sortAskSkip", d.sortAskSkip) { decodeWatchedFolders(values.getValue("sortAskSkip")).toSet() },
        threadAskSkip = field("threadAskSkip", d.threadAskSkip) { decodeWatchedFolders(values.getValue("threadAskSkip")).toSet() },
        searchAskSkip = field("searchAskSkip", d.searchAskSkip) { decodeWatchedFolders(values.getValue("searchAskSkip")).toSet() },
        fallbackAskSkip = field("fallbackAskSkip", d.fallbackAskSkip) { decodeWatchedFolders(values.getValue("fallbackAskSkip")).toSet() },
        folderCountFreshSeconds = field("folderCountFreshSeconds", d.folderCountFreshSeconds) { parseIntField(values.getValue("folderCountFreshSeconds")) },
        androidContactsCacheSeconds = field("androidContactsCacheSeconds", d.androidContactsCacheSeconds) { parseIntField(values.getValue("androidContactsCacheSeconds")) },
        unknownSettings = unknown,
    )
}

