#!/usr/bin/env python3
"""Phase E: rerun the lab suite on the emulator against the strict fake for each personality and compare
with the real run it was built from. Usage: fake_run.py SERVER... (or --all).
Writes recordings/fake-<stamp>-summary.tsv: server, real steps, fake steps, equivalence, differing steps,
unexpected commands, never-hits; and recordings/fake-<stamp>-capgap.tsv: capabilities advertised by the
server but never used by the app in any recording."""
import glob, json, os, re, socket, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab, personality

# capability -> regex over client command lines that shows the app used it
USES = {"SORT": r"^\S+ (UID )?SORT", "THREAD=REFERENCES": r"THREAD REFERENCES", "THREAD=ORDEREDSUBJECT": r"THREAD ORDEREDSUBJECT",
        "ESEARCH": r"SEARCH RETURN", "MOVE": r"(UID )?MOVE ", "UIDPLUS": r"UID EXPUNGE", "IDLE": r" IDLE$",
        "CONDSTORE": r"CHANGEDSINCE|CONDSTORE|MODSEQ", "QRESYNC": r"QRESYNC", "ENABLE": r" ENABLE ", "ID": r"^\S+ ID ",
        "NAMESPACE": r" NAMESPACE$", "SPECIAL-USE": r"SPECIAL-USE", "LIST-STATUS": r"RETURN \(.*STATUS",
        "BINARY": r"BINARY", "COMPRESS=DEFLATE": r"COMPRESS", "UNSELECT": r" UNSELECT$", "LITERAL+": r"\{\d+\+\}",
        "LIST-EXTENDED": r"LIST \(|RETURN \(", "ESORT": r"SORT RETURN", "SEARCHRES": r"\$", "STATUS=SIZE": r"STATUS .*SIZE",
        "OBJECTID": r"EMAILID|THREADID|MAILBOXID", "PREVIEW": r"PREVIEW", "SAVEDATE": r"SAVEDATE", "APPENDLIMIT": r"APPEND",
        "NOTIFY": r" NOTIFY ", "METADATA": r"GETMETADATA", "SASL-IR": r"AUTHENTICATE \S+ \S", "IMAP4REV2": r"ENABLE IMAP4REV2",
        "UTF8=ACCEPT": r"ENABLE UTF8", "CATENATE": r"CATENATE", "MULTIAPPEND": r"APPEND", "WITHIN": r"YOUNGER|OLDER",
        "SEARCH=FUZZY": r"FUZZY", "CHILDREN": r".", "LOGIN-REFERRALS": r".", "SASL": r"."}


def steps(path):
    return {r[0]: r[1] for r in (l.split("\t") for l in open(path).read().splitlines()[1:]) if len(r) > 1}


def capgap(server):
    adv, used = set(), []
    for f in glob.glob(os.path.join(lab.REC, server, "2*", "*.jsonl")):
        for l in open(f):
            if not l.strip().startswith("{"):
                continue
            j = json.loads(l)
            if j.get("dir") == "S" and "CAPABILITY" in j.get("line", "").upper():
                m = re.search(r"CAPABILITY ([^\]]*)", j["line"], re.I)
                if m:
                    adv |= {w.upper() for w in m.group(1).split() if re.match(r"^[A-Z0-9][A-Z0-9=+\-._]*$", w, re.I) and not w.endswith(".")}
            elif j.get("dir") == "C" and "line" in j:
                used.append(j["line"])
    adv -= {"CAPABILITY", "OK", "*", "IMAP4REV1", "IMAP4", "STARTTLS", "LOGINDISABLED", "COMPLETED", "PRE-LOGIN", "LOGGED", "IN"}
    adv = {a for a in adv if not a.startswith("AUTH=") and not re.match(r"^\d+$", a)}
    gap = sorted(c for c in adv if not any(re.search(USES.get(c, r"(?!)"), u, re.I) for u in used))
    return sorted(adv), gap


def side_source(server, proto):
    """Newest recording of [server] with a [proto] connection (StartTls preferred), or None."""
    files = sorted(glob.glob(os.path.join(lab.REC, server, "2*", "*.jsonl")), reverse=True)
    files.sort(key=lambda f: "StartTls" not in f)
    for f in files:
        if f'"conn":"{proto}","dir":"C"' in open(f).read():
            return f
    return None


