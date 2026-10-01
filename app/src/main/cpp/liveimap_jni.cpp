#include <libetpan/libetpan.h>
#include <libetpan/unselect.h>

#include <jni.h>

#include <poll.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <strings.h>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

extern "C" {
int mailimap_space_parse(mailstream * fd, MMAPString * buffer, size_t * indx);
int mailimap_token_case_insensitive_parse(mailstream * fd, MMAPString * buffer,
    size_t * indx, const char * token);
int mailimap_oparenth_parse(mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx);
int mailimap_cparenth_parse(mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx);
int mailimap_nz_number_parse(mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx, uint32_t * result);
int mailimap_response_data_parse(mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx,
    struct mailimap_response_data ** result, size_t progr_rate,
    progress_function * progr_fun);
int mailimap_crlf_send(mailstream * fd);
int mailimap_space_send(mailstream * fd);
int mailimap_token_send(mailstream * fd, const char * atom);
int mailimap_mailbox_send(mailstream * fd, const char * mb);
int mailimap_astring_send(mailstream * fd, const char * astring);
}

namespace {

struct LiveThread {
    int has_uid;
    uint32_t uid;
    clist * children;
};

struct LiveSession {
    std::mutex mu;
    mailimap * imap = nullptr;
    std::string host;
    int imapPort = 143;
    std::string user;
    std::string password;
    std::string smtpHost;
    int smtpPort = 25;
    std::string from;
    std::string capabilityLine;
    std::map<std::string, char> delims;

    mailimap * watch = nullptr;
    std::thread watchThread;
    std::atomic<bool> watchStop{false};
    std::mutex startMu;
    std::condition_variable startCv;
    int startState = 0;
    std::string startError;
    jobject selfGlobal = nullptr;
    uint32_t watchUidValidity = 0;
    int watchExists = 0;
};

JavaVM * gVm = nullptr;
std::mutex gLiveMu;
std::unordered_set<LiveSession *> gLive;
std::mutex gErrMu;
std::string gLastError;
int (* gKeepUnselect)(mailimap *) = mailimap_unselect;

struct JniCache {
    bool ready = false;
    jclass mailFailure = nullptr;
    jclass indexRow = nullptr;
    jclass folderEntry = nullptr;
    jclass ns = nullptr;
    jclass nsKind = nullptr;
    jclass selectResult = nullptr;
    jclass mimePart = nullptr;
    jclass threadNode = nullptr;
    jclass arrayList = nullptr;
    jclass hashSet = nullptr;
    jclass longCls = nullptr;
    jmethodID indexRowInit = nullptr;
    jmethodID folderInit = nullptr;
    jmethodID nsInit = nullptr;
    jmethodID selectInit = nullptr;
    jmethodID mimeInit = nullptr;
    jmethodID threadInit = nullptr;
    jmethodID listInit = nullptr;
    jmethodID listAdd = nullptr;
    jmethodID setInit = nullptr;
    jmethodID setAdd = nullptr;
    jmethodID longValueOf = nullptr;
    jmethodID onWatch = nullptr;
    jfieldID personal = nullptr;
    jfieldID other = nullptr;
    jfieldID shared = nullptr;
};

JniCache gJni;
std::once_flag gExtOnce;
thread_local LiveSession * tlsWatch = nullptr;

static int thread_ext_parse(int calling_parser, mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx,
    struct mailimap_extension_data ** result, size_t progr_rate, progress_function * progr_fun);
static void thread_ext_free(struct mailimap_extension_data * ext_data);

mailimap_extension_api liveimap_thread_extension = {
    const_cast<char *>("THREAD"),
    -1,
    thread_ext_parse,
    thread_ext_free,
};

void registerExtensions() {
    std::call_once(gExtOnce, [] {
        mailimap_extension_register(&mailimap_extension_sort);
        mailimap_extension_register(&mailimap_extension_namespace);
        mailimap_extension_register(&mailimap_extension_uidplus);
        mailimap_extension_register(&liveimap_thread_extension);
        (void)gKeepUnselect;
    });
}

std::string asciiSafe(const char * text, const char * fallback) {
    std::string out;
    if (text != nullptr) {
        for (const unsigned char * p = reinterpret_cast<const unsigned char *>(text); *p != 0; ++p) {
            unsigned char c = *p;
            if (c >= 32 && c < 127) {
                out.push_back(static_cast<char>(c));
            } else if (c == '\n' || c == '\r' || c == '\t') {
                out.push_back(' ');
            } else {
                out.push_back('?');
            }
        }
    }
    if (out.empty()) {
        return fallback != nullptr ? fallback : "imap error";
    }
    return out;
}

void setLastError(const std::string & text) {
    std::lock_guard<std::mutex> lock(gErrMu);
    gLastError = text;
}

std::string takeLastError() {
    std::lock_guard<std::mutex> lock(gErrMu);
    std::string out = gLastError.empty() ? "imap error" : gLastError;
    gLastError.clear();
    return out;
}

std::string imapText(mailimap * session, const char * fallback) {
    if (session != nullptr && session->imap_response != nullptr && session->imap_response[0] != 0) {
        return asciiSafe(session->imap_response, fallback);
    }
    return fallback != nullptr ? fallback : "imap error";
}

void throwFailure(JNIEnv * env, const std::string & text) {
    if (env->ExceptionCheck()) {
        return;
    }
    env->ThrowNew(gJni.mailFailure, text.c_str());
}

void throwImap(JNIEnv * env, mailimap * session, const char * fallback) {
    throwFailure(env, imapText(session, fallback));
}

bool ensureJni(JNIEnv * env) {
    if (gJni.ready) {
        return true;
    }
    jclass local;
    local = env->FindClass("org/dlang/liveimap/session/MailFailure");
    gJni.mailFailure = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("org/dlang/liveimap/session/IndexRow");
    gJni.indexRow = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("org/dlang/liveimap/session/FolderEntry");
    gJni.folderEntry = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("org/dlang/liveimap/session/Namespace");
    gJni.ns = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("org/dlang/liveimap/session/NamespaceKind");
    gJni.nsKind = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("org/dlang/liveimap/session/SelectResult");
    gJni.selectResult = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("org/dlang/liveimap/session/MimePart");
    gJni.mimePart = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("org/dlang/liveimap/session/ThreadNode");
    gJni.threadNode = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("java/util/ArrayList");
    gJni.arrayList = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("java/util/HashSet");
    gJni.hashSet = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    local = env->FindClass("java/lang/Long");
    gJni.longCls = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    gJni.indexRowInit = env->GetMethodID(gJni.indexRow, "<init>",
        "(JILjava/util/Set;JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    gJni.folderInit = env->GetMethodID(gJni.folderEntry, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;ZC)V");
    gJni.nsInit = env->GetMethodID(gJni.ns, "<init>",
        "(Ljava/lang/String;CLorg/dlang/liveimap/session/NamespaceKind;)V");
    gJni.selectInit = env->GetMethodID(gJni.selectResult, "<init>", "(JJI)V");
    gJni.mimeInit = env->GetMethodID(gJni.mimePart, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/util/List;)V");
    gJni.threadInit = env->GetMethodID(gJni.threadNode, "<init>",
        "(Ljava/lang/Long;Ljava/util/List;)V");
    gJni.listInit = env->GetMethodID(gJni.arrayList, "<init>", "()V");
    gJni.listAdd = env->GetMethodID(gJni.arrayList, "add", "(Ljava/lang/Object;)Z");
    gJni.setInit = env->GetMethodID(gJni.hashSet, "<init>", "()V");
    gJni.setAdd = env->GetMethodID(gJni.hashSet, "add", "(Ljava/lang/Object;)Z");
    gJni.longValueOf = env->GetStaticMethodID(gJni.longCls, "valueOf", "(J)Ljava/lang/Long;");
    gJni.personal = env->GetStaticFieldID(gJni.nsKind, "Personal",
        "Lorg/dlang/liveimap/session/NamespaceKind;");
    gJni.other = env->GetStaticFieldID(gJni.nsKind, "Other",
        "Lorg/dlang/liveimap/session/NamespaceKind;");
    gJni.shared = env->GetStaticFieldID(gJni.nsKind, "Shared",
        "Lorg/dlang/liveimap/session/NamespaceKind;");
    jclass sessionCls = env->FindClass("org/dlang/liveimap/engine/LibetpanMailSession");
    gJni.onWatch = env->GetMethodID(sessionCls, "onNativeWatch", "(IIJ[Ljava/lang/String;)V");
    env->DeleteLocalRef(sessionCls);
    gJni.ready = gJni.mailFailure != nullptr && gJni.indexRowInit != nullptr && gJni.onWatch != nullptr;
    return gJni.ready;
}

struct JChars {
    JNIEnv * env;
    jstring js;
    const char * p;
    JChars(JNIEnv * e, jstring s) : env(e), js(s), p(nullptr) {
        if (s != nullptr) {
            p = e->GetStringUTFChars(s, nullptr);
        }
    }
    ~JChars() {
        if (js != nullptr && p != nullptr) {
            env->ReleaseStringUTFChars(js, p);
        }
    }
    const char * c() const { return p != nullptr ? p : ""; }
};

jstring newString(JNIEnv * env, const char * text) {
    if (text == nullptr) {
        return nullptr;
    }
    std::u16string u;
    const unsigned char * p = reinterpret_cast<const unsigned char *>(text);
    while (*p != 0) {
        uint32_t cp = 0;
        int n = 1;
        if (*p < 0x80) {
            cp = *p;
        } else if ((*p & 0xE0) == 0xC0 && (p[1] & 0xC0) == 0x80 && *p >= 0xC2) {
            cp = ((*p & 0x1F) << 6) | (p[1] & 0x3F);
            n = 2;
        } else if ((*p & 0xF0) == 0xE0 && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80) {
            cp = ((*p & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
            n = 3;
        } else if ((*p & 0xF8) == 0xF0 && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80 && (p[3] & 0xC0) == 0x80) {
            cp = ((*p & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
            n = 4;
        } else {
            u.push_back(u'?');
            ++p;
            continue;
        }
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            u.push_back(u'?');
        } else if (cp < 0x10000) {
            u.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            u.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            u.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
        p += n;
    }
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size()));
}

jobject newList(JNIEnv * env) {
    return env->NewObject(gJni.arrayList, gJni.listInit);
}

void listAdd(JNIEnv * env, jobject list, jobject item) {
    env->CallBooleanMethod(list, gJni.listAdd, item);
}

bool connectOk(int r) {
    return r == MAILIMAP_NO_ERROR || r == MAILIMAP_NO_ERROR_AUTHENTICATED
        || r == MAILIMAP_NO_ERROR_NON_AUTHENTICATED;
}

bool cmdOk(int r) {
    return r == MAILIMAP_NO_ERROR;
}

LiveSession * lockSession(JNIEnv * env, jlong handle) {
    std::lock_guard<std::mutex> lock(gLiveMu);
    auto * session = reinterpret_cast<LiveSession *>(handle);
    if (gLive.find(session) == gLive.end()) {
        throwFailure(env, "not connected");
        return nullptr;
    }
    session->mu.lock();
    return session;
}

void unlockSession(LiveSession * session) {
    if (session != nullptr) {
        session->mu.unlock();
    }
}

std::string joinCapabilities(struct mailimap_capability_data * cap) {
    std::string line;
    if (cap == nullptr || cap->cap_list == nullptr) {
        return line;
    }
    for (clistiter * cur = clist_begin(cap->cap_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_capability *>(clist_content(cur));
        if (item == nullptr) {
            continue;
        }
        std::string tok;
        if (item->cap_type == MAILIMAP_CAPABILITY_AUTH_TYPE) {
            tok = "AUTH=";
            if (item->cap_data.cap_auth_type != nullptr) {
                tok += item->cap_data.cap_auth_type;
            }
        } else if (item->cap_data.cap_name != nullptr) {
            tok = item->cap_data.cap_name;
        }
        if (tok.empty()) {
            continue;
        }
        if (!line.empty()) {
            line.push_back(' ');
        }
        line += tok;
    }
    return line;
}

struct mailimap_set * setFromUids(const std::vector<uint32_t> & uids) {
    struct mailimap_set * set = mailimap_set_new_empty();
    if (set == nullptr) {
        return nullptr;
    }
    for (uint32_t uid : uids) {
        if (uid == 0) {
            continue;
        }
        if (mailimap_set_add_single(set, uid) != MAILIMAP_NO_ERROR) {
            mailimap_set_free(set);
            return nullptr;
        }
    }
    return set;
}

struct mailimap_fetch_type * indexFetchType(bool withStructure) {
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    if (fetch == nullptr) {
        return nullptr;
    }
    mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_uid());
    mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_flags());
    mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_internaldate());
    mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_rfc822_size());
    mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_envelope());
    if (withStructure) {
        mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_bodystructure());
    }
    return fetch;
}

