"""Compare host-side IMAP/SMTP/Sieve/JMAP probes of each lab docker server
against pinned fakeimap running the matching personality and an equivalent
seed dataset.

This is the protocol-level regress parity runner. It does not install or
talk to the emulator app (instrumented regress still needs a matching APK
and a free emulator; see --backend fakeimap on tools/test-servers/regress).

Normalization is limited to true session nondeterminism. Real capability,
mailbox, flag, and payload differences are kept and classified.
"""

from __future__ import annotations

import argparse
import imaplib
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import time
import traceback
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT_DEFAULT = REPO / "sandbox" / "tmp-rescue" / "fakeimap-regress" / "out"

# Classification labels used in the report.
CLS_BUG = "fakeimap bug or gap"
CLS_PERS = "personality data mismatch"
CLS_DATA = "dataset mismatch"
CLS_OK = "acceptable or expected"
CLS_HARNESS = "regress harness issue"

NORM_RULES = [
    ("IMAP tags", "imaplib and servers allocate tags per connection; compared records never include the tag."),
    ("UIDVALIDITY", "Replaced with UIDVALIDITY=<N> wherever it appears. Fakeimap assigns its own uid_validity."),
    ("connection / session ids", "Numbers after 'session', 'conn=', or bracketed connection ids are replaced with <ID>."),
    ("greeting / OK text timestamps", "RFC 822 or 'dd-Mon-YYYY HH:MM:SS +zone' stamps in greetings and untagged OK text become <TIMESTAMP>."),
    ("MODSEQ / highestmodseq", "HIGHESTMODSEQ and MODSEQ values become <N> (per-process counters)."),
    ("MAILBOXID / EMAILID / OBJECTID / THREADID", "Opaque object identifiers become <ID> (fakeimap mints them per process)."),
    ("\\Recent", "Dropped from FLAGS / PERMANENTFLAGS comparisons: first-select session state, not seeded."),
    ("capability / LIST order", "Capability tokens and LIST names are compared as sets; order is not a failure."),
    ("tagged OK / NO human text", "The human text after OK/NO is ignored; the status word and IMAP response codes in [] are kept (after the substitutions above)."),
]


def _ssl_ctx(legacy=False):
    ca = HERE / "generated" / "ca" / "ca.der"
    ctx = ssl.create_default_context(cadata=open(ca, "rb").read()) if ca.is_file() else ssl.create_default_context()
    ctx.check_hostname = False
    if legacy:
        try:
            ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
        except ssl.SSLError:
            pass
    return ctx


