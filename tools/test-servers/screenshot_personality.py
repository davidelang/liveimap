#!/usr/bin/env python3
"""Write the screenshot mailbox as a fakeimap personality plus dataset.

The old replay-personality table (LOGIN accepted any user; exact FETCH lines
were canned) is gone. This script writes:

  personalities/screenshot.json     fakeimap personality
  datasets/screenshot/dataset.json  accounts, folders, generated fillers
  datasets/screenshot/sNN.eml       the 40 visible subjects

fakeimap only accepts listed dataset accounts. The fixture account is
me@example.test / screenshot (a committed test password, not a live secret).

Generated messages cannot carry per-message subjects or flags, and explicit
dataset messages are loaded before generated ones, so they would be the
oldest. start_server() therefore injects the 40 visible messages at the end
of each large folder (freeze-clock sets INTERNALDATE) and STOREs \\Seen on
the leading messages so STATUS UNSEEN matches. Small folders (count <= 40)
are stored entirely as .eml messages in the dataset.

Usage:
  ./screenshot_personality.py              # rewrite the fixture files
  ./screenshot_personality.py --start      # start fakeimap and print bound JSON
"""
import argparse, datetime, json, os, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fakeimap_pin

OUT_PERSONALITY = os.path.join(HERE, "personalities", "screenshot.json")
OUT_DATASET = os.path.join(HERE, "datasets", "screenshot")
SCREENSHOT_USER = "me@example.test"
SCREENSHOT_PASSWORD = "screenshot"
CAPABILITY = [
    "IMAP4rev1", "CHILDREN", "NAMESPACE", "UIDPLUS", "LIST-EXTENDED",
    "LIST-STATUS", "SPECIAL-USE", "IDLE", "LITERAL+", "AUTH=PLAIN", "AUTH=LOGIN",
]
FETCH_ITEM_ORDER = [
    "FLAGS", "UID", "INTERNALDATE", "RFC822.SIZE", "ENVELOPE", "BODY",
    "BODYSTRUCTURE", "RFC822", "RFC822.HEADER", "RFC822.TEXT", "BODY[]", "BODY.PEEK[]",
]
SELECT_ORDER = ["EXISTS", "RECENT", "FLAGS", "PERMANENTFLAGS", "UNSEEN", "UIDVALIDITY", "UIDNEXT"]
NEWEST = datetime.date(2026, 10, 21)
VISIBLE = 40

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


def _day(index):
    return NEWEST - datetime.timedelta(days=(len(SUBJECTS) - 1 - index))


def _internal(index):
    day = _day(index)
    return f"{day.day:02d}-{day.strftime('%b')}-{day.year} 09:00:00 +0000"


def _rfc2822(index):
    day = _day(index)
    return f"{day.strftime('%a')}, {day.day} {day.strftime('%b')} {day.year} 09:00:00 +0000"


def _rfc3339(index):
    day = _day(index)
    return f"{day.isoformat()}T09:00:00Z"


def _size(index):
    return 640 + ((index * 251) % 7200)


def eml_name(index):
    return f"s{index:02d}.eml"


def render_eml(index):
    subject, name, local = SUBJECTS[index]
    size = _size(index)
    header = (
        f"From: {name} <{local}@example.test>\r\n"
        f"To: Me <{SCREENSHOT_USER}>\r\n"
        f"Subject: {subject}\r\n"
        f"Date: {_rfc2822(index)}\r\n"
        f"Message-ID: <s{index}@screenshot.test>\r\n"
        "MIME-Version: 1.0\r\n"
        'Content-Type: text/plain; charset=UTF-8\r\n'
        "Content-Transfer-Encoding: 7bit\r\n"
        "\r\n"
    )
    pad = size - len(header)
    if pad < 1:
        pad = 1
    return header + ("x" * pad) + "\r\n"


def build_personality():
    caps = list(CAPABILITY)
    return {
        "greeting": "Screenshot ready",
        "delimiter": "/",
        "greeting_capabilities": caps,
        "pre_login_capabilities": caps,
        "post_login_capabilities": [c for c in caps if not c.startswith("AUTH=")],
        "logout_bye": True,
        "select_flags": "\\Answered \\Flagged \\Draft \\Deleted \\Seen",
        "permanent_flags": "\\Answered \\Flagged \\Draft \\Deleted \\Seen \\*",
        "select_order": SELECT_ORDER,
        "flag_order": ["\\Recent", "\\Answered", "\\Flagged", "\\Draft", "\\Deleted", "\\Seen"],
        "fetch_peek_body": True,
        "fetch_seen_flags": True,
        "move_copyuid_untagged": True,
        "sort_partial_count_only": False,
        "fetch_item_order": FETCH_ITEM_ORDER,
    }


def _visible_slice(count):
    shown = min(count, VISIBLE)
    return list(range(len(SUBJECTS) - shown, len(SUBJECTS)))