def start_side(name, dest):
    """Start fake SMTP / ManageSieve servers from recordings; returns (instrument extras, procs, compare map)."""
    side, procs, cmp = [], [], {}
    for proto, stepname in (("smtp", "smtpSend"), ("sieve", "sieveDeliver")):
        src = side_source(name, proto)
        if not src:
            continue
        pj = os.path.join(dest, f"fake-{proto}.json")
        subprocess.run([sys.executable, os.path.join(HERE, "fake_line.py"), "build", proto, src, pj], check=True)
        sock = socket.socket(); sock.bind((lab.LAB_HOST, 0)); port = sock.getsockname()[1]; sock.close()
        procs.append(subprocess.Popen([sys.executable, os.path.join(HERE, "fake_line.py"), "serve", pj, lab.LAB_HOST, str(port),
                                       os.path.join(dest, f"fake-{proto}-log.jsonl")], stderr=open(os.path.join(dest, f"fake-{proto}.err"), "w")))
        cmp[stepname] = src
        if proto == "smtp":
            rcpt = next((re.search(r"<([^>]*)>", json.loads(l)["line"]).group(1) for l in open(src)
                         if '"conn":"smtp","dir":"C","line":"RCPT TO:' in l), "liveimap@lab.test")
            side += ["-e", "labSmtpPort", str(port), "-e", "labRcpt", rcpt]
        else:
            side += ["-e", "labSievePort", str(port), "-e", "labSieveTls", "None", "-e", "labSievePlain", "true"]
    return side, procs, cmp


def main():
    args = [a for a in sys.argv[1:] if a != "--skip-install"]
    names = [s["name"] for s in lab.servers()] if args == ["--all"] else args
    stamp = time.strftime("%Y%m%d-%H%M%S")
    commit = lab.head()
    if "--skip-install" not in sys.argv:
        lab.install(commit)
    summ = open(os.path.join(lab.REC, f"fake-{stamp}-summary.tsv"), "w")
    summ.write("server\tsource\treal\tfake\tequivalent\tdiffering_steps\tunexpected\tnever\n")
    gapf = open(os.path.join(lab.REC, f"fake-{stamp}-capgap.tsv"), "w")
    gapf.write("server\tadvertised\tunused_by_app\n")
    pdir = os.path.join(lab.GEN, "personalities")
    os.makedirs(pdir, exist_ok=True)
    for name in names:
        s = next(x for x in lab.servers() if x["name"] == name)
        adv, gap = capgap(name)
        gapf.write(f"{name}\t{len(adv)}\t{' '.join(gap)}\n"); gapf.flush()
        src = personality.pick(name)
        if not src:
            summ.write(f"{name}\t-\t-\t-\tno-recording\t\t\t\n"); continue
        p = personality.build(src)
        pj = os.path.join(pdir, f"{name}.json")
        json.dump(p, open(pj, "w"), indent=1)
        dest = os.path.join(lab.REC, name, f"fake-{stamp}")
        os.makedirs(dest, exist_ok=True)
        flog = os.path.join(dest, "fake-log.jsonl")
        sock = socket.socket(); sock.bind((lab.LAB_HOST, 0)); port = sock.getsockname()[1]; sock.close()
        fk = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_imap.py"), pj, lab.LAB_HOST, str(port), flog],
                              stdout=subprocess.DEVNULL, stderr=open(os.path.join(dest, "fake.err"), "w"))
        time.sleep(1)
        folder = "LiveIMAP-Test"
        seed = os.path.join(os.path.dirname(src), "seed.txt")
        if os.path.exists(seed) and ": " in open(seed).read():
            folder = open(seed).read().split(":", 1)[0].strip()
        s["folder"] = folder
        run = f"{name}-fake"
        side, procs, cmp = start_side(name, dest)
        time.sleep(0.5)
        try:
            res, msg = lab.instrument(s, port, "None", run, commit, "LOGIN-CMD", side=side)
        except subprocess.TimeoutExpired:
            res, msg = "fail", "timeout"
        finally:
            fk.terminate()
            for pr in procs:
                pr.terminate()
        files = lab.pull(run, dest)
        st = [f for f in files if f.endswith(".steps.tsv")]
        real = steps(src[:-6] + ".steps.tsv")
        fake = steps(st[0]) if st else {}
        ev = [json.loads(l) for l in open(flog)] if os.path.exists(flog) else []
        unexp = [e["cmd"] for e in ev if e.get("unexpected")]
        # SMTP and ManageSieve steps compare against the recording each line fake was built from.
        skip = {"smtpSend", "sieveDeliver"}
        diff = sorted(k for k in (set(real) | set(fake)) - skip if real.get(k) != fake.get(k))
        for stepname, ssrc in cmp.items():
            want = steps(ssrc[:-6] + ".steps.tsv").get(stepname)
            if want != fake.get(stepname):
                diff.append(f"{stepname}(real={want},fake={fake.get(stepname)})")
        for proto in ("smtp", "sieve"):
            lp = os.path.join(dest, f"fake-{proto}-log.jsonl")
            if os.path.exists(lp):
                unexp += [f"{proto}:{json.loads(l)['cmd']}" for l in open(lp) if '"unexpected": true' in l]
        never = [e["cmd"] for e in ev if e.get("never")]
        r_ok = sum(v == "ok" for v in real.values()); f_ok = sum(v == "ok" for v in fake.values())
        summ.write(f"{name}\t{os.path.basename(src)}\t{r_ok}/{len(real)}\t{f_ok}/{len(fake)}\t{'yes' if not diff else 'no'}\t"
                   f"{','.join(diff)}\t{' | '.join(unexp)[:300]}\t{' | '.join(never)[:200]}\n")
        summ.flush()
    print(summ.name); print(gapf.name)


if __name__ == "__main__":
    main()
