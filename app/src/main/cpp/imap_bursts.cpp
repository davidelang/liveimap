#include "imap_bursts.h"

#include <climits>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <strings.h>

extern "C" {
int mailimap_crlf_send(mailstream * fd);
int mailimap_uid_fetch_send(mailstream * fd, struct mailimap_set * set,
    struct mailimap_fetch_type * fetch_type);
int mailimap_status_send(mailstream * fd, const char * mb,
    struct mailimap_status_att_list * status_att_list);
}

namespace {

// mailimap_send_current_tag increments before it writes. The high tag is put back
// on every exit. An earlier tag is installed only around that reply's read and parse.
struct TagGuard {
    mailimap * imap;
    int highest;
    bool issued = false;

    explicit TagGuard(mailimap * session)
        : imap(session), highest(session != nullptr ? session->imap_tag : 0) {}

    void note() {
        if (imap == nullptr) return;
        issued = true;
        if (imap->imap_tag > highest) highest = imap->imap_tag;
    }

    void forReply(int tag) {
        if (imap != nullptr) imap->imap_tag = tag;
    }

    void restore() {
        if (imap != nullptr) imap->imap_tag = highest;
    }

    ~TagGuard() { restore(); }
};

int streamError() {
    return MAILIMAP_ERROR_STREAM;
}

void takeMessages(mailimap * imap, std::map<std::string, int> * out) {
    if (imap->imap_response_info == nullptr) return;
    struct mailimap_mailbox_data_status * status = imap->imap_response_info->rsp_status;
    imap->imap_response_info->rsp_status = nullptr;
    if (status == nullptr) return;
    if (status->st_mailbox != nullptr && status->st_info_list != nullptr) {
        for (clistiter * cur = clist_begin(status->st_info_list); cur != nullptr; cur = clist_next(cur)) {
            auto * info = static_cast<struct mailimap_status_info *>(clist_content(cur));
            if (info == nullptr || info->st_att != MAILIMAP_STATUS_ATT_MESSAGES) continue;
            if (info->st_value > static_cast<uint32_t>(INT_MAX)) continue;
            (*out)[status->st_mailbox] = static_cast<int>(info->st_value);
        }
    }
    mailimap_mailbox_data_status_free(status);
}

int readStatusReply(mailimap * imap, TagGuard & guard, int tag, std::map<std::string, int> * out) {
    dropStatus(imap);
    guard.forReply(tag);
    if (mailimap_read_line(imap) == nullptr) return streamError();
    struct mailimap_response * response = nullptr;
    int r = mailimap_parse_response(imap, &response);
    guard.restore();
    if (r != MAILIMAP_NO_ERROR) {
        dropStatus(imap);
        return streamError();
    }
    if (taggedOk(response)) takeMessages(imap, out);
    else dropStatus(imap);
    mailimap_response_free(response);
    return MAILIMAP_NO_ERROR;
}

int readPreviewReply(mailimap * imap, TagGuard & guard, int tag, size_t index,
    const std::vector<PreviewAsk> & asks, std::vector<std::string> & previews) {
    dropFetchList(imap);
    guard.forReply(tag);
    if (mailimap_read_line(imap) == nullptr) return streamError();
    struct mailimap_response * response = nullptr;
    int r = mailimap_parse_response(imap, &response);
    guard.restore();
    if (r != MAILIMAP_NO_ERROR) {
        dropFetchList(imap);
        return streamError();
    }
    if (taggedOk(response)) {
        clist * fetched = imap->imap_response_info != nullptr
            ? imap->imap_response_info->rsp_fetch_list : nullptr;
        previews[index] = previewSectionText(
            fetched, asks[index].charset, asks[index].encoding, asks[index].html);
    }
    dropFetchList(imap);
    mailimap_response_free(response);
    return MAILIMAP_NO_ERROR;
}

PreviewDecoder previewDecoder = nullptr;

}  // namespace

void setPreviewDecoder(PreviewDecoder decoder) {
    previewDecoder = decoder;
}

