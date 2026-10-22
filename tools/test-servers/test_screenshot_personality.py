#!/usr/bin/env python3
"""Check personalities/screenshot.json against a fresh build of screenshot_personality.py."""
import json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import screenshot_personality as shot

COUNTS = [
    18432, 4201, 7, 903, 1566, 27650, 842, 126, 2104, 9330, 418, 12044,
    2677, 15890, 96, 14, 3502, 7844, 231, 88, 640, 1520, 4102, 8901, 6233,
    1904, 733, 2488, 57, 164, 3110, 43, 512, 22018, 6755, 990,
]
INBOX_FETCH = "FETCH 18313:18432 (UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE)"


def fail(text):
    sys.exit(text)


def variants(doc, cmd):
    found = []
    for group in doc["table"].values():
        for variant in group:
            if variant.get("cmd") == cmd:
                found.append(variant)
    return found


def message_counts(variant):
    counts = []
    for item in variant["items"]:
        counts += [int(n) for n in re.findall(r"MESSAGES (\d+)", item["line"])]
    return counts


def main():
    path = shot.OUT
    if not os.path.exists(path):
        fail(f"missing {path}")
    disk = open(path).read()
    if disk != shot.render(shot.build()):
        fail("personality differs from a fresh build")
    subprocess.check_call([sys.executable, os.path.join(HERE, "screenshot_personality.py")])
    if open(path).read() != disk:
        fail("running screenshot_personality.py changed the file")
    doc = json.loads(disk)
    messages = variants(doc, shot.LIST_MESSAGES)
    unseen = variants(doc, shot.LIST_UNSEEN)
    if len(messages) != 1 or len(unseen) != 1:
        fail("both LIST commands must be present once")
    if message_counts(messages[0]) != COUNTS or message_counts(unseen[0]) != COUNTS:
        fail("the 36 message counts must appear once each")
    inbox = variants(doc, INBOX_FETCH)
    if len(inbox) != 1:
        fail("INBOX fetch command missing")
    reply = "\n".join(item["line"] for item in inbox[0]["items"])
    subjects = re.findall(r'ENVELOPE \("[^"]*" "([^"]+)"', reply)
    if len(subjects) != 40:
        fail(f"INBOX reply has {len(subjects)} ENVELOPE subjects")
    if "{" in reply.replace("{TAG}", ""):
        fail("INBOX reply has a literal")
    for group in doc["table"].values():
        for variant in group:
            cmd = variant["cmd"].upper()
            if "BODY.PEEK" in cmd or "PREVIEW" in cmd:
                fail(f"forbidden command: {variant['cmd']}")
    print("ok")


if __name__ == "__main__":
    main()
