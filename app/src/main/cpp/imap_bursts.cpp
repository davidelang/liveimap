#include "imap_bursts.h"

#include <climits>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <strings.h>

static PreviewDecoder previewDecoder = nullptr;

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
    struct mailimap_status_att_list * atts = mailimap_status_att_list_new_empty();
    if (atts == nullptr) return MAILIMAP_ERROR_MEMORY;
    int r = mailimap_status_att_list_add(atts, MAILIMAP_STATUS_ATT_MESSAGES);
    if (r != MAILIMAP_NO_ERROR) {
        mailimap_status_att_list_free(atts);
        return r;
    }
    for (const std::string & mb : mailboxes) {
        struct mailimap_mailbox_data_status * status = nullptr;
        r = mailimap_status(imap, mb.c_str(), atts, &status);
        if (r == MAILIMAP_NO_ERROR) putStatusMessages(status, out);
        if (status != nullptr) mailimap_mailbox_data_status_free(status);
        int checked = pipelineResult(imap, r);
        if (checked == MAILIMAP_ERROR_STREAM) {
            mailimap_status_att_list_free(atts);
            return MAILIMAP_ERROR_STREAM;
        }
        // A tagged NO or BAD omits that mailbox. Anything else stops the burst.
        if (checked != MAILIMAP_NO_ERROR && checked != MAILIMAP_ERROR_STATUS) {
            mailimap_status_att_list_free(atts);
            return checked;
        }
    }
    mailimap_status_att_list_free(atts);
    return MAILIMAP_NO_ERROR;
}

int previewBurst(mailimap * imap, const std::vector<PreviewAsk> & asks, int byteLimit,
    std::vector<std::string> & previews, bool pipeline) {
    previews.assign(asks.size(), std::string());
    if (asks.empty() || byteLimit <= 0) return MAILIMAP_NO_ERROR;
    if (imap == nullptr || imap->imap_stream == nullptr) return MAILIMAP_ERROR_STREAM;
    if (pipeline) return previewViaLibrary(imap, asks, byteLimit, previews);
    for (size_t i = 0; i < asks.size(); ++i) {
        struct mailimap_uid_fetch_request * req = previewRequest(asks[i], byteLimit);
        if (req == nullptr) return MAILIMAP_ERROR_MEMORY;
        clist * result = nullptr;
        int r = mailimap_uid_fetch(imap, req->ufr_set, req->ufr_fetch_type, &result);
        freeUidFetchRequest(req);
        int checked = pipelineResult(imap, r);
        if (checked == MAILIMAP_ERROR_STREAM) {
            if (r == MAILIMAP_NO_ERROR && result != nullptr) mailimap_fetch_list_free(result);
            return MAILIMAP_ERROR_STREAM;
        }
        if (r == MAILIMAP_NO_ERROR && result != nullptr) {
            for (clistiter * cur = clist_begin(result); cur != nullptr; cur = clist_next(cur)) {
                auto * att = static_cast<struct mailimap_msg_att *>(clist_content(cur));
                if (uidOfAtt(att) != asks[i].uid) continue;
                previews[i] = textOfAtt(att, asks[i].charset, asks[i].encoding, asks[i].html);
            }
            mailimap_fetch_list_free(result);
        }
        // A tagged NO leaves that preview empty. Anything else stops the burst.
        if (checked != MAILIMAP_NO_ERROR && checked != MAILIMAP_ERROR_UID_FETCH) return checked;
    }
    return MAILIMAP_NO_ERROR;
}