struct mailimap_section * sectionFromSpec(const std::string & spec) {
    clist * ids = clist_new();
    if (ids == nullptr) {
        return nullptr;
    }
    size_t i = 0;
    while (i < spec.size()) {
        if (spec[i] == '.') {
            ++i;
            continue;
        }
        uint32_t value = 0;
        bool any = false;
        while (i < spec.size() && spec[i] >= '0' && spec[i] <= '9') {
            any = true;
            value = value * 10u + static_cast<uint32_t>(spec[i] - '0');
            ++i;
        }
        if (!any) {
            break;
        }
        auto * n = static_cast<uint32_t *>(malloc(sizeof(uint32_t)));
        if (n == nullptr) {
            clist_foreach(ids, reinterpret_cast<clist_func>(mailimap_number_alloc_free), nullptr);
            clist_free(ids);
            return nullptr;
        }
        *n = value;
        clist_append(ids, n);
    }
    struct mailimap_section_part * part = mailimap_section_part_new(ids);
    if (part == nullptr) {
        clist_foreach(ids, reinterpret_cast<clist_func>(mailimap_number_alloc_free), nullptr);
        clist_free(ids);
        return nullptr;
    }
    struct mailimap_section_spec * sec = mailimap_section_spec_new(
        MAILIMAP_SECTION_SPEC_SECTION_PART, nullptr, part, nullptr);
    if (sec == nullptr) {
        mailimap_section_part_free(part);
        return nullptr;
    }
    struct mailimap_section * section = mailimap_section_new(sec);
    if (section == nullptr) {
        mailimap_section_spec_free(sec);
    }
    return section;
}

std::string previewSectionText(clist * list, const std::string & charsetName, const std::string & encoding, bool html) {
    std::string text;
    if (list == nullptr || previewDecoder == nullptr) {
        return text;
    }
    for (clistiter * cur = clist_begin(list); cur != nullptr && text.empty(); cur = clist_next(cur)) {
        auto * msg = static_cast<struct mailimap_msg_att *>(clist_content(cur));
        if (msg == nullptr || msg->att_list == nullptr) continue;
        for (clistiter * ic = clist_begin(msg->att_list); ic != nullptr && text.empty(); ic = clist_next(ic)) {
            auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(ic));
            if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC || item->att_data.att_static == nullptr) {
                continue;
            }
            struct mailimap_msg_att_static * st = item->att_data.att_static;
            if (st->att_type != MAILIMAP_MSG_ATT_BODY_SECTION || st->att_data.att_body_section == nullptr) {
                continue;
            }
            struct mailimap_msg_att_body_section * body = st->att_data.att_body_section;
            if (body->sec_body_part == nullptr || body->sec_length == 0) continue;
            text = previewDecoder(body->sec_body_part, body->sec_length, charsetName, encoding, html);
        }
    }
    return text;
}

bool taggedOk(struct mailimap_response * response) {
    if (response == nullptr || response->rsp_resp_done == nullptr) return false;
    if (response->rsp_resp_done->rsp_type != MAILIMAP_RESP_DONE_TYPE_TAGGED) return false;
    struct mailimap_response_tagged * tagged = response->rsp_resp_done->rsp_data.rsp_tagged;
    if (tagged == nullptr || tagged->rsp_cond_state == nullptr) return false;
    return tagged->rsp_cond_state->rsp_type == MAILIMAP_RESP_COND_STATE_OK;
}

void dropStatus(mailimap * imap) {
    if (imap->imap_response_info == nullptr || imap->imap_response_info->rsp_status == nullptr) return;
    mailimap_mailbox_data_status_free(imap->imap_response_info->rsp_status);
    imap->imap_response_info->rsp_status = nullptr;
}

void dropFetchList(mailimap * imap) {
    if (imap == nullptr || imap->imap_response_info == nullptr) return;
    clist * fetch = imap->imap_response_info->rsp_fetch_list;
    if (fetch == nullptr) return;
    mailimap_fetch_list_free(fetch);
    imap->imap_response_info->rsp_fetch_list = nullptr;
}

int pipelineResult(mailimap * imap, int r) {
    if (r == MAILIMAP_ERROR_STREAM || r == MAILIMAP_ERROR_FATAL) return MAILIMAP_ERROR_STREAM;
    if (imap != nullptr && mailimap_is_desynchronized(imap) != 0) return MAILIMAP_ERROR_STREAM;
    return r;
}

bool statusRejected(const char * name, const std::vector<std::string> & mailboxes,
    const std::vector<int> & cmd) {
    if (name == nullptr) return false;
    for (size_t i = 0; i < mailboxes.size() && i < cmd.size(); ++i) {
        if (cmd[i] == MAILIMAP_NO_ERROR) continue;
        if (mailboxes[i] == name) return true;
        if (strcasecmp(mailboxes[i].c_str(), "INBOX") == 0 && strcasecmp(name, "INBOX") == 0) return true;
    }
    return false;
}