std::string flagName(struct mailimap_flag_fetch * flag) {
    if (flag == nullptr) {
        return "";
    }
    if (flag->fl_type == MAILIMAP_FLAG_FETCH_RECENT) {
        return "\\Recent";
    }
    struct mailimap_flag * f = flag->fl_flag;
    if (f == nullptr) {
        return "";
    }
    switch (f->fl_type) {
    case MAILIMAP_FLAG_ANSWERED: return "\\Answered";
    case MAILIMAP_FLAG_FLAGGED: return "\\Flagged";
    case MAILIMAP_FLAG_DELETED: return "\\Deleted";
    case MAILIMAP_FLAG_SEEN: return "\\Seen";
    case MAILIMAP_FLAG_DRAFT: return "\\Draft";
    case MAILIMAP_FLAG_KEYWORD:
        return f->fl_data.fl_keyword != nullptr ? f->fl_data.fl_keyword : "";
    case MAILIMAP_FLAG_EXTENSION:
        return f->fl_data.fl_extension != nullptr ? f->fl_data.fl_extension : "";
    default:
        return "";
    }
}

struct mailimap_flag * flagFromName(const char * name) {
    if (name == nullptr || name[0] == 0) {
        return nullptr;
    }
    if (strcasecmp(name, "\\Answered") == 0) return mailimap_flag_new_answered();
    if (strcasecmp(name, "\\Flagged") == 0) return mailimap_flag_new_flagged();
    if (strcasecmp(name, "\\Deleted") == 0) return mailimap_flag_new_deleted();
    if (strcasecmp(name, "\\Seen") == 0) return mailimap_flag_new_seen();
    if (strcasecmp(name, "\\Draft") == 0) return mailimap_flag_new_draft();
    if (name[0] == '\\') return mailimap_flag_new_flag_extension(strdup(name));
    return mailimap_flag_new_flag_keyword(strdup(name));
}

bool flagListFromArray(JNIEnv * env, jobjectArray names, struct mailimap_flag_list ** out) {
    *out = mailimap_flag_list_new_empty();
    if (*out == nullptr) {
        return false;
    }
    if (names == nullptr) {
        return true;
    }
    jsize n = env->GetArrayLength(names);
    for (jsize i = 0; i < n; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        JChars chars(env, js);
        struct mailimap_flag * flag = flagFromName(chars.c());
        if (js != nullptr) {
            env->DeleteLocalRef(js);
        }
        if (flag == nullptr) {
            continue;
        }
        if (mailimap_flag_list_add(*out, flag) != MAILIMAP_NO_ERROR) {
            mailimap_flag_free(flag);
            mailimap_flag_list_free(*out);
            *out = nullptr;
            return false;
        }
    }
    return true;
}

int64_t internalDateEpoch(struct mailimap_date_time * dt) {
    if (dt == nullptr) {
        return 0;
    }
    std::tm tm{};
    tm.tm_year = dt->dt_year - 1900;
    tm.tm_mon = dt->dt_month - 1;
    tm.tm_mday = dt->dt_day;
    tm.tm_hour = dt->dt_hour;
    tm.tm_min = dt->dt_min;
    tm.tm_sec = dt->dt_sec;
    tm.tm_isdst = 0;
    time_t asUtc = timegm(&tm);
    int zone = dt->dt_zone;
    int sign = zone < 0 ? -1 : 1;
    int absz = zone < 0 ? -zone : zone;
    int offset = sign * ((absz / 100) * 3600 + (absz % 100) * 60);
    return static_cast<int64_t>(asUtc) - offset;
}

std::string addressText(struct mailimap_address * addr) {
    if (addr == nullptr) {
        return "";
    }
    if (addr->ad_personal_name != nullptr && addr->ad_personal_name[0] != 0) {
        return addr->ad_personal_name;
    }
    std::string box = addr->ad_mailbox_name != nullptr ? addr->ad_mailbox_name : "";
    std::string host = addr->ad_host_name != nullptr ? addr->ad_host_name : "";
    if (!box.empty() && !host.empty()) {
        return box + "@" + host;
    }
    return box;
}

const char * paramValue(struct mailimap_body_fld_param * param, const char * key) {
    if (param == nullptr || param->pa_list == nullptr || key == nullptr) {
        return nullptr;
    }
    for (clistiter * cur = clist_begin(param->pa_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_single_body_fld_param *>(clist_content(cur));
        if (item != nullptr && item->pa_name != nullptr && strcasecmp(item->pa_name, key) == 0) {
            return item->pa_value;
        }
    }
    return nullptr;
}

const char * filenameOf(struct mailimap_body_fields * fields, struct mailimap_body_fld_dsp * disp) {
    const char * name = nullptr;
    if (disp != nullptr) {
        name = paramValue(disp->dsp_attributes, "filename");
    }
    if (name == nullptr && fields != nullptr) {
        name = paramValue(fields->bd_parameter, "name");
    }
    if (name == nullptr && disp != nullptr) {
        name = paramValue(disp->dsp_attributes, "name");
    }
    return name;
}

struct PartWant {
    bool found = false;
    std::string section;
    std::string charset;
};

struct mailimap_body_fields * fieldsOf1(struct mailimap_body_type_1part * part) {
    if (part == nullptr) {
        return nullptr;
    }
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_TEXT && part->bd_data.bd_type_text != nullptr) {
        return part->bd_data.bd_type_text->bd_fields;
    }
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_BASIC && part->bd_data.bd_type_basic != nullptr) {
        return part->bd_data.bd_type_basic->bd_fields;
    }
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_MSG && part->bd_data.bd_type_msg != nullptr) {
        return part->bd_data.bd_type_msg->bd_fields;
    }
    return nullptr;
}

void describe1(struct mailimap_body_type_1part * part, std::string * type, std::string * subtype) {
    *type = "application";
    *subtype = "octet-stream";
    if (part == nullptr) {
        return;
    }
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_TEXT && part->bd_data.bd_type_text != nullptr) {
        *type = "text";
        if (part->bd_data.bd_type_text->bd_media_text != nullptr) {
            *subtype = part->bd_data.bd_type_text->bd_media_text;
        }
        return;
    }
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_MSG) {
        *type = "message";
        *subtype = "rfc822";
        return;
    }
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_BASIC && part->bd_data.bd_type_basic != nullptr
        && part->bd_data.bd_type_basic->bd_media_basic != nullptr) {
        struct mailimap_media_basic * media = part->bd_data.bd_type_basic->bd_media_basic;
        switch (media->med_type) {
        case MAILIMAP_MEDIA_BASIC_APPLICATION: *type = "application"; break;
        case MAILIMAP_MEDIA_BASIC_AUDIO: *type = "audio"; break;
        case MAILIMAP_MEDIA_BASIC_IMAGE: *type = "image"; break;
        case MAILIMAP_MEDIA_BASIC_MESSAGE: *type = "message"; break;
        case MAILIMAP_MEDIA_BASIC_VIDEO: *type = "video"; break;
        default:
            *type = media->med_basic_type != nullptr ? media->med_basic_type : "application";
            break;
        }
        if (media->med_subtype != nullptr) {
            *subtype = media->med_subtype;
        }
    }
}

