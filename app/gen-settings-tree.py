#!/usr/bin/env python3
"""Build settings-tree.json metadata and emit SettingsTreeGenerated / SettingsAccessGenerated.

Gradle calls: python3 app/gen-settings-tree.py <tree.json> <ktOutDir>
One-shot upgrade (reads the legacy array tree plus SettingsRegistry.kt): --upgrade
"""
import hashlib
import json
import re
import sys
from pathlib import Path

UI7 = [
    "indexPageSize", "swipeThresholdPercent", "reconnectDelaysSec",
    "maxRawViewBytes", "maxImageTrustBytes", "moveChunk", "clientSortChunk",
]
TOOLBARS = ["indexBar", "selectionBar", "folderBar", "readerToolbar", "composeBar", "toolbarRows"]
SERVER = [
    "imapHost", "imapPort", "tlsMode", "clientCertAlias", "certPin", "jmapCertPin",
    "smtpHost", "smtpPort", "smtpTlsMode", "smtpUsername", "sieveSameAsInbound", "sieveHost",
    "sieveUsername", "sievePort", "sieveTlsMode", "sieveCertPin", "sieveConnectSeconds", "sieveReadSeconds",
    "passwordStorage",
]
TOOLBAR_GROUPS = {
    "Toolbars", "Index toolbar", "Selection toolbar", "Folder toolbar", "Reader toolbar", "Compose toolbar",
}
JMAP_HIDDEN = {
    "imapPort", "tlsMode", "allowPlaintextAuth", "acceptInvalidCert", "allowOldTls",
    "authMechanism", "clientCertAlias", "certPin",
}
SMTP_HIDDEN = {"smtpHost", "smtpPort", "smtpTlsMode", "smtpUsername", "smtpSameAsImap", "smtpNoAuth"}
PANE_DISABLED = {"customLayout", "folderPane", "foldPosture", "paneArrangement", "folderPosition"}

# Existing curated resources whose English equals the registry title, or a known UI label.
TITLE = {
    "friendlyName": "settings_friendly_name",
    "email": "settings_email",
    "displayName": "settings_display_name",
    "altAddresses": "settings_alt_addresses",
    "jmapFolders": "settings_mail_access",
    "imapHost": "settings_imap_host",
    "imapPort": "settings_imap_port",
    "username": "settings_username",
    "tlsMode": "settings_tls",
    "acceptInvalidCert": "settings_accept_invalid_cert",
    "smtpHost": "settings_smtp_host",
    "smtpPort": "settings_smtp_port",
    "smtpUsername": "settings_smtp_username",
    "sieveHost": "settings_sieve_host",
    "sievePort": "settings_sieve_port",
    "sieveTlsMode": "settings_sieve_tls",
    "watchedFolders": "settings_watched_folders",
    "extraIdleBudget": "settings_extra_idle_budget",
    "sendingRules": "settings_sending_rules",
    "signature": "settings_signature",
    "sigDashes": "settings_sig_dashes",
    "sigAtBottom": "settings_sig_bottom",
    "replyLeadIn": "settings_lead_in",
    "composerHeaders": "settings_composer_headers",
    "sentMailbox": "settings_sent_mailbox",
    "postponedMailbox": "settings_postponed_mailbox",
    "addressBookMailbox": "settings_address_book_mailbox",
    "spamMailbox": "settings_spam_mailbox",
    "trashMailbox": "settings_trash_mailbox",
    "showUnreadCounts": "settings_show_unread",
    "expandedFolders": "settings_expanded_folders",
    "defaultView": "settings_default_view",
    "folderViews": "settings_folder_views",
    "markSeenOnOpen": "settings_mark_seen",
    "viewerHeaders": "settings_viewer_headers",
    "showDeleted": "settings_show_deleted",
    "autoExpunge": "settings_auto_expunge",
    "moveMethod": "settings_move_method",
    "bounceFcc": "settings_bounce_fcc",
    "composerWrapColumn": "settings_wrap_column",
    "replyAboveQuote": "settings_reply_above",
    "includeForwardAttachments": "settings_include_attachments",
    "forwardAsAttachment": "settings_forward_attachment",
    "savedMailbox": "settings_saved_mailbox",
    "saveNameRule": "settings_save_rule",
    "addressBookHistory": "settings_history_label",
    "addressBookNeverTrim": "settings_never_trim",
    "extraAddressBooks": "settings_extra_books",
    "globalAddressBooks": "settings_global_books",
    "theme": "settings_theme",
    "dynamicColor": "settings_dynamic_color",
    "density": "settings_density",
    "dateFormat": "settings_date_format",
    "datePattern": "settings_date_pattern",
    "foldPosture": "settings_fold_posture",
    "fullScreen": "settings_full_screen",
    "clientSort": "settings_client_sort",
    "clientThread": "settings_client_thread",
    "pollForNewMail": "settings_poll_mail",
    "forceSlowerFallbacks": "settings_force_slower",
    "logImapTraffic": "settings_log_traffic",
    "showUserInDebugReport": "settings_show_user",
    "indexBar": "settings_panel_index",
    "selectionBar": "settings_panel_selection",
    "folderBar": "settings_panel_folders",
    "readerToolbar": "settings_panel_reader",
    "composeBar": "settings_panel_compose",
    "toolbarRows": "toolbar_rows",
    "favorites": "settings_favorites",
    "pipelineCommands": "settings_pipeline",
    "quotePrefix": "pinerc_quote_prefix",
    "askBeforeExpunge": "settings_ask_expunge",
    "inboxStart": "settings_inbox_opens",
    "folderStart": "settings_other_opens",
    "askThread": "settings_ask_thread",
    "askSearch": "settings_ask_search",
    "askFallback": "settings_ask_fallback",
    "askSort": "settings_ask_sort",
    "maxConnections": "settings_max_connections",
    "multiPane": "settings_multi_pane",
    "folderPane": "settings_folder_pane",
    "paneArrangement": "settings_pane_arrangement",
    "previewSize": "settings_preview_size",
    "folderPosition": "settings_folder_position",
    "readerTextLayout": "settings_reader_display",
    "readerTextZoom": "settings_text_size",
    "nonAsciiDisplay": "settings_non_ascii",
    "compression": "settings_compression",
    "customLayout": "layout_custom",
    "threadIndexStyle": "settings_thread_index",
    "startAfterChange": "settings_after_change",
    "showRecentRules": "settings_show_recent",
    "openAtInIndexMenu": "settings_open_at_menu",
    "dsnMode": "settings_dsn",
    "emailSubmission": "settings_email_submission",
}