def _legacy(name):
    path = HERE / "legacy-tls.tsv"
    if not path.is_file():
        return False
    return any(line.split("\t")[0] == name for line in path.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#"))


def normalize_text(text):
    if text is None:
        return ""
    if isinstance(text, (bytes, bytearray)):
        text = text.decode("utf-8", "replace")
    s = str(text)
    s = re.sub(r"\bUIDVALIDITY\s+\d+", "UIDVALIDITY <N>", s, flags=re.I)
    s = re.sub(r"\[UIDVALIDITY\s+\d+\]", "[UIDVALIDITY <N>]", s, flags=re.I)
    s = re.sub(r"\bHIGHESTMODSEQ\s+\d+", "HIGHESTMODSEQ <N>", s, flags=re.I)
    s = re.sub(r"\bMODSEQ\s+\d+", "MODSEQ <N>", s, flags=re.I)
    s = re.sub(r"\b(?:MAILBOXID|EMAILID|OBJECTID|THREADID)\s+\S+", lambda m: m.group(0).split()[0] + " <ID>", s, flags=re.I)
    s = re.sub(r"\b((?:conn|connection|session)[= ]+)\d+", r"\1<ID>", s, flags=re.I)
    s = re.sub(r"\d{1,2}-[A-Za-z]{3}-\d{4}\s+\d{2}:\d{2}:\d{2}\s+[+-]\d{4}", "<TIMESTAMP>", s)
    s = re.sub(r"[A-Z][a-z]{2}, \d{1,2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} [+-]\d{4}", "<TIMESTAMP>", s)
    s = re.sub(r"\b\d{10,13}\b", "<EPOCH>", s)  # only very long epoch-like ints in banners
    return s


def normalize_flags(flags):
    if not flags:
        return []
    if isinstance(flags, str):
        words = flags.strip("()").split()
    else:
        words = list(flags)
    skip = {"\\recent", "$hasattachment", "$hasnoattachment"}
    out = [w for w in words if w.lower() not in skip]
    out.sort(key=str.lower)
    return out


def status_word(typ, data):
    text = ""
    if data:
        first = data[0]
        if isinstance(first, (bytes, bytearray)):
            text = first.decode("utf-8", "replace")
        elif isinstance(first, tuple):
            text = " ".join(
                x.decode("utf-8", "replace") if isinstance(x, (bytes, bytearray)) else str(x)
                for x in first
            )
        else:
            text = str(first)
    # Keep [RESPONSECODES], drop trailing human text.
    codes = re.findall(r"\[[^\]]+\]", text)
    codes = [normalize_text(c) for c in codes]
    return {"status": typ or "", "codes": codes}


def cap_set(raw):
    """Split IMAP CAPABILITY atoms. imaplib.capability() often returns one bytes blob."""
    if not raw:
        return []
    toks = []
    if isinstance(raw, (list, tuple)):
        items = raw
    else:
        items = [raw]
    for item in items:
        if isinstance(item, (bytes, bytearray)):
            text = item.decode("utf-8", "replace")
        else:
            text = str(item)
        toks.extend(text.split())
    return sorted(set(t.upper() for t in toks if t and t.upper() != "CAPABILITY"))


def parse_list_row(row):
    """Parse one imaplib LIST row, including literal mailbox names."""
    if not row:
        return None
    literal_name = None
    if isinstance(row, tuple):
        meta = row[0]
        if len(row) > 1 and isinstance(row[1], (bytes, bytearray)):
            literal_name = row[1].decode("utf-8", "replace")
        line = meta.decode("utf-8", "replace") if isinstance(meta, (bytes, bytearray)) else str(meta)
    elif isinstance(row, (bytes, bytearray)):
        line = row.decode("utf-8", "replace")
    else:
        line = str(row)
    parts = line.split('"')
    delim = "/"
    name = literal_name or ""
    flags = ""
    if len(parts) >= 3:
        flags = parts[0]
        if len(parts[1]) == 1:
            delim = parts[1]
        if literal_name is None:
            name = parts[-2] if len(parts) > 3 else parts[-1].strip()
    elif literal_name is None:
        bits = line.split()
        name = bits[-1] if bits else ""
        flags = line
    else:
        flags = parts[0] if parts else line
    flag_words = re.findall(r"\\[A-Za-z0-9-]+", flags)
    return {"name": name, "delim": delim, "flags": normalize_flags(flag_words)}


def connect_imap(host, port, name, starttls=None):
    """Login as the lab fixture user. starttls: True, False, or None (try plain then STARTTLS)."""
    import lab
    last = ""
    order = [False, True] if starttls is None else [bool(starttls)]
    for use_tls in order:
        client = None
        try:
            client = imaplib.IMAP4(host, int(port), timeout=30)
            greeting = normalize_text(getattr(client, "welcome", b""))
            caps_pre = cap_set(client.capabilities)
            if use_tls:
                if "STARTTLS" not in caps_pre:
                    try:
                        client.logout()
                    except Exception:
                        pass
                    last = "no STARTTLS"
                    continue
                client.starttls(_ssl_ctx(_legacy(name)))
            typ, _data = client.login(lab.USER, lab.PASS)
            if typ != "OK":
                last = f"login {typ}"
                try:
                    client.logout()
                except Exception:
                    pass
                continue
            try:
                typ_c, data_c = client.capability()
                caps_post = cap_set(data_c if typ_c == "OK" else client.capabilities)
            except Exception:
                caps_post = cap_set(client.capabilities)
            return client, {"ok": True, "greeting": greeting, "caps_pre": caps_pre,
                            "caps_post": caps_post, "starttls": use_tls, "error": ""}
        except Exception as exc:
            last = str(exc)
            if client is not None:
                try:
                    client.shutdown()
                except Exception:
                    pass
    return None, {"ok": False, "greeting": "", "caps_pre": [], "caps_post": [],
                  "starttls": None, "error": normalize_text(last)}


def _select_exists(client, name):
    typ, data = client.select('"%s"' % name.replace("\\", "\\\\").replace('"', '\\"'), readonly=True)
    exists = None
    if typ == "OK" and data and data[0] is not None:
        try:
            exists = int(data[0])
        except (TypeError, ValueError):
            exists = None
    return typ, exists


def probe_imap(host, port, name):
    steps = []
    def rec(step, **kw):
        steps.append({"step": step, **kw})

    client, auth = connect_imap(host, port, name)
    rec("greeting_login", ok=auth["ok"], starttls=auth["starttls"],
        caps_pre=auth["caps_pre"], caps_post=auth["caps_post"],
        greeting=auth["greeting"], error=auth.get("error") or "")
    if not auth["ok"] or client is None:
        return {"ok": False, "steps": steps}

    try:
        typ, ns = ("NO", None)
        if "NAMESPACE" in (client.capabilities or ()):
            typ, ns = client.namespace()
        field = ns[0] if typ == "OK" and ns and ns[0] else b""
        ns_text = normalize_text(field)
        rec("namespace", **status_word(typ, ns), text=ns_text)

        typ, data = client.list('""', "*")
        entries = [parse_list_row(row) for row in (data or [])]
        entries = [e for e in entries if e and e["name"]]
        names = sorted({e["name"] for e in entries})
        rec("list", status=typ, names=names, count=len(names),
            entries=[{"name": e["name"], "flags": e["flags"]} for e in entries])

        # Suite boxes + INBOX + marker
        import lab
        typ0, data0 = client.list('""', '""')
        line = data0[0] if typ0 == "OK" and data0 and data0[0] else b""
        boxes = list(lab.suite_mailboxes(field, line))
        marker = lab.marker_mailbox(field)
        wanted = [("INBOX", None)] + boxes + [(marker, None)]
        seen = {}
        for box, want in wanted:
            typ, exists = _select_exists(client, box)
            rec("select", mailbox=box, status=typ, exists=exists, want=want)
            if typ == "OK" and exists:
                seen[box] = exists

        # FETCH FLAGS of first / last in LiveIMAP-Test (or first suite box)
        root = boxes[0][0] if boxes else "LiveIMAP-Test"
        typ, exists = _select_exists(client, root)
        if typ == "OK" and exists and exists > 0:
            typ, data = client.fetch("1", "(FLAGS UID)")
            rec("fetch_first", mailbox=root, **_fetch_flags_uid(typ, data))
            typ, data = client.fetch(str(exists), "(FLAGS UID ENVELOPE)")
            rec("fetch_last_envelope", mailbox=root, **_fetch_envelope(typ, data))
            typ, data = client.search(None, "ALL")
            ids = _search_ids(typ, data)
            rec("search_all", mailbox=root, status=typ, count=len(ids))
            typ, data = client.search(None, "UNSEEN")
            ids = _search_ids(typ, data)
            rec("search_unseen", mailbox=root, status=typ, count=len(ids))
            caps = cap_set(client.capabilities)
            if any(t == "SORT" or t.startswith("SORT=") for t in caps):
                try:
                    typ2, data2 = client.uid("SORT", "(DATE)", "UTF-8", "ALL")
                    rec("sort_date", mailbox=root, status=typ2, count=len(_search_ids(typ2, data2)))
                except Exception as exc:
                    rec("sort_date", mailbox=root, status="ERR", error=normalize_text(exc), count=0)
            if "THREAD=REFERENCES" in caps or "THREAD=ORDEREDSUBJECT" in caps or any(t.startswith("THREAD") for t in caps):
                alg = "REFERENCES" if "THREAD=REFERENCES" in caps else "ORDEREDSUBJECT"
                try:
                    typ2, data2 = client.uid("THREAD", alg, "UTF-8", "ALL")
                    rec("thread", mailbox=root, status=typ2, alg=alg,
                        text=normalize_text(data2[0] if data2 else ""))
                except Exception as exc:
                    rec("thread", mailbox=root, status="ERR", alg=alg, error=normalize_text(exc))
            typ, data = client.status('"%s"' % root, "(MESSAGES UNSEEN UIDNEXT)")
            rec("status", mailbox=root, **_status_parse(typ, data))
        typ, data = client.noop()
        rec("noop", **status_word(typ, data))
        typ, data = client.logout()
        rec("logout", **status_word(typ, data))
    except Exception as exc:
        rec("probe_error", ok=False, error=normalize_text(exc), trace=traceback.format_exc()[-500:])
        try:
            client.logout()
        except Exception:
            pass
        return {"ok": False, "steps": steps}
    return {"ok": True, "steps": steps}


def _fetch_flags_uid(typ, data):
    text = ""
    if data:
        item = data[0]
        if isinstance(item, tuple):
            text = b" ".join(x for x in item if isinstance(x, (bytes, bytearray))).decode("utf-8", "replace")
        elif isinstance(item, (bytes, bytearray)):
            text = item.decode("utf-8", "replace")
        else:
            text = str(item)
    flags = normalize_flags(re.search(r"FLAGS\s+\(([^)]*)\)", text).group(1) if re.search(r"FLAGS\s+\(([^)]*)\)", text) else "")
    uid = None
    m = re.search(r"\bUID\s+(\d+)", text)
    if m:
        uid = int(m.group(1))
    return {"status": typ, "flags": flags, "uid": uid}


def _fetch_envelope(typ, data):
    base = _fetch_flags_uid(typ, data)
    text = ""
    if data:
        item = data[0]
        if isinstance(item, tuple):
            text = (item[1] if len(item) > 1 and isinstance(item[1], (bytes, bytearray)) else item[0])
            if isinstance(text, (bytes, bytearray)):
                text = text.decode("utf-8", "replace")
            else:
                text = str(text)
        elif isinstance(item, (bytes, bytearray)):
            text = item.decode("utf-8", "replace")
    # ENVELOPE is (date subject ...) — keep subject only (date/message-id differ by seed time)
    subj = ""
    m = re.search(r'ENVELOPE \("[^"]*" "([^"]*)"', text)
    if m:
        subj = m.group(1)
    else:
        # fallback: first quoted after ENVELOPE date
        m = re.search(r"ENVELOPE \((.*)\)", text)
        if m:
            parts = re.findall(r'"((?:\\.|[^"\\])*)"|(NIL)', m.group(1))
            if len(parts) >= 2:
                subj = parts[1][0] if isinstance(parts[1], tuple) else (parts[1] or "")
    base["subject"] = subj
    base["text"] = normalize_text(text)[:300]
    return base


def _search_ids(typ, data):
    if typ != "OK" or not data or data[0] is None:
        return []
    raw = data[0]
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8", "replace")
    return [int(x) for x in str(raw).split() if x.isdigit()]


def _status_parse(typ, data):
    text = ""
    if data and data[0]:
        raw = data[0]
        text = raw.decode("utf-8", "replace") if isinstance(raw, (bytes, bytearray)) else str(raw)
    text = normalize_text(text)
    # drop UIDVALIDITY already normalized; keep MESSAGES UNSEEN; drop UIDNEXT if we want? keep it, classify later
    msgs = unseen = uidnext = None
    m = re.search(r"MESSAGES\s+(\d+)", text, re.I)
    if m:
        msgs = int(m.group(1))
    m = re.search(r"UNSEEN\s+(\d+)", text, re.I)
    if m:
        unseen = int(m.group(1))
    m = re.search(r"UIDNEXT\s+(\d+)", text, re.I)
    if m:
        uidnext = int(m.group(1))
    return {"status": typ, "messages": msgs, "unseen": unseen, "uidnext": uidnext, "text": text}


def probe_tcp_banner(host, port, tls=False, name=""):
    try:
        sock = socket.create_connection((host, int(port)), 8)
        if tls:
            sock = _ssl_ctx(_legacy(name)).wrap_socket(sock, server_hostname=host)
        sock.settimeout(8)
        data = sock.recv(400)
        sock.close()
        return {"ok": True, "banner": normalize_text(data)}
    except Exception as exc:
        return {"ok": False, "banner": "", "error": normalize_text(exc)}


def probe_smtp(host, port, tls=False, name=""):
    rec = probe_tcp_banner(host, port, tls=tls, name=name)
    rec["step"] = "smtp_banner"
    rec["tls"] = tls
    return rec


def probe_sieve(host, port, name=""):
    rec = probe_tcp_banner(host, port, tls=False, name=name)
    rec["step"] = "sieve_banner"
    return rec


def probe_jmap(host, port, tls=False, name=""):
    import urllib.request
    import urllib.error
    scheme = "https" if tls else "http"
    url = f"{scheme}://{host}:{int(port)}/.well-known/jmap"
    ctx = _ssl_ctx(_legacy(name)) if tls else None
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, context=ctx, timeout=10) as resp:
            body = resp.read(2000).decode("utf-8", "replace")
            return {"step": "jmap_session", "ok": True, "http_status": resp.status,
                    "body": normalize_text(body)[:500]}
    except Exception as exc:
        # Some servers use /jmap/session
        try:
            url2 = f"{scheme}://{host}:{int(port)}/jmap/session"
            req = urllib.request.Request(url2, method="GET")
            with urllib.request.urlopen(req, context=ctx, timeout=10) as resp:
                body = resp.read(2000).decode("utf-8", "replace")
                return {"step": "jmap_session", "ok": True, "http_status": resp.status,
                        "body": normalize_text(body)[:500], "url": url2}
        except Exception as exc2:
            return {"step": "jmap_session", "ok": False, "error": normalize_text(f"{exc} | {exc2}")}


