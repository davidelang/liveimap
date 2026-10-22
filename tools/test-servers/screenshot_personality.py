#!/usr/bin/env python3
"""Write personalities/screenshot.json: a plaintext mailbox for the compact tablet.

LOGIN takes any user. The password is not stored. Shapes follow personality.shape.
The order array is not read by fake_imap.py.
"""
import datetime, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from personality import shape

OUT = os.path.join(HERE, "personalities", "screenshot.json")
CAPABILITY = (
    "IMAP4rev1 CHILDREN NAMESPACE UIDPLUS LIST-EXTENDED LIST-STATUS "
    "SPECIAL-USE IDLE LITERAL+"
)
FETCH_ATTS = "UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE"
LIST_MESSAGES = 'LIST "" "%" RETURN (CHILDREN SPECIAL-USE STATUS (MESSAGES))'
LIST_UNSEEN = 'LIST "" "%" RETURN (CHILDREN SPECIAL-USE STATUS (MESSAGES UNSEEN))'
NEWEST = datetime.date(2026, 10, 21)

# name, messages, unseen, SPECIAL-USE attribute (already with the backslash)
FOLDERS = [
    ("INBOX", 18432, 1260, ""),
    ("Sent", 4201, 0, "\\Sent"),
    ("Drafts", 7, 7, "\\Drafts"),
    ("Trash", 903, 0, "\\Trash"),
    ("Junk", 1566, 1566, "\\Junk"),
    ("Archive", 27650, 40, "\\Archive"),
    ("Receipts", 842, 3, ""),
    ("Travel", 126, 12, ""),
    ("Family", 2104, 18, ""),
    ("Work", 9330, 640, ""),
    ("Bills", 418, 6, ""),
    ("Newsletters", 12044, 2100, ""),
    ("Orders", 2677, 14, ""),
    ("Lists", 15890, 430, ""),
    ("School", 96, 4, ""),
    ("Photos", 14, 0, ""),
    ("Projects", 3502, 55, ""),
    ("Clients", 7844, 210, ""),
    ("Taxes", 231, 2, ""),
    ("Health", 88, 1, ""),
    ("Meetings", 640, 9, ""),
    ("Personal", 1520, 22, ""),
    ("2023", 4102, 0, ""),
    ("2024", 8901, 0, ""),
    ("2025", 6233, 15, ""),
    ("2026", 1904, 80, ""),
    ("Vendors", 733, 11, ""),
    ("Support", 2488, 96, ""),
    ("Legal", 57, 5, ""),
    ("HR", 164, 7, ""),
    ("Shipping", 3110, 28, ""),
    ("Ideas", 43, 43, ""),
    ("Read-later", 512, 200, ""),
    ("Notifications", 22018, 8400, ""),
    ("Social", 6755, 310, ""),
    ("Finance", 990, 19, ""),
]

# Oldest first. The last row is 21 Oct 2026. Reused for every folder, newest last.
SUBJECTS = [
    ("Warehouse receiving hours next week", "Dana Cho", "dana"),
    ("Your October statement is ready", "First Credit", "statements"),
    ("Standup notes", "Priya Shah", "priya"),
    ("Re: contract redlines", "Owen Blake", "owen"),
    ("Flight check-in is open", "North Air", "trips"),
    ("Photos from the lake", "Sam Ibarra", "sam"),
    ("Invoice 1841", "Harbor Electric", "billing"),
    ("Weekly digest", "City News", "digest"),
    ("Package out for delivery", "Parcel Post", "parcels"),
    ("School concert Thursday", "Riverside School", "office"),
    ("Q4 roadmap draft", "Lena Ortiz", "lena"),
    ("Client kickoff moved to 10:00", "Miles Grant", "miles"),
    ("Tax document uploaded", "North Ledger", "docs"),
    ("Appointment reminder", "Clinic Desk", "clinic"),
    ("Agenda for Monday", "Robin Hale", "robin"),
    ("Dinner at 7", "Alex Nguyen", "alex"),
    ("Year-end archive complete", "Records Bot", "records"),
    ("Renewal quote", "Fieldstone Insurance", "quotes"),
    ("Ticket 9021 updated", "Help Desk", "support"),
    ("Please review the clause", "Jordan Pike", "jordan"),
    ("Benefits enrollment closes Friday", "People Team", "benefits"),
    ("Shipment delayed one day", "Dock 4", "dock"),
    ("Sketch for the landing page", "Noor Adel", "noor"),
    ("Saved for later: the long interview", "Mara Quinn", "mara"),
    ("Build failed on main", "CI Notify", "ci"),
    ("Comment on your photo", "Social", "notes"),
    ("Transfer posted", "Community Bank", "alerts"),
    ("Re: the offsite", "Chris Daley", "chris"),
    ("Newsletter: what changed in maps", "Map Weekly", "maps"),
    ("Order confirmed", "Corner Books", "orders"),
    ("List digest: 14 new messages", "Dev List", "list"),
    ("Permission slip", "Homeroom", "homeroom"),
    ("Album shared with you", "Family Photos", "albums"),
    ("Sprint review notes", "Project Desk", "projects"),
    ("Question about the proposal", "Avery Chen", "avery"),
    ("Estimated tax payment", "Tax Desk", "taxes"),
    ("Lab results are in", "Patient Portal", "results"),
    ("Room change for the 2pm", "Office Admin", "rooms"),
    ("Can you pick up milk", "Jamie Cole", "jamie"),
    ("Board deck for tomorrow", "Morgan Ellis", "morgan"),
]


