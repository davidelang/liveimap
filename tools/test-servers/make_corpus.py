#!/usr/bin/env python3
"""Write the base lab corpus from seed tables. No network and no Docker."""

import argparse
import csv
import hashlib
import shutil
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SEED = ROOT / "seed"
START = datetime(2024, 10, 1, 0, 0, 0, tzinfo=timezone.utc)


def read_tsv(path):
    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    if not rows:
        sys.exit(f"empty table: {path}")
    return rows


def message_bytes(n, user, marker):
    instant = START + timedelta(hours=n - 1)
    subject = "LiveIMAP seed corpus-v1 base" if marker else f"Corpus {n}"
    lines = [
        f"From: Lab Sender {n % 7} <sender{n % 7}@corpus.liveimap.test>",
        f"To: {user}@corpus.liveimap.test",
        f"Date: {format_datetime(instant)}",
        f"Message-ID: <c{n:06d}.v1@corpus.liveimap.test>",
        f"Subject: {subject}",
        "X-LiveIMAP-Seed: corpus-v1",
    ]
    if marker:
        lines.append("X-LiveIMAP-Seed-Profile: base")
    lines.extend(
        [
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=utf-8",
            "Content-Transfer-Encoding: 7bit",
            "",
            f"corpus-v1 {n}",
        ]
    )
    return ("\r\n".join(lines) + "\r\n").encode("utf-8"), instant


def flag_words(folder, index, n, marker):
    if marker:
        return "-"
    if folder == "Drafts":
        return r"\Draft"
    if folder == "Flags":
        if index < 3:
            return r"\Seen \Deleted"
        return r"\Seen"
    if n % 2 == 0:
        return r"\Seen"
    return "-"


def slug_for(folder):
    return folder.replace("/", "_")


def generate(out_dir, accounts, folders):
    profiles = {row["user"]: row["profile"] for row in accounts}
    by_user = {}
    for row in folders:
        by_user.setdefault(row["user"], []).append(row)
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    manifest = ["user\tfolder\tfile\tflags\tinternaldate\tsha256"]
    per_folder = []
    n = 1
    for account in accounts:
        user = account["user"]
        if account["profile"] != "base":
            continue
        for row in by_user.get(user, []):
            folder = row["folder"]
            count = int(row["count"])
            hashes = []
            if count:
                dest = out_dir / user / slug_for(folder)
                dest.mkdir(parents=True, exist_ok=True)
            for index in range(count):
                marker = folder == "INBOX" and index == 0
                payload, instant = message_bytes(n, user, marker)
                name = f"{index + 1:05d}.eml"
                path = out_dir / user / slug_for(folder) / name
                path.write_bytes(payload)
                digest = hashlib.sha256(payload).hexdigest()
                hashes.append(digest)
                internal = instant.strftime("%Y-%m-%dT%H:%M:%SZ")
                flags = flag_words(folder, index, n, marker)
                manifest.append(
                    f"{user}\t{folder}\t{name}\t{flags}\t{internal}\t{digest}"
                )
                n += 1
            per_folder.append((user, folder, count, hashes))
    (out_dir / "manifest.tsv").write_text("\n".join(manifest) + "\n", encoding="utf-8")
    unused = [row["user"] for row in folders if profiles.get(row["user"]) != "base"]
    if unused:
        sys.exit("refusing to write a non-base folder row")
    return per_folder


def sums_text(per_folder):
    lines = []
    for user, folder, count, hashes in per_folder:
        joined = "\n".join(hashes).encode("utf-8")
        digest = hashlib.sha256(joined).hexdigest()
        lines.append(f"{user}\t{folder}\t{count}\t{digest}")
    return "\n".join(lines) + "\n"


def load_tables():
    accounts = read_tsv(SEED / "accounts.tsv")
    folders = read_tsv(SEED / "folders.tsv")
    return accounts, folders


def main():
    parser = argparse.ArgumentParser(description="Generate the lab corpus.")
    parser.add_argument("--version", required=True, choices=["v1"])
    parser.add_argument("--sums", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    accounts, folders = load_tables()
    if args.check:
        with tempfile.TemporaryDirectory(prefix="corpus-v1-") as temp:
            per_folder = generate(Path(temp) / "corpus-v1", accounts, folders)
            got = sums_text(per_folder)
        sums_path = SEED / "corpus-v1.sums"
        want = sums_path.read_text(encoding="utf-8")
        if got != want:
            print("corpus-v1 sums mismatch", file=sys.stderr)
            return 1
        return 0
    out = ROOT / "generated" / "corpus-v1"
    per_folder = generate(out, accounts, folders)
    if args.sums:
        sys.stdout.write(sums_text(per_folder))
    return 0


if __name__ == "__main__":
    sys.exit(main())