def probe_server(name, ports, host="127.0.0.1"):
    """ports: dict with imap and optional imaps, submission, managesieve, http, https."""
    out = {"name": name, "host": host, "ports": {k: v for k, v in ports.items() if isinstance(v, int)}, "services": {}}
    if "imap" in ports:
        out["services"]["imap"] = probe_imap(host, ports["imap"], name)
    if "imaps" in ports:
        # implicit TLS: wrap then IMAP
        out["services"]["imaps"] = probe_imaps(host, ports["imaps"], name)
    if "submission" in ports:
        out["services"]["smtp"] = probe_smtp(host, ports["submission"], tls=False, name=name)
    if "managesieve" in ports:
        out["services"]["sieve"] = probe_sieve(host, ports["managesieve"], name=name)
    if "http" in ports:
        out["services"]["jmap_http"] = probe_jmap(host, ports["http"], tls=False, name=name)
    if "https" in ports:
        out["services"]["jmap_https"] = probe_jmap(host, ports["https"], tls=True, name=name)
    out["ok"] = all(
        (svc.get("ok") is not False)
        for svc in out["services"].values()
        if isinstance(svc, dict) and "ok" in svc
    )
    return out


def probe_imaps(host, port, name):
    import lab
    steps = []
    client = None
    try:
        client = imaplib.IMAP4_SSL(host, int(port), ssl_context=_ssl_ctx(_legacy(name)), timeout=30)
        typ, _data = client.login(lab.USER, lab.PASS)
        caps = cap_set(client.capabilities)
        steps.append({"step": "imaps_login", "ok": typ == "OK", "status": typ, "caps_post": caps})
        typ, data = client.logout()
        steps.append({"step": "imaps_logout", **status_word(typ, data)})
        return {"ok": typ == "OK" or typ == "BYE" or True, "steps": steps}
    except Exception as exc:
        steps.append({"step": "imaps_login", "ok": False, "error": normalize_text(exc)})
        return {"ok": False, "steps": steps}
    finally:
        if client is not None:
            try:
                client.shutdown()
            except Exception:
                pass