void findPreferred(struct mailimap_body * body, const std::string & prefix, bool preferHtml, PartWant * want) {
    if (body == nullptr || want->found) {
        return;
    }
    if (body->bd_type == MAILIMAP_BODY_MPART && body->bd_data.bd_body_mpart != nullptr) {
        struct mailimap_body_type_mpart * mpart = body->bd_data.bd_body_mpart;
        int index = 1;
        if (mpart->bd_list == nullptr) {
            return;
        }
        for (clistiter * cur = clist_begin(mpart->bd_list); cur != nullptr; cur = clist_next(cur), ++index) {
            std::string section = prefix.empty() ? std::to_string(index) : prefix + "." + std::to_string(index);
            findPreferred(static_cast<struct mailimap_body *>(clist_content(cur)), section, preferHtml, want);
            if (want->found) {
                return;
            }
        }
        return;
    }
    if (body->bd_type != MAILIMAP_BODY_1PART || body->bd_data.bd_body_1part == nullptr) {
        return;
    }
    struct mailimap_body_type_1part * part = body->bd_data.bd_body_1part;
    std::string section = prefix.empty() ? "1" : prefix;
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_MSG && part->bd_data.bd_type_msg != nullptr) {
        struct mailimap_body * inner = part->bd_data.bd_type_msg->bd_body;
        if (inner != nullptr && inner->bd_type == MAILIMAP_BODY_MPART) {
            findPreferred(inner, section, preferHtml, want);
        } else {
            findPreferred(inner, section + ".1", preferHtml, want);
        }
        return;
    }
    std::string type;
    std::string subtype;
    describe1(part, &type, &subtype);
    bool match = preferHtml ? (strcasecmp(type.c_str(), "text") == 0 && strcasecmp(subtype.c_str(), "html") == 0)
                            : (strcasecmp(type.c_str(), "text") == 0 && strcasecmp(subtype.c_str(), "plain") == 0);
    if (!match) {
        return;
    }
    want->found = true;
    want->section = section;
    struct mailimap_body_fields * fields = fieldsOf1(part);
    const char * charset = fields != nullptr ? paramValue(fields->bd_parameter, "charset") : nullptr;
    want->charset = charset != nullptr ? charset : "UTF-8";
}

jobject mimeFromBody(JNIEnv * env, struct mailimap_body * body, const std::string & section);

jobject mimeFrom1(JNIEnv * env, struct mailimap_body_type_1part * part, const std::string & section) {
    std::string type;
    std::string subtype;
    describe1(part, &type, &subtype);
    struct mailimap_body_fields * fields = fieldsOf1(part);
    struct mailimap_body_fld_dsp * disp = part != nullptr && part->bd_ext_1part != nullptr
        ? part->bd_ext_1part->bd_disposition : nullptr;
    const char * dispType = disp != nullptr ? disp->dsp_type : "";
    const char * filename = filenameOf(fields, disp);
    int size = fields != nullptr ? static_cast<int>(fields->bd_size) : 0;
    jobject children = newList(env);
    if (part != nullptr && part->bd_type == MAILIMAP_BODY_TYPE_1PART_MSG && part->bd_data.bd_type_msg != nullptr
        && part->bd_data.bd_type_msg->bd_body != nullptr) {
        struct mailimap_body * inner = part->bd_data.bd_type_msg->bd_body;
        jobject child = mimeFromBody(env, inner, section + ".1");
        if (child != nullptr) {
            listAdd(env, children, child);
            env->DeleteLocalRef(child);
        }
    }
    jstring jSection = newString(env, section.c_str());
    jstring jType = newString(env, type.c_str());
    jstring jSubtype = newString(env, subtype.c_str());
    jstring jDisp = newString(env, dispType != nullptr ? dispType : "");
    jstring jFile = filename != nullptr ? newString(env, filename) : nullptr;
    jobject obj = env->NewObject(gJni.mimePart, gJni.mimeInit, jSection, jType, jSubtype, jDisp, jFile, size, children);
    env->DeleteLocalRef(jSection);
    env->DeleteLocalRef(jType);
    env->DeleteLocalRef(jSubtype);
    env->DeleteLocalRef(jDisp);
    if (jFile != nullptr) env->DeleteLocalRef(jFile);
    env->DeleteLocalRef(children);
    return obj;
}

jobject mimeFromBody(JNIEnv * env, struct mailimap_body * body, const std::string & section) {
    if (body == nullptr) {
        return nullptr;
    }
    if (body->bd_type == MAILIMAP_BODY_MPART && body->bd_data.bd_body_mpart != nullptr) {
        struct mailimap_body_type_mpart * mpart = body->bd_data.bd_body_mpart;
        struct mailimap_body_fld_dsp * disp = mpart->bd_ext_mpart != nullptr ? mpart->bd_ext_mpart->bd_disposition : nullptr;
        const char * filename = disp != nullptr ? paramValue(disp->dsp_attributes, "filename") : nullptr;
        jobject children = newList(env);
        int index = 1;
        if (mpart->bd_list != nullptr) {
            for (clistiter * cur = clist_begin(mpart->bd_list); cur != nullptr; cur = clist_next(cur), ++index) {
                std::string childSection = section.empty() ? std::to_string(index) : section + "." + std::to_string(index);
                jobject child = mimeFromBody(env, static_cast<struct mailimap_body *>(clist_content(cur)), childSection);
                if (child != nullptr) {
                    listAdd(env, children, child);
                    env->DeleteLocalRef(child);
                }
            }
        }
        jstring jSection = newString(env, section.c_str());
        jstring jType = newString(env, "multipart");
        jstring jSubtype = newString(env, mpart->bd_media_subtype != nullptr ? mpart->bd_media_subtype : "mixed");
        jstring jDisp = newString(env, disp != nullptr && disp->dsp_type != nullptr ? disp->dsp_type : "");
        jstring jFile = filename != nullptr ? newString(env, filename) : nullptr;
        jobject obj = env->NewObject(gJni.mimePart, gJni.mimeInit, jSection, jType, jSubtype, jDisp, jFile, 0, children);
        env->DeleteLocalRef(jSection);
        env->DeleteLocalRef(jType);
        env->DeleteLocalRef(jSubtype);
        env->DeleteLocalRef(jDisp);
        if (jFile != nullptr) env->DeleteLocalRef(jFile);
        env->DeleteLocalRef(children);
        return obj;
    }
    std::string sec = section.empty() ? "1" : section;
    return mimeFrom1(env, body->bd_data.bd_body_1part, sec);
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

struct Row {
    uint32_t uid = 0;
    uint32_t sequence = 0;
    std::vector<std::string> flags;
    int64_t epoch = 0;
    int size = 0;
    std::string from;
    std::string subject;
    std::string date;
    struct mailimap_body * structure = nullptr;
    bool deleted = false;
};

void readAtt(struct mailimap_msg_att * att, Row * row) {
    if (att == nullptr) {
        return;
    }
    row->sequence = att->att_number;
    if (att->att_list == nullptr) {
        return;
    }
    for (clistiter * cur = clist_begin(att->att_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(cur));
        if (item == nullptr) {
            continue;
        }
        if (item->att_type == MAILIMAP_MSG_ATT_ITEM_DYNAMIC && item->att_data.att_dyn != nullptr
            && item->att_data.att_dyn->att_list != nullptr) {
            for (clistiter * fc = clist_begin(item->att_data.att_dyn->att_list); fc != nullptr; fc = clist_next(fc)) {
                auto * flag = static_cast<struct mailimap_flag_fetch *>(clist_content(fc));
                std::string name = flagName(flag);
                if (!name.empty()) {
                    row->flags.push_back(name);
                    if (strcasecmp(name.c_str(), "\\Deleted") == 0) {
                        row->deleted = true;
                    }
                }
            }
        } else if (item->att_type == MAILIMAP_MSG_ATT_ITEM_STATIC && item->att_data.att_static != nullptr) {
            struct mailimap_msg_att_static * st = item->att_data.att_static;
            switch (st->att_type) {
            case MAILIMAP_MSG_ATT_UID:
                row->uid = st->att_data.att_uid;
                break;
            case MAILIMAP_MSG_ATT_INTERNALDATE:
                row->epoch = internalDateEpoch(st->att_data.att_internal_date);
                break;
            case MAILIMAP_MSG_ATT_RFC822_SIZE:
                row->size = static_cast<int>(st->att_data.att_rfc822_size);
                break;
            case MAILIMAP_MSG_ATT_ENVELOPE:
                if (st->att_data.att_env != nullptr) {
                    struct mailimap_envelope * env = st->att_data.att_env;
                    if (env->env_subject != nullptr) row->subject = env->env_subject;
                    if (env->env_date != nullptr) row->date = env->env_date;
                    if (env->env_from != nullptr && env->env_from->frm_list != nullptr) {
                        clistiter * a = clist_begin(env->env_from->frm_list);
                        if (a != nullptr) {
                            row->from = addressText(static_cast<struct mailimap_address *>(clist_content(a)));
                        }
                    }
                }
                break;
            case MAILIMAP_MSG_ATT_BODYSTRUCTURE:
                row->structure = st->att_data.att_bodystructure;
                break;
            default:
                break;
            }
        }
    }
}

int fetchRows(mailimap * imap, struct mailimap_set * set, bool uidFetch, bool withStructure, clist ** out) {
    *out = nullptr;
    struct mailimap_fetch_type * fetch = indexFetchType(withStructure);
    if (fetch == nullptr || set == nullptr) {
        if (fetch != nullptr) mailimap_fetch_type_free(fetch);
        return MAILIMAP_ERROR_MEMORY;
    }
    int r = uidFetch ? mailimap_uid_fetch(imap, set, fetch, out) : mailimap_fetch(imap, set, fetch, out);
    mailimap_fetch_type_free(fetch);
    return r;
}

std::string previewText(mailimap * imap, uint32_t uid, const PartWant & want, int byteLimit) {
    if (!want.found || byteLimit <= 0) {
        return "";
    }
    struct mailimap_section * section = sectionFromSpec(want.section);
    if (section == nullptr) {
        return "";
    }
    struct mailimap_fetch_att * att = mailimap_fetch_att_new_body_peek_section_partial(
        section, 0, static_cast<uint32_t>(byteLimit));
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    if (att == nullptr || fetch == nullptr) {
        if (fetch != nullptr) mailimap_fetch_type_free(fetch);
        if (att == nullptr && section != nullptr) mailimap_section_free(section);
        return "";
    }
    mailimap_fetch_type_new_fetch_att_list_add(fetch, att);
    struct mailimap_set * set = mailimap_set_new_single(uid);
    clist * list = nullptr;
    int r = mailimap_uid_fetch(imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    std::string text;
    if (r == MAILIMAP_NO_ERROR && list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr && text.empty(); cur = clist_next(cur)) {
            auto * msg = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            if (msg == nullptr || msg->att_list == nullptr) continue;
            for (clistiter * ic = clist_begin(msg->att_list); ic != nullptr; ic = clist_next(ic)) {
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
                char * converted = nullptr;
                size_t convertedLen = 0;
                const char * charset = want.charset.empty() ? "UTF-8" : want.charset.c_str();
                int cr = charconv_buffer("UTF-8", charset, body->sec_body_part, body->sec_length, &converted, &convertedLen);
                if (cr != MAIL_CHARCONV_NO_ERROR || converted == nullptr) {
                    if (converted != nullptr) charconv_buffer_free(converted);
                    converted = nullptr;
                    cr = charconv_buffer("UTF-8", "ISO-8859-1", body->sec_body_part, body->sec_length, &converted, &convertedLen);
                }
                if (cr == MAIL_CHARCONV_NO_ERROR && converted != nullptr) {
                    text.assign(converted, convertedLen);
                    charconv_buffer_free(converted);
                } else if (converted != nullptr) {
                    charconv_buffer_free(converted);
                }
            }
        }
        mailimap_fetch_list_free(list);
    }
    return text;
}

jobject rowObject(JNIEnv * env, const Row & row, const std::string & preview, bool hasPreview) {
    jobject flags = env->NewObject(gJni.hashSet, gJni.setInit);
    for (const std::string & flag : row.flags) {
        jstring js = newString(env, flag.c_str());
        env->CallBooleanMethod(flags, gJni.setAdd, js);
        env->DeleteLocalRef(js);
    }
    jstring from = newString(env, row.from.c_str());
    jstring subject = newString(env, row.subject.c_str());
    jstring date = newString(env, row.date.c_str());
    jstring prev = hasPreview ? newString(env, preview.c_str()) : nullptr;
    jobject obj = env->NewObject(gJni.indexRow, gJni.indexRowInit,
        static_cast<jlong>(row.uid), static_cast<jint>(row.sequence), flags,
        static_cast<jlong>(row.epoch), static_cast<jint>(row.size),
        from, subject, date, prev);
    env->DeleteLocalRef(flags);
    env->DeleteLocalRef(from);
    env->DeleteLocalRef(subject);
    env->DeleteLocalRef(date);
    if (prev != nullptr) env->DeleteLocalRef(prev);
    return obj;
}

std::vector<Row> rowsFromList(clist * list) {
    std::vector<Row> rows;
    if (list == nullptr) {
        return rows;
    }
    for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
        Row row;
        readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row);
        if (row.uid != 0) {
            rows.push_back(row);
        }
    }
    return rows;
}

