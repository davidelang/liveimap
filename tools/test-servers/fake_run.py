#!/usr/bin/env python3
"""Rerun the lab suite on the emulator against pinned fakeimap for each server personality
and compare with the real run it was built from. Usage: fake_run.py SERVER... (or --all).

Starts third_party/fakeimap/artifact/fakeimap with that lab server's personality file,
a generated dataset, and SMTP plus ManageSieve listeners. Fails if the pin version is
still UNSELECTED or the binary has not been emerged.

Writes recordings/fake-<stamp>-summary.tsv: server, real steps, fake steps, equivalence,
differing steps, unexpected, never; and recordings/fake-<stamp>-capgap.tsv.

The unexpected and never columns are n/a:no-replay-log — fakeimap does not replay
recordings or log unexpected/never-hit commands (see fakeimap_pin.py).
"""
import glob, json, os, re, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lab
import fakeimap_pin

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

NA_REPLAY = "n/a:no-replay-log"



def pick_recording(server):
    """Newest real-lab recording of [server] that has a steps file with a passing first step."""
    dirs = sorted(d for d in glob.glob(os.path.join(lab.REC, server, "2*")) if glob.glob(os.path.join(d, f"{server}-*.jsonl")))
    for d in reversed(dirs):
        for pat in ("None-LOGIN-CMD", "None", "Implicit", "StartTls", "Implicit-LOGIN-CMD", "StartTls-LOGIN-CMD"):
            f = os.path.join(d, f"{server}-{pat}.jsonl")
            st = f[:-6] + ".steps.tsv"
            if os.path.exists(f) and os.path.exists(st) and "\tok\t" in open(st).read().split("\n", 2)[1] + "\t":
                return f
    return None

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


def main():
    fakeimap_pin.require_ready()
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
    for name in names:
        s = next(x for x in lab.servers() if x["name"] == name)
        adv, gap = capgap(name)
        gapf.write(f"{name}\t{len(adv)}\t{' '.join(gap)}\n"); gapf.flush()
        pers = fakeimap_pin.PERSONALITIES / f"{name}.json"
        if not pers.is_file():
            print(f"{name}: no fakeimap personality at {pers}", flush=True)
            summ.write(f"{name}\t-\t-\t-\tno-personality\t\t\t\n"); summ.flush(); continue
        src = pick_recording(name)
        dest = os.path.join(lab.REC, name, f"fake-{stamp}")
        os.makedirs(dest, exist_ok=True)
        folder = "LiveIMAP-Test"
        if src:
            seed = os.path.join(os.path.dirname(src), "seed.txt")
            if os.path.exists(seed) and ": " in open(seed).read():
                folder = open(seed).read().split(":", 1)[0].strip()
        s = dict(s, folder=folder)
        ds = fakeimap_pin.write_dataset(os.path.join(dest, "dataset"), folder=folder,
                                        user=lab.USER, password=lab.PASS)
        control = fakeimap_pin.short_control_path(f"fr-{name}")
        flog = os.path.join(dest, "fake-log.jsonl")
        cfg = fakeimap_pin.write_config(
            os.path.join(dest, "config.json"),
            listen=f"{lab.LAB_HOST}:0",
            personality=os.path.abspath(pers),
            dataset=os.path.abspath(ds),
            control=str(control),
            log_path=os.path.abspath(flog),
            smtp_listen=f"{lab.LAB_HOST}:0",
            sieve_listen=f"{lab.LAB_HOST}:0",
        )
        srv = fakeimap_pin.start(cfg, os.path.join(dest, "fake.err"))
        run = f"{name}-fake"
        smtp_src = side_source(name, "smtp")
        sieve_src = side_source(name, "sieve")
        side = ["-e", "labSmtpPort", str(srv.port("smtp")), "-e", "labRcpt", f"{lab.USER}@lab.test"]
        side += ["-e", "labSievePort", str(srv.port("sieve")), "-e", "labSieveTls", "None", "-e", "labSievePlain", "true"]
        try:
            res, msg = lab.instrument(s, srv.port("listen"), "None", run, commit, "LOGIN-CMD", side=side)
        except subprocess.TimeoutExpired:
            res, msg = "fail", "timeout"
        finally:
            srv.close()
        files = lab.pull(run, dest)
        st = [f for f in files if f.endswith(".steps.tsv")]
        real = steps(src[:-6] + ".steps.tsv") if src else {}
        fake = steps(st[0]) if st else {}
        skip = {"smtpSend", "sieveDeliver"}
        diff = sorted(k for k in (set(real) | set(fake)) - skip if real.get(k) != fake.get(k))
        for stepname, ssrc in (("smtpSend", smtp_src), ("sieveDeliver", sieve_src)):
            if not ssrc:
                continue
            want = steps(ssrc[:-6] + ".steps.tsv").get(stepname)
            if want != fake.get(stepname):
                diff.append(f"{stepname}(real={want},fake={fake.get(stepname)})")
        r_ok = sum(v == "ok" for v in real.values()); f_ok = sum(v == "ok" for v in fake.values())
        equiv = "no-recording" if not src else ("yes" if not diff else "no")
        source = os.path.basename(src) if src else "-"
        summ.write(f"{name}\t{source}\t{r_ok}/{len(real)}\t{f_ok}/{len(fake)}\t{equiv}\t"
                   f"{','.join(diff)}\t{NA_REPLAY}\t{NA_REPLAY}\n")
        summ.flush()
    print(summ.name); print(gapf.name)


if __name__ == "__main__":
    main()