CHOICE = {
    "tlsMode": {"None": "settings_tls_none", "StartTls": "settings_tls_starttls", "Implicit": "settings_tls_implicit"},
    "smtpTlsMode": {"None": "settings_tls_none", "StartTls": "settings_tls_starttls", "Implicit": "settings_tls_implicit"},
    "sieveTlsMode": {"None": "settings_tls_none", "StartTls": "settings_tls_starttls", "Implicit": "settings_tls_implicit"},
    "theme": {"Dark": "label_dark", "Light": "label_light", "FollowSystem": "label_system"},
    "dateFormat": {"Local": "label_local", "Short": "label_short", "Relative": "label_relative", "Custom": "label_custom"},
    "density": {"Compact": "label_compact", "Medium": "label_comfortable", "Large": "label_large"},
    "bodyView": {
        "PlainOrHtml": "label_html", "PlainOrText": "label_html_text", "PlainOrError": "label_plain",
        "Headers": "label_headers", "Raw": "label_raw",
    },
    "deletePolicy": {
        "MarkDeleted": "settings_mark_deleted", "MoveToTrash": "settings_move_to_trash",
        "DeletePermanently": "settings_delete_permanently",
    },
    "compression": {"On": "settings_compression_on", "Off": "settings_compression_off"},
    "threadIndexStyle": {"Expanded": "settings_thread_expanded", "Collapsed": "settings_thread_collapsed"},
    "dsnMode": {
        "Off": "settings_dsn_off", "Failure": "settings_dsn_failure",
        "FailureDelay": "settings_dsn_delay", "All": "settings_dsn_all",
    },
    "inboxStart": {
        "FirstUnseen": "label_first_unread", "FirstRecent": "label_first_recent",
        "FirstImportant": "label_first_important", "FirstImportantOrUnseen": "label_first_important_unread",
        "FirstImportantOrRecent": "label_first_important_recent", "First": "label_top", "Last": "label_bottom",
        "Newest": "label_newest_message",
    },
    "startAfterChange": {"RerunRule": "label_rerun_rule", "KeepTopVisible": "label_keep_top"},
    "pinercStartDefault": {"LeaveUnchanged": "label_leave_unchanged", "AlpineDefault": "label_alpine_default"},
    "multiPane": {"Off": "settings_multi_pane_off", "Wide": "settings_multi_pane_wide", "Auto": "settings_pane_auto"},
    "folderPane": {"Off": "settings_multi_pane_off", "On": "settings_multi_pane_wide", "Auto": "settings_pane_auto"},
    "foldPosture": {"Off": "settings_multi_pane_off", "On": "settings_multi_pane_wide", "Auto": "settings_pane_auto"},
    "moveMethod": {"CopyThenMarkDeleted": "settings_copy_then_mark", "ImapMove": "settings_imap_move"},
    "folderStart": {
        "FirstUnseen": "label_first_unread", "FirstRecent": "label_first_recent",
        "FirstImportant": "label_first_important", "FirstImportantOrUnseen": "label_first_important_unread",
        "FirstImportantOrRecent": "label_first_important_recent", "First": "label_top", "Last": "label_bottom",
        "Newest": "label_newest_message",
    },
    "paneArrangement": {"Auto": "settings_pane_auto", "SideBySide": "settings_pane_side_by_side", "Stacked": "settings_pane_stacked"},
    "previewSize": {
        "Auto": "settings_pane_auto", "Density": "settings_preview_density", "None": "settings_preview_none",
        "Small": "settings_preview_small", "Large": "settings_preview_large",
    },
    "folderPosition": {"Auto": "settings_pane_auto", "Beside": "settings_folder_beside", "AboveList": "settings_folder_above"},
    "nonAsciiDisplay": {
        "Normal": "settings_non_ascii_normal", "HighlightSuspicious": "settings_non_ascii_suspicious",
        "HighlightAll": "settings_non_ascii_all", "Warn": "settings_non_ascii_warn", "Escape": "settings_non_ascii_escape",
    },
    "saveNameRule": {
        "DefaultFolder": "settings_save_default", "ByFrom": "settings_save_from", "BySender": "settings_save_sender",
        "ByRecipient": "settings_save_recipient", "LastFolderUsed": "settings_save_last",
    },
    "readerTextLayout": {"Text": "label_plain", "Flowed": "display_flowed", "Monospaced": "settings_monospace"},
}

EDITOR = {
    "hiddenCapabilities": ("hiddenOptions", "screen"),
    "swipeTrailing": ("swipes", "popup"),
    "swipeLeading": ("swipes", "popup"),
    "readerSwipeTrailing": ("swipes", "popup"),
    "readerSwipeLeading": ("swipes", "popup"),
    "clientCert": ("clientCert", "inline"),
    "clientCertAlias": ("clientCert", "inline"),
    "sendingRules": ("rules", "screen"),
    "inboundRules": ("rules", "screen"),
    "favorites": ("folderLists", "screen"),
    "folderViews": ("folderLists", "screen"),
    "folderStarts": ("folderLists", "screen"),
    "expandedFolders": ("folderLists", "screen"),
    "watchedFolders": ("folderLists", "screen"),
    "extraAddressBooks": ("addressBooks", "screen"),
    "globalAddressBooks": ("addressBooks", "screen"),
    "completionSources": ("addressBooks", "screen"),
    "customLayout": ("paneLayout", "screen"),
    "compression": ("compression", "inline"),
    "deletePolicy": ("deletePolicy", "inline"),
    "moveMethod": ("moveMethod", "inline"),
}

PINERC = {
    "displayName": ("personal-name", "text"),
    "altAddresses": ("alt-addresses", "list"),
    "addressBookHistory": ("remote-abook-history", "int"),
    "defaultView": ("sort-key", "sort"),
    "inboxStart": ("incoming-startup-rule", "startup"),
    "signature": ("literal-signature", "signature"),
    "quotePrefix": ("reply-indent-string", "text"),
    "replyLeadIn": ("reply-leadin", "leadin"),
    "composerHeaders": ("customized-hdrs", "headers"),
    "viewerHeaders": ("viewer-hdrs", "headers"),
    "savedMailbox": ("default-saved-msg-folder", "mailbox"),
    "saveNameRule": ("saved-msg-name-rule", "saveRule"),
    "threadIndexStyle": ("threading-index-style", "threadStyle"),
    "composerWrapColumn": ("composer-wrap-column", "int"),
    "sentMailbox": ("default-fcc", "folder"),
    "postponedMailbox": ("postponed-folder", "folder"),
    "addressBookMailbox": ("address-book", "books"),
    "extraAddressBooks": ("address-book", "booksExtra"),
    "globalAddressBooks": ("global-address-book", "books"),
    "favorites": ("incoming-folders", "incoming"),
    "imapHost": ("inbox-path", "remote"),
    "imapPort": ("inbox-path", "remote"),
    "tlsMode": ("inbox-path", "remote"),
    "username": ("user-id", "text"),
    "email": ("user-domain", "email"),
    "smtpHost": ("smtp-server", "smtp"),
    "smtpPort": ("smtp-server", "smtp"),
    "smtpTlsMode": ("smtp-server", "smtp"),
    "smtpUsername": ("smtp-server", "smtp"),
    "acceptInvalidCert": ("inbox-path", "novalidate"),
    "sendingRules": ("patterns-roles", "roles"),
    "sigDashes": ("feature-list", "feature"),
    "sigAtBottom": ("feature-list", "feature"),
    "dsnMode": ("feature-list", "feature"),
    "askBeforeExpunge": ("feature-list", "feature"),
}