bool selectMailbox(JNIEnv * env, LiveSession * session, const char * mailbox) {
    int r = mailimap_select(session->imap, mailbox);
    if (!cmdOk(r)) {
        throwImap(env, session->imap, "select failed");
        return false;
    }
    return true;
}

jlongArray uidArray(JNIEnv * env, clist * list) {
    std::vector<jlong> values;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * n = static_cast<uint32_t *>(clist_content(cur));
            if (n != nullptr) values.push_back(static_cast<jlong>(*n));
        }
    }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(values.size()));
    if (!values.empty()) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(values.size()), values.data());
    }
    return arr;
}

void live_thread_free(LiveThread * node) {
    if (node == nullptr) {
        return;
    }
    if (node->children != nullptr) {
        for (clistiter * cur = clist_begin(node->children); cur != nullptr; cur = clist_next(cur)) {
            live_thread_free(static_cast<LiveThread *>(clist_content(cur)));
        }
        clist_free(node->children);
    }
    free(node);
}

LiveThread * newThread(int hasUid, uint32_t uid) {
    auto * node = static_cast<LiveThread *>(calloc(1, sizeof(LiveThread)));
    if (node == nullptr) {
        return nullptr;
    }
    node->has_uid = hasUid;
    node->uid = uid;
    node->children = clist_new();
    return node;
}

char peekChar(MMAPString * buffer, size_t indx) {
    if (buffer == nullptr || buffer->str == nullptr || indx >= buffer->len) {
        return 0;
    }
    return buffer->str[indx];
}

int parseThreadList(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx,
    size_t * indx, LiveThread ** out);

int parseThreadMembers(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx,
    size_t * indx, LiveThread ** out) {
    uint32_t number = 0;
    int r = mailimap_nz_number_parse(fd, buffer, ctx, indx, &number);
    if (r != MAILIMAP_NO_ERROR) {
        return r;
    }
    LiveThread * node = newThread(1, number);
    if (node == nullptr) {
        return MAILIMAP_ERROR_MEMORY;
    }
    for (;;) {
        size_t save = *indx;
        r = mailimap_space_parse(fd, buffer, indx);
        if (r != MAILIMAP_NO_ERROR) {
            *indx = save;
            break;
        }
        char c = peekChar(buffer, *indx);
        if (c == '(') {
            while (peekChar(buffer, *indx) == '(') {
                LiveThread * child = nullptr;
                r = parseThreadList(fd, buffer, ctx, indx, &child);
                if (r != MAILIMAP_NO_ERROR) {
                    live_thread_free(node);
                    return r;
                }
                clist_append(node->children, child);
            }
            break;
        }
        if (c >= '1' && c <= '9') {
            LiveThread * child = nullptr;
            r = parseThreadMembers(fd, buffer, ctx, indx, &child);
            if (r != MAILIMAP_NO_ERROR) {
                live_thread_free(node);
                return r;
            }
            clist_append(node->children, child);
            continue;
        }
        *indx = save;
        break;
    }
    *out = node;
    return MAILIMAP_NO_ERROR;
}

int parseThreadList(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx,
    size_t * indx, LiveThread ** out) {
    int r = mailimap_oparenth_parse(fd, buffer, ctx, indx);
    if (r != MAILIMAP_NO_ERROR) {
        return r;
    }
    LiveThread * node = nullptr;
    if (peekChar(buffer, *indx) == '(') {
        node = newThread(0, 0);
        if (node == nullptr) {
            return MAILIMAP_ERROR_MEMORY;
        }
        while (peekChar(buffer, *indx) == '(') {
            LiveThread * child = nullptr;
            r = parseThreadList(fd, buffer, ctx, indx, &child);
            if (r != MAILIMAP_NO_ERROR) {
                live_thread_free(node);
                return r;
            }
            clist_append(node->children, child);
        }
    } else {
        r = parseThreadMembers(fd, buffer, ctx, indx, &node);
        if (r != MAILIMAP_NO_ERROR) {
            return r;
        }
    }
    r = mailimap_cparenth_parse(fd, buffer, ctx, indx);
    if (r != MAILIMAP_NO_ERROR) {
        live_thread_free(node);
        return r;
    }
    *out = node;
    return MAILIMAP_NO_ERROR;
}

int thread_ext_parse(int calling_parser, mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx,
    struct mailimap_extension_data ** result, size_t, progress_function *) {
    if (calling_parser != MAILIMAP_EXTENDED_PARSER_RESPONSE_DATA
        && calling_parser != MAILIMAP_EXTENDED_PARSER_MAILBOX_DATA) {
        return MAILIMAP_ERROR_PARSE;
    }
    size_t cur = *indx;
    int r = mailimap_token_case_insensitive_parse(fd, buffer, &cur, "THREAD");
    if (r != MAILIMAP_NO_ERROR) {
        return r;
    }
    LiveThread * root = newThread(0, 0);
    if (root == nullptr) {
        return MAILIMAP_ERROR_MEMORY;
    }
    size_t save = cur;
    r = mailimap_space_parse(fd, buffer, &cur);
    if (r == MAILIMAP_NO_ERROR) {
        while (peekChar(buffer, cur) == '(') {
            LiveThread * child = nullptr;
            r = parseThreadList(fd, buffer, parser_ctx, &cur, &child);
            if (r != MAILIMAP_NO_ERROR) {
                live_thread_free(root);
                return r;
            }
            clist_append(root->children, child);
        }
    } else {
        cur = save;
    }
    struct mailimap_extension_data * ext = mailimap_extension_data_new(&liveimap_thread_extension, 1, root);
    if (ext == nullptr) {
        live_thread_free(root);
        return MAILIMAP_ERROR_MEMORY;
    }
    *result = ext;
    *indx = cur;
    return MAILIMAP_NO_ERROR;
}

