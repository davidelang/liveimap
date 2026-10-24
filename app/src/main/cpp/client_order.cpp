// Client order follows ClientSortCollation and does not match server SORT.
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <regex>
#include <string>
#include <vector>

#ifdef LIVEIMAP_IMAP
#include <libetpan/libetpan.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#endif

namespace {

int cmpBytes(const uint8_t * a, int an, const uint8_t * b, int bn) {
    int n = an < bn ? an : bn;
    for (int i = 0; i < n; ++i) {
        if (a[i] != b[i]) return static_cast<int>(a[i]) - static_cast<int>(b[i]);
    }
    return an - bn;
}

std::string lowerAscii(std::string s) {
    for (char & c : s) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return s;
}

std::string trimCopy(const std::string & s) {
    size_t a = 0;
    size_t b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r')) --b;
    return s.substr(a, b - a);
}

std::string collapseWs(const std::string & s) {
    std::string out;
    out.reserve(s.size());
    bool sp = false;
    for (unsigned char c : s) {
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            sp = true;
            continue;
        }
        if (sp && !out.empty()) out.push_back(' ');
        sp = false;
        out.push_back(static_cast<char>(c));
    }
    return out;
}

bool startsWithI(const std::string & s, const char * pfx) {
    size_t n = std::strlen(pfx);
    if (s.size() < n) return false;
    for (size_t i = 0; i < n; ++i) {
        char c = s[i];
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        if (c != pfx[i]) return false;
    }
    return true;
}

