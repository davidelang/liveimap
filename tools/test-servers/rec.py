"""Shared JSON-lines recorder (format 1), same layout as the app's TrafficRecorder."""
import base64, json, re, time

_LIT = re.compile(rb"~?\{(\d+)[+-]?\}$")


class Recorder:
    def __init__(self, path, header):
        self.f = open(path, "w", encoding="utf-8")
        h = {"type": "run", "format": 1}
        h.update(header)
        h.setdefault("started", int(time.time() * 1000))
        self._w(h)
        self.client_lit = 0
        self.armed = False

    def _w(self, obj):
        self.f.write(json.dumps(obj, separators=(",", ":")) + "\n")
        self.f.flush()

    def _now(self):
        return int(time.time() * 1000)

    def event(self, kind, detail="", conn="main"):
        self._w({"t": self._now(), "conn": conn, "dir": "E", "event": kind, "line": detail})

    def line(self, d, raw, conn="main", redacted=0):
        rec = {"t": self._now(), "conn": conn, "dir": d}
        if raw is None:
            rec["redacted"] = redacted
        else:
            rec["line"] = raw.decode("latin-1")
            if redacted:
                rec["redacted"] = redacted
        self._w(rec)

    def literal(self, d, data, conn="main"):
        self._w({"t": self._now(), "conn": conn, "dir": d, "literal_b64": base64.b64encode(data).decode()})

    def sent(self, data):
        while data:
            if self.client_lit:
                n = min(self.client_lit, len(data))
                self.literal("C", data[:n])
                self.client_lit -= n
                data = data[n:]
                continue
            nl = data.find(b"\n")
            chunk, data = (data, b"") if nl < 0 else (data[: nl + 1], data[nl + 1 :])
            text = chunk.rstrip(b"\r\n")
            if not text and chunk in (b"\r\n", b"\n"):
                continue
            toks = text.split(b" ")
            cmd = toks[1].upper() if len(toks) > 1 else b""
            if cmd == b"LOGIN" and len(toks) >= 3:
                kept = b" ".join(toks[:2])
                self.line("C", kept, redacted=len(text) - len(kept))
            elif cmd == b"AUTHENTICATE" and len(toks) >= 4:
                kept = b" ".join(toks[:3])
                self.line("C", kept, redacted=len(text) - len(kept))
            elif self.armed and not (len(toks) > 1 and toks[1].isalpha()):
                self.line("C", None, redacted=len(text))
            else:
                self.line("C", text)
            self.armed = cmd == b"AUTHENTICATE"
            m = _LIT.search(text)
            if m:
                self.client_lit = int(m.group(1))

    def received_line(self, raw):
        text = raw.rstrip(b"\r\n")
        self.line("S", text)
        if not (text == b"+" or text.startswith(b"+ ")):
            self.armed = False
        m = _LIT.search(text)
        return int(m.group(1)) if m else None

    def close(self):
        self.event("close")
        self.f.close()
