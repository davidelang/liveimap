#include <libetpan/mailimap.h>

#include <cstdio>

extern "C" {
#include "imap_test_utils.h"
}

namespace {

const char kFireworks[] =
    "* 16 FETCH (FLAGS () UID 16 INTERNALDATE \" 3-Jul-2011 00:29:59 -0700\" RFC822.SIZE 1876 "
    "ENVELOPE (\"Sat, 02 Jul 2011 18:29:04 -0700\" \"Check in\" ((\"MrDX\" NIL \"mrdx\" \"cox.net\")) "
    "((\"MrDX\" NIL \"mrdx\" \"cox.net\")) ((\"MrDX\" NIL \"mrdx\" \"cox.net\")) "
    "((NIL NIL \"fireworks\" \"lang.hm\")) NIL NIL NIL \"<4E0FC5E0.1080605@cox.net>\") "
    "BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"ISO-8859-1\" \"FORMAT\" \"flowed\") "
    "NIL NIL \"7BIT\" 200 11 NIL NIL NIL))\r\n"
    "22 OK Completed\r\n";

int fail(const char * why) {
    std::fprintf(stderr, "fireworks: %s\n", why);
    return 1;
}

uint32_t uidOf(struct mailimap_msg_att * att) {
    if (att == nullptr || att->att_list == nullptr) return 0;
    for (clistiter * cur = clist_begin(att->att_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(cur));
        if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC) continue;
        struct mailimap_msg_att_static * st = item->att_data.att_static;
        if (st != nullptr && st->att_type == MAILIMAP_MSG_ATT_UID) return st->att_data.att_uid;
    }
    return 0;
}

}  // namespace

int main() {
    MMAPString * output = nullptr;
    mailimap * imap = mailimap_new(0, nullptr);
    if (imap == nullptr) return fail("no session");
    imap->imap_stream = imap_test_stream_from_string_with_output(kFireworks, &output);
    if (imap->imap_stream == nullptr) return fail("no stream");
    imap->imap_state = MAILIMAP_STATE_SELECTED;
    imap->imap_tag = 21;

    struct mailimap_set * set = mailimap_set_new_single(16);
    struct mailimap_fetch_att * attReq = mailimap_fetch_att_new_uid();
    struct mailimap_fetch_type * fetchType = mailimap_fetch_type_new_fetch_att(attReq);
    clist * list = nullptr;
    int r = mailimap_fetch(imap, set, fetchType, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetchType);
    if (r != MAILIMAP_NO_ERROR) {
        std::fprintf(stderr, "fireworks: mailimap_fetch returned %d\n", r);
        return 1;
    }
    int count = list == nullptr ? -1 : clist_count(list);
    if (count != 1) {
        std::fprintf(stderr, "fireworks: item count %d\n", count);
        return 1;
    }
    auto * att = static_cast<struct mailimap_msg_att *>(clist_content(clist_begin(list)));
    if (att == nullptr || att->att_number != 16) return fail("att_number");
    uint32_t uid = uidOf(att);
    if (uid != 16) {
        std::fprintf(stderr, "fireworks: uid %u\n", uid);
        return 1;
    }

    mailimap_fetch_list_free(list);
    if (imap->imap_stream != nullptr) {
        mailstream_close(imap->imap_stream);
        imap->imap_stream = nullptr;
    }
    mailimap_free(imap);
    if (output != nullptr) mmap_string_free(output);
    return 0;
}