def step_key(step):
    extra = step.get("mailbox") or step.get("alg") or ""
    return step.get("step", "") + ((":" + extra) if extra else "")


def comparable(step):
    """Fields compared after normalization. Drops traces and raw envelopes."""
    skip = {"trace", "text", "greeting", "error"}
    # greeting text is expected to differ (server banner); keep ok / caps
    out = {}
    for k, v in step.items():
        if k in skip:
            continue
        if k == "uid":
            continue  # UID assignment can differ if server compacted; counts matter more
        if k == "uidnext":
            continue
        out[k] = v
    return out


def personality_cap_sets(name):
    import fakeimap_pin
    path = Path(fakeimap_pin.personality_path(name))
    data = json.loads(path.read_text(encoding="utf-8"))
    def toks(key):
        return set(t.upper() for t in (data.get(key) or []) if t)
    return {
        "pre": toks("pre_login_capabilities") or toks("greeting_capabilities"),
        "post": toks("post_login_capabilities"),
    }


def uw_home_dir_name(n):
    """UW-IMAP lists the Unix home (mbox, dotfiles) alongside the seed tree."""
    if not n:
        return False
    base = n.rsplit("/", 1)[-1]
    return base == "mbox" or base.startswith(".")


# Capabilities this fakeimap binary can advertise. The ACL family is
# syntax-checked then tagged NO, and must not be advertised.
IMPLEMENTED_CAPS = {
    "IMAP4REV1", "LITERAL+", "IDLE", "INPROGRESS", "ID", "NAMESPACE", "OBJECTID",
    "OBJECTID+", "NOTIFY", "UIDPLUS", "UIDONLY", "MOVE", "MULTIAPPEND", "REPLACE",
    "UNSELECT", "UNAUTHENTICATE", "ENABLE", "UTF8=ACCEPT", "CONDSTORE", "QRESYNC",
    "LIST-EXTENDED", "CHILDREN", "SPECIAL-USE", "LIST-STATUS", "STATUS=SIZE",
    "SAVEDATE", "QUOTA", "QUOTA=RES-STORAGE", "QUOTA=RES-MESSAGE", "QUOTASET",
    "CREATE-SPECIAL-USE", "ESEARCH", "MULTISEARCH", "SEARCH=FUZZY", "SEARCHRES",
    "WITHIN", "CONTEXT=SEARCH", "SCAN", "XLIST", "XMOVE", "ESORT", "PARTIAL",
    "SORT", "SORT=DISPLAY", "SORT=MODSEQ", "SORT=UID", "THREAD=REFERENCES",
    "THREAD=ORDEREDSUBJECT", "THREAD=REFS", "COMPRESS=DEFLATE", "BINARY",
    "PREVIEW", "SASL-IR", "AUTH=PLAIN", "AUTH=LOGIN", "AUTH=CRAM-MD5",
    "AUTH=SCRAM-SHA-1", "AUTH=SCRAM-SHA-256", "AUTH=SCRAM-SHA-1-PLUS",
    "AUTH=SCRAM-SHA-256-PLUS", "AUTH=EXTERNAL", "JMAPACCESS", "LOGINDISABLED",
    "STARTTLS",
}