void thread_ext_free(struct mailimap_extension_data * ext_data) {
    if (ext_data == nullptr) {
        return;
    }
    if (ext_data->ext_data != nullptr) {
        live_thread_free(static_cast<LiveThread *>(ext_data->ext_data));
    }
    free(ext_data);
}

jobject threadObject(JNIEnv * env, LiveThread * node) {
    if (node == nullptr) {
        node = newThread(0, 0);
    }
    jobject children = newList(env);
    if (node->children != nullptr) {
        for (clistiter * cur = clist_begin(node->children); cur != nullptr; cur = clist_next(cur)) {
            jobject child = threadObject(env, static_cast<LiveThread *>(clist_content(cur)));
            listAdd(env, children, child);
            env->DeleteLocalRef(child);
        }
    }
    jobject uid = nullptr;
    if (node->has_uid) {
        uid = env->CallStaticObjectMethod(gJni.longCls, gJni.longValueOf, static_cast<jlong>(node->uid));
    }
    jobject obj = env->NewObject(gJni.threadNode, gJni.threadInit, uid, children);
    if (uid != nullptr) env->DeleteLocalRef(uid);
    env->DeleteLocalRef(children);
    return obj;
}

LiveThread * detachThread(mailimap * imap) {
    LiveThread * tree = nullptr;
    if (imap->imap_response_info == nullptr || imap->imap_response_info->rsp_extension_list == nullptr) {
        return newThread(0, 0);
    }
    for (clistiter * cur = clist_begin(imap->imap_response_info->rsp_extension_list); cur != nullptr; cur = clist_next(cur)) {
        auto * ext = static_cast<struct mailimap_extension_data *>(clist_content(cur));
        if (ext != nullptr && ext->ext_extension == &liveimap_thread_extension && tree == nullptr) {
            tree = static_cast<LiveThread *>(ext->ext_data);
            ext->ext_data = nullptr;
            ext->ext_type = -1;
        }
    }
    clist_foreach(imap->imap_response_info->rsp_extension_list,
        reinterpret_cast<clist_func>(mailimap_extension_data_free), nullptr);
    clist_free(imap->imap_response_info->rsp_extension_list);
    imap->imap_response_info->rsp_extension_list = nullptr;
    if (tree == nullptr) {
        tree = newThread(0, 0);
    }
    return tree;
}

int sendUidThread(mailimap * imap, const char * algorithm, struct mailimap_response ** response) {
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "UID");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "THREAD");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, algorithm);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_astring_send(imap->imap_stream, "US-ASCII");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "ALL");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    return mailimap_parse_response(imap, response);
}

bool taggedOk(struct mailimap_response * response) {
    if (response == nullptr || response->rsp_resp_done == nullptr) return false;
    if (response->rsp_resp_done->rsp_type != MAILIMAP_RESP_DONE_TYPE_TAGGED) return false;
    struct mailimap_response_tagged * tagged = response->rsp_resp_done->rsp_data.rsp_tagged;
    if (tagged == nullptr || tagged->rsp_cond_state == nullptr) return false;
    return tagged->rsp_cond_state->rsp_type == MAILIMAP_RESP_COND_STATE_OK;
}

int appendLiteralPlus(mailimap * imap, const char * mailbox, const char * bytes, size_t size) {
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "APPEND");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_mailbox_send(imap->imap_stream, mailbox);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    char marker[64];
    snprintf(marker, sizeof(marker), "{%zu+}", size);
    r = mailimap_token_send(imap->imap_stream, marker);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    size_t off = 0;
    while (off < size) {
        ssize_t wrote = mailstream_write(imap->imap_stream, bytes + off, size - off);
        if (wrote <= 0) return MAILIMAP_ERROR_STREAM;
        off += static_cast<size_t>(wrote);
    }
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(imap, &response);
    if (r != MAILIMAP_NO_ERROR) return r;
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    return ok ? MAILIMAP_NO_ERROR : MAILIMAP_ERROR_APPEND;
}

void stopWatchLocked(LiveSession * session, JNIEnv * env) {
    session->watchStop.store(true);
    if (session->watchThread.joinable()) {
        session->watchThread.join();
    }
    if (session->watch != nullptr) {
        mailimap_free(session->watch);
        session->watch = nullptr;
    }
    if (session->selfGlobal != nullptr && env != nullptr) {
        env->DeleteGlobalRef(session->selfGlobal);
        session->selfGlobal = nullptr;
    }
    session->watchStop.store(false);
    session->startState = 0;
}

void emitWatch(LiveSession * session, int kind, int exists, jlong uid, jobjectArray flags) {
    if (gVm == nullptr || session->selfGlobal == nullptr) {
        return;
    }
    JNIEnv * env = nullptr;
    bool attached = false;
    jint got = gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (got == JNI_EDETACHED) {
        if (gVm->AttachCurrentThread(&env, nullptr) != 0) {
            return;
        }
        attached = true;
    }
    if (env == nullptr) {
        return;
    }
    env->CallVoidMethod(session->selfGlobal, gJni.onWatch, kind, exists, uid, flags);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        session->watchStop.store(true);
    }
    if (flags != nullptr) {
        env->DeleteLocalRef(flags);
    }
    if (attached) {
        gVm->DetachCurrentThread();
    }
}

