#!/usr/bin/env python3
"""Check the screenshot fakeimap personality and dataset, and that a started
server lists the 36 folders and the 40 INBOX subjects."""
import json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import screenshot_personality as shot

COUNTS = [
    18432, 4201, 7, 903, 1566, 27650, 842, 126, 2104, 9330, 418, 12044,
    2677, 15890, 96, 14, 3502, 7844, 231, 88, 640, 1520, 4102, 8901, 6233,
    1904, 733, 2488, 57, 164, 3110, 43, 512, 22018, 6755, 990,
]


def fail(text):
    sys.exit(text)


def snapshot():
    files = {
        "personality": open(shot.OUT_PERSONALITY).read(),
        "dataset": open(os.path.join(shot.OUT_DATASET, "dataset.json")).read(),
    }
    for i in range(shot.VISIBLE):
        path = os.path.join(shot.OUT_DATASET, shot.eml_name(i))
        files[shot.eml_name(i)] = open(path, "rb").read()
    return files


def main():
    if not os.path.exists(shot.OUT_PERSONALITY):
        fail(f"missing {shot.OUT_PERSONALITY}")
    if not os.path.exists(os.path.join(shot.OUT_DATASET, "dataset.json")):
        fail(f"missing {shot.OUT_DATASET}/dataset.json")
    before = snapshot()
    pers = json.loads(before["personality"])
    if "table" in pers or "source" in pers:
        fail("personality is still in the old replay-table format")
    for key in ("greeting", "delimiter", "pre_login_capabilities", "post_login_capabilities", "fetch_item_order"):
        if key not in pers:
            fail(f"personality missing {key}")
    caps = " ".join(pers["post_login_capabilities"]).upper()
    for token in ("IMAP4REV1", "CHILDREN", "NAMESPACE", "UIDPLUS", "LIST-EXTENDED", "LIST-STATUS", "SPECIAL-USE", "IDLE", "LITERAL+"):
        if token not in caps.split():
            fail(f"personality missing {token}")
    ds = json.loads(before["dataset"])
    if not ds.get("accounts"):
        fail("dataset has no accounts")
    user = ds["accounts"][0].get("username")
    if user != shot.SCREENSHOT_USER:
        fail(f"dataset username is {user!r}")
    if len(ds.get("mailboxes") or []) != 36:
        fail(f"dataset has {len(ds.get('mailboxes') or [])} mailboxes")
    counts = [shot.mailbox_count(mb) for mb in ds["mailboxes"]]
    if counts != COUNTS:
        fail(f"mailbox counts {counts} != {COUNTS}")
    names = [mb["name"] for mb in ds["mailboxes"]]
    if names != [n for n, *_ in shot.FOLDERS]:
        fail("mailbox names do not match FOLDERS")
    special = {mb["name"]: (mb.get("special_use") or [None])[0] for mb in ds["mailboxes"]}
    for name, _c, _u, attr in shot.FOLDERS:
        if attr and special.get(name) != attr:
            fail(f"{name} special_use {special.get(name)} != {attr}")
    for i in range(shot.VISIBLE):
        raw = before[shot.eml_name(i)]
        if raw != shot.render_eml(i).encode("ascii"):
            fail(f"{shot.eml_name(i)} differs from render_eml")
        if shot.SUBJECTS[i][0].encode("ascii") not in raw:
            fail(f"{shot.eml_name(i)} missing subject")
    subprocess.check_call([sys.executable, os.path.join(HERE, "screenshot_personality.py")])
    after = snapshot()
    if after != before:
        fail("running screenshot_personality.py changed the fixture")

    srv = shot.start_server()
    try:
        import imaplib
        host, port_s = srv.bound["listen"].rsplit(":", 1)
        mail = imaplib.IMAP4(host, int(port_s), timeout=30)
        typ, _ = mail.login(shot.SCREENSHOT_USER, shot.SCREENSHOT_PASSWORD)
        if typ != "OK":
            fail("login failed")
        typ, data = mail.list()
        if typ != "OK":
            fail("LIST failed")
        listed = []
        for raw in data:
            line = raw.decode("utf-8", "replace") if isinstance(raw, (bytes, bytearray)) else raw
            listed.append(line.rsplit(" ", 1)[-1].strip('"'))
        want = [n for n, *_ in shot.FOLDERS]
        if listed != want:
            fail(f"LIST names {listed} != {want}")
        for name, count, unseen, _attr in shot.FOLDERS:
            typ, data = mail.status(name, "(MESSAGES UNSEEN)")
            if typ != "OK":
                fail(f"STATUS {name} failed")
            text = data[0].decode("utf-8", "replace") if isinstance(data[0], (bytes, bytearray)) else data[0]
            got_m = int(re.search(r"MESSAGES (\d+)", text).group(1))
            got_u = int(re.search(r"UNSEEN (\d+)", text).group(1))
            if got_m != count or got_u != unseen:
                fail(f"STATUS {name} MESSAGES {got_m} UNSEEN {got_u}, want {count} {unseen}")
        typ, _ = mail.select("INBOX")
        if typ != "OK":
            fail("SELECT INBOX failed")
        typ, data = mail.fetch("18393:18432", "(ENVELOPE)")
        if typ != "OK":
            fail("FETCH INBOX failed")
        blob = b"\n".join(x[1] if isinstance(x, tuple) else x for x in data if x)
        # imaplib may return tuples; also join all bytes parts
        parts = []
        for item in data:
            if isinstance(item, tuple):
                parts.extend(p for p in item if isinstance(p, (bytes, bytearray)))
            elif isinstance(item, (bytes, bytearray)):
                parts.append(item)
        blob = b"\n".join(parts)
        text = blob.decode("utf-8", "replace")
        subjects = re.findall(r'ENVELOPE \("[^"]*" "([^"]+)"', text)
        if not subjects:
            # some servers quote differently; fall back to the 40 known subjects in order
            subjects = [s for s, _n, _l in shot.SUBJECTS if s in text]
        if subjects != [s for s, _n, _l in shot.SUBJECTS]:
            fail(f"INBOX newest subjects {subjects}")
        typ, _ = mail.select("Photos")
        if typ != "OK":
            fail("SELECT Photos failed")
        typ, _ = mail.select("Drafts")
        if typ != "OK":
            fail("SELECT Drafts failed")
        mail.logout()
    finally:
        srv.close()
    print("ok")


if __name__ == "__main__":
    main()