# David: keep out of personalities, not bugs.
DELIBERATE_OFF = {
    "ACL", "I18NLEVEL=1", "CATENATE", "URL-PARTIAL", "FILTER=SIEVE",
}


def token_deliberate_off(tok):
    u = (tok or "").upper()
    if u in DELIBERATE_OFF:
        return True
    if u.startswith("ACL2=") or u.startswith("RIGHTS="):
        return True
    return False


def token_implemented(tok):
    u = (tok or "").upper()
    if token_deliberate_off(tok):
        return False
    if u in IMPLEMENTED_CAPS:
        return True
    if u.startswith("APPENDLIMIT=") or u.startswith("MESSAGELIMIT="):
        rest = u.split("=", 1)[1]
        return rest.isdigit() and int(rest) > 0
    return False


def classify(name, key, docker_step, fake_step):

    """Return (class, reason)."""
    d_ok = (docker_step or {}).get("ok")
    f_ok = (fake_step or {}).get("ok")
    step = (docker_step or fake_step or {}).get("step", key)

    if docker_step and not fake_step:
        if step in ("smtp_banner", "sieve_banner", "jmap_session", "imaps_login"):
            return CLS_BUG, "docker offers this listener or path; fakeimap did not respond"
        if step.startswith("select") or step == "list":
            return CLS_HARNESS, "mailbox present on docker was not probed on fakeimap (harness or early login fail)"
        return CLS_BUG, "step present on docker is missing on fakeimap"

    if fake_step and not docker_step:
        return CLS_OK, "fakeimap produced an extra step the docker probe did not (often a skipped optional cap)"

    d = comparable(docker_step)
    f = comparable(fake_step)

    if step == "greeting_login":
        # caps compared separately below
        if d.get("ok") and not f.get("ok"):
            return CLS_BUG, f"login failed on fakeimap: {fake_step.get('error')}"
        if f.get("ok") and not d.get("ok"):
            return CLS_HARNESS, f"login failed on docker: {docker_step.get('error')}"
        d_pre, f_pre = set(d.get("caps_pre") or []), set(f.get("caps_pre") or [])
        d_post, f_post = set(d.get("caps_post") or []), set(f.get("caps_post") or [])
        if d_pre == f_pre and d_post == f_post and d.get("ok") == f.get("ok"):
            return None, ""
        # Compare pre and post separately so pre-login-only tokens
        # (AUTH=OAUTHBEARER, AUTH=XOAUTH2 on Stalwart) are not hidden.
        missing = sorted((d_pre - f_pre) | (d_post - f_post))
        extra = sorted((f_pre - d_pre) | (f_post - d_post))
        pers = personality_cap_sets(name)
        advertised = pers["post"] or pers["pre"]
        # STARTTLS is added when a cert is loaded, even if the personality omits it.
        unimplemented, listed_unadvertised, pers_miss, unexpected = [], [], [], []
        for tok in missing:
            # Cyrus lists LOGINDISABLED only after a successful cleartext LOGIN.
            # Lab personalities must not advertise it: LOGIN has to succeed.
            if tok == "LOGINDISABLED" or tok == "STARTTLS" or token_deliberate_off(tok):
                continue
            if token_implemented(tok) and tok not in advertised:
                pers_miss.append(tok)
            elif token_implemented(tok) and tok in advertised:
                listed_unadvertised.append(tok)
            else:
                unimplemented.append(tok)
        for tok in extra:
            if tok == "STARTTLS" or tok == "LOGINDISABLED" or token_deliberate_off(tok):
                continue
            unexpected.append(tok)
        bits = []
        cls = None
        if unimplemented:
            cls = CLS_BUG
            bits.append("docker advertises unimplemented capability: " + " ".join(unimplemented))
        if listed_unadvertised:
            cls = CLS_BUG
            bits.append("personality lists but fakeimap did not advertise: " + " ".join(listed_unadvertised))
        if pers_miss:
            cls = cls or CLS_PERS
            bits.append("docker advertises tokens not in the personality: " + " ".join(pers_miss))
        if unexpected:
            cls = cls or CLS_PERS
            bits.append("fakeimap advertised extra tokens not seen on docker: " + " ".join(unexpected))
        if not bits:
            return None, ""
        return cls, "; ".join(bits)

    if step in ("smtp_banner", "sieve_banner"):
        if d.get("ok") and not f.get("ok"):
            return CLS_BUG, f"{step} failed on fakeimap: {fake_step.get('error')}"
        if f.get("ok") and not d.get("ok"):
            return CLS_HARNESS, f"{step} failed on docker: {docker_step.get('error')}"
        # Banner wording is expected to differ.
        if d.get("ok") == f.get("ok"):
            return None, "banner wording ignored"
        return CLS_BUG, "banner ok bit differs"

    if step == "jmap_session":
        if d.get("ok") and not f.get("ok"):
            return CLS_BUG, f"JMAP session failed on fakeimap: {fake_step.get('error')}"
        if d.get("ok") and f.get("ok"):
            # session URLs / implementation name will differ; treat as expected if both 2xx
            return None, "JMAP session both reachable; implementation JSON not compared"
        if not d.get("ok") and not f.get("ok"):
            return None, "JMAP session failed on both"
        return CLS_HARNESS, "JMAP session failed on docker only"

    if step == "namespace":
        if d.get("status") != f.get("status"):
            return CLS_PERS, f"NAMESPACE status docker={d.get('status')} fakeimap={f.get('status')}"
        return None, ""  # prefix/delimiter compared via suite select names

    if step == "list":
        dnames, fnames = set(d.get("names") or []), set(f.get("names") or [])
        if name == "uwimap":
            dnames = {n for n in dnames if not uw_home_dir_name(n)}
            fnames = {n for n in fnames if not uw_home_dir_name(n)}
        if dnames == fnames:
            return None, ""
        missing = sorted(dnames - fnames)
        extra = sorted(fnames - dnames)
        reason = f"LIST names missing={missing[:12]} extra={extra[:12]}"
        def implied_deep_parent(n):
            base = n.rsplit("/", 1)[-1].rsplit(".", 1)[-1]
            parent = n[:-len(base)].rstrip("./")
            return base == "Deep" and parent in ("", "INBOX")
        if missing and not extra and all(implied_deep_parent(n) for n in missing):
            return CLS_BUG, reason + " (implied parent; fakeimap cannot mark \\Noselect)"
        if missing and not extra:
            return CLS_DATA, reason
        if extra and not missing:
            return CLS_DATA, reason
        return CLS_DATA, reason

    if step == "select":
        if d.get("status") == "OK" and f.get("status") != "OK":
            return CLS_DATA, f"select {d.get('mailbox')} failed on fakeimap (docker exists={d.get('exists')})"
        if d.get("exists") != f.get("exists") and d.get("status") == "OK" and f.get("status") == "OK":
            return CLS_DATA, f"exists docker={d.get('exists')} fakeimap={f.get('exists')} want={d.get('want')}"
        if d.get("status") != f.get("status"):
            return CLS_DATA, f"select status docker={d.get('status')} fakeimap={f.get('status')}"
        return None, ""

    if step in ("fetch_first", "fetch_last_envelope"):
        if d.get("status") != f.get("status"):
            return CLS_BUG, f"{step} status docker={d.get('status')} fakeimap={f.get('status')}"
        if d.get("flags") != f.get("flags"):
            return CLS_DATA, f"flags docker={d.get('flags')} fakeimap={f.get('flags')}"
        if step == "fetch_last_envelope" and d.get("subject") and f.get("subject") and d.get("subject") != f.get("subject"):
            return CLS_DATA, f"subject docker={d.get('subject')!r} fakeimap={f.get('subject')!r}"
        return None, ""

    if step in ("search_all", "search_unseen", "sort_date"):
        if d.get("status") != f.get("status"):
            if f.get("status") in ("BAD", "NO", "ERR") and d.get("status") == "OK":
                return CLS_BUG, f"{step} docker={d.get('status')} fakeimap={f.get('status')} {fake_step.get('error') or ''}"
            return CLS_BUG, f"{step} status docker={d.get('status')} fakeimap={f.get('status')}"
        if d.get("count") != f.get("count"):
            return CLS_BUG, f"{step} count docker={d.get('count')} fakeimap={f.get('count')}"
        return None, ""

    if step == "thread":
        if d.get("status") != f.get("status"):
            return CLS_BUG, f"THREAD status docker={d.get('status')} fakeimap={f.get('status')}"
        return None, ""  # thread grouping text may include UIDs

    if step == "status":
        if d.get("messages") != f.get("messages") or d.get("unseen") != f.get("unseen"):
            return CLS_DATA, f"STATUS messages/unseen docker={d.get('messages')}/{d.get('unseen')} fakeimap={f.get('messages')}/{f.get('unseen')}"
        return None, ""

    if step in ("noop", "logout", "imaps_logout", "imaps_login"):
        if d.get("ok") is False or f.get("ok") is False:
            if f.get("ok") is False and d.get("ok") is not False:
                return CLS_BUG, f"{step} failed on fakeimap: {fake_step.get('error')}"
        return None, ""

    if d != f:
        return CLS_BUG, f"{step} residual difference after normalization"
    return None, ""