SUMMARIES = {
    "Account": [{"when": {"setting": "imapHost", "empty": True}, "text": "settings_no_server"}, {"value": {"setting": "imapHost"}}],
    "Identity": [{"when": {"setting": "email", "empty": True}, "value": {"setting": "displayName"}}, {"value": {"setting": "email"}}],
    "Mail access": [{"value": {"setting": "jmapFolders", "titles": {"true": "settings_access_jmap", "false": "settings_access_imap"}}}],
    "Server": [
        {"when": {"setting": "imapHost", "empty": True}, "text": "settings_no_server"},
        {"when": {"setting": "jmapFolders", "equals": True}, "value": {"setting": "imapHost"}},
        {"text": "settings_host_port", "args": [{"setting": "imapHost"}, {"setting": "imapPort"}]},
    ],
    "Sending": [
        {"when": {"setting": "emailSubmission", "equals": True}, "text": "settings_send_over_jmap"},
        {"when": {"setting": "smtpHost", "empty": True}, "text": "settings_no_smtp"},
        {"text": "settings_host_port", "args": [{"setting": "smtpHost"}, {"setting": "smtpPort"}]},
    ],
    "Watched folders": [
        {"when": {"setting": "watchedFolders", "empty": True}, "text": "settings_watched_none"},
        {"value": {"count": "watchedFolders"}},
    ],
    "Sending rules": [{"text": "settings_rules_summary", "args": [{"format": "rulesWho"}, {"count": "sendingRules"}]}],
    "Mailboxes": [{"text": "settings_mailboxes_set", "args": [{"countSet": ["sentMailbox", "postponedMailbox", "addressBookMailbox", "spamMailbox", "trashMailbox"]}]}],
    "Special-use": [{"text": "settings_mailboxes_set", "args": [{"countSet": ["sentMailbox", "postponedMailbox", "addressBookMailbox", "spamMailbox", "trashMailbox"]}]}],
    "Folders": [{"text": "settings_sort_line", "args": [
        {"choiceTitle": "defaultView", "field": "key"},
        {"setting": "defaultView", "field": "newestFirst", "titles": {"true": "reader_newest", "false": "reader_oldest"}},
    ]}],
    "Folder views": [{"text": "settings_sort_line", "args": [
        {"choiceTitle": "defaultView", "field": "key"},
        {"setting": "defaultView", "field": "newestFirst", "titles": {"true": "reader_newest", "false": "reader_oldest"}},
    ]}],
    "Folder list": [{"plural": "settings_expanded", "count": "expandedFolders"}],
    "Start position": [{"text": "settings_inbox_line", "args": [{"choiceTitle": "inboxStart"}]}],
    "Large folders": [{"text": "settings_ask_note"}],
    "Reading": [{"value": {"choiceTitle": "bodyView"}}],
    "Opening": [{"value": {"choiceTitle": "bodyView"}}],
    "Deleting": [{"value": {"setting": "askBeforeExpunge", "titles": {"true": "settings_ask_expunge", "false": "settings_expunge_without"}}}],
    "Actions": [{"text": "settings_swipe_line", "args": [
        {"choiceTitle": "swipeTrailing", "field": "action"},
        {"choiceTitle": "swipeLeading", "field": "action"},
    ]}],
    "Compose": [{"value": {"setting": "replyAboveQuote", "titles": {"true": "settings_reply_above", "false": "settings_reply_below"}}}],
    "Replying": [{"value": {"setting": "replyAboveQuote", "titles": {"true": "settings_reply_above", "false": "settings_reply_below"}}}],
    "Composing": [{"value": {"setting": "composerWrapColumn"}}],
    "Forwarding": [
        {"when": {"setting": "forwardAsAttachment", "equals": True}, "text": "settings_forward_attachment"},
        {"when": {"setting": "includeForwardAttachments", "equals": True}, "text": "settings_include_short"},
        {"text": "settings_attachments_off"},
    ],
    "Fcc": [{"value": {"choiceTitle": "saveNameRule"}}],
    "Address completion": [
        {"when": {"setting": "completionSources", "empty": True}, "text": "settings_no_sources"},
        {"when": {"setting": "completionSources", "equals": ["pine"]}, "text": "settings_pine"},
        {"plural": "settings_sources", "count": "completionSources"},
    ],
    "Address book": [
        {"when": {"setting": "addressBookNeverTrim", "equals": True}, "text": "settings_never_trim"},
        {"text": "settings_history", "args": [{"setting": "addressBookHistory"}]},
    ],
    "Appearance": [{"value": {"choiceTitle": "theme"}}],
    "Theme": [
        {"when": {"all": [{"sdkAtLeast": 31}, {"setting": "dynamicColor", "equals": True}]}, "text": "settings_dynamic_line", "args": [{"choiceTitle": "theme"}]},
        {"value": {"choiceTitle": "theme"}},
    ],
    "Display": [{"text": "settings_display_line", "args": [{"choiceTitle": "density"}, {"choiceTitle": "dateFormat"}]}],
    "Layout": [{"value": {"choiceTitle": "multiPane"}}],
    "Custom panel layout": [
        {"when": {"setting": "customLayout", "empty": True}, "text": "settings_pane_auto"},
        {"text": "layout_custom"},
    ],
    "Slower": [
        {"when": {"any": [
            {"setting": "clientSort", "equals": True}, {"setting": "clientThread", "equals": True},
            {"setting": "pollForNewMail", "equals": True}, {"setting": "statusVisibleCounts", "equals": True},
        ]}, "text": "settings_slower_on"},
        {"text": "settings_slower_off"},
    ],
    "Debug": [{"value": {"setting": "logImapTraffic", "titles": {"true": "settings_traffic_on", "false": "settings_traffic_off"}}}],
    "Logging": [{"text": "settings_logging_line", "args": [
        {"setting": "pipelineCommands", "titles": {"true": "settings_pipeline_on", "false": "settings_pipeline_off"}},
        {"setting": "logImapTraffic", "titles": {"true": "settings_log_on", "false": "settings_log_off"}},
    ]}],
    "Hide capabilities": [
        {"when": {"setting": "hiddenCapabilities", "empty": True}, "text": "settings_hide_none"},
        {"text": "settings_hide_count", "args": [{"count": "hiddenCapabilities"}]},
    ],
    "Favorites": [{"value": {"count": "favorites"}}],
}


def slug(title):
    return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")


def parse_registry(path):
    src = Path(path).read_text()
    body = src.split('const val registrySpec: String = """', 1)[1].split('"""', 1)[0]
    reg = {}
    for line in body.splitlines():
        line = line.strip()
        if not line:
            continue
        key, typ, scope, control, title, extra, hard = line.split("|")
        enum_values = extra.split(",") if typ == "Enum" and extra else []
        int_choices = []
        if typ == "Int" and extra and all(c.isdigit() or c == "," for c in extra):
            int_choices = [int(x) for x in extra.split(",")]
        limit = None
        if hard.startswith("list:"):
            bits = hard[len("list:"):].split(":")
            def num(s):
                return int(s) if s else None
            limit = {"minItems": num(bits[0]), "maxItems": num(bits[1]), "itemMin": num(bits[2]), "itemMax": num(bits[3])}
        elif hard:
            a, b = hard.split("..")
            limit = {"min": int(a), "max": int(b)}
        reg[key] = {
            "type": typ, "scope": scope, "control": control, "title": title,
            "enum": enum_values, "intChoices": int_choices, "hard": limit, "nullable": extra == "nullable",
        }
    return reg