int hexVal(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string latin1Utf8(const std::string & bytes) {
    std::string out;
    for (unsigned char c : bytes) {
        if (c < 0x80) out.push_back(static_cast<char>(c));
        else {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    return out;
}

std::string decodeB(const std::string & payload) {
    std::string out;
    int val = 0;
    int bits = -8;
    for (unsigned char c : payload) {
        int d = -1;
        if (c >= 'A' && c <= 'Z') d = c - 'A';
        else if (c >= 'a' && c <= 'z') d = c - 'a' + 26;
        else if (c >= '0' && c <= '9') d = c - '0' + 52;
        else if (c == '+') d = 62;
        else if (c == '/') d = 63;
        else if (c == '=') break;
        else continue;
        val = (val << 6) + d;
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<char>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

std::string decodeQ(const std::string & payload) {
    std::string out;
    for (size_t i = 0; i < payload.size(); ++i) {
        char c = payload[i];
        if (c == '_') out.push_back(' ');
        else if (c == '=' && i + 2 < payload.size()) {
            int h = hexVal(payload[i + 1]);
            int l = hexVal(payload[i + 2]);
            if (h >= 0 && l >= 0) {
                out.push_back(static_cast<char>((h << 4) | l));
                i += 2;
            } else out.push_back(c);
        } else out.push_back(c);
    }
    return out;
}

std::string charsetBytes(const std::string & charset, const std::string & bytes) {
    std::string name = lowerAscii(charset);
    if (name == "utf-8" || name == "utf8" || name == "us-ascii" || name == "ascii") return bytes;
    if (name == "iso-8859-1" || name == "latin1" || name == "windows-1252") return latin1Utf8(bytes);
    return "";
}

}  // namespace

std::string co_decode_words(const std::string & value) {
    std::string out;
    size_t i = 0;
    while (i < value.size()) {
        if (!(i + 1 < value.size() && value[i] == '=' && value[i + 1] == '?')) {
            out.push_back(value[i]);
            ++i;
            continue;
        }
        size_t cs = i + 2;
        size_t q1 = value.find('?', cs);
        if (q1 == std::string::npos || q1 == cs) {
            out.push_back(value[i]);
            ++i;
            continue;
        }
        if (q1 + 3 >= value.size() || value[q1 + 2] != '?') {
            out.push_back(value[i]);
            ++i;
            continue;
        }
        char enc = value[q1 + 1];
        if (enc != 'B' && enc != 'b' && enc != 'Q' && enc != 'q') {
            out.push_back(value[i]);
            ++i;
            continue;
        }
        size_t pay = q1 + 3;
        size_t q2 = value.find('?', pay);
        if (q2 == std::string::npos || q2 == pay || q2 + 1 >= value.size() || value[q2 + 1] != '=') {
            out.push_back(value[i]);
            ++i;
            continue;
        }
        std::string raw = value.substr(i, q2 + 2 - i);
        std::string bytes = (enc == 'B' || enc == 'b') ? decodeB(value.substr(pay, q2 - pay))
                                                        : decodeQ(value.substr(pay, q2 - pay));
        std::string text = charsetBytes(value.substr(cs, q1 - cs), bytes);
        if (text.empty() && !bytes.empty()) out += raw;
        else out += text.empty() ? raw : text;
        i = q2 + 2;
        size_t gap = i;
        while (gap < value.size() && (value[gap] == ' ' || value[gap] == '\t')) ++gap;
        if (gap > i && gap + 1 < value.size() && value[gap] == '=' && value[gap + 1] == '?') i = gap;
    }
    return out;
}

std::string co_base_subject(const std::string & decoded) {
    std::string s = trimCopy(collapseWs(decoded));
    try {
        static const std::regex trailer(R"(\s*\(fwd\)\s*$)", std::regex::icase);
        static const std::regex refwd(R"(^(re|fwd?)\s*(\[[^\[\]]*\]\s*)?:\s*)", std::regex::icase);
        static const std::regex blob(R"(^\[[^\[\]]*\]\s*)");
        for (;;) {
            std::string before = s;
            for (;;) {
                std::string t = trimCopy(std::regex_replace(s, trailer, ""));
                if (t == s) break;
                s = t;
            }
            for (;;) {
                std::smatch m;
                if (std::regex_search(s, m, refwd) && m.position() == 0) {
                    s = trimCopy(s.substr(static_cast<size_t>(m.length())));
                    continue;
                }
                if (std::regex_search(s, m, blob) && m.position() == 0 && s.size() > static_cast<size_t>(m.length())) {
                    s = trimCopy(s.substr(static_cast<size_t>(m.length())));
                    continue;
                }
                break;
            }
            if (startsWithI(s, "[fwd:") && !s.empty() && s.back() == ']') {
                s = trimCopy(s.substr(5, s.size() - 6));
            }
            if (s == before) break;
        }
    } catch (const std::regex_error &) {
        return lowerAscii(s);
    }
    return lowerAscii(s);
}

std::string co_first_mailbox(const std::string & header) {
    std::string plain;
    std::string angle;
    bool inQuote = false;
    int depth = 0;
    bool inAngle = false;
    bool sawAngle = false;
    for (size_t i = 0; i < header.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(header[i]);
        auto & dest = inAngle ? angle : plain;
        if (inQuote) {
            if (c == '\\' && i + 1 < header.size()) dest.push_back(header[++i]);
            else if (c == '"') inQuote = false;
            else dest.push_back(static_cast<char>(c));
        } else if (depth > 0) {
            if (c == '\\') ++i;
            else if (c == '(') ++depth;
            else if (c == ')') --depth;
        } else if (c == '(') depth = 1;
        else if (c == '"') inQuote = true;
        else if (inAngle) {
            if (c == '>') inAngle = false;
            else angle.push_back(static_cast<char>(c));
        } else if (c == '<') {
            inAngle = true;
            sawAngle = true;
            angle.clear();
        } else if (c == ':') plain.clear();
        else if (c == ',' || c == ';') {
            const std::string & cur = sawAngle ? angle : plain;
            if (trimCopy(cur).size() > 0) break;
            plain.clear();
            angle.clear();
            sawAngle = false;
        } else plain.push_back(static_cast<char>(c));
    }
    std::string spec = trimCopy(sawAngle ? angle : plain);
    if (sawAngle) {
        auto pos = spec.rfind(':');
        if (pos != std::string::npos) spec = spec.substr(pos + 1);
        spec = trimCopy(spec);
    }
    auto at = spec.rfind('@');
    std::string local = at == std::string::npos ? spec : spec.substr(0, at);
    return lowerAscii(trimCopy(co_decode_words(trimCopy(local))));
}

bool co_date_epoch(const std::string & header, int64_t * out) {
    std::string text = header;
    try {
        static const std::regex comments(R"(\([^)]*\))");
        text = std::regex_replace(text, comments, " ");
    } catch (const std::regex_error &) {
        return false;
    }
    text = trimCopy(collapseWs(text));
    if (text.empty()) return false;
    std::smatch m;
    try {
        static const std::regex shape(
            R"(^(?:[A-Za-z]+\s*,\s*)?(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([+-]\d{4}|[A-Za-z]{1,5})?)",
            std::regex::icase);
        if (!std::regex_search(text, m, shape)) return false;
    } catch (const std::regex_error &) {
        return false;
    }
    int day = std::stoi(m[1].str());
    std::string mon = lowerAscii(m[2].str());
    static const char * months[] = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    int month = 0;
    for (int i = 0; i < 12; ++i) if (mon == months[i]) month = i + 1;
    if (month == 0) return false;
    std::string ys = m[3].str();
    int year = std::stoi(ys);
    if (ys.size() == 2) year = year < 50 ? 2000 + year : 1900 + year;
    else if (ys.size() == 3) year = 1900 + year;
    int hh = std::stoi(m[4].str());
    int mm = std::stoi(m[5].str());
    int ss = m[6].matched ? std::stoi(m[6].str()) : 0;
    int offsetMin = 0;
    if (m[7].matched && !m[7].str().empty()) {
        std::string zone = m[7].str();
        if (zone[0] == '+' || zone[0] == '-') {
            int minutes = std::stoi(zone.substr(1, 2)) * 60 + std::stoi(zone.substr(3));
            offsetMin = zone[0] == '-' ? -minutes : minutes;
        } else {
            std::string z = lowerAscii(zone);
            if (z == "edt") offsetMin = -4 * 60;
            else if (z == "est" || z == "cdt") offsetMin = -5 * 60;
            else if (z == "cst" || z == "mdt") offsetMin = -6 * 60;
            else if (z == "mst" || z == "pdt") offsetMin = -7 * 60;
            else if (z == "pst") offsetMin = -8 * 60;
        }
    }
    if (hh > 23 || mm > 59 || ss > 60 || day < 1 || day > 31) return false;
    std::tm tm{};
    tm.tm_year = year - 1900;
    tm.tm_mon = month - 1;
    tm.tm_mday = day;
    tm.tm_hour = hh;
    tm.tm_min = mm;
    tm.tm_sec = ss;
    tm.tm_isdst = 0;
    time_t utc = timegm(&tm);
    if (utc == static_cast<time_t>(-1) && (year < 1970)) {
        // timegm can fail before the epoch on some libcs; compute from a known day when it does.
    }
    if (out != nullptr) *out = static_cast<int64_t>(utc) - static_cast<int64_t>(offsetMin) * 60;
    std::tm back{};
    if (gmtime_r(&utc, &back) == nullptr) return false;
    if (back.tm_year != tm.tm_year || back.tm_mon != tm.tm_mon || back.tm_mday != tm.tm_mday) return false;
    return true;
}

int co_order(const int64_t * uids, const int64_t * number, const uint8_t * blank, const uint8_t * bytes,
    const int32_t * off, const int32_t * len, int n, int numeric, int newestFirst, int64_t * out) {
    std::vector<int> filled;
    std::vector<int> blanks;
    filled.reserve(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) {
        if (uids[i] <= 0) continue;
        if (blank != nullptr && blank[i]) blanks.push_back(i);
        else filled.push_back(i);
    }
    auto lessAsc = [&](int a, int b) {
        if (numeric) {
            if (number[a] != number[b]) return number[a] < number[b];
        } else {
            int c = cmpBytes(bytes + off[a], len[a], bytes + off[b], len[b]);
            if (c != 0) return c < 0;
        }
        return uids[a] < uids[b];
    };
    std::sort(filled.begin(), filled.end(), lessAsc);
    if (newestFirst) std::reverse(filled.begin(), filled.end());
    std::sort(blanks.begin(), blanks.end(), [&](int a, int b) { return uids[a] < uids[b]; });
    int k = 0;
    for (int i : filled) out[k++] = uids[i];
    for (int i : blanks) out[k++] = uids[i];
    return k;
}

static int cmpRow(int numeric, int64_t uidA, int64_t numA, const uint8_t * bytesA, int lenA, int64_t uidB, int64_t numB,
    const uint8_t * bytesB, int lenB) {
    if (numeric) {
        if (numA != numB) return numA < numB ? -1 : 1;
    } else {
        int c = cmpBytes(bytesA, lenA, bytesB, lenB);
        if (c != 0) return c < 0 ? -1 : 1;
    }
    if (uidA != uidB) return uidA < uidB ? -1 : 1;
    return 0;
}

int co_insert_at(const int64_t * uids, const int64_t * number, const uint8_t * blank, const uint8_t * bytes,
    const int32_t * off, const int32_t * len, int n, int numeric, int newestFirst, int64_t newUid, int64_t newNumber,
    int newBlank, const uint8_t * newBytes, int newLen) {
    int blankCount = 0;
    for (int i = 0; i < n; ++i) if (blank != nullptr && blank[i]) ++blankCount;
    int prefix = n - blankCount;
    if (newBlank) {
        int lo = prefix;
        int hi = n;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (newUid < uids[mid]) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }
    int lo = 0;
    int hi = prefix;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        const uint8_t * midBytes = bytes == nullptr ? nullptr : bytes + off[mid];
        int midLen = len == nullptr ? 0 : len[mid];
        int c = cmpRow(numeric, newUid, newNumber, newBytes, newLen, uids[mid], number == nullptr ? 0 : number[mid],
            midBytes, midLen);
        if (newestFirst) c = -c;
        if (c < 0) hi = mid;
        else lo = mid + 1;
    }
    return lo;
}

namespace {

int cmpDateUid(int64_t epochA, int blankA, int64_t uidA, int64_t epochB, int blankB, int64_t uidB) {
    if (blankA && !blankB) return 1;
    if (!blankA && blankB) return -1;
    if (!blankA && !blankB && epochA != epochB) return epochA < epochB ? -1 : 1;
    if (uidA != uidB) return uidA < uidB ? -1 : 1;
    return 0;
}

std::vector<std::string> messageIds(const std::string & raw) {
    std::vector<std::string> found;
    for (size_t i = 0; i < raw.size(); ++i) {
        if (raw[i] != '<') continue;
        size_t j = raw.find('>', i + 1);
        if (j == std::string::npos) break;
        found.push_back(lowerAscii(raw.substr(i, j - i + 1)));
        i = j;
    }
    if (!found.empty()) return found;
    std::string bare = trimCopy(raw);
    if (bare.empty()) return found;
    if (!bare.empty() && bare.front() == '<') bare.erase(bare.begin());
    if (!bare.empty() && bare.back() == '>') bare.pop_back();
    bare = lowerAscii(bare);
    if (!bare.empty()) found.push_back("<" + bare + ">");
    return found;
}

bool createsCycle(int64_t uid, int64_t parent, const std::vector<int64_t> & parentOf, const std::vector<int64_t> & uids) {
    int64_t cursor = parent;
    std::vector<int64_t> seen;
    while (cursor != 0) {
        if (cursor == uid) return true;
        for (int64_t s : seen) if (s == cursor) return true;
        seen.push_back(cursor);
        int64_t next = 0;
        for (size_t i = 0; i < uids.size(); ++i) {
            if (uids[i] == cursor) {
                next = parentOf[i];
                break;
            }
        }
        cursor = next;
    }
    return false;
}

int indexOfUid(const std::vector<int64_t> & uids, int64_t uid) {
    for (size_t i = 0; i < uids.size(); ++i) if (uids[i] == uid) return static_cast<int>(i);
    return -1;
}

}  // namespace

struct CoFlat {
    std::vector<int64_t> uids;
    std::vector<int16_t> depths;
};

CoFlat co_thread_subject(const int64_t * uids, const char * const * subjects, const int64_t * epochs, const uint8_t * blankDate,
    int n) {
    struct Item {
        int64_t uid;
        int64_t epoch;
        int blank;
        std::string subject;
    };
    std::vector<Item> rows;
    for (int i = 0; i < n; ++i) {
        if (uids[i] <= 0) continue;
        Item item;
        item.uid = uids[i];
        item.epoch = epochs == nullptr ? 0 : epochs[i];
        item.blank = blankDate != nullptr && blankDate[i];
        item.subject = subjects != nullptr && subjects[i] != nullptr ? subjects[i] : "";
        rows.push_back(std::move(item));
    }
    std::vector<std::string> keys;
    std::vector<std::vector<Item>> groups;
    std::vector<Item> blanks;
    for (const Item & row : rows) {
        if (row.subject.empty()) {
            blanks.push_back(row);
            continue;
        }
        size_t g = 0;
        for (; g < keys.size(); ++g) if (keys[g] == row.subject) break;
        if (g == keys.size()) {
            keys.push_back(row.subject);
            groups.emplace_back();
        }
        groups[g].push_back(row);
    }
    auto byDate = [](const Item & a, const Item & b) {
        return cmpDateUid(a.epoch, a.blank, a.uid, b.epoch, b.blank, b.uid) < 0;
    };
    struct Thread {
        Item root;
        std::vector<Item> kids;
    };
    std::vector<Thread> threads;
    for (auto & group : groups) {
        std::sort(group.begin(), group.end(), byDate);
        Thread t;
        t.root = group.front();
        t.kids.assign(group.begin() + 1, group.end());
        threads.push_back(std::move(t));
    }
    for (const Item & row : blanks) {
        Thread t;
        t.root = row;
        threads.push_back(std::move(t));
    }
    std::sort(threads.begin(), threads.end(), [&](const Thread & a, const Thread & b) { return byDate(a.root, b.root); });
    CoFlat out;
    for (const Thread & t : threads) {
        out.uids.push_back(t.root.uid);
        out.depths.push_back(0);
        for (const Item & kid : t.kids) {
            out.uids.push_back(kid.uid);
            out.depths.push_back(1);
        }
    }
    return out;
}

CoFlat co_thread_references(const int64_t * uidIn, const char * const * messageId, const char * const * references,
    const char * const * inReplyTo, int n) {
    std::vector<int64_t> uids;
    std::vector<std::string> mid;
    std::vector<std::string> refs;
    std::vector<std::string> reply;
    for (int i = 0; i < n; ++i) {
        if (uidIn[i] <= 0) continue;
        uids.push_back(uidIn[i]);
        mid.push_back(messageId != nullptr && messageId[i] != nullptr ? messageId[i] : "");
        refs.push_back(references != nullptr && references[i] != nullptr ? references[i] : "");
        reply.push_back(inReplyTo != nullptr && inReplyTo[i] != nullptr ? inReplyTo[i] : "");
    }
    std::vector<int> order(uids.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = static_cast<int>(i);
    std::sort(order.begin(), order.end(), [&](int a, int b) { return uids[a] < uids[b]; });
    std::vector<int64_t> su;
    std::vector<std::string> sm, sr, si;
    su.reserve(order.size());
    for (int i : order) {
        su.push_back(uids[i]);
        sm.push_back(mid[i]);
        sr.push_back(refs[i]);
        si.push_back(reply[i]);
    }
    std::vector<std::string> idKey;
    std::vector<int64_t> idUid;
    for (size_t i = 0; i < su.size(); ++i) {
        auto ids = messageIds(sm[i]);
        if (ids.empty()) continue;
        bool seen = false;
        for (const auto & k : idKey) if (k == ids[0]) seen = true;
        if (seen) continue;
        idKey.push_back(ids[0]);
        idUid.push_back(su[i]);
    }
    auto lookup = [&](const std::string & id) -> int64_t {
        for (size_t i = 0; i < idKey.size(); ++i) if (idKey[i] == id) return idUid[i];
        return 0;
    };
    auto parentOfRow = [&](size_t i) -> int64_t {
        auto rev = messageIds(sr[i]);
        for (int k = static_cast<int>(rev.size()) - 1; k >= 0; --k) {
            int64_t uid = lookup(rev[k]);
            if (uid != 0 && uid != su[i]) return uid;
        }
        auto irt = messageIds(si[i]);
        for (int k = static_cast<int>(irt.size()) - 1; k >= 0; --k) {
            int64_t uid = lookup(irt[k]);
            if (uid != 0 && uid != su[i]) return uid;
        }
        return 0;
    };
    std::vector<int64_t> parentOf(su.size(), 0);
    bool allDescending = true;
    for (size_t i = 0; i < su.size(); ++i) {
        int64_t parent = parentOfRow(i);
        if (parent == 0 || parent == su[i]) continue;
        if (!(allDescending && parent < su[i]) && createsCycle(su[i], parent, parentOf, su)) continue;
        if (parent > su[i]) allDescending = false;
        parentOf[i] = parent;
    }
    std::vector<int64_t> roots;
    std::vector<std::vector<int64_t>> kids(su.size());
    for (size_t i = 0; i < su.size(); ++i) {
        if (parentOf[i] == 0) roots.push_back(su[i]);
        else {
            int p = indexOfUid(su, parentOf[i]);
            if (p >= 0) kids[p].push_back(su[i]);
            else roots.push_back(su[i]);
        }
    }
    std::sort(roots.begin(), roots.end());
    for (auto & k : kids) std::sort(k.begin(), k.end());
    CoFlat out;
    std::vector<int64_t> stackUid;
    std::vector<int> stackDepth;
    for (int i = static_cast<int>(roots.size()) - 1; i >= 0; --i) {
        stackUid.push_back(roots[i]);
        stackDepth.push_back(0);
    }
    while (!stackUid.empty()) {
        int64_t uid = stackUid.back();
        int depth = stackDepth.back();
        stackUid.pop_back();
        stackDepth.pop_back();
        out.uids.push_back(uid);
        out.depths.push_back(static_cast<int16_t>(depth));
        int ix = indexOfUid(su, uid);
        if (ix < 0) continue;
        const auto & ch = kids[ix];
        for (int i = static_cast<int>(ch.size()) - 1; i >= 0; --i) {
            stackUid.push_back(ch[i]);
            stackDepth.push_back(depth + 1);
        }
    }
    return out;
}

extern "C" int liveimap_client_order_parse(const uint8_t * data, size_t size) {
    if (data == nullptr || size == 0) return 0;
    std::string block(reinterpret_cast<const char *>(data), size > (1 << 20) ? (1 << 20) : size);
    co_base_subject(block);
    co_first_mailbox(block);
    int64_t epoch = 0;
    co_date_epoch(block, &epoch);
    const int cap = 32;
    int n = 0;
    int64_t uids[32];
    int64_t numbers[32];
    uint8_t blanks[32];
    uint8_t bytes[32 * 8];
    int32_t off[32];
    int32_t len[32];
    const char * subjects[32];
    const char * mids[32];
    std::string held[32];
    size_t at = 0;
    while (n < cap && at + 8 <= size) {
        int64_t uid = 0;
        for (int b = 0; b < 8; ++b) uid = (uid << 8) | data[at++];
        if (uid < 0) uid = -uid;
        uids[n] = uid == 0 ? n + 1 : uid;
        numbers[n] = static_cast<int64_t>(data[at > 0 ? at - 1 : 0]);
        blanks[n] = (data[0] & 1) && (n % 5 == 4) ? 1 : 0;
        off[n] = n * 8;
        len[n] = 8;
        for (int b = 0; b < 8; ++b) bytes[off[n] + b] = data[(at + b) % size];
        held[n] = block.substr(0, std::min(block.size(), static_cast<size_t>(16 + n)));
        subjects[n] = held[n].c_str();
        mids[n] = held[n].c_str();
        ++n;
    }
    int64_t ordered[32];
    if (n > 0) {
        co_order(uids, numbers, blanks, bytes, off, len, n, data[0] & 2 ? 1 : 0, data[0] & 4 ? 1 : 0, ordered);
        uint8_t dateBlank[32];
        for (int i = 0; i < n; ++i) dateBlank[i] = blanks[i];
        co_thread_subject(uids, subjects, numbers, dateBlank, n);
        co_thread_references(uids, mids, mids, mids, n);
        co_insert_at(ordered, numbers, blanks, bytes, off, len, n, 1, 0, 1, 0, 0, bytes, 8);
    }
    return 0;
}

#ifdef LIVEIMAP_FUZZ_CLIENT_THREAD
extern "C" int LLVMFuzzerTestOneInput(const uint8_t * data, size_t size) {
    liveimap_client_order_parse(data, size);
    return 0;
}
#endif

#ifdef LIVEIMAP_IMAP

namespace {

const int kClientSortChunk = 5000;

void freeCStringList(clist * hdrs) {
    if (hdrs == nullptr) return;
    for (clistiter * cur = clist_begin(hdrs); cur != nullptr; cur = clist_next(cur)) free(clist_content(cur));
    clist_free(hdrs);
}

struct mailimap_fetch_att * peekHeaderFields(const char * const * names, size_t count) {
    clist * hdrs = clist_new();
    if (hdrs == nullptr) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        char * copy = strdup(names[i]);
        if (copy == nullptr) {
            freeCStringList(hdrs);
            return nullptr;
        }
        if (clist_append(hdrs, copy) != 0) {
            free(copy);
            freeCStringList(hdrs);
            return nullptr;
        }
    }
    struct mailimap_header_list * list = mailimap_header_list_new(hdrs);
    if (list == nullptr) {
        freeCStringList(hdrs);
        return nullptr;
    }
    struct mailimap_section * section = mailimap_section_new_header_fields(list);
    if (section == nullptr) {
        mailimap_header_list_free(list);
        return nullptr;
    }
    struct mailimap_fetch_att * att = mailimap_fetch_att_new_body_peek_section(section);
    if (att == nullptr) mailimap_section_free(section);
    return att;
}

bool addFetchAtt(struct mailimap_fetch_type * fetch, struct mailimap_fetch_att * att) {
    if (att == nullptr) return false;
    if (mailimap_fetch_type_new_fetch_att_list_add(fetch, att) != MAILIMAP_NO_ERROR) {
        mailimap_fetch_att_free(att);
        return false;
    }
    return true;
}

struct mailimap_fetch_type * uidPlusExtra(struct mailimap_fetch_att * extra) {
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    if (fetch == nullptr) {
        if (extra != nullptr) mailimap_fetch_att_free(extra);
        return nullptr;
    }
    bool uidOk = addFetchAtt(fetch, mailimap_fetch_att_new_uid());
    bool extraOk = addFetchAtt(fetch, extra);
    if (!uidOk || !extraOk) {
        mailimap_fetch_type_free(fetch);
        return nullptr;
    }
    return fetch;
}

std::string unfoldHeaderBlock(const char * bytes, size_t n) {
    std::string out;
    if (bytes == nullptr || n == 0) return out;
    out.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        char c = bytes[i];
        if (c == '\r' && i + 2 < n && bytes[i + 1] == '\n' && (bytes[i + 2] == ' ' || bytes[i + 2] == '\t')) {
            out.push_back(' ');
            i += 2;
            continue;
        }
        if (c == '\n' && i + 1 < n && (bytes[i + 1] == ' ' || bytes[i + 1] == '\t')) {
            out.push_back(' ');
            ++i;
            continue;
        }
        out.push_back(c);
    }
    return out;
}

std::string headerField(const std::string & block, const char * name) {
    size_t start = 0;
    while (start < block.size()) {
        size_t end = block.find('\n', start);
        if (end == std::string::npos) end = block.size();
        std::string line = block.substr(start, end - start);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        start = end < block.size() ? end + 1 : block.size();
        size_t colon = line.find(':');
        if (colon == std::string::npos) continue;
        if (strcasecmp(trimCopy(line.substr(0, colon)).c_str(), name) != 0) continue;
        return trimCopy(line.substr(colon + 1));
    }
    return "";
}

std::string firstBodyText(struct mailimap_msg_att * att) {
    if (att == nullptr || att->att_list == nullptr) return "";
    for (clistiter * cur = clist_begin(att->att_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(cur));
        if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC) continue;
        struct mailimap_msg_att_static * st = item->att_data.att_static;
        if (st == nullptr || st->att_type != MAILIMAP_MSG_ATT_BODY_SECTION) continue;
        struct mailimap_msg_att_body_section * body = st->att_data.att_body_section;
        if (body == nullptr || body->sec_body_part == nullptr || body->sec_length == 0) return "";
        return unfoldHeaderBlock(body->sec_body_part, body->sec_length);
    }
    return "";
}

uint32_t attUid(struct mailimap_msg_att * att) {
    if (att == nullptr || att->att_list == nullptr) return 0;
    for (clistiter * cur = clist_begin(att->att_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(cur));
        if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC) continue;
        struct mailimap_msg_att_static * st = item->att_data.att_static;
        if (st != nullptr && st->att_type == MAILIMAP_MSG_ATT_UID) return st->att_data.att_uid;
    }
    return 0;
}

bool attSize(struct mailimap_msg_att * att, int64_t * number) {
    if (att == nullptr || att->att_list == nullptr) return false;
    for (clistiter * cur = clist_begin(att->att_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(cur));
        if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC) continue;
        struct mailimap_msg_att_static * st = item->att_data.att_static;
        if (st != nullptr && st->att_type == MAILIMAP_MSG_ATT_RFC822_SIZE) {
            *number = static_cast<int64_t>(st->att_data.att_rfc822_size);
            return true;
        }
    }
    return false;
}