def build_dataset():
    mailboxes = []
    for uidv, (name, count, unseen, attr) in enumerate(FOLDERS, start=1):
        mb = {"name": name, "uid_validity": uidv}
        if attr:
            mb["special_use"] = [attr]
        if count <= VISIBLE:
            shown = _visible_slice(count)
            messages = []
            start = count - len(shown) + 1
            for offset, index in enumerate(shown):
                seq = start + offset
                flags = [] if seq > count - unseen else ["\\Seen"]
                messages.append({
                    "file": eml_name(index),
                    "flags": flags,
                    "internal_date": _internal(index),
                })
            mb["messages"] = messages
        else:
            # Fillers end the day before the first visible subject.
            first_visible = _day(len(SUBJECTS) - VISIBLE)
            fillers = count - VISIBLE
            start = first_visible - datetime.timedelta(days=fillers)
            mb["generated"] = {
                "count": fillers,
                "seed": f"screenshot-{name}",
                "from": "Earlier Mail <earlier@example.test>",
                "to": f"Me <{SCREENSHOT_USER}>",
                "subject": "Earlier",
                "start": f"{start.day:02d}-{start.strftime('%b')}-{start.year} 09:00:00 +0000",
                "step_seconds": 86400,
            }
        mailboxes.append(mb)
    return {
        "accounts": [{"username": SCREENSHOT_USER, "password": SCREENSHOT_PASSWORD}],
        "mailboxes": mailboxes,
    }


def mailbox_count(mb):
    if mb.get("generated"):
        return mb["generated"]["count"] + VISIBLE
    return len(mb.get("messages") or [])


def render_json(doc):
    return json.dumps(doc, indent=1) + "\n"


def write_fixture(personality_path=None, dataset_dir=None):
    if len(SUBJECTS) != VISIBLE:
        raise SystemExit(f"expected {VISIBLE} subjects, got {len(SUBJECTS)}")
    if len(FOLDERS) != 36:
        raise SystemExit(f"expected 36 folders, got {len(FOLDERS)}")
    personality_path = personality_path or OUT_PERSONALITY
    dataset_dir = dataset_dir or OUT_DATASET
    os.makedirs(os.path.dirname(personality_path), exist_ok=True)
    os.makedirs(dataset_dir, exist_ok=True)
    pers = render_json(build_personality())
    if not os.path.exists(personality_path) or open(personality_path).read() != pers:
        open(personality_path, "w").write(pers)
    for i in range(VISIBLE):
        path = os.path.join(dataset_dir, eml_name(i))
        data = render_eml(i)
        if not os.path.exists(path) or open(path, "rb").read() != data.encode("ascii"):
            open(path, "wb").write(data.encode("ascii"))
    ds = render_json(build_dataset())
    ds_path = os.path.join(dataset_dir, "dataset.json")
    if not os.path.exists(ds_path) or open(ds_path).read() != ds:
        open(ds_path, "w").write(ds)
    return personality_path, dataset_dir


def start_server(personality_path=None, dataset_dir=None, work_dir=None, listen="127.0.0.1:0"):
    """Start pinned fakeimap on the screenshot fixture and finish the visible tail.

    For every folder larger than 40 messages, injects the 40 visible subjects
    (INTERNALDATE via freeze-clock) and STOREs \\Seen so UNSEEN matches FOLDERS.
    """
    fakeimap_pin.require_ready()
    personality_path, dataset_dir = write_fixture(personality_path, dataset_dir)
    work = work_dir or os.path.join(os.path.abspath(os.path.join(HERE, "..", "..", "sandbox", "tmp-rescue")), "screenshot-run")
    os.makedirs(work, exist_ok=True)
    control = fakeimap_pin.short_control_path("screenshot")
    cfg = fakeimap_pin.write_config(
        os.path.join(work, "config.json"),
        listen=listen,
        personality=os.path.abspath(personality_path),
        dataset=os.path.abspath(dataset_dir),
        control=str(control),
        log_path=os.path.abspath(os.path.join(work, "fakeimap.log")),
    )
    srv = fakeimap_pin.start(cfg, os.path.join(work, "fakeimap.err"), timeout=30)
    try:
        _finish_visible(srv)
    except Exception:
        srv.close()
        raise
    return srv


def _finish_visible(srv):
    import imaplib
    host, port_s = srv.bound["listen"].rsplit(":", 1)
    port = int(port_s)
    for name, count, unseen, _attr in FOLDERS:
        if count <= VISIBLE:
            continue
        shown = _visible_slice(count)
        for index in shown:
            srv.control({"op": "freeze-clock", "time": _rfc3339(index)})
            srv.control({"op": "inject", "mailbox": name, "raw": render_eml(index).replace("\r\n", "\n")})
        srv.control({"op": "thaw-clock"})
    # STORE \\Seen on the leading messages of every folder.
    mail = imaplib.IMAP4(host, port, timeout=30)
    try:
        typ, _ = mail.login(SCREENSHOT_USER, SCREENSHOT_PASSWORD)
        if typ != "OK":
            raise RuntimeError("screenshot login failed")
        for name, count, unseen, _attr in FOLDERS:
            typ, _ = mail.select(name)
            if typ != "OK":
                raise RuntimeError(f"SELECT {name} failed")
            seen_until = count - unseen
            if seen_until > 0:
                typ, _ = mail.store(f"1:{seen_until}", "+FLAGS", r"(\Seen)")
                if typ != "OK":
                    raise RuntimeError(f"STORE \\Seen on {name} failed")
    finally:
        try:
            mail.logout()
        except Exception:
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--start", action="store_true", help="start fakeimap and print the bound-address JSON")
    args = parser.parse_args()
    write_fixture()
    if args.start:
        srv = start_server()
        print(json.dumps(srv.bound, indent=1))
        try:
            while srv.proc.poll() is None:
                time.sleep(3600)
        except KeyboardInterrupt:
            pass
        finally:
            srv.close()


if __name__ == "__main__":
    main()