void putStatusMessages(struct mailimap_mailbox_data_status * status, std::map<std::string, int> * out) {
    if (status == nullptr || status->st_mailbox == nullptr || status->st_info_list == nullptr) return;
    for (clistiter * cur = clist_begin(status->st_info_list); cur != nullptr; cur = clist_next(cur)) {
        auto * info = static_cast<struct mailimap_status_info *>(clist_content(cur));
        if (info == nullptr || info->st_att != MAILIMAP_STATUS_ATT_MESSAGES) continue;
        if (info->st_value > static_cast<uint32_t>(INT_MAX)) continue;
        (*out)[status->st_mailbox] = static_cast<int>(info->st_value);
    }
}

void freeUidFetchRequest(struct mailimap_uid_fetch_request * req) {
    if (req == nullptr) return;
    mailimap_set_free(req->ufr_set);
    mailimap_fetch_type_free(req->ufr_fetch_type);
    req->ufr_set = nullptr;
    req->ufr_fetch_type = nullptr;
    mailimap_uid_fetch_request_free(req);
}

void freeUidFetchRequests(clist * requests) {
    if (requests == nullptr) return;
    for (clistiter * cur = clist_begin(requests); cur != nullptr; cur = clist_next(cur)) {
        freeUidFetchRequest(static_cast<struct mailimap_uid_fetch_request *>(clist_content(cur)));
    }
    clist_free(requests);
}

uint32_t uidOfAtt(struct mailimap_msg_att * msg) {
    if (msg == nullptr || msg->att_list == nullptr) return 0;
    for (clistiter * cur = clist_begin(msg->att_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(cur));
        if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC) continue;
        struct mailimap_msg_att_static * st = item->att_data.att_static;
        if (st == nullptr || st->att_type != MAILIMAP_MSG_ATT_UID) continue;
        return st->att_data.att_uid;
    }
    return 0;
}

std::string textOfAtt(struct mailimap_msg_att * att, const std::string & charset, const std::string & encoding, bool html) {
    clist * one = clist_new();
    if (one == nullptr || clist_append(one, att) != 0) {
        if (one != nullptr) clist_free(one);
        return std::string();
    }
    std::string text = previewSectionText(one, charset, encoding, html);
    clist_free(one);
    return text;
}

struct mailimap_uid_fetch_request * previewRequest(const PreviewAsk & ask, int byteLimit) {
    struct mailimap_section * section = sectionFromSpec(ask.section);
    if (section == nullptr) return nullptr;
    struct mailimap_fetch_att * body = mailimap_fetch_att_new_body_peek_section_partial(
        section, 0, static_cast<uint32_t>(byteLimit));
    if (body == nullptr) {
        mailimap_section_free(section);
        return nullptr;
    }
    struct mailimap_fetch_att * uid = mailimap_fetch_att_new_uid();
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    if (uid == nullptr || fetch == nullptr) {
        if (fetch != nullptr) mailimap_fetch_type_free(fetch);
        mailimap_fetch_att_free(body);
        if (uid != nullptr) mailimap_fetch_att_free(uid);
        return nullptr;
    }
    if (mailimap_fetch_type_new_fetch_att_list_add(fetch, body) != MAILIMAP_NO_ERROR) {
        mailimap_fetch_type_free(fetch);
        mailimap_fetch_att_free(body);
        mailimap_fetch_att_free(uid);
        return nullptr;
    }
    if (mailimap_fetch_type_new_fetch_att_list_add(fetch, uid) != MAILIMAP_NO_ERROR) {
        mailimap_fetch_type_free(fetch);
        mailimap_fetch_att_free(uid);
        return nullptr;
    }
    struct mailimap_set * set = mailimap_set_new_single(ask.uid);
    if (set == nullptr) {
        mailimap_fetch_type_free(fetch);
        return nullptr;
    }
    struct mailimap_uid_fetch_request * req = mailimap_uid_fetch_request_new(set, fetch);
    if (req == nullptr) {
        mailimap_set_free(set);
        mailimap_fetch_type_free(fetch);
        return nullptr;
    }
    return req;
}