def choice_titles(key, choices):
    known = CHOICE.get(key, {})
    out = {}
    for choice in choices:
        out[str(choice)] = known.get(str(choice), "setting_%s_%s" % (key, str(choice)))
    return out


def upgrade_leaf(node, reg):
    key = node["setting"]
    spec = reg[key]
    scope = "ui" if key in UI7 or key in TOOLBARS else "account"
    leaf = {
        "setting": key,
        "type": spec["type"],
        "scope": scope,
        "kind": spec["control"],
        "default": node["default"],
        "title": TITLE.get(key, "setting_%s_title" % key),
    }
    if key in SERVER:
        leaf["server"] = True
    if spec["enum"]:
        leaf["choices"] = spec["enum"]
        leaf["choiceTitles"] = choice_titles(key, spec["enum"])
    elif spec["intChoices"]:
        leaf["choices"] = spec["intChoices"]
        leaf["choiceTitles"] = choice_titles(key, spec["intChoices"])
    if spec["nullable"]:
        leaf["nullable"] = True
    hard = spec["hard"] or {}
    for name, value in hard.items():
        if value is not None:
            leaf[name] = value
    for name in ("min", "max", "minItems", "maxItems", "itemMin", "itemMax", "order"):
        if name in node:
            leaf[name] = node[name]
    if "hardLimit" in node:
        for name, value in node["hardLimit"].items():
            leaf[name] = value
    if key in JMAP_HIDDEN:
        leaf["visibleWhen"] = {"setting": "jmapFolders", "equals": False}
    if key in SMTP_HIDDEN:
        leaf["visibleWhen"] = {"setting": "emailSubmission", "equals": False}
    if key in ("smtpUsername", "smtpSameAsImap"):
        leaf["enabledWhen"] = {"setting": "smtpNoAuth", "notEquals": "On"}
    if key == "datePattern":
        leaf["visibleWhen"] = {"setting": "dateFormat", "equals": "Custom"}
    if key == "dynamicColor":
        leaf["visibleWhen"] = {"sdkAtLeast": 31}
    if key in PANE_DISABLED:
        leaf["enabledWhen"] = {"setting": "multiPane", "notEquals": "Off"}
    if key == "imapHost":
        leaf["titleWhen"] = [{
            "when": {"setting": "jmapFolders", "equals": True},
            "title": "settings_jmap_host",
        }]
    if key in PINERC:
        leaf["pinerc"], leaf["pinercFormat"] = PINERC[key]
    if key == "preferHtml":
        leaf["derived"] = True
    if key in EDITOR:
        leaf["editor"], leaf["opens"] = EDITOR[key]
    if key == "maxConnections":
        leaf["titleArgs"] = "range"
    if key == "altAddresses":
        leaf["format"] = "commaList"
    return leaf


def fresh_leaf(key, typ, scope, kind, default, title, **extra):
    leaf = {
        "setting": key, "type": typ, "scope": scope, "kind": kind, "default": default, "title": title,
    }
    leaf.update(extra)
    return leaf


def upgrade_group(node, reg):
    title = node["title"]
    if title in TOOLBAR_GROUPS:
        return None
    children = []
    for child in node["children"]:
        if "setting" in child:
            if child["setting"] in UI7 or child["setting"] in TOOLBARS:
                continue
            children.append(upgrade_leaf(child, reg))
        else:
            grouped = upgrade_group(child, reg)
            if grouped is not None and grouped["children"]:
                children.append(grouped)
    if title == "Folder list":
        children.append(fresh_leaf(
            "folderCountFreshSeconds", "Int", "account", "number", 300,
            "setting_folderCountFreshSeconds_title", min=1, max=86400,
        ))
    if title == "Address book":
        children.append(fresh_leaf(
            "androidContactsCacheSeconds", "Int", "account", "number", 300,
            "setting_androidContactsCacheSeconds_title", min=0, max=86400,
        ))
    group = {
        "group": slug(title),
        "title": "settings_group_%s_title" % slug(title),
        "label": title,
        "header": "label",
        "children": children,
    }
    if title in SUMMARIES:
        group["summary"] = SUMMARIES[title]
    if title == "Watched folders":
        group["visibleWhen"] = {"setting": "jmapFolders", "equals": False}
    if title == "Server":
        group["editor"] = "server"
        group["opens"] = "inline"
        group["introWhen"] = [
            {"when": {"setting": "jmapFolders", "equals": True}, "text": "settings_jmap_server_note"},
            {"text": "settings_plaintext_ports"},
        ]
    return group


def upgrade(tree_path, registry_path):
    tree = json.loads(Path(tree_path).read_text())
    reg = parse_registry(registry_path)
    account_children = []
    for node in tree:
        grouped = upgrade_group(node, reg)
        if grouped is not None and grouped["children"]:
            account_children.append(grouped)
    globals_ = []
    for key in UI7:
        # Defaults live on the legacy leaves inside Slower.
        found = find_setting(tree, key)
        if found is None:
            raise SystemExit("missing legacy leaf " + key)
        globals_.append(upgrade_leaf(found, reg))
    toolbars = []
    for key in TOOLBARS:
        found = find_setting(tree, key)
        if found is None:
            raise SystemExit("missing legacy leaf " + key)
        toolbars.append(upgrade_leaf(found, reg))
    opens = fresh_leaf(
        "settingsOpensOn", "Enum", "ui", "choice", "drawer", "setting_settingsOpensOn_title",
        choices=["drawer", "lastEdited"],
        choiceTitles={
            "drawer": "setting_settingsOpensOn_drawer",
            "lastEdited": "setting_settingsOpensOn_lastEdited",
        },
    )
    ui_children = globals_ + [{
        "editor": "toolbars",
        "opens": "screen",
        "title": "settings_toolbars",
        "settings": toolbars,
    }, opens]
    doc = {
        "pages": [
            {
                "page": "top",
                "title": "drawer_settings",
                "children": [
                    {
                        "page": "ui",
                        "title": "settings_all_accounts",
                        "summary": [{"text": "settings_all_accounts_summary"}],
                        "children": ui_children,
                    },
                    {
                        "editor": "accountList",
                        "opens": "screen",
                        "title": "settings_accounts",
                        "summary": [{"plural": "settings_account_count", "count": "$accounts"}],
                    },
                    {"editor": "pinerc", "opens": "screen", "title": "settings_import"},
                ],
            },
            {"page": "account", "title": "settings_account", "children": account_children},
        ],
        "stored": [
            fresh_leaf(
                "settingsLastEditedAccount", "String", "ui", "text", "",
                "setting_settingsLastEditedAccount_title",
            ),
        ],
    }
    return doc


