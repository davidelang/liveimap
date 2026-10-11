"""Stateful mailbox model for the strict fake (fake_imap.py, FAKE_MODEL=<json>), used by safety_run.py.

The personality still answers greeting, CAPABILITY, login, ENABLE, NAMESPACE, LIST and the like. Mailbox
commands (SELECT/EXAMINE, SEARCH, FLAGS FETCH, STORE, COPY, MOVE, EXPUNGE, CLOSE, UNSELECT, NOOP, CHECK,
STATUS) are answered from this model so that deletes and moves have real effects that can be checked.
Config keys:
  drop_caps: capability names removed from every CAPABILITY list (to test degradation); a command that
             needs a dropped or never-advertised capability (MOVE, UID EXPUNGE, UNSELECT) gets BAD and is
             logged as unexpected.
  folder:    test mailbox (default LiveIMAP-Test) holding UIDs 1..count (default 10).
  script:    outside changes, e.g. {"at_noop": 1, "expunge_uid": 5} or {"at_noop": 2, "append": 1}:
             applied as if by another client, reported as untagged EXPUNGE / EXISTS on that NOOP.
"""
import re

FLAGS = r"\Answered \Flagged \Deleted \Seen \Draft"


def parse_set(text, uids):
    out = set()
    top = max(uids) if uids else 0
    for part in text.split(","):
        if part == "$":
            continue
        a, _, b = part.partition(":")
        lo = top if a == "*" else int(a)
        hi = lo if not b else (top if b == "*" else int(b))
        lo, hi = min(lo, hi), max(lo, hi)
        out |= {u for u in uids if lo <= u <= hi}
    return out


def compact(uids):
    uids = sorted(uids)
    if not uids:
        return ""
    out, start, prev = [], uids[0], uids[0]
    for u in uids[1:] + [None]:
        if u is not None and u == prev + 1:
            prev = u
            continue
        out.append(str(start) if start == prev else f"{start}:{prev}")
        if u is not None:
            start = prev = u
    return ",".join(out)