struct mailimap_set * setFromUids(const uint32_t * uids, size_t n) {
    struct mailimap_set * set = mailimap_set_new_empty();
    if (set == nullptr) return nullptr;
    for (size_t i = 0; i < n; ++i) {
        if (uids[i] == 0) {
            mailimap_set_free(set);
            return nullptr;
        }
        if (mailimap_set_add_single(set, uids[i]) != MAILIMAP_NO_ERROR) {
            mailimap_set_free(set);
            return nullptr;
        }
    }
    return set;
}

}  // namespace

int co_client_sort_chunk() { return kClientSortChunk; }

std::vector<uint32_t> co_search_all(mailimap * imap, int * err) {
    std::vector<uint32_t> uids;
    if (err != nullptr) *err = MAILIMAP_NO_ERROR;
    if (imap == nullptr) {
        if (err != nullptr) *err = MAILIMAP_ERROR_BAD_STATE;
        return uids;
    }
    struct mailimap_search_key * key = mailimap_search_key_new_all();
    if (key == nullptr) {
        if (err != nullptr) *err = MAILIMAP_ERROR_MEMORY;
        return uids;
    }
    clist * result = nullptr;
    int r = mailimap_uid_search(imap, nullptr, key, &result);
    mailimap_search_key_free(key);
    if (r != MAILIMAP_NO_ERROR) {
        if (result != nullptr) mailimap_search_result_free(result);
        if (err != nullptr) *err = r;
        return uids;
    }
    if (result != nullptr) {
        for (clistiter * cur = clist_begin(result); cur != nullptr; cur = clist_next(cur)) {
            auto * n = static_cast<uint32_t *>(clist_content(cur));
            if (n != nullptr && *n != 0) uids.push_back(*n);
        }
        mailimap_search_result_free(result);
    }
    return uids;
}