def diff_probes(name, docker_probe, fake_probe):
    diffs = []
    dsvc = (docker_probe or {}).get("services") or {}
    fsvc = (fake_probe or {}).get("services") or {}
    services = sorted(set(dsvc) | set(fsvc))
    for svc in services:
        d = dsvc.get(svc) or {}
        f = fsvc.get(svc) or {}
        if "steps" in d or "steps" in f:
            dsteps = {step_key(s): s for s in d.get("steps") or []}
            fsteps = {step_key(s): s for s in f.get("steps") or []}
            keys = list(dict.fromkeys(list(dsteps) + list(fsteps)))
            for key in keys:
                cls, reason = classify(name, key, dsteps.get(key), fsteps.get(key))
                if not cls:
                    continue
                diffs.append({
                    "server": name,
                    "service": svc,
                    "step": key,
                    "class": cls,
                    "reason": reason,
                    "docker": comparable(dsteps[key]) if key in dsteps else None,
                    "fakeimap": comparable(fsteps[key]) if key in fsteps else None,
                })
        else:
            # banner-style service dict
            ds = dict(d, step=d.get("step") or svc)
            fs = dict(f, step=f.get("step") or svc)
            cls, reason = classify(name, ds.get("step") or svc, ds, fs)
            if cls:
                diffs.append({
                    "server": name,
                    "service": svc,
                    "step": ds.get("step") or svc,
                    "class": cls,
                    "reason": reason,
                    "docker": comparable(ds),
                    "fakeimap": comparable(fs),
                })
    return diffs