def fetch_command(count):
    first = count - 119 if count > 120 else 1
    return f"FETCH {first}:{count} ({FETCH_ATTS})"


def render(doc):
    return json.dumps(doc, indent=1) + "\n"


def _day(index):
    return NEWEST - datetime.timedelta(days=(len(SUBJECTS) - 1 - index))


def _fetch_lines(count, unseen):
    shown = min(count, len(SUBJECTS))
    base = len(SUBJECTS) - shown
    start = count - shown + 1
    lines = []
    for offset in range(shown):
        index = base + offset
        seq = start + offset
        subject, name, local = SUBJECTS[index]
        day = _day(index)
        internal = f"{day.day:02d}-{day.strftime('%b')}-{day.year} 09:00:00 +0000"
        envelope_date = f"{day.strftime('%a')}, {day.day} {day.strftime('%b')} {day.year} 09:00:00 +0000"
        size = 640 + ((index * 251) % 7200)
        lines_n = 1 + size // 78
        flags = "()" if seq > count - unseen else "(\\Seen)"
        addr = f'(("{name}" NIL "{local}" "example.test"))'
        envelope = (
            f'("{envelope_date}" "{subject}" {addr} {addr} {addr} '
            f'((NIL NIL "me" "example.test")) NIL NIL NIL "<s{index}@screenshot.test>")'
        )
        structure = (
            f'("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" {size} {lines_n} NIL NIL NIL NIL)'
        )
        lines.append(
            f"* {seq} FETCH (UID {seq} FLAGS {flags} INTERNALDATE \"{internal}\" "
            f"RFC822.SIZE {size} ENVELOPE {envelope} BODYSTRUCTURE {structure})"
        )
    lines.append("{TAG} OK FETCH completed")
    return lines


def _list_lines(with_unseen):
    lines = []
    for name, messages, unseen, attr in FOLDERS:
        flags = "\\HasNoChildren" + (f" {attr}" if attr else "")
        lines.append(f'* LIST ({flags}) "/" {name}')
        if with_unseen:
            lines.append(f"* STATUS {name} (MESSAGES {messages} UNSEEN {unseen})")
        else:
            lines.append(f"* STATUS {name} (MESSAGES {messages})")
    lines.append("{TAG} OK LIST completed")
    return lines


def _select_lines(count, examine):
    word = "EXAMINE" if examine else "SELECT"
    mode = "READ-ONLY" if examine else "READ-WRITE"
    return [
        f"* {count} EXISTS",
        "* 0 RECENT",
        "* OK [UIDVALIDITY 1] UIDs valid",
        f"* OK [UIDNEXT {count + 1}] Predicted next UID",
        "{TAG} OK [" + mode + "] " + word + " completed",
    ]


def _add(table, order, cmd, lines, auth):
    key = "LOGIN" if cmd.split(" ", 1)[0].upper() in ("LOGIN", "AUTHENTICATE") else shape(cmd)
    table.setdefault(key, []).append(
        {"auth": auth, "cmd": cmd, "items": [{"line": line} for line in lines]}
    )
    order.append(key)


def build():
    if len(SUBJECTS) != 40:
        raise SystemExit(f"expected 40 subjects, got {len(SUBJECTS)}")
    if len(FOLDERS) != 36:
        raise SystemExit(f"expected 36 folders, got {len(FOLDERS)}")
    table, order = {}, []
    caps = [f"* CAPABILITY {CAPABILITY}", "{TAG} OK CAPABILITY completed"]
    _add(table, order, "CAPABILITY", caps, False)
    _add(table, order, "CAPABILITY", caps, True)
    _add(table, order, "LOGIN", ["{TAG} OK LOGIN completed"], False)
    _add(table, order, "NAMESPACE", ['* NAMESPACE (("" "/")) NIL NIL', "{TAG} OK NAMESPACE completed"], True)
    _add(table, order, LIST_MESSAGES, _list_lines(False), True)
    _add(table, order, LIST_UNSEEN, _list_lines(True), True)
    for name, messages, unseen, _attr in FOLDERS:
        _add(table, order, f"SELECT {name}", _select_lines(messages, False), True)
        _add(table, order, f"EXAMINE {name}", _select_lines(messages, True), True)
        _add(table, order, fetch_command(messages), _fetch_lines(messages, unseen), True)
    return {
        "server": "screenshot",
        "source": "screenshot_personality.py",
        "greeting": f"* OK [CAPABILITY {CAPABILITY}] Screenshot ready",
        "table": table,
        "order": order,
    }


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else OUT
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = render(build())
    if not os.path.exists(path) or open(path).read() != data:
        open(path, "w").write(data)


if __name__ == "__main__":
    main()