struct CoFetch {
    std::vector<int64_t> uids;
    std::vector<int64_t> numbers;
    std::vector<uint8_t> blank;
    std::vector<std::string> text;
    int err = 0;
};

CoFetch co_fetch_sort(mailimap * imap, const uint32_t * uids, size_t n, int field) {
    CoFetch out;
    if (imap == nullptr) {
        out.err = MAILIMAP_ERROR_BAD_STATE;
        return out;
    }
    if (n == 0) return out;
    const char * header = nullptr;
    struct mailimap_fetch_att * extra = nullptr;
    if (field == 1) extra = mailimap_fetch_att_new_rfc822_size();
    else {
        header = "Subject";
        if (field == 0) header = "Date";
        else if (field == 3) header = "From";
        else if (field == 4) header = "To";
        else if (field == 5) header = "Cc";
        const char * names[] = {header};
        extra = peekHeaderFields(names, 1);
    }
    struct mailimap_fetch_type * fetch = uidPlusExtra(extra);
    struct mailimap_set * set = setFromUids(uids, n);
    if (fetch == nullptr || set == nullptr) {
        if (fetch != nullptr) mailimap_fetch_type_free(fetch);
        if (set != nullptr) mailimap_set_free(set);
        out.err = MAILIMAP_ERROR_MEMORY;
        return out;
    }
    clist * list = nullptr;
    int r = mailimap_uid_fetch(imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    if (r != MAILIMAP_NO_ERROR) {
        if (list != nullptr) mailimap_fetch_list_free(list);
        out.err = r;
        return out;
    }
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * att = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            uint32_t uid = attUid(att);
            if (uid == 0) continue;
            out.uids.push_back(static_cast<int64_t>(uid));
            if (field == 1) {
                int64_t number = 0;
                bool saw = attSize(att, &number);
                out.numbers.push_back(saw ? number : 0);
                out.blank.push_back(saw ? 0 : 1);
                out.text.emplace_back();
            } else {
                std::string block = firstBodyText(att);
                out.numbers.push_back(0);
                out.blank.push_back(0);
                out.text.push_back(headerField(block, header));
            }
        }
        mailimap_fetch_list_free(list);
    }
    return out;
}