int statusViaLibrary(mailimap * imap, const std::vector<std::string> & mailboxes,
    std::map<std::string, int> * out) {
    clist * names = clist_new();
    if (names == nullptr) return MAILIMAP_ERROR_MEMORY;
    for (const std::string & mb : mailboxes) {
        if (clist_append(names, const_cast<char *>(mb.c_str())) != 0) {
            clist_free(names);
            return MAILIMAP_ERROR_MEMORY;
        }
    }
    struct mailimap_status_att_list * atts = mailimap_status_att_list_new_empty();
    if (atts == nullptr) {
        clist_free(names);
        return MAILIMAP_ERROR_MEMORY;
    }
    int r = mailimap_status_att_list_add(atts, MAILIMAP_STATUS_ATT_MESSAGES);
    if (r != MAILIMAP_NO_ERROR) {
        mailimap_status_att_list_free(atts);
        clist_free(names);
        return r;
    }
    std::vector<int> cmd(mailboxes.size(), MAILIMAP_ERROR_STATUS);
    clist * result = nullptr;
    r = mailimap_status_multiple(imap, names, atts, &result, cmd.data());
    clist_free(names);
    mailimap_status_att_list_free(atts);
    if (pipelineResult(imap, r) == MAILIMAP_ERROR_STREAM) {
        if (r == MAILIMAP_NO_ERROR && result != nullptr) mailimap_status_list_free(result);
        return MAILIMAP_ERROR_STREAM;
    }
    if (r != MAILIMAP_NO_ERROR) return r;
    if (result != nullptr) {
        for (clistiter * cur = clist_begin(result); cur != nullptr; cur = clist_next(cur)) {
            auto * status = static_cast<struct mailimap_mailbox_data_status *>(clist_content(cur));
            if (status == nullptr || status->st_mailbox == nullptr) continue;
            if (statusRejected(status->st_mailbox, mailboxes, cmd)) continue;
            putStatusMessages(status, out);
        }
        mailimap_status_list_free(result);
    }
    return MAILIMAP_NO_ERROR;
}

int previewViaLibrary(mailimap * imap, const std::vector<PreviewAsk> & asks, int byteLimit,
    std::vector<std::string> & previews) {
    clist * requests = clist_new();
    if (requests == nullptr) return MAILIMAP_ERROR_MEMORY;
    for (const PreviewAsk & ask : asks) {
        struct mailimap_uid_fetch_request * req = previewRequest(ask, byteLimit);
        if (req == nullptr) {
            freeUidFetchRequests(requests);
            return MAILIMAP_ERROR_MEMORY;
        }
        if (clist_append(requests, req) != 0) {
            freeUidFetchRequest(req);
            freeUidFetchRequests(requests);
            return MAILIMAP_ERROR_MEMORY;
        }
    }
    std::vector<int> cmd(asks.size(), MAILIMAP_ERROR_UID_FETCH);
    clist * result = nullptr;
    int r = mailimap_uid_fetch_multiple(imap, requests, &result, cmd.data());
    freeUidFetchRequests(requests);
    if (pipelineResult(imap, r) == MAILIMAP_ERROR_STREAM) {
        if (r == MAILIMAP_NO_ERROR && result != nullptr) mailimap_fetch_list_free(result);
        return MAILIMAP_ERROR_STREAM;
    }
    if (r != MAILIMAP_NO_ERROR) return r;
    if (result != nullptr) {
        for (clistiter * cur = clist_begin(result); cur != nullptr; cur = clist_next(cur)) {
            auto * att = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            uint32_t uid = uidOfAtt(att);
            if (uid == 0) continue;
            for (size_t i = 0; i < asks.size(); ++i) {
                if (asks[i].uid != uid || cmd[i] != MAILIMAP_NO_ERROR) continue;
                previews[i] = textOfAtt(att, asks[i].charset, asks[i].encoding, asks[i].html);
            }
        }
        mailimap_fetch_list_free(result);
    }
    return MAILIMAP_NO_ERROR;
}