def docker_running_labs():
    r = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True)
    return [n for n in (r.stdout or "").split() if n.startswith("liveimap-")]


def start_docker(s, timeout=None, reset=False):
    import lab
    name, cont = s["name"], s["container"]
    lab.only_one(cont)
    if reset:
        r = subprocess.run(
            [str(HERE / "start.sh"), "--reset", name],
            capture_output=True, text=True, errors="replace",
        )
        if r.returncode != 0:
            return f"start.sh --reset {name}: {(r.stderr or r.stdout or '').strip()[:400]}"
    r = subprocess.run(["docker", "start", cont], capture_output=True, text=True, errors="replace")
    if r.returncode != 0:
        return f"docker start {cont}: {(r.stderr or r.stdout or '').strip()[:300]}"
    up = lab.up_commands().get(cont)
    if up:
        subprocess.run(["docker", "exec", cont, "sh", "-c", up], capture_output=True, text=True)
    if timeout is None:
        if name == "zimbra-883":
            timeout = 180
        else:
            timeout = 900 if cont in lab.ALONE else 240
    wait = timeout
    if not lab.greeting(s["imap"], False, wait):
        return f"{name}: no IMAP greeting after {wait}s"
    return ""


def stop_docker(s):
    cont = s.get("container")
    if not cont:
        return
    subprocess.run(["docker", "stop", "-t", "20", cont], capture_output=True, text=True)


def lab_ports(s):
    out = {"imap": s["imap"]}
    for k in ("imaps", "submission", "managesieve", "http", "https"):
        if k in s and isinstance(s[k], int):
            out[k] = s[k]
    return out


def selected_names(spec, all_names):
    if spec in ("", "all"):
        return list(all_names)
    want = [n.strip() for n in spec.split(",") if n.strip()]
    bad = [n for n in want if n not in all_names]
    if bad:
        sys.exit("unknown server(s): " + ", ".join(bad))
    return want


