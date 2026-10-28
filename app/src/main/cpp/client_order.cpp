// Client order follows ClientSortCollation and does not match server SORT.
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>
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

}  // namespace

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

bool createsCycle(int64_t uid, int64_t parent, const std::vector<int64_t> & parentOf,
    const std::unordered_map<int64_t, int> & uidAt) {
    int64_t cursor = parent;
    std::unordered_map<int64_t, int> seen;
    while (cursor != 0) {
        if (cursor == uid) return true;
        if (!seen.emplace(cursor, 1).second) return true;
        auto at = uidAt.find(cursor);
        if (at == uidAt.end()) return false;
        cursor = parentOf[static_cast<size_t>(at->second)];
    }
    return false;
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
    std::unordered_map<std::string, size_t> groupAt;
    std::vector<std::vector<Item>> groups;
    std::vector<Item> blanks;
    groupAt.reserve(rows.size());
    for (const Item & row : rows) {
        if (row.subject.empty()) {
            blanks.push_back(row);
            continue;
        }
        auto found = groupAt.find(row.subject);
        size_t g;
        if (found == groupAt.end()) {
            g = groups.size();
            groupAt.emplace(row.subject, g);
            groups.emplace_back();
        } else {
            g = found->second;
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
    std::unordered_map<std::string, int64_t> idUid;
    idUid.reserve(su.size() * 2);
    for (size_t i = 0; i < su.size(); ++i) {
        auto ids = messageIds(sm[i]);
        if (ids.empty()) continue;
        if (idUid.find(ids[0]) != idUid.end()) continue;
        idUid.emplace(ids[0], su[i]);
    }
    std::unordered_map<int64_t, int> uidAt;
    uidAt.reserve(su.size() * 2);
    for (size_t i = 0; i < su.size(); ++i) uidAt.emplace(su[i], static_cast<int>(i));
    auto lookup = [&](const std::string & id) -> int64_t {
        auto found = idUid.find(id);
        return found == idUid.end() ? 0 : found->second;
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
        if (!(allDescending && parent < su[i]) && createsCycle(su[i], parent, parentOf, uidAt)) continue;
        if (parent > su[i]) allDescending = false;
        parentOf[i] = parent;
    }
    std::vector<int64_t> roots;
    std::vector<std::vector<int64_t>> kids(su.size());
    for (size_t i = 0; i < su.size(); ++i) {
        if (parentOf[i] == 0) roots.push_back(su[i]);
        else {
            auto parent = uidAt.find(parentOf[i]);
            if (parent != uidAt.end()) kids[static_cast<size_t>(parent->second)].push_back(su[i]);
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
        auto at = uidAt.find(uid);
        if (at == uidAt.end()) continue;
        const auto & ch = kids[static_cast<size_t>(at->second)];
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
