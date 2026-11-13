"""Build a fakeimap dataset equivalent to the lab docker seed.

The tree comes from seed/folders.tsv (corpus-v1 .eml files and flags) plus
the six suite boxes and the LiveIMAP-Fixture marker from suite-fixture.tsv
and lab_imap message builders. Mailbox names use the personality delimiter
and personal prefix so fixture_check and verify_one see the same wire names
as the matching docker lab server.

Accounts are read from seed/accounts.tsv and written only into dataset.json.
This module never prints a password.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from email.utils import format_datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
CORPUS = HERE / "generated" / "corpus-v1"
SEED = HERE / "seed"

# Fixed Date for suite-built messages so a fakeimap run is repeatable.
# Docker images were seeded with wall-clock dates at build time, so Date and
# INTERNALDATE on suite boxes are expected to differ; subjects, kinds, and
# flags follow the same lab_imap.seed formulae.
SUITE_DATE = datetime(2024, 10, 1, 0, 0, tzinfo=timezone.utc)


def _load_tsv(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        header = None
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if header is None:
                header = fields
                continue
            rows.append(dict(zip(header, fields)))
    return rows


def read_accounts():
    """[{username, password}, ...] from seed/accounts.tsv. Do not print."""
    out = []
    for row in _load_tsv(SEED / "accounts.tsv"):
        out.append({"username": row["user"], "password": row["password"]})
    if not out:
        sys.exit("fakeimap_seed: seed/accounts.tsv has no accounts")
    return out


def read_folders():
    return [row for row in _load_tsv(SEED / "folders.tsv") if row.get("user") and row.get("folder")]


# Stalwart docker labs also LIST these special-use display names in addition
# to the folders.tsv Sent / Trash / Junk mailboxes. Personality files have no
# display-name field; the extra mailboxes live in the seed dataset. special_use
# stays on the folders.tsv names (a flag may belong to only one mailbox).
STALWART_DISPLAY_MAILBOXES = ("Sent Items", "Deleted Items", "Junk Mail")
STALWART_SERVERS = {"stalwart", "stalwart-0155", "stalwart-01625", "stalwart-dev"}
# Docker LISTs an empty selectable Deep parent of Deep/L2 on these servers.
# dovecot-245, dovecot-dev, courier-*, and uwimap list Deep as \Noselect;
# fakeimap cannot mark a seeded mailbox \Noselect, so those stay a gap.
SELECTABLE_DEEP_PARENT_SERVERS = STALWART_SERVERS | {
    "cyrus-3124", "cyrus-3137", "cyrus-36",
}


def personality_layout(name):
    """delimiter and personal prefix from the pinned personality JSON."""
    import fakeimap_pin
    path = fakeimap_pin.personality_path(name)
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    delim = data.get("delimiter") or "/"
    prefix = data.get("personal_prefix") or ""
    # Use the first personal NAMESPACE entry when personal_prefix is omitted.
    # Do not skip an empty prefix: UW lists ("", "/") first and ("#mhinbox", NIL)
    # second; picking #mhinbox prepends it onto every mailbox name.
    if not data.get("personal_prefix"):
        nss = data.get("personal_namespaces") or []
        if nss:
            ns = nss[0] or {}
            prefix = ns.get("prefix") or ""
            if ns.get("delimiter"):
                delim = ns["delimiter"]
    return delim, prefix


def wire_folder(folder, delim, prefix, encode_mutf7=False):
    """Map a folders.tsv path to the dataset mailbox name for this personality.

    Names are stored as UTF-8 (with the personality delimiter and prefix).
    fakeimap applies modified UTF-7 on the wire; putting already-encoded
    mutf7 in the dataset double-encodes '&' as '&-'.
    """
    if folder == "INBOX":
        return "INBOX"
    leaf = folder.replace("/", delim)
    if encode_mutf7 and not all(32 <= ord(ch) <= 126 for ch in leaf):
        import lab_imap
        parts = leaf.split(delim)
        leaf = delim.join(
            part if all(32 <= ord(ch) <= 126 for ch in part) else lab_imap.mutf7(part)
            for part in parts
        )
    if prefix and not leaf.startswith(prefix) and not leaf.upper().startswith("INBOX" + delim):
        return prefix + leaf
    return leaf



def decode_mutf7(name):
    """Undo RFC 3501 modified UTF-7 so dataset names stay UTF-8."""
    import base64, re
    def repl(m):
        body = m.group(1)
        if body == "":
            return "&"
        pad = "=" * ((4 - len(body) % 4) % 4)
        raw = base64.b64decode(body.replace(",", "/") + pad)
        return raw.decode("utf-16-be")
    return re.sub(r"&([^-]*)-", repl, name)


def suite_wire_names(delim, prefix):
    """The six suite boxes plus the fixture marker, using lab.py rules."""
    import lab
    field = b'(("' + prefix.encode("utf-8") + b'" "' + delim.encode("utf-8") + b'"))' if prefix or delim != "/" else b""
    # list_delimiter reads the first quoted char; give it a LIST-shaped line.
    list_line = b'(\\Noselect) "' + delim.encode("utf-8") + b'" "' + prefix.encode("utf-8") + b'"'
    boxes = list(lab.suite_mailboxes(field if prefix else b"", list_line))
    marker = lab.marker_mailbox(field if prefix else b"")
    return boxes, marker


def internaldate_from_iso(value):
    """corpus-v1 ISO-8601 -> IMAP date-time."""
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    when = datetime.fromisoformat(text)
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    return when.strftime("%d-%b-%Y %H:%M:%S %z")


def _special_use(label):
    if not label or label == "-":
        return None
    token = label if label.startswith("\\") else "\\" + label
    return [token]


def _corpus_dir(user, folder):
    """On-disk corpus directory: slashes in folder names become underscores."""
    return CORPUS / user / folder.replace("/", "_")


def _mailbox_from_corpus(user, folder, delim, prefix, uid_validity):
    rows = []
    manifest = CORPUS / "manifest.tsv"
    with open(manifest, encoding="utf-8") as handle:
        header = handle.readline().rstrip("\n").split("\t")
        for line in handle:
            rec = dict(zip(header, line.rstrip("\n").split("\t")))
            if rec.get("user") != user or rec.get("folder") != folder:
                continue
            flags = [] if rec.get("flags") in ("", "-", None) else rec["flags"].split()
            src = _corpus_dir(user, folder) / rec["file"]
            rows.append({
                "file": str(src.resolve()),
                "flags": flags,
                "internal_date": internaldate_from_iso(rec["internaldate"]),
            })
    mb = {
        "name": wire_folder(folder, delim, prefix),
        "uid_validity": uid_validity,
        "messages": rows,
    }
    return mb


def _write_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def _fixed_date_bytes(builder):
    """Call a lab_imap builder and, when it uses Date=now, the caller already
    patched lab_imap? We rewrite Date after the fact only if needed.

    lab_imap.message/spoof/deep embed Date at call time. We accept that and
    then overwrite the Date header so the dataset is stable across runs.
    """
    import re
    raw = builder()
    if isinstance(raw, str):
        raw = raw.encode()
    # normalize newlines for a reliable substitution, then CRLF at the end
    text = raw.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    stamp = format_datetime(SUITE_DATE).encode("ascii")
    text, n = re.subn(br"(?im)^Date:.*$", b"Date: " + stamp, text, count=1)
    if n == 0:
        # prepend after the first header block start
        text = b"Date: " + stamp + b"\n" + text
    return text.replace(b"\n", b"\r\n")


def _suite_messages(dest_dir, box_leaf, count, kinds):
    """Build the same flags/kinds pattern as lab_imap.seed()."""
    import lab_imap
    out = []
    for i in range(count):
        kind = kinds[i % len(kinds)] if kinds else "plain"
        raw = _fixed_date_bytes(lambda i=i, kind=kind: lab_imap.message(i, kind))
        name = f"suite-{box_leaf}-{i:04d}.eml"
        _write_bytes(dest_dir / name, raw)
        if i % 11 == 0:
            flags = ["\\Flagged"]
        elif i % 3:
            flags = ["\\Seen"]
        else:
            flags = []
        out.append({
            "file": name,
            "flags": flags,
            "internal_date": SUITE_DATE.strftime("%d-%b-%Y %H:%M:%S %z"),
        })
    return out


def _spoof_messages(dest_dir, rows):
    import lab_imap
    out = []
    for i, fields in enumerate(rows):
        subject, filename = fields[0], fields[1]
        mime, content = lab_imap.SPOOFS[i][2], lab_imap.SPOOFS[i][3]
        raw = _fixed_date_bytes(
            lambda subject=subject, filename=filename, mime=mime, content=content:
            lab_imap.spoof_message(subject, filename, mime, content)
        )
        name = f"spoof-{i:02d}.eml"
        _write_bytes(dest_dir / name, raw)
        out.append({
            "file": name,
            "flags": ["\\Seen"],
            "internal_date": SUITE_DATE.strftime("%d-%b-%Y %H:%M:%S %z"),
        })
    return out


def _deep_messages(dest_dir, rows):
    import lab_imap
    out = []
    for i, fields in enumerate(rows):
        levels = int(fields[0])
        raw = _fixed_date_bytes(lambda levels=levels: lab_imap.deep_message(levels))
        name = f"deep-{levels}.eml"
        _write_bytes(dest_dir / name, raw)
        out.append({
            "file": name,
            "flags": ["\\Seen"],
            "internal_date": SUITE_DATE.strftime("%d-%b-%Y %H:%M:%S %z"),
        })
    return out


def write_seed_dataset(dest_dir, personality, primary_user="liveimap"):
    """Write dest_dir/dataset.json and suite .eml files. Returns the dest Path.

    Mailboxes for every folders.tsv user are included, but fakeimap has a
    single shared store: every account sees the same mailbox set. Only
    primary_user's folders keep their corpus (including INBOX). Other users
    are present so LOGIN succeeds; their folder rows are omitted to avoid
    colliding INBOX counts. That limitation is a fakeimap gap, not hidden.
    """
    import lab
    dest = Path(dest_dir)
    dest.mkdir(parents=True, exist_ok=True)
    delim, prefix = personality_layout(personality)
    kinds = [fields[0] for fields in lab.fixture_fields("kind")]
    accounts = read_accounts()
    mailboxes = []
    uidv = 1
    seen_names = set()

    def add_mb(mb):
        nonlocal uidv
        name = mb["name"]
        if name in seen_names:
            return
        seen_names.add(name)
        if "uid_validity" not in mb:
            mb["uid_validity"] = uidv
        uidv = max(uidv, int(mb.get("uid_validity") or uidv)) + 1
        mailboxes.append(mb)

    # Parents with count 0 still need a mailbox object (Archive, Lists, Empty, ...).
    for row in read_folders():
        if row["user"] != primary_user:
            continue
        folder = row["folder"]
        mb = _mailbox_from_corpus(row["user"], folder, delim, prefix, uidv)
        special = _special_use(row.get("special"))
        if special:
            mb["special_use"] = special
        if not mb["messages"] and int(row.get("count") or "0") == 0:
            mb["messages"] = []
        add_mb(mb)

    if personality in STALWART_SERVERS:
        # Docker lists Sent/Trash/Junk without special-use flags, and also
        # "Sent Items" (\Sent), "Deleted Items" (\Trash), "Junk Mail" (\Junk).
        # A special-use flag may be on only one mailbox.
        uses = {"Sent": ["\\Sent"], "Trash": ["\\Trash"], "Junk": ["\\Junk"]}
        for mb in mailboxes:
            base = mb["name"].rsplit(delim, 1)[-1]
            if base in uses and mb.get("special_use") == uses[base]:
                del mb["special_use"]
        display_flags = {
            "Sent Items": ["\\Sent"],
            "Deleted Items": ["\\Trash"],
            "Junk Mail": ["\\Junk"],
        }
        for name, flags in display_flags.items():
            add_mb({"name": wire_folder(name, delim, prefix), "special_use": flags, "messages": []})

    if personality in SELECTABLE_DEEP_PARENT_SERVERS:
        if not any(m["name"] == wire_folder("Deep", delim, prefix) for m in mailboxes):
            add_mb({"name": wire_folder("Deep", delim, prefix), "messages": []})

    boxes, marker = suite_wire_names(delim, prefix)
    # boxes: LiveIMAP-Test, Sub, Trash, Tëst-Ünï, Deep, Spoof
    labels = ["LiveIMAP-Test", "Sub", "Trash", "unicode", "Deep", "Spoof"]
    for (wire, count), label in zip(boxes, labels):
        if label == "Spoof":
            msgs = _spoof_messages(dest, lab.fixture_fields("spoof"))
        elif label == "Deep":
            msgs = _deep_messages(dest, lab.fixture_fields("deep"))
        elif count == 0:
            msgs = []
        else:
            msgs = _suite_messages(dest, label.replace(" ", "_"), count, kinds)
        add_mb({"name": decode_mutf7(wire), "messages": msgs})

    version = lab.required_fixture_version()
    marker_name = "marker.eml"
    raw = b"Subject: LiveIMAP-Fixture\r\nDate: " + format_datetime(SUITE_DATE).encode("ascii") + \
        b"\r\n\r\n" + str(version).encode("ascii") + b"\r\n"
    _write_bytes(dest / marker_name, raw)
    add_mb({
        "name": decode_mutf7(marker),
        "messages": [{
            "file": marker_name,
            "flags": [],
            "internal_date": SUITE_DATE.strftime("%d-%b-%Y %H:%M:%S %z"),
        }],
    })

    data = {"accounts": accounts, "mailboxes": mailboxes}
    (dest / "dataset.json").write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")
    # A side manifest without secrets, for logs.
    summary = {
        "personality": personality,
        "delimiter": delim,
        "personal_prefix": prefix,
        "accounts": [a["username"] for a in accounts],
        "mailboxes": [{"name": m["name"], "count": len(m.get("messages") or [])} for m in mailboxes],
        "primary_user": primary_user,
    }
    (dest / "dataset.summary.json").write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    return dest


if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser(description="Write a lab-equivalent fakeimap dataset.")
    p.add_argument("--personality", required=True)
    p.add_argument("--dest", required=True)
    a = p.parse_args()
    dest = write_seed_dataset(a.dest, a.personality)
    summary = json.loads((dest / "dataset.summary.json").read_text())
    print(f"wrote {dest / 'dataset.json'} mailboxes={len(summary['mailboxes'])} accounts={len(summary['accounts'])}")