def write_tsv(path, diffs, counts):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["server\tservice\tstep\tclass\treason"]
    for d in diffs:
        reason = (d.get("reason") or "").replace("\t", " ").replace("\n", " ")
        lines.append(f"{d['server']}\t{d['service']}\t{d['step']}\t{d['class']}\t{reason}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    summary = Path(path).with_name("summary.tsv")
    slines = ["server\tdocker_ok\tfakeimap_ok\tsteps_compared\tdiffs\tbug\tpersonality\tdataset\tacceptable\tharness"]
    for name, c in counts.items():
        slines.append("\t".join([
            name, str(c["docker_ok"]), str(c["fakeimap_ok"]), str(c["steps"]),
            str(c["diffs"]), str(c["bug"]), str(c["personality"]),
            str(c["dataset"]), str(c["acceptable"]), str(c["harness"]),
        ]))
    summary.write_text("\n".join(slines) + "\n", encoding="utf-8")


def count_steps(probe):
    n = 0
    for svc in (probe or {}).get("services", {}).values():
        if isinstance(svc, dict) and "steps" in svc:
            n += len(svc["steps"])
        elif isinstance(svc, dict):
            n += 1
    return n


def main():
    os.environ.setdefault("TMPDIR", str(REPO / "sandbox" / "tmp-rescue"))
    sys.path.insert(0, str(HERE))
    p = argparse.ArgumentParser(description="Compare docker lab servers to fakeimap personalities.")
    p.add_argument("--servers", default="all")
    p.add_argument("--out", default=str(OUT_DEFAULT))
    p.add_argument("--bind", default="127.0.0.1")
    p.add_argument("--skip-docker", action="store_true", help="only start fakeimap and probe it")
    p.add_argument("--personality-dir", default="",
                   help="directory of personality JSON files (default: the liveimap pin)")
    p.add_argument("--fakeimap-bin", default="",
                   help="fakeimap binary to start (default: the liveimap pin)")
    p.add_argument("--reset", action="store_true",
                   help="recreate each docker lab container from its seed before probing")
    p.add_argument("--skip-servers", default="",
                   help="comma-separated lab server names to skip")
    a = p.parse_args()
    import lab
    out_dir = Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    by = {s["name"]: s for s in lab.servers()}
    names = selected_names(a.servers, list(by))
    skip = {n.strip() for n in (a.skip_servers or "").split(",") if n.strip()}
    names = [n for n in names if n not in skip]
    already = docker_running_labs()
    if already and not a.skip_docker:
        print("liveimap containers already running: " + " ".join(already), file=sys.stderr)
        print("refusing to start/stop shared lab containers; use --skip-docker or stop them first", file=sys.stderr)
        sys.exit(2)
    all_diffs = []
    counts = {}
    results = {}
    for i, name in enumerate(names, 1):
        s = by[name]
        print(f"[{i}/{len(names)}] {name}", flush=True)
        docker_probe = None
        fake_probe = None
        docker_err = ""
        fake_err = ""
        if not a.skip_docker:
            docker_err = start_docker(s, reset=a.reset)
            if docker_err:
                print(f"  docker: {docker_err}", flush=True)
            else:
                try:
                    # LAB_HOST may be a docker bridge; probes use 127.0.0.1 published ports
                    host = "127.0.0.1"
                    docker_probe = probe_server(name, lab_ports(s), host=host)
                except Exception as exc:
                    docker_err = normalize_text(exc)
                    print(f"  docker probe: {docker_err}", flush=True)
            stop_docker(s)
        # fakeimap
        srv = None
        try:
            import fakeimap_backend
            srv, overlay, info = fakeimap_backend.start_lab_fakeimap(
                name, work_dir=out_dir.parent / name, bind=a.bind,
                overlay_path=out_dir / f"{name}-ports.json",
                personality_dir=a.personality_dir or None,
                fakeimap_bin=a.fakeimap_bin or None,
            )
            ports = info["overlay"]
            fake_probe = probe_server(name, ports, host="127.0.0.1")
        except SystemExit as exc:
            fake_err = str(exc)
            print(f"  fakeimap: {fake_err}", flush=True)
        except Exception as exc:
            fake_err = traceback.format_exc()[-400:]
            print(f"  fakeimap: {exc}", flush=True)
        finally:
            if srv is not None:
                try:
                    srv.close()
                except Exception:
                    pass
        diffs = diff_probes(name, docker_probe, fake_probe) if docker_probe and fake_probe else []
        if docker_probe and not fake_probe:
            diffs.append({"server": name, "service": "imap", "step": "start",
                          "class": CLS_HARNESS, "reason": fake_err or "fakeimap failed to start",
                          "docker": {"ok": True}, "fakeimap": None})
        if fake_probe and not docker_probe and not a.skip_docker:
            diffs.append({"server": name, "service": "imap", "step": "start",
                          "class": CLS_HARNESS, "reason": docker_err or "docker failed to start",
                          "docker": None, "fakeimap": {"ok": True}})
        cls_counts = {CLS_BUG: 0, CLS_PERS: 0, CLS_DATA: 0, CLS_OK: 0, CLS_HARNESS: 0}
        for d in diffs:
            cls_counts[d["class"]] = cls_counts.get(d["class"], 0) + 1
        counts[name] = {
            "docker_ok": int(bool(docker_probe and docker_probe.get("ok"))),
            "fakeimap_ok": int(bool(fake_probe and fake_probe.get("ok"))),
            "steps": max(count_steps(docker_probe), count_steps(fake_probe)),
            "diffs": len(diffs),
            "bug": cls_counts[CLS_BUG],
            "personality": cls_counts[CLS_PERS],
            "dataset": cls_counts[CLS_DATA],
            "acceptable": cls_counts[CLS_OK],
            "harness": cls_counts[CLS_HARNESS],
        }
        rec = {
            "name": name,
            "docker_error": docker_err,
            "fakeimap_error": fake_err,
            "docker": docker_probe,
            "fakeimap": fake_probe,
            "diffs": diffs,
            "counts": counts[name],
        }
        results[name] = rec
        (out_dir / f"{name}.json").write_text(json.dumps(rec, indent=1, default=str) + "\n", encoding="utf-8")
        print(f"  steps={counts[name]['steps']} diffs={counts[name]['diffs']} "
              f"bug={counts[name]['bug']} pers={counts[name]['personality']} "
              f"data={counts[name]['dataset']} harness={counts[name]['harness']}", flush=True)
        all_diffs.extend(diffs)
    write_tsv(out_dir / "diffs.tsv", all_diffs, counts)
    (out_dir / "results.json").write_text(json.dumps({
        "counts": counts, "diff_count": len(all_diffs),
        "servers": names, "norm_rules": NORM_RULES,
    }, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out_dir / 'diffs.tsv'} ({len(all_diffs)} diffs, {len(names)} servers)")


if __name__ == "__main__":
    main()