def find_setting(nodes, key):
    for node in nodes:
        if isinstance(node, dict) and node.get("setting") == key:
            return node
        kids = node.get("children") if isinstance(node, dict) else None
        if kids:
            found = find_setting(kids, key)
            if found is not None:
                return found
    return None


def walk_nodes(nodes, visit):
    for node in nodes:
        visit(node)
        for key in ("children", "settings"):
            if isinstance(node.get(key), list) and node[key] and isinstance(node[key][0], dict):
                walk_nodes(node[key], visit)


def all_leaves(doc):
    found = []
    def visit(node):
        if "setting" in node:
            found.append(node)
    for page in doc["pages"]:
        walk_nodes([page], visit)
    walk_nodes(doc["stored"], visit)
    return found


def groups_of(doc):
    found = []
    def visit(node):
        if "group" in node:
            found.append(node)
    for page in doc["pages"]:
        walk_nodes([page], visit)
    return found


def kt_string(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def emit(doc, kt_root, tree_bytes):
    leaves = all_leaves(doc)
    groups = groups_of(doc)
    by_key = {}
    for leaf in leaves:
        if leaf["setting"] in by_key:
            raise SystemExit("duplicate " + leaf["setting"])
        by_key[leaf["setting"]] = leaf
    fingerprint = hashlib.sha256(tree_bytes).hexdigest()
    kt_dir = Path(kt_root) / "org" / "dlang" / "liveimap" / "settings"
    kt_dir.mkdir(parents=True, exist_ok=True)
    resource_names = []
    plural_names = set()
    def add_res(name):
        if isinstance(name, str) and name and name not in resource_names:
            resource_names.append(name)
    def collect_strings(value):
        if isinstance(value, dict):
            if isinstance(value.get("text"), str):
                add_res(value["text"])
            if isinstance(value.get("plural"), str):
                plural_names.add(value["plural"])
                add_res(value["plural"])
            if isinstance(value.get("note"), str):
                add_res(value["note"])
            if isinstance(value.get("title"), str):
                add_res(value["title"])
            titles = value.get("titles")
            if isinstance(titles, dict):
                for item in titles.values():
                    add_res(item)
            choices = value.get("choiceTitles")
            if isinstance(choices, dict):
                for item in choices.values():
                    add_res(item)
            for child in value.values():
                collect_strings(child)
        elif isinstance(value, list):
            for item in value:
                collect_strings(item)
    collect_strings(doc)
    add_res("settings_all_accounts_summary")
    add_res("settings_logging_line")
    add_res("settings_account_count")
    plural_names.add("settings_account_count")
    add_res("sending_rule_numbered")
    add_res("address_book_row")
    resource_names = sorted(set(resource_names))
    def res_expr(name):
        kind = "plurals" if name in plural_names else "string"
        return "R.%s.%s" % (kind, name)
    group_ids = [group["group"] for group in groups]
    lines = [
        "package org.dlang.liveimap.settings",
        "",
        "import com.davidlang.liveimap.R",
        "",
        "object SettingsTreeGenerated {",
        "    const val fingerprint: String = %s" % kt_string(fingerprint),
        "    val groupIds: List<String> = listOf(%s)" % ", ".join(kt_string(i) for i in group_ids),
        "    val resourceNames: List<String> = listOf(%s)" % ", ".join(kt_string(n) for n in resource_names),
        "    private val resourceKeys: Array<String> = arrayOf(%s)" % ", ".join(kt_string(n) for n in resource_names),
        "    private val resourceIds: IntArray = intArrayOf(%s)" % ", ".join(res_expr(n) for n in resource_names),
        "    val pluralNames: List<String> = listOf(%s)" % ", ".join(kt_string(n) for n in sorted(plural_names)),
        "",
        "    fun resource(name: String): Int {",
        "        val index = resourceKeys.binarySearch(name)",
        "        return if (index >= 0) resourceIds[index] else 0",
        "    }",
        "",
        "    fun titleOf(id: String): Int = resource(\"settings_group_${id}_title\")",
        "",
        "    private val titleKeys: Array<String> = arrayOf(%s)" % ", ".join(kt_string(leaf["setting"]) for leaf in sorted(leaves, key=lambda item: item["setting"])),
        "    private val titleIds: IntArray = intArrayOf(%s)" % ", ".join("R.string.%s" % leaf.get("title", "drawer_settings") for leaf in sorted(leaves, key=lambda item: item["setting"])),
        "",
        "    fun regTitle(key: String): Int {",
        "        val index = titleKeys.binarySearch(key)",
        "        return if (index >= 0) titleIds[index] else 0",
        "    }",
        "",
        "    fun warm(): Int {",
        "        var n = 0",
    ]
    for name in resource_names:
        lines.append("        n += resource(%s)" % kt_string(name))
    lines += [
        "        return n",
        "    }",
        "}",
        "",
        "object SettingsDefaults {",
    ]
    for leaf in leaves:
        key = leaf["setting"]
        typ = leaf["type"]
        default = leaf["default"]
        if typ == "Int":
            lines.append("    const val %s: Int = %s" % (key, int(default)))
        elif typ == "Long":
            lines.append("    const val %s: Long = %sL" % (key, int(default)))
        elif typ == "Bool":
            lines.append("    const val %s: Boolean = %s" % (key, "true" if default else "false"))
        elif key in ("tlsMode", "multiPane", "folderPane", "paneArrangement", "previewSize", "folderPosition"):
            lines.append("    const val %s: String = %s" % (key, kt_string(default)))
    extra_max = by_key["extraIdleBudget"].get("max", 8)
    lines.append("    const val extraIdleBudgetMax: Int = %s" % int(extra_max))
    lines.append("}")
    lines.append("")
    (kt_dir / "SettingsTreeGenerated.kt").write_text("\n".join(lines) + "\n")
    emit_access(doc, leaves, groups, kt_dir)
    print("groups", len(groups), "leaves", len(leaves), "fingerprint", fingerprint)


ENUM_CLASS = {
    "tlsMode": "TlsMode",
    "smtpTlsMode": "TlsMode",
    "smtpNoAuth": "SmtpNoAuth",
    "sieveTlsMode": "TlsMode",
    "passwordStorage": "PasswordStorageChoice",
    "threadIndexStyle": "ThreadIndexStyle",
    "inboxStart": "StartRule",
    "folderStart": "StartRule",
    "startAfterChange": "StartAfterChange",
    "pinercStartDefault": "PinercStartDefault",
    "bodyView": "BodyView",
    "readerTextLayout": "TextLayout",
    "nonAsciiDisplay": "NonAsciiDisplay",
    "deletePolicy": "DeletePolicy",
    "moveMethod": "MoveMethod",
    "compression": "Compression",
    "saveNameRule": "SaveNameRule",
    "dsnMode": "org.dlang.liveimap.ui.compose.DsnMode",
    "theme": "ThemeMode",
    "density": "Density",
    "dateFormat": "DateFormat",
    "multiPane": "MultiPane",
    "folderPane": "LayoutChoice",
    "foldPosture": "LayoutChoice",
    "paneArrangement": "ArrangementChoice",
    "previewSize": "PreviewChoice",
    "folderPosition": "FolderPositionChoice",
    "clientSortCollation": "ClientSortCollation",
}

SET_FIELDS = {
    "expandedFolders",
    "hiddenCapabilities",
    "sortAskSkip",
    "threadAskSkip",
    "searchAskSkip",
    "fallbackAskSkip",
}

TYPE_OVERRIDE = {
    "defaultView": "FolderView",
    "folderViews": "Map<String, FolderView>",
    "folderStarts": "Map<String, StartRule>",
    "favorites": "List<FolderFavorite>",
    "inboundRules": "List<InboundRule>",
    "savedSearches": "List<SavedSearch>",
    "savedAdvanced": "List<SavedAdvanced>",
    "swipeTrailing": "SwipeBinding",
    "swipeLeading": "SwipeBinding",
    "readerSwipeTrailing": "SwipeBinding",
    "readerSwipeLeading": "SwipeBinding",
    "altAddresses": "List<String>",
    "watchedFolders": "List<String>",
    "completionSources": "List<String>",
    "extraAddressBooks": "List<String>",
    "globalAddressBooks": "List<String>",
}

# Readers that are not the type's ordinary decode. The id still comes from the JSON leaf.
DECODE_READER = {
    "authMechanism": 'percentDecode(values.getValue("authMechanism")).trim().uppercase()',
    "completionSources": 'decodeCompletionSources(values["completionSources"])',
    "customLayout": 'values["customLayout"]?.let { percentDecode(it) }?.takeIf { decodePaneLayout(it) != null }.orEmpty()',
    "readerTextZoom": 'clampTextZoom(parseIntField(values.getValue("readerTextZoom")))',
    "composerWrapColumn": 'parseIntField(values.getValue("composerWrapColumn")).coerceIn(0, 998)',
    "sievePort": 'parseSievePort(values.getValue("sievePort"))',
    "maxConnections": 'org.dlang.liveimap.session.clampMaxConnections(parseIntField(values.getValue("maxConnections")))',
    "askSort": 'parseAskLimit(values.getValue("askSort"))',
    "askThread": 'parseAskLimit(values.getValue("askThread"))',
    "askSearch": 'parseAskLimit(values.getValue("askSearch"))',
    "askFallback": 'parseAskLimit(values.getValue("askFallback"))',
    "bodyView": "decodeBodyView(values)",
    "sigDashes": 'values.getValue("sigDashes") == "true"',
    "sigAtBottom": 'values.getValue("sigAtBottom") == "true"',
    "smtpSameAsImap": 'values["smtpSameAsImap"] == "true"',
}

SETTINGS_DEFAULT_ENUM = {
    "tlsMode",
    "multiPane",
    "folderPane",
    "paneArrangement",
    "previewSize",
    "folderPosition",
}


def account_leaves(leaves):
    found = [leaf for leaf in leaves if leaf.get("scope") == "account"]
    for leaf in found:
        if leaf.get("derived") and leaf["setting"] != "preferHtml":
            raise SystemExit("derived account leaf " + leaf["setting"])
    return found


def kt_type(leaf):
    key = leaf["setting"]
    if key in ENUM_CLASS:
        return ENUM_CLASS[key]
    if key in SET_FIELDS:
        return "Set<String>"
    if key in TYPE_OVERRIDE:
        return TYPE_OVERRIDE[key]
    kind = leaf["type"]
    if kind in ("String", "Encoded"):
        return "String"
    if kind == "Int":
        return "Int"
    if kind == "Bool":
        return "Boolean"
    if kind == "StringList":
        return "List<String>"
    raise SystemExit("no Kotlin type for " + key)


def kt_default(leaf):
    key = leaf["setting"]
    typ = kt_type(leaf)
    default = leaf.get("default")
    if key == "smtpTlsMode":
        return "enumValueOf(SettingsDefaults.tlsMode)"
    if key in SETTINGS_DEFAULT_ENUM:
        return "enumValueOf(SettingsDefaults.%s)" % key
    if key == "clientSortCollation":
        if default == "locale":
            return "ClientSortCollation.Locale"
        if default == "plain":
            return "ClientSortCollation.Plain"
        raise SystemExit("clientSortCollation default")
    if key in ENUM_CLASS:
        return "%s.%s" % (ENUM_CLASS[key], default)
    if typ == "Int":
        return "SettingsDefaults.%s" % key
    if typ == "Boolean":
        return "true" if default else "false"
    if typ == "String":
        return kt_string("" if default is None else default)
    if typ == "FolderView":
        return "parseView(%s)" % kt_string(default)
    if typ == "SwipeBinding":
        return "parseSwipe(%s)" % kt_string(default)
    if typ.startswith("Map"):
        if default not in (None, ""):
            raise SystemExit("map default " + key)
        return "emptyMap()"
    if typ.startswith("Set"):
        if default not in (None, "", []):
            raise SystemExit("set default " + key)
        return "emptySet()"
    if typ.startswith("List"):
        if key == "completionSources":
            if default != ["pine"]:
                raise SystemExit("completionSources default")
            return "listOf(pineSourceId)"
        if default not in (None, "", []):
            raise SystemExit("list default " + key)
        return "emptyList()"
    raise SystemExit("default " + key)


def encode_put(leaf):
    key = leaf["setting"]
    if key in COMPLEX:
        value = COMPLEX[key][0]
    else:
        typ = kt_type(leaf)
        if typ == "String":
            value = "percentEncode(%s)" % key
        elif typ in ("Int", "Boolean"):
            value = key
        elif key in ENUM_CLASS:
            value = "%s.name" % key
        else:
            raise SystemExit("encode " + key)
    return 'put("%s=${%s}")' % (key, value)


def decode_reader(leaf):
    key = leaf["setting"]
    if key in DECODE_READER:
        return DECODE_READER[key]
    if key in COMPLEX and key != "clientSortCollation":
        return COMPLEX[key][1].replace("value.text", 'values.getValue("%s")' % key)
    typ = kt_type(leaf)
    if typ == "String":
        return 'percentDecode(values.getValue("%s"))' % key
    if typ == "Boolean":
        return 'parseBoolean(values.getValue("%s"))' % key
    if typ == "Int":
        return 'parseIntField(values.getValue("%s"))' % key
    if key in ENUM_CLASS:
        return "enumValueOf<%s>(values.getValue(%s))" % (ENUM_CLASS[key], json.dumps(key))
    raise SystemExit("decode " + key)


def special_decode(key):
    if key == "tlsMode":
        return "tlsMode = decodedTlsMode,"
    if key == "smtpTlsMode":
        return "smtpTlsMode = decodedSmtpTls,"
    if key == "readerTextLayout":
        return 'readerTextLayout = if ("readerTextLayout" !in values) d.readerTextLayout else enumValueOf<TextLayout>(values.getValue("readerTextLayout")),'
    if key == "compression":
        return "\n".join([
            'compression = if ("compression" !in values) d.compression else when (values.getValue("compression")) {',
            '    "Auto" -> Compression.On',
            '    else -> enumValueOf<Compression>(values.getValue("compression"))',
            "},",
        ])
    if key == "smtpNoAuth":
        return "\n".join([
            'smtpNoAuth = if ("smtpNoAuth" !in values) d.smtpNoAuth else when (values.getValue("smtpNoAuth")) {',
            '    "Auto" -> SmtpNoAuth.Off',
            '    else -> enumValueOf<SmtpNoAuth>(values.getValue("smtpNoAuth"))',
            "},",
        ])
    if key == "paneArrangement":
        return 'paneArrangement = if ("paneArrangement" !in values) d.paneArrangement else enumValueOf<ArrangementChoice>(values.getValue("paneArrangement")),'
    if key == "folderPosition":
        return 'folderPosition = if ("folderPosition" !in values) d.folderPosition else enumValueOf<FolderPositionChoice>(values.getValue("folderPosition")),'
    if key == "dsnMode":
        return 'dsnMode = if ("dsnMode" !in values) d.dsnMode else enumValueOf<org.dlang.liveimap.ui.compose.DsnMode>(values.getValue("dsnMode")),'
    if key == "nonAsciiDisplay":
        return 'nonAsciiDisplay = if ("nonAsciiDisplay" !in values) d.nonAsciiDisplay else enumValueOf<NonAsciiDisplay>(values.getValue("nonAsciiDisplay")),'
    if key == "previewSize":
        return 'previewSize = if ("previewSize" !in values) d.previewSize else enumValueOf<PreviewChoice>(values.getValue("previewSize")),'
    if key == "idleWakeSeconds":
        return "\n".join([
            'idleWakeSeconds = field("idleWakeSeconds", d.idleWakeSeconds) {',
            '    val seconds = parseIntField(values.getValue("idleWakeSeconds"))',
            '    if (seconds !in IdleWakeSeconds) throw IllegalArgumentException("bad idleWakeSeconds")',
            "    seconds",
            "},",
        ])
    if key == "clientSortCollation":
        return "\n".join([
            'clientSortCollation = if ("clientSortCollation" !in values) {',
            "    d.clientSortCollation",
            "} else {",
            '    val word = values.getValue("clientSortCollation")',
            '    if (word != "locale" && word != "plain") throw IllegalArgumentException("clientSortCollation")',
            '    field("clientSortCollation", d.clientSortCollation) { clientSortCollationOf(word) }',
            "},",
        ])
    if key == "unlabeledCharset":
        return "\n".join([
            'unlabeledCharset = if ("unlabeledCharset" !in values) {',
            "    d.unlabeledCharset",
            "} else {",
            "    val decoded = try {",
            '        percentDecode(values.getValue("unlabeledCharset"))',
            "    } catch (_: IllegalArgumentException) {",
            '        throw IllegalArgumentException("unlabeledCharset")',
            "    }",
            '    if (!knownUnlabeledCharset(decoded)) throw IllegalArgumentException("unlabeledCharset")',
            '    field("unlabeledCharset", d.unlabeledCharset) { decoded }',
            "},",
        ])
    return None


def decode_line(leaf):
    key = leaf["setting"]
    special = special_decode(key)
    if special is not None:
        return special
    return '%s = field("%s", d.%s) { %s },' % (key, key, key, decode_reader(leaf))


def indent_block(text, spaces):
    pad = " " * spaces
    return "\n".join(pad + line if line else line for line in text.split("\n"))


def generated_account_record(leaves):
    rows = account_leaves(leaves)
    fields = []
    names = []
    puts = []
    decoded = []
    for leaf in rows:
        names.append('    "%s",' % leaf["setting"])
        puts.append("    " + encode_put(leaf))
        if leaf.get("derived"):
            continue
        fields.append("    val %s: %s = %s," % (leaf["setting"], kt_type(leaf), kt_default(leaf)))
        decoded.append(indent_block(decode_line(leaf), 8))
    return {
        "gen-fields": "\n".join(fields),
        "gen-names": "\n".join(names),
        "gen-encode": "\n".join(puts),
        "gen-decode": "\n".join(decoded),
    }


def emit_access(doc, leaves, groups, kt_dir):
    # Constructor fields, fieldNames, encode() lines and the decode table come from the JSON ids.
    template_path = Path(__file__).with_name("gen-account-template.kt")
    if not template_path.is_file():
        (kt_dir / "SettingsAccessGenerated.kt").write_text(access_stub())
        return
    body = template_path.read_text()
    for marker, text in generated_account_record(leaves).items():
        token = "/* @%s */" % marker
        if token not in body:
            raise SystemExit("missing " + token)
        body = body.replace(token, text, 1)
    global _FIELD_TYPES
    _FIELD_TYPES = {}
    for leaf in account_leaves(leaves):
        if not leaf.get("derived"):
            _FIELD_TYPES[leaf["setting"]] = kt_type(leaf)
    group_of = {}
    def visit(node, group):
        if "group" in node:
            group = node["group"]
        if "setting" in node and node.get("scope") == "account" and not node.get("derived"):
            group_of[node["setting"]] = group or "account"
        for key in ("children", "settings"):
            kids = node.get(key)
            if isinstance(kids, list):
                for child in kids:
                    if isinstance(child, dict):
                        visit(child, group)
    for page in doc["pages"]:
        visit(page, None)
    buckets = {}
    for key, group in group_of.items():
        buckets.setdefault(group, []).append(key)
    imports = account_imports()
    parts = [
        "@file:JvmName(\"SettingsAccessGeneratedKt\")",
        "package org.dlang.liveimap.settings",
        "",
        "import android.content.Context",
        imports,
        "",
        body.rstrip(),
        "",
        generated_with(buckets),
        "",
        generated_ui(leaves),
        "",
    ]
    (kt_dir / "SettingsAccessGenerated.kt").write_text("\n".join(parts) + "\n")


def access_stub():
    return "\n".join([
        "@file:JvmName(\"SettingsAccessGeneratedKt\")",
        "package org.dlang.liveimap.settings",
        "",
        "// Template missing. Run python3 app/gen-settings-tree.py --upgrade",
        "",
    ])


def generated_with(buckets):
    pairs = []
    for group, keys in buckets.items():
        for key in keys:
            pairs.append((key, group))
    pairs.sort()
    lines = [
        "class SettingValue(val text: String)",
        "",
        "private val accountKeys: Array<String> = arrayOf(%s)" % ", ".join(json.dumps(key) for key, _group in pairs),
        "private val accountGroups: Array<String> = arrayOf(%s)" % ", ".join(json.dumps(group) for _key, group in pairs),
        "",
        "fun AccountSettings.withSetting(key: String, value: SettingValue): AccountSettings {",
        "    if (key == \"preferHtml\") {",
        "        val html = value.text == \"true\"",
        "        val view = if (html) BodyView.PlainOrHtml else if (bodyView == BodyView.PlainOrHtml) BodyView.PlainOrText else bodyView",
        "        return copy(bodyView = view)",
        "    }",
        "    val index = accountKeys.binarySearch(key)",
        "    if (index < 0) return this",
        "    return when (accountGroups[index]) {",
    ]
    for group in buckets:
        lines.append('        "%s" -> with_%s(key, value)' % (group, group))
    lines += [
        "        else -> this",
        "    }",
        "}",
        "",
        "fun AccountSettings.getSetting(key: String): String {",
        "    if (key == \"preferHtml\") return preferHtml.toString()",
        "    val index = accountKeys.binarySearch(key)",
        "    if (index < 0) return \"\"",
        "    return when (accountGroups[index]) {",
    ]
    for group in buckets:
        lines.append('        "%s" -> get_%s(key)' % (group, group))
    lines += [
        '        else -> ""',
        "    }",
        "}",
        "",
    ]
    for group, keys in buckets.items():
        lines.append("fun AccountSettings.with_%s(key: String, value: SettingValue): AccountSettings = when (key) {" % group)
        for key in keys:
            lines.append('    "%s" -> %s' % (key, with_expr(key)))
        lines += ["    else -> this", "}", ""]
        lines.append("fun AccountSettings.get_%s(key: String): String = when (key) {" % group)
        for key in keys:
            lines.append('    "%s" -> %s' % (key, get_expr(key)))
        lines += ['    else -> ""', "}", ""]
    return "\n".join(lines)


COMPLEX = {
    "defaultView": ("encodeView(defaultView)", "parseView(value.text)"),
    "folderViews": ("encodeFolderViews(folderViews)", "parseFolderViews(value.text)"),
    "expandedFolders": (
        'expandedFolders.joinToString(";") { percentEncode(it) }',
        "parseExpanded(value.text)",
    ),
    "swipeTrailing": ("encodeSwipe(swipeTrailing)", "parseSwipe(value.text)"),
    "swipeLeading": ("encodeSwipe(swipeLeading)", "parseSwipe(value.text)"),
    "readerSwipeTrailing": ("encodeSwipe(readerSwipeTrailing)", "parseSwipe(value.text)"),
    "readerSwipeLeading": ("encodeSwipe(readerSwipeLeading)", "parseSwipe(value.text)"),
    "favorites": ("encodeFavorites(favorites)", "parseFavorites(value.text)"),
    "folderStarts": ("encodeFolderStarts(folderStarts)", "parseFolderStarts(value.text)"),
    "altAddresses": ("encodeAltAddresses(altAddresses)", "decodeAltAddresses(value.text)"),
    "completionSources": ("encodeCompletionSources(completionSources)", "decodeCompletionSources(value.text)"),
    "extraAddressBooks": ("encodeCompletionSources(extraAddressBooks)", "decodeCompletionSources(value.text)"),
    "globalAddressBooks": ("encodeCompletionSources(globalAddressBooks)", "decodeCompletionSources(value.text)"),
    "clientSortCollation": ("clientSortCollationWord(clientSortCollation)", "clientSortCollationOf(value.text)"),
    "hiddenCapabilities": ("encodeHiddenCapabilities(hiddenCapabilities)", "decodeHiddenCapabilities(value.text)"),
    "inboundRules": ("encodeInboundRules(inboundRules)", "decodeInboundRules(value.text)"),
    "watchedFolders": ("encodeWatchedFolders(watchedFolders)", "decodeWatchedFolders(value.text)"),
    "savedSearches": ("encodeSavedSearches(savedSearches)", "decodeSavedSearches(value.text)"),
    "savedAdvanced": ("encodeSavedAdvanced(savedAdvanced)", "decodeSavedAdvanced(value.text)"),
    "sortAskSkip": ("encodeWatchedFolders(sortAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
    "threadAskSkip": ("encodeWatchedFolders(threadAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
    "searchAskSkip": ("encodeWatchedFolders(searchAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
    "fallbackAskSkip": ("encodeWatchedFolders(fallbackAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
}


_FIELD_TYPES = {}


def field_types():
    if not _FIELD_TYPES:
        raise SystemExit("account field types are not loaded")
    return _FIELD_TYPES


def with_expr(key):
    special = {
        "jmapFolders": 'withMailAccess(this, value.text == "true")',
        "username": "copy(username = value.text, email = emailDefaultedFromUsername(value.text, email))",
        "tlsMode": 'withTlsMode(TlsMode.valueOf(value.text)).withoutPreserved("tlsMode")',
        "sieveTlsMode": 'copy(sieveTlsMode = TlsMode.valueOf(value.text)).withoutPreserved("sieveTlsMode")',
    }
    if key in special:
        return special[key]
    if key in COMPLEX:
        return "copy(%s = %s)" % (key, COMPLEX[key][1])
    typ = field_types().get(key, "String")
    if typ == "String":
        return "copy(%s = value.text)" % key
    if typ == "Int":
        return "copy(%s = value.text.toInt())" % key
    if typ == "Boolean":
        return "copy(%s = value.text == \"true\")" % key
    return "copy(%s = %s.valueOf(value.text))" % (key, typ)


def account_imports():
    source = Path(__file__).resolve().parent / "src/main/kotlin/org/dlang/liveimap/settings/AccountSettings.kt"
    lines = []
    for line in source.read_text().splitlines():
        if line.startswith("import "):
            lines.append(line)
        elif line.startswith("enum class ") or line.startswith("data class ") or line.startswith("class "):
            break
    return "\n".join(lines)


def get_expr(key):
    if key in COMPLEX:
        return COMPLEX[key][0]
    typ = field_types().get(key, "String")
    if typ in ("String", "Int", "Boolean"):
        return "%s.toString()" % key if typ != "String" else key
    return "%s.name" % key


def generated_ui(leaves):
    lines = []
    for leaf in leaves:
        if leaf.get("scope") != "ui":
            continue
        key = leaf["setting"]
        lines.append("fun ui_%s(record: String): String = readUiLine(record, %s) ?: \"\"" % (key, json.dumps(key)))
        lines.append("suspend fun setUi_%s(context: Context, text: String) { saveGlobal(context, %s, text) }" % (key, json.dumps(key)))
        lines.append("")
    return "\n".join(lines)


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--upgrade":
        root = Path(__file__).resolve().parents[1]
        tree = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "app/src/main/assets/settings-tree.json"
        registry = root / "app/src/main/kotlin/org/dlang/liveimap/settings/SettingsRegistry.kt"
        doc = upgrade(tree, registry)
        tree = root / "app/src/main/assets/settings-tree.json"
        text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
        tree.write_text(text)
        print("wrote", tree, "leaves", len(all_leaves(doc)), "groups", len(groups_of(doc)))
        return
    if len(sys.argv) != 3:
        raise SystemExit("usage: gen-settings-tree.py <tree.json> <ktOut> | --upgrade")
    tree_path, kt_root = sys.argv[1:]
    raw = Path(tree_path).read_bytes()
    doc = json.loads(raw)
    emit(doc, kt_root, raw)


if __name__ == "__main__":
    main()