jobjectArray flagsArray(JNIEnv * env, const std::vector<std::string> & flags) {
    jclass stringCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(flags.size()), stringCls, nullptr);
    env->DeleteLocalRef(stringCls);
    for (jsize i = 0; i < static_cast<jsize>(flags.size()); ++i) {
        jstring js = newString(env, flags[static_cast<size_t>(i)].c_str());
        env->SetObjectArrayElement(arr, i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

void handleUntagged(LiveSession * session, struct mailimap_response_data * data) {
    if (data == nullptr) {
        return;
    }
    if (data->rsp_type == MAILIMAP_RESP_DATA_TYPE_MAILBOX_DATA && data->rsp_data.rsp_mailbox_data != nullptr) {
        struct mailimap_mailbox_data * mb = data->rsp_data.rsp_mailbox_data;
        if (mb->mbd_type == MAILIMAP_MAILBOX_DATA_EXISTS) {
            session->watchExists = static_cast<int>(mb->mbd_data.mbd_exists);
            emitWatch(session, 0, session->watchExists, 0, nullptr);
        }
    } else if (data->rsp_type == MAILIMAP_RESP_DATA_TYPE_MESSAGE_DATA && data->rsp_data.rsp_message_data != nullptr) {
        struct mailimap_message_data * msg = data->rsp_data.rsp_message_data;
        if (msg->mdt_type == MAILIMAP_MESSAGE_DATA_EXPUNGE) {
            if (session->watchExists > 0) session->watchExists -= 1;
            emitWatch(session, 1, session->watchExists, 0, nullptr);
        } else if (msg->mdt_type == MAILIMAP_MESSAGE_DATA_FETCH) {
            Row row;
            readAtt(msg->mdt_msg_att, &row);
            if (row.uid != 0) {
                JNIEnv * env = nullptr;
                gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
                if (env != nullptr) {
                    jobjectArray flags = flagsArray(env, row.flags);
                    emitWatch(session, 2, session->watchExists, static_cast<jlong>(row.uid), flags);
                }
            }
        }
    } else if (data->rsp_type == MAILIMAP_RESP_DATA_TYPE_COND_STATE && data->rsp_data.rsp_cond_state != nullptr) {
        struct mailimap_resp_text * text = data->rsp_data.rsp_cond_state->rsp_text;
        if (text != nullptr && text->rsp_code != nullptr
            && text->rsp_code->rc_type == MAILIMAP_RESP_TEXT_CODE_UIDVALIDITY) {
            uint32_t uv = text->rsp_code->rc_data.rc_uidvalidity;
            if (session->watchUidValidity != 0 && uv != session->watchUidValidity) {
                session->watchUidValidity = uv;
                emitWatch(session, 3, session->watchExists, 0, nullptr);
            }
        }
    }
    if (session->watch != nullptr && session->watch->imap_selection_info != nullptr) {
        uint32_t uv = session->watch->imap_selection_info->sel_uidvalidity;
        if (session->watchUidValidity != 0 && uv != 0 && uv != session->watchUidValidity) {
            session->watchUidValidity = uv;
            emitWatch(session, 3, session->watchExists, 0, nullptr);
        }
    }
}

void watchMain(LiveSession * session) {
    JNIEnv * attached = nullptr;
    if (gVm == nullptr || gVm->AttachCurrentThread(&attached, nullptr) != 0) {
        std::lock_guard<std::mutex> lock(session->startMu);
        session->startState = -1;
        session->startError = "idle failed";
        session->startCv.notify_all();
        return;
    }
    tlsWatch = session;
    int r = mailimap_idle(session->watch);
    {
        std::lock_guard<std::mutex> lock(session->startMu);
        if (r != MAILIMAP_NO_ERROR) {
            session->startState = -1;
            session->startError = imapText(session->watch, "idle failed");
        } else {
            session->startState = 1;
        }
    }
    session->startCv.notify_all();
    if (r != MAILIMAP_NO_ERROR) {
        tlsWatch = nullptr;
        gVm->DetachCurrentThread();
        return;
    }
    int fd = mailimap_idle_get_fd(session->watch);
    while (!session->watchStop.load()) {
        struct pollfd pfd{};
        pfd.fd = fd;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 500);
        if (session->watchStop.load()) break;
        if (pr <= 0) continue;
        if (mailimap_read_line(session->watch) == nullptr) break;
        size_t indx = 0;
        struct mailimap_parser_context * ctx = mailimap_parser_context_new(session->watch);
        struct mailimap_response_data * data = nullptr;
        int prs = mailimap_response_data_parse(session->watch->imap_stream, session->watch->imap_stream_buffer,
            ctx, &indx, &data, 0, nullptr);
        if (prs == MAILIMAP_NO_ERROR) {
            handleUntagged(session, data);
        }
        if (data != nullptr) mailimap_response_data_free(data);
        if (ctx != nullptr) mailimap_parser_context_free(ctx);
    }
    mailimap_idle_done(session->watch);
    tlsWatch = nullptr;
    gVm->DetachCurrentThread();
}

mailimap * openPlain(const char * host, int port, const char * user, const char * password, std::string * error) {
    mailimap * imap = mailimap_new(0, nullptr);
    if (imap == nullptr) {
        *error = "imap error";
        return nullptr;
    }
    mailimap_set_timeout(imap, 60);
    int r = mailimap_socket_connect(imap, host, static_cast<uint16_t>(port));
    if (!connectOk(r)) {
        *error = imapText(imap, "connection failed");
        mailimap_free(imap);
        return nullptr;
    }
    r = mailimap_login(imap, user, password);
    if (!cmdOk(r)) {
        *error = imapText(imap, "login failed");
        mailimap_free(imap);
        return nullptr;
    }
    return imap;
}

void freeSession(LiveSession * session, JNIEnv * env) {
    {
        std::lock_guard<std::mutex> lock(gLiveMu);
        gLive.erase(session);
    }
    session->mu.lock();
    if (tlsWatch != session) {
        stopWatchLocked(session, env);
    } else {
        session->watchStop.store(true);
    }
    if (session->imap != nullptr) {
        mailimap_free(session->imap);
        session->imap = nullptr;
    }
    session->mu.unlock();
    if (tlsWatch != session) {
        delete session;
    }
}

std::string leafOf(const char * mailbox, char delim) {
    std::string raw = mailbox != nullptr ? mailbox : "";
    std::string comp = raw;
    if (delim != 0) {
        auto pos = raw.find_last_of(delim);
        if (pos != std::string::npos) {
            comp = raw.substr(pos + 1);
        }
    }
    char * decoded = charconv_decode_mutf7(comp.c_str());
    std::string leaf = decoded != nullptr ? decoded : comp;
    free(decoded);
    return leaf;
}

bool hasChildren(struct mailimap_mailbox_list * mb) {
    if (mb->mb_flag == nullptr || mb->mb_flag->mbf_oflags == nullptr) {
        return false;
    }
    for (clistiter * cur = clist_begin(mb->mb_flag->mbf_oflags); cur != nullptr; cur = clist_next(cur)) {
        auto * of = static_cast<struct mailimap_mbx_list_oflag *>(clist_content(cur));
        if (of != nullptr && of->of_type == MAILIMAP_MBX_LIST_OFLAG_HASCHILDREN) {
            return true;
        }
    }
    return false;
}

char delimFor(LiveSession * session, const char * parent) {
    if (parent == nullptr) return 0;
    auto it = session->delims.find(parent);
    if (it != session->delims.end()) return it->second;
    char best = 0;
    size_t bestLen = 0;
    std::string name = parent;
    for (const auto & item : session->delims) {
        if (!item.first.empty() && name.compare(0, item.first.size(), item.first) == 0 && item.first.size() >= bestLen) {
            bestLen = item.first.size();
            best = item.second;
        }
    }
    return best;
}

jobjectArray emptyArray(JNIEnv * env, jclass cls) {
    return env->NewObjectArray(0, cls, nullptr);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    gVm = vm;
    gKeepUnselect = mailimap_unselect;
    registerExtensions();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeTakeError(JNIEnv * env, jclass) {
    registerExtensions();
    if (!ensureJni(env)) return nullptr;
    return newString(env, takeLastError().c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeOpen(JNIEnv * env, jobject,
    jstring host, jint port, jstring user, jstring password, jstring smtpHost, jint smtpPort, jstring from) {
    registerExtensions();
    if (!ensureJni(env)) return 0;
    JChars h(env, host);
    JChars u(env, user);
    JChars p(env, password);
    JChars sh(env, smtpHost);
    JChars fr(env, from);
    std::string error;
    mailimap * imap = openPlain(h.c(), port, u.c(), p.c(), &error);
    if (imap == nullptr) {
        setLastError(error);
        return 0;
    }
    struct mailimap_capability_data * caps = nullptr;
    int r = mailimap_capability(imap, &caps);
    if (!cmdOk(r) || caps == nullptr) {
        setLastError(imapText(imap, "capability failed"));
        mailimap_free(imap);
        return 0;
    }
    auto * session = new LiveSession();
    session->imap = imap;
    session->host = h.c();
    session->imapPort = port;
    session->user = u.c();
    session->password = p.c();
    session->smtpHost = sh.c();
    session->smtpPort = smtpPort;
    session->from = fr.c()[0] != 0 ? fr.c() : u.c();
    session->capabilityLine = joinCapabilities(caps);
    mailimap_capability_data_free(caps);
    std::lock_guard<std::mutex> lock(gLiveMu);
    gLive.insert(session);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCapabilityLine(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    jstring line = newString(env, session->capabilityLine.c_str());
    unlockSession(session);
    return line;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeClose(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return;
    LiveSession * session = nullptr;
    {
        std::lock_guard<std::mutex> lock(gLiveMu);
        session = reinterpret_cast<LiveSession *>(handle);
        if (gLive.find(session) == gLive.end()) return;
    }
    freeSession(session, env);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeNamespaces(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    struct mailimap_namespace_data * data = nullptr;
    int r = mailimap_namespace(session->imap, &data);
    if (!cmdOk(r) || data == nullptr) {
        throwImap(env, session->imap, "namespace failed");
        unlockSession(session);
        return nullptr;
    }
    struct Item { struct mailimap_namespace_item * item; jfieldID kind; };
    Item items[3] = {
        {data->ns_personal, gJni.personal},
        {data->ns_other, gJni.other},
        {data->ns_shared, gJni.shared},
    };
    std::vector<jobject> built;
    for (Item item : items) {
        if (item.item == nullptr || item.item->ns_data_list == nullptr) continue;
        jobject kind = env->GetStaticObjectField(gJni.nsKind, item.kind);
        for (clistiter * cur = clist_begin(item.item->ns_data_list); cur != nullptr; cur = clist_next(cur)) {
            auto * info = static_cast<struct mailimap_namespace_info *>(clist_content(cur));
            if (info == nullptr) continue;
            const char * prefix = info->ns_prefix != nullptr ? info->ns_prefix : "";
            session->delims[prefix] = info->ns_delimiter;
            jstring jp = newString(env, prefix);
            jobject ns = env->NewObject(gJni.ns, gJni.nsInit, jp, static_cast<jchar>(info->ns_delimiter), kind);
            env->DeleteLocalRef(jp);
            built.push_back(ns);
        }
        env->DeleteLocalRef(kind);
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(built.size()), gJni.ns, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(built.size()); ++i) {
        env->SetObjectArrayElement(arr, i, built[static_cast<size_t>(i)]);
        env->DeleteLocalRef(built[static_cast<size_t>(i)]);
    }
    mailimap_namespace_data_free(data);
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeListLevel(JNIEnv * env, jobject, jlong handle,
    jstring prefix, jstring parent) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars pref(env, prefix);
    JChars par(env, parent);
    std::string pattern;
    if (parent != nullptr && par.c()[0] != 0) {
        pattern = par.c();
        char delim = delimFor(session, par.c());
        if (delim != 0) pattern.push_back(delim);
        pattern.push_back('%');
    } else {
        pattern = pref.c();
        pattern.push_back('%');
    }
    clist * list = nullptr;
    int r = mailimap_list(session->imap, "", pattern.c_str(), &list);
    if (!cmdOk(r)) {
        if (list != nullptr) mailimap_list_result_free(list);
        throwImap(env, session->imap, "list failed");
        unlockSession(session);
        return nullptr;
    }
    std::vector<jobject> built;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * mb = static_cast<struct mailimap_mailbox_list *>(clist_content(cur));
            if (mb == nullptr || mb->mb_name == nullptr) continue;
            session->delims[mb->mb_name] = mb->mb_delimiter;
            std::string leaf = leafOf(mb->mb_name, mb->mb_delimiter);
            jstring jmb = newString(env, mb->mb_name);
            jstring jleaf = newString(env, leaf.c_str());
            jobject entry = env->NewObject(gJni.folderEntry, gJni.folderInit, jmb, jleaf,
                static_cast<jboolean>(hasChildren(mb)), static_cast<jchar>(mb->mb_delimiter));
            env->DeleteLocalRef(jmb);
            env->DeleteLocalRef(jleaf);
            built.push_back(entry);
        }
        mailimap_list_result_free(list);
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(built.size()), gJni.folderEntry, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(built.size()); ++i) {
        env->SetObjectArrayElement(arr, i, built[static_cast<size_t>(i)]);
        env->DeleteLocalRef(built[static_cast<size_t>(i)]);
    }
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSelect(JNIEnv * env, jobject, jlong handle, jstring mailbox) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars mb(env, mailbox);
    if (!selectMailbox(env, session, mb.c())) {
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_selection_info * info = session->imap->imap_selection_info;
    jlong uidValidity = info != nullptr ? static_cast<jlong>(info->sel_uidvalidity) : 0;
    jlong uidNext = info != nullptr ? static_cast<jlong>(info->sel_uidnext) : 0;
    jint exists = info != nullptr ? static_cast<jint>(info->sel_exists) : 0;
    jobject result = env->NewObject(gJni.selectResult, gJni.selectInit, uidValidity, uidNext, exists);
    unlockSession(session);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeUnselect(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    int r = mailimap_unselect(session->imap);
    if (!cmdOk(r)) {
        throwImap(env, session->imap, "unselect failed");
    }
    unlockSession(session);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchIndex(JNIEnv * env, jobject, jlong handle,
    jstring mailbox, jint mode, jlongArray uids, jint limit, jint prefetch, jboolean includePreview,
    jint previewByteLimit, jboolean preferHtml, jboolean showDeleted) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars mb(env, mailbox);
    if (!selectMailbox(env, session, mb.c())) {
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_selection_info * info = session->imap->imap_selection_info;
    uint32_t exists = info != nullptr ? info->sel_exists : 0;
    bool byUid = mode == 2;
    if (!byUid) {
        int count = limit + prefetch;
        if (count < 0) count = 0;
        if (exists == 0 || count == 0) {
            unlockSession(session);
            return emptyArray(env, gJni.indexRow);
        }
        uint32_t first = 1;
        uint32_t last = exists;
        if (mode == 0) {
            last = exists;
            first = exists > static_cast<uint32_t>(count) ? exists - static_cast<uint32_t>(count) + 1 : 1;
        } else {
            first = 1;
            last = exists < static_cast<uint32_t>(count) ? exists : static_cast<uint32_t>(count);
        }
        struct mailimap_set * set = mailimap_set_new_interval(first, last);
        clist * list = nullptr;
        int r = fetchRows(session->imap, set, false, includePreview == JNI_TRUE, &list);
        mailimap_set_free(set);
        if (!cmdOk(r)) {
            throwImap(env, session->imap, "fetch failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<Row> rows = rowsFromList(list);
        std::vector<std::string> previews(rows.size());
        std::vector<char> havePreview(rows.size(), 0);
        if (includePreview == JNI_TRUE) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (rows[i].structure == nullptr) continue;
                PartWant want;
                findPreferred(rows[i].structure, "", preferHtml == JNI_TRUE, &want);
                if (!want.found) continue;
                previews[i] = previewText(session->imap, rows[i].uid, want, previewByteLimit);
                havePreview[i] = 1;
            }
        }
        if (list != nullptr) mailimap_fetch_list_free(list);
        std::vector<jobject> built;
        for (size_t i = 0; i < rows.size(); ++i) {
            const Row & row = rows[i];
            if (showDeleted == JNI_FALSE && row.deleted) continue;
            built.push_back(rowObject(env, row, previews[i], havePreview[i] != 0));
        }
        jobjectArray arr = env->NewObjectArray(static_cast<jsize>(built.size()), gJni.indexRow, nullptr);
        for (jsize i = 0; i < static_cast<jsize>(built.size()); ++i) {
            env->SetObjectArrayElement(arr, i, built[static_cast<size_t>(i)]);
            env->DeleteLocalRef(built[static_cast<size_t>(i)]);
        }
        unlockSession(session);
        return arr;
    }
    std::vector<uint32_t> all;
    if (uids != nullptr) {
        jsize n = env->GetArrayLength(uids);
        std::vector<jlong> raw(static_cast<size_t>(n));
        if (n > 0) env->GetLongArrayRegion(uids, 0, n, raw.data());
        for (jlong value : raw) {
            if (value > 0) all.push_back(static_cast<uint32_t>(value));
        }
    }
    std::vector<jobject> built;
    const size_t pageSize = 60;
    for (size_t off = 0; off < all.size(); off += pageSize) {
        size_t end = std::min(all.size(), off + pageSize);
        std::vector<uint32_t> page(all.begin() + static_cast<std::ptrdiff_t>(off), all.begin() + static_cast<std::ptrdiff_t>(end));
        struct mailimap_set * set = setFromUids(page);
        clist * list = nullptr;
        int r = fetchRows(session->imap, set, true, includePreview == JNI_TRUE, &list);
        if (set != nullptr) mailimap_set_free(set);
        if (!cmdOk(r)) {
            throwImap(env, session->imap, "fetch failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<Row> rows = rowsFromList(list);
        std::vector<std::string> previews(rows.size());
        std::vector<char> havePreview(rows.size(), 0);
        if (includePreview == JNI_TRUE) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (rows[i].structure == nullptr) continue;
                PartWant want;
                findPreferred(rows[i].structure, "", preferHtml == JNI_TRUE, &want);
                if (!want.found) continue;
                previews[i] = previewText(session->imap, rows[i].uid, want, previewByteLimit);
                havePreview[i] = 1;
            }
        }
        if (list != nullptr) mailimap_fetch_list_free(list);
        for (size_t i = 0; i < rows.size(); ++i) {
            const Row & row = rows[i];
            if (showDeleted == JNI_FALSE && row.deleted) continue;
            built.push_back(rowObject(env, row, previews[i], havePreview[i] != 0));
        }
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(built.size()), gJni.indexRow, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(built.size()); ++i) {
        env->SetObjectArrayElement(arr, i, built[static_cast<size_t>(i)]);
        env->DeleteLocalRef(built[static_cast<size_t>(i)]);
    }
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchStructure(JNIEnv * env, jobject, jlong handle, jlong uid) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    struct mailimap_set * set = mailimap_set_new_single(static_cast<uint32_t>(uid));
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_bodystructure());
    clist * list = nullptr;
    int r = mailimap_uid_fetch(session->imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    if (!cmdOk(r)) {
        throwImap(env, session->imap, "fetch failed");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_body * body = nullptr;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr && body == nullptr; cur = clist_next(cur)) {
            Row row;
            readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row);
            body = row.structure;
        }
    }
    jobject obj = mimeFromBody(env, body, "");
    if (list != nullptr) mailimap_fetch_list_free(list);
    if (obj == nullptr && !env->ExceptionCheck()) {
        throwImap(env, session->imap, "fetch failed");
    }
    unlockSession(session);
    return obj;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativePeekPart(JNIEnv * env, jobject, jlong handle,
    jlong uid, jstring section, jint offset, jint length) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars spec(env, section);
    struct mailimap_section * sec = sectionFromSpec(spec.c());
    struct mailimap_fetch_att * att = nullptr;
    if (length > 0) {
        att = mailimap_fetch_att_new_body_peek_section_partial(sec, static_cast<uint32_t>(offset), static_cast<uint32_t>(length));
    } else {
        att = mailimap_fetch_att_new_body_peek_section(sec);
    }
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    mailimap_fetch_type_new_fetch_att_list_add(fetch, att);
    struct mailimap_set * set = mailimap_set_new_single(static_cast<uint32_t>(uid));
    clist * list = nullptr;
    int r = mailimap_uid_fetch(session->imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    if (!cmdOk(r)) {
        throwImap(env, session->imap, "fetch failed");
        unlockSession(session);
        return nullptr;
    }
    const char * bytes = nullptr;
    size_t n = 0;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr && bytes == nullptr; cur = clist_next(cur)) {
            auto * msg = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            if (msg == nullptr || msg->att_list == nullptr) continue;
            for (clistiter * ic = clist_begin(msg->att_list); ic != nullptr; ic = clist_next(ic)) {
                auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(ic));
                if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC || item->att_data.att_static == nullptr) continue;
                struct mailimap_msg_att_static * st = item->att_data.att_static;
                if (st->att_type == MAILIMAP_MSG_ATT_BODY_SECTION && st->att_data.att_body_section != nullptr) {
                    bytes = st->att_data.att_body_section->sec_body_part;
                    n = st->att_data.att_body_section->sec_length;
                }
            }
        }
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (n > 0 && bytes != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(bytes));
    }
    if (list != nullptr) mailimap_fetch_list_free(list);
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchRfc822(JNIEnv * env, jobject, jlong handle, jlong uid) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    struct mailimap_set * set = mailimap_set_new_single(static_cast<uint32_t>(uid));
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    mailimap_fetch_type_new_fetch_att_list_add(fetch, mailimap_fetch_att_new_rfc822());
    clist * list = nullptr;
    int r = mailimap_uid_fetch(session->imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    if (!cmdOk(r)) {
        throwImap(env, session->imap, "fetch failed");
        unlockSession(session);
        return nullptr;
    }
    const char * bytes = nullptr;
    size_t n = 0;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr && bytes == nullptr; cur = clist_next(cur)) {
            auto * msg = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            if (msg == nullptr || msg->att_list == nullptr) continue;
            for (clistiter * ic = clist_begin(msg->att_list); ic != nullptr; ic = clist_next(ic)) {
                auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(ic));
                if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC || item->att_data.att_static == nullptr) continue;
                struct mailimap_msg_att_static * st = item->att_data.att_static;
                if (st->att_type == MAILIMAP_MSG_ATT_RFC822) {
                    bytes = st->att_data.att_rfc822.att_content;
                    n = st->att_data.att_rfc822.att_length;
                }
            }
        }
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (n > 0 && bytes != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(bytes));
    }
    if (list != nullptr) mailimap_fetch_list_free(list);
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStoreFlags(JNIEnv * env, jobject, jlong handle,
    jlongArray uids, jobjectArray add, jobjectArray remove) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    std::vector<uint32_t> values;
    if (uids != nullptr) {
        jsize n = env->GetArrayLength(uids);
        std::vector<jlong> raw(static_cast<size_t>(n));
        if (n > 0) env->GetLongArrayRegion(uids, 0, n, raw.data());
        for (jlong value : raw) if (value > 0) values.push_back(static_cast<uint32_t>(value));
    }
    if (values.empty()) {
        unlockSession(session);
        return;
    }
    struct mailimap_flag_list * addFlags = nullptr;
    struct mailimap_flag_list * removeFlags = nullptr;
    if (!flagListFromArray(env, add, &addFlags) || !flagListFromArray(env, remove, &removeFlags)) {
        throwFailure(env, "imap error");
        unlockSession(session);
        return;
    }
    struct mailimap_set * set = setFromUids(values);
    if (add != nullptr && env->GetArrayLength(add) > 0) {
        struct mailimap_store_att_flags * att = mailimap_store_att_flags_new_add_flags(addFlags);
        addFlags = nullptr;
        int r = mailimap_uid_store(session->imap, set, att);
        mailimap_store_att_flags_free(att);
        if (!cmdOk(r)) {
            mailimap_set_free(set);
            if (removeFlags != nullptr) mailimap_flag_list_free(removeFlags);
            throwImap(env, session->imap, "store failed");
            unlockSession(session);
            return;
        }
    } else if (addFlags != nullptr) {
        mailimap_flag_list_free(addFlags);
    }
    if (remove != nullptr && env->GetArrayLength(remove) > 0) {
        struct mailimap_store_att_flags * att = mailimap_store_att_flags_new_remove_flags(removeFlags);
        removeFlags = nullptr;
        int r = mailimap_uid_store(session->imap, set, att);
        mailimap_store_att_flags_free(att);
        if (!cmdOk(r)) {
            mailimap_set_free(set);
            throwImap(env, session->imap, "store failed");
            unlockSession(session);
            return;
        }
    } else if (removeFlags != nullptr) {
        mailimap_flag_list_free(removeFlags);
    }
    mailimap_set_free(set);
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeUidExpungeDeleted(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    struct mailimap_set * set = mailimap_set_new_interval(1, 0);
    int r = mailimap_uid_expunge(session->imap, set);
    mailimap_set_free(set);
    if (!cmdOk(r)) throwImap(env, session->imap, "expunge failed");
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCopyThenDelete(JNIEnv * env, jobject, jlong handle,
    jlongArray uids, jstring target) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    std::vector<uint32_t> values;
    if (uids != nullptr) {
        jsize n = env->GetArrayLength(uids);
        std::vector<jlong> raw(static_cast<size_t>(n));
        if (n > 0) env->GetLongArrayRegion(uids, 0, n, raw.data());
        for (jlong value : raw) if (value > 0) values.push_back(static_cast<uint32_t>(value));
    }
    if (values.empty()) {
        unlockSession(session);
        return;
    }
    JChars dest(env, target);
    struct mailimap_set * set = setFromUids(values);
    int r = mailimap_uid_copy(session->imap, set, dest.c());
    if (!cmdOk(r)) {
        mailimap_set_free(set);
        throwImap(env, session->imap, "copy failed");
        unlockSession(session);
        return;
    }
    struct mailimap_flag_list * flags = mailimap_flag_list_new_empty();
    mailimap_flag_list_add(flags, mailimap_flag_new_deleted());
    struct mailimap_store_att_flags * att = mailimap_store_att_flags_new_add_flags(flags);
    r = mailimap_uid_store(session->imap, set, att);
    mailimap_store_att_flags_free(att);
    mailimap_set_free(set);
    if (!cmdOk(r)) throwImap(env, session->imap, "store failed");
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeAppend(JNIEnv * env, jobject, jlong handle,
    jstring mailbox, jbyteArray message) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    JChars mb(env, mailbox);
    jsize n = message != nullptr ? env->GetArrayLength(message) : 0;
    jbyte * bytes = n > 0 ? env->GetByteArrayElements(message, nullptr) : nullptr;
    int r = appendLiteralPlus(session->imap, mb.c(), bytes != nullptr ? reinterpret_cast<char *>(bytes) : "", static_cast<size_t>(n));
    if (bytes != nullptr) env->ReleaseByteArrayElements(message, bytes, JNI_ABORT);
    if (!cmdOk(r)) throwImap(env, session->imap, "append failed");
    unlockSession(session);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchText(JNIEnv * env, jobject, jlong handle, jstring query) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars q(env, query);
    struct mailimap_search_key * key = mailimap_search_key_new_text(strdup(q.c()));
    clist * result = nullptr;
    int r = mailimap_uid_search(session->imap, "UTF-8", key, &result);
    mailimap_search_key_free(key);
    if (!cmdOk(r)) {
        if (result != nullptr) mailimap_search_result_free(result);
        throwImap(env, session->imap, "search failed");
        unlockSession(session);
        return nullptr;
    }
    jlongArray arr = uidArray(env, result);
    if (result != nullptr) mailimap_search_result_free(result);
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSort(JNIEnv * env, jobject, jlong handle,
    jstring keyName, jboolean newestFirst) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars name(env, keyName);
    int rev = newestFirst == JNI_TRUE ? 0 : 1;
    struct mailimap_sort_key * key = nullptr;
    if (strcasecmp(name.c(), "DATE") == 0) key = mailimap_sort_key_new_date(rev);
    else if (strcasecmp(name.c(), "FROM") == 0) key = mailimap_sort_key_new_from(rev);
    else if (strcasecmp(name.c(), "SUBJECT") == 0) key = mailimap_sort_key_new_subject(rev);
    else if (strcasecmp(name.c(), "TO") == 0) key = mailimap_sort_key_new_to(rev);
    else if (strcasecmp(name.c(), "CC") == 0) key = mailimap_sort_key_new_cc(rev);
    else if (strcasecmp(name.c(), "SIZE") == 0) key = mailimap_sort_key_new_size(rev);
    if (key == nullptr) {
        throwFailure(env, "use an arrival IndexMode");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_search_key * all = mailimap_search_key_new_all();
    clist * result = nullptr;
    int r = mailimap_uid_sort(session->imap, "US-ASCII", key, all, &result);
    mailimap_sort_key_free(key);
    mailimap_search_key_free(all);
    if (!cmdOk(r)) {
        throwImap(env, session->imap, "sort failed");
        unlockSession(session);
        return nullptr;
    }
    jlongArray arr = uidArray(env, result);
    if (result != nullptr) mailimap_sort_result_free(result);
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeThread(JNIEnv * env, jobject, jlong handle, jstring algorithm) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars alg(env, algorithm);
    struct mailimap_response * response = nullptr;
    int r = sendUidThread(session->imap, alg.c(), &response);
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session->imap, "thread failed");
        unlockSession(session);
        return nullptr;
    }
    LiveThread * tree = detachThread(session->imap);
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) {
        live_thread_free(tree);
        throwImap(env, session->imap, "thread failed");
        unlockSession(session);
        return nullptr;
    }
    jobject obj = threadObject(env, tree);
    live_thread_free(tree);
    unlockSession(session);
    return obj;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(JNIEnv * env, jobject thiz, jlong handle, jstring mailbox) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    if (tlsWatch == session) {
        session->watchStop.store(true);
        unlockSession(session);
        return;
    }
    stopWatchLocked(session, env);
    JChars mb(env, mailbox);
    std::string error;
    mailimap * watch = openPlain(session->host.c_str(), session->imapPort, session->user.c_str(), session->password.c_str(), &error);
    if (watch == nullptr) {
        throwFailure(env, error);
        unlockSession(session);
        return;
    }
    int r = mailimap_select(watch, mb.c());
    if (!cmdOk(r)) {
        throwImap(env, watch, "select failed");
        mailimap_free(watch);
        unlockSession(session);
        return;
    }
    session->watch = watch;
    session->watchStop.store(false);
    session->startState = 0;
    session->startError.clear();
    if (watch->imap_selection_info != nullptr) {
        session->watchUidValidity = watch->imap_selection_info->sel_uidvalidity;
        session->watchExists = static_cast<int>(watch->imap_selection_info->sel_exists);
    }
    if (session->selfGlobal != nullptr) env->DeleteGlobalRef(session->selfGlobal);
    session->selfGlobal = env->NewGlobalRef(thiz);
    session->watchThread = std::thread(watchMain, session);
    {
        std::unique_lock<std::mutex> lock(session->startMu);
        session->startCv.wait(lock, [&] { return session->startState != 0; });
    }
    if (session->startState < 0) {
        std::string why = session->startError;
        if (session->watchThread.joinable()) session->watchThread.join();
        mailimap_free(session->watch);
        session->watch = nullptr;
        throwFailure(env, why);
    }
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStopWatch(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return;
    if (tlsWatch != nullptr && reinterpret_cast<jlong>(tlsWatch) == handle) {
        tlsWatch->watchStop.store(true);
        return;
    }
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    stopWatchLocked(session, env);
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSmtp(JNIEnv * env, jobject, jlong handle,
    jbyteArray message, jobjectArray recipients) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    mailsmtp * smtp = mailsmtp_new(0, nullptr);
    if (smtp == nullptr) {
        throwFailure(env, "smtp error");
        unlockSession(session);
        return;
    }
    mailsmtp_set_timeout(smtp, 60);
    int r = mailsmtp_socket_connect(smtp, session->smtpHost.c_str(), static_cast<uint16_t>(session->smtpPort));
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = asciiSafe(smtp->response, "connection failed");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    r = mailsmtp_helo(smtp);
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = asciiSafe(smtp->response, "smtp error");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    r = mailsmtp_mail(smtp, session->from.c_str());
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = asciiSafe(smtp->response, "smtp error");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    jsize nrcpt = recipients != nullptr ? env->GetArrayLength(recipients) : 0;
    for (jsize i = 0; i < nrcpt; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(recipients, i));
        JChars rcpt(env, js);
        r = mailsmtp_rcpt(smtp, rcpt.c());
        if (js != nullptr) env->DeleteLocalRef(js);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = asciiSafe(smtp->response, "smtp error");
            mailsmtp_free(smtp);
            throwFailure(env, why);
            unlockSession(session);
            return;
        }
    }
    r = mailsmtp_data(smtp);
    if (r == MAILSMTP_NO_ERROR) {
        jsize n = message != nullptr ? env->GetArrayLength(message) : 0;
        jbyte * bytes = n > 0 ? env->GetByteArrayElements(message, nullptr) : nullptr;
        r = mailsmtp_data_message(smtp, bytes != nullptr ? reinterpret_cast<char *>(bytes) : "", static_cast<size_t>(n));
        if (bytes != nullptr) env->ReleaseByteArrayElements(message, bytes, JNI_ABORT);
    }
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = asciiSafe(smtp->response, "smtp error");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    mailsmtp_quit(smtp);
    mailsmtp_free(smtp);
    unlockSession(session);
}