class Model:
    def __init__(self, cfg, caps):
        self.cfg = cfg
        self.drop = {c.upper() for c in cfg.get("drop_caps", [])}
        self.caps = {c.upper() for c in caps} - self.drop
        self.folder = cfg.get("folder", "LiveIMAP-Test")
        n = cfg.get("count", 10)
        self.boxes = {self.folder: {"uids": list(range(1, n + 1)), "flags": {u: set() for u in range(1, n + 1)},
                                    "next": n + 1},
                      "INBOX": {"uids": [], "flags": {}, "next": 1}}
        self.script = list(cfg.get("script", []))
        self.noops = 0
        self.expunged = []  # (mailbox, uid, why)

    def has(self, cap):
        return cap.upper() in self.caps

    def strip_caps(self, line):
        if not self.drop or "CAPABILITY" not in line.upper():
            return line
        return " ".join(w for w in line.split(" ") if w.upper().strip("[]") not in self.drop)

    def box(self, name):
        key = "INBOX" if name.upper() == "INBOX" else name
        return self.boxes.setdefault(key, {"uids": [], "flags": {}, "next": 1})

    # Each handler returns (lines without CRLF, ok, unexpected-reason or None), or None when not handled.
    def handle(self, st, tag, rest):
        words = rest.split(" ")
        cmd = words[0].upper()
        uid = cmd == "UID"
        if uid:
            words = words[1:]
            cmd = words[0].upper() if words else ""
        arg = " ".join(words[1:])
        if cmd in ("SELECT", "EXAMINE"):
            name = re.sub(r" \(.*$", "", arg).strip('"')
            b = self.box(name)
            st["sel"], st["rw"] = name, cmd == "SELECT"
            st["seen"] = list(b["uids"])
            return ([f"* FLAGS ({FLAGS})", f"* {len(b['uids'])} EXISTS", "* 0 RECENT", "* OK [UIDVALIDITY 1] v",
                     f"* OK [UIDNEXT {b['next']}] n", f"* OK [PERMANENTFLAGS ({FLAGS} \\*)] p",
                     f"{tag} OK [{'READ-WRITE' if st['rw'] else 'READ-ONLY'}] {cmd} done"], True, None)
        if cmd == "STATUS":
            name = arg.split(" (")[0].strip('"')
            b = self.box(name)
            return ([f'* STATUS "{name}" (MESSAGES {len(b["uids"])} UIDNEXT {b["next"]} UIDVALIDITY 1 UNSEEN 0)',
                     f"{tag} OK STATUS done"], True, None)
        sel = st.get("sel")
        if cmd in ("NOOP", "CHECK"):
            out = self.outside(st) if cmd == "NOOP" else []
            return (out + [f"{tag} OK {cmd} done"], True, None)
        if cmd == "UNSELECT":
            if not self.has("UNSELECT"):
                return ([f"{tag} BAD UNSELECT not advertised"], False, "UNSELECT without capability")
            st["sel"] = None
            return ([f"{tag} OK UNSELECT done"], True, None)
        if cmd == "CLOSE":
            out = []
            if sel and st.get("rw"):
                self.expunge(st, sel, None, "CLOSE")
            st["sel"] = None
            return (out + [f"{tag} OK CLOSE done"], True, None)
        if sel is None:
            return None
        b = self.box(sel)
        if cmd == "SEARCH":
            crit = arg.upper()
            ret = re.match(r"RETURN \(([^)]*)\) ?(.*)$", arg, re.I)
            if ret:
                crit = ret.group(2).upper()
            hits = list(b["uids"])
            if "UNDELETED" in crit:
                hits = [u for u in hits if "\\Deleted" not in b["flags"][u]]
            elif re.search(r"\bDELETED\b", crit):
                hits = [u for u in hits if "\\Deleted" in b["flags"][u]]
            m = re.search(r"\bUID ([0-9:*,]+)", crit)
            if m:
                hits = sorted(parse_set(m.group(1), hits))
            nums = hits if uid else [b["uids"].index(u) + 1 for u in hits]
            if ret:
                opts = ret.group(1).upper().split()
                parts = []
                if "MIN" in opts and nums: parts.append(f"MIN {min(nums)}")
                if "MAX" in opts and nums: parts.append(f"MAX {max(nums)}")
                if "COUNT" in opts: parts.append(f"COUNT {len(nums)}")
                if ("ALL" in opts or not opts) and nums: parts.append(f"ALL {compact(nums)}")
                return ([f'* ESEARCH (TAG "{tag}"){" UID" if uid else ""} ' + " ".join(parts), f"{tag} OK SEARCH done"],
                        True, None)
            return ([("* SEARCH " + " ".join(map(str, nums))).rstrip(), f"{tag} OK SEARCH done"], True, None)
        if cmd == "FETCH":
            items = arg.split(" ", 1)[1].upper() if " " in arg else ""
            if not re.fullmatch(r"\(?(UID|FLAGS|\s)+\)?", items.strip()):
                return None  # anything richer than flags falls through to the recorded table
            targets = self.targets(b, words[1], uid)
            return ([f"* {b['uids'].index(u) + 1} FETCH (UID {u} FLAGS ({' '.join(sorted(b['flags'][u]))}))"
                     for u in targets] + [f"{tag} OK FETCH done"], True, None)
        if cmd == "STORE":
            if not st.get("rw"):
                return ([f"{tag} NO read-only"], False, "STORE on a read-only mailbox")
            m = re.match(r"(\S+) ([+-]?)FLAGS(\.SILENT)? \(?([^)]*)\)?", arg, re.I)
            targets = self.targets(b, m.group(1), uid)
            fl = set(m.group(4).split())
            for u in targets:
                if m.group(2) == "+": b["flags"][u] |= fl
                elif m.group(2) == "-": b["flags"][u] -= fl
                else: b["flags"][u] = set(fl)
            out = [] if m.group(3) else [f"* {b['uids'].index(u) + 1} FETCH (UID {u} FLAGS ({' '.join(sorted(b['flags'][u]))}))"
                                         for u in targets]
            return (out + [f"{tag} OK STORE done"], True, None)
        if cmd in ("COPY", "MOVE"):
            if cmd == "MOVE" and not self.has("MOVE"):
                return ([f"{tag} BAD MOVE not advertised"], False, "MOVE without capability")
            setspec, dest = arg.split(" ", 1)
            targets = sorted(self.targets(b, setspec, uid))
            d = self.box(dest.strip('"'))
            new = []
            for u in targets:
                nu = d["next"]; d["next"] += 1
                d["uids"].append(nu); d["flags"][nu] = set(b["flags"][u]) - {"\\Deleted"}; new.append(nu)
            code = f"[COPYUID 1 {compact(targets)} {compact(new)}] " if self.has("UIDPLUS") and targets else ""
            if cmd == "COPY":
                return ([f"{tag} OK {code}COPY done"], True, None)
            out = [f"* OK {code}moved"] if code else []
            out += self.expunge(st, sel, set(targets), "MOVE", report=True, force=True)
            return (out + [f"{tag} OK MOVE done"], True, None)
        if cmd == "EXPUNGE":
            if uid and not self.has("UIDPLUS"):
                return ([f"{tag} BAD UID EXPUNGE not advertised"], False, "UID EXPUNGE without UIDPLUS")
            if not st.get("rw"):
                return ([f"{tag} NO read-only"], False, "EXPUNGE on a read-only mailbox")
            only = self.targets(b, words[1], True) if uid else None
            out = self.expunge(st, sel, only, "UID EXPUNGE" if uid else "EXPUNGE", report=True)
            return (out + [f"{tag} OK EXPUNGE done"], True, None)
        return None

    def targets(self, b, spec, uid):
        if uid:
            return parse_set(spec, b["uids"])
        seqs = parse_set(spec, list(range(1, len(b["uids"]) + 1)))
        return {b["uids"][s - 1] for s in seqs}

    def expunge(self, st, name, only, why, report=False, force=False):
        b = self.box(name)
        gone = [u for u in b["uids"] if (force or "\\Deleted" in b["flags"][u]) and (only is None or u in only)]
        out = []
        for u in sorted(gone, reverse=True):
            out.append(f"* {b['uids'].index(u) + 1} EXPUNGE")
            b["uids"].remove(u); del b["flags"][u]
            self.expunged.append((name, u, why))
        return out if report else []

    def outside(self, st):
        """Scripted changes by 'another client', reported on the selected mailbox at the Nth NOOP."""
        self.noops += 1
        out = []
        for ev in [e for e in self.script if e.get("at_noop") == self.noops]:
            b = self.box(self.folder)
            if "expunge_uid" in ev and ev["expunge_uid"] in b["uids"]:
                u = ev["expunge_uid"]
                if st.get("sel") == self.folder:
                    out.append(f"* {b['uids'].index(u) + 1} EXPUNGE")
                b["uids"].remove(u); del b["flags"][u]
                self.expunged.append((self.folder, u, "outside"))
            for _ in range(ev.get("append", 0)):
                nu = b["next"]; b["next"] += 1
                b["uids"].append(nu); b["flags"][nu] = set()
            if ev.get("append") and st.get("sel") == self.folder:
                out.append(f"* {len(b['uids'])} EXISTS")
        return out