int statusMessagesPipeline(mailimap * imap, const std::vector<std::string> & mailboxes,
    std::map<std::string, int> * out, bool pipeline) {
    out->clear();
    if (mailboxes.empty()) return MAILIMAP_NO_ERROR;
    if (imap == nullptr || imap->imap_stream == nullptr) return MAILIMAP_ERROR_STREAM;
    if (pipeline) return statusViaLibrary(imap, mailboxes, out);
    TagGuard guard(imap);
    struct mailimap_status_att_list * atts = mailimap_status_att_list_new_empty();
    if (atts == nullptr) return MAILIMAP_ERROR_MEMORY;
    int r = mailimap_status_att_list_add(atts, MAILIMAP_STATUS_ATT_MESSAGES);
    if (r != MAILIMAP_NO_ERROR) {
        mailimap_status_att_list_free(atts);
        return r;
    }
    std::vector<int> tags;
    tags.reserve(mailboxes.size());
    for (size_t i = 0; i < mailboxes.size(); ++i) {
        r = mailimap_send_current_tag(imap);
        guard.note();
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_status_att_list_free(atts);
            return streamError();
        }
        r = mailimap_status_send(imap->imap_stream, mailboxes[i].c_str(), atts);
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_status_att_list_free(atts);
            return streamError();
        }
        r = mailimap_crlf_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_status_att_list_free(atts);
            return streamError();
        }
        int tag = imap->imap_tag;
        tags.push_back(tag);
        if (!pipeline) {
            if (mailstream_flush(imap->imap_stream) == -1) {
                mailimap_status_att_list_free(atts);
                return streamError();
            }
            int rr = readStatusReply(imap, guard, tag, out);
            if (rr != MAILIMAP_NO_ERROR) {
                mailimap_status_att_list_free(atts);
                return rr;
            }
        }
    }
    mailimap_status_att_list_free(atts);
    if (!pipeline) return MAILIMAP_NO_ERROR;
    if (mailstream_flush(imap->imap_stream) == -1) return streamError();
    for (int tag : tags) {
        int rr = readStatusReply(imap, guard, tag, out);
        if (rr != MAILIMAP_NO_ERROR) return rr;
    }
    return MAILIMAP_NO_ERROR;
}

int previewBurst(mailimap * imap, const std::vector<PreviewAsk> & asks, int byteLimit,
    std::vector<std::string> & previews, bool pipeline) {
    previews.assign(asks.size(), std::string());
    if (asks.empty() || byteLimit <= 0) return MAILIMAP_NO_ERROR;
    if (imap == nullptr || imap->imap_stream == nullptr) return MAILIMAP_ERROR_STREAM;
    if (pipeline) return previewViaLibrary(imap, asks, byteLimit, previews);
    TagGuard guard(imap);
    struct Sent {
        int tag;
        size_t index;
    };
    std::vector<Sent> sent;
    sent.reserve(asks.size());
    bool allocFailed = false;
    for (size_t i = 0; i < asks.size(); ++i) {
        struct mailimap_section * section = sectionFromSpec(asks[i].section);
        if (section == nullptr) {
            if (!guard.issued) return MAILIMAP_ERROR_MEMORY;
            allocFailed = true;
            continue;
        }
        struct mailimap_fetch_att * att = mailimap_fetch_att_new_body_peek_section_partial(
            section, 0, static_cast<uint32_t>(byteLimit));
        struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
        if (att == nullptr || fetch == nullptr) {
            if (fetch != nullptr) mailimap_fetch_type_free(fetch);
            if (att == nullptr) mailimap_section_free(section);
            else mailimap_fetch_att_free(att);
            if (!guard.issued) return MAILIMAP_ERROR_MEMORY;
            allocFailed = true;
            continue;
        }
        mailimap_fetch_type_new_fetch_att_list_add(fetch, att);
        struct mailimap_set * set = mailimap_set_new_single(asks[i].uid);
        if (set == nullptr) {
            mailimap_fetch_type_free(fetch);
            if (!guard.issued) return MAILIMAP_ERROR_MEMORY;
            allocFailed = true;
            continue;
        }
        int r = mailimap_send_current_tag(imap);
        guard.note();
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_set_free(set);
            mailimap_fetch_type_free(fetch);
            return streamError();
        }
        r = mailimap_uid_fetch_send(imap->imap_stream, set, fetch);
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_set_free(set);
            mailimap_fetch_type_free(fetch);
            return streamError();
        }
        r = mailimap_crlf_send(imap->imap_stream);
        mailimap_set_free(set);
        mailimap_fetch_type_free(fetch);
        if (r != MAILIMAP_NO_ERROR) return streamError();
        int tag = imap->imap_tag;
        if (!pipeline) {
            if (mailstream_flush(imap->imap_stream) == -1) return streamError();
            int rr = readPreviewReply(imap, guard, tag, i, asks, previews);
            if (rr != MAILIMAP_NO_ERROR) return rr;
            continue;
        }
        sent.push_back(Sent{tag, i});
    }
    if (pipeline && !sent.empty()) {
        if (mailstream_flush(imap->imap_stream) == -1) return streamError();
        for (const Sent & item : sent) {
            int rr = readPreviewReply(imap, guard, item.tag, item.index, asks, previews);
            if (rr != MAILIMAP_NO_ERROR) return rr;
        }
    }
    // A later send already returned STREAM. An allocation that never issued a tag
    // still fails the burst after any earlier replies have been read.
    if (allocFailed) return MAILIMAP_ERROR_MEMORY;
    return MAILIMAP_NO_ERROR;
}