struct CoHeaders {
    std::vector<int64_t> uids;
    std::vector<std::string> messageId, references, inReplyTo, subject, date;
    int err = 0;
};

CoHeaders co_fetch_thread(mailimap * imap, const uint32_t * uids, size_t n) {
    CoHeaders out;
    if (imap == nullptr) {
        out.err = MAILIMAP_ERROR_BAD_STATE;
        return out;
    }
    if (n == 0) return out;
    const char * names[] = {"Message-ID", "References", "In-Reply-To", "Subject", "Date"};
    struct mailimap_fetch_type * fetch = uidPlusExtra(peekHeaderFields(names, 5));
    struct mailimap_set * set = setFromUids(uids, n);
    if (fetch == nullptr || set == nullptr) {
        if (fetch != nullptr) mailimap_fetch_type_free(fetch);
        if (set != nullptr) mailimap_set_free(set);
        out.err = MAILIMAP_ERROR_MEMORY;
        return out;
    }
    clist * list = nullptr;
    int r = mailimap_uid_fetch(imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    if (r != MAILIMAP_NO_ERROR) {
        if (list != nullptr) mailimap_fetch_list_free(list);
        out.err = r;
        return out;
    }
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * att = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            uint32_t uid = attUid(att);
            if (uid == 0) continue;
            std::string block = firstBodyText(att);
            out.uids.push_back(static_cast<int64_t>(uid));
            out.messageId.push_back(headerField(block, "Message-ID"));
            out.references.push_back(headerField(block, "References"));
            out.inReplyTo.push_back(headerField(block, "In-Reply-To"));
            out.subject.push_back(headerField(block, "Subject"));
            out.date.push_back(headerField(block, "Date"));
        }
        mailimap_fetch_list_free(list);
    }
    return out;
}

#endif
