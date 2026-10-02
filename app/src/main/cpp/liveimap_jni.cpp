#include <libetpan/libetpan.h>
#include <libetpan/unselect.h>

#include <jni.h>

#include <poll.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <climits>
#include <cstring>
#include <ctime>
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
int mailimap_status_send(mailstream * fd, const char * mb,
    struct mailimap_status_att_list * status_att_list);
int mailimap_space_send(mailstream * fd);
int mailimap_token_send(mailstream * fd, const char * atom);
int mailimap_mailbox_send(mailstream * fd, const char * mb);
int mailimap_flag_list_send(mailstream * fd, struct mailimap_flag_list * flag_list);
int mailimap_astring_send(mailstream * fd, const char * astring);
int mailimap_select_send(mailstream * fd, const char * mb, int condstore);
int mailimap_oparenth_send(mailstream * fd);
int mailimap_cparenth_send(mailstream * fd);
int mailimap_number_send(mailstream * fd, uint32_t number);
int mailimap_uint64_send(mailstream * fd, uint64_t number);
int mailimap_list_mailbox_send(mailstream * fd, const char * pattern);
int mailimap_search_key_send(mailstream * fd, struct mailimap_search_key * key);
int mailimap_nstring_parse(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * parser_ctx,
    size_t * indx, char ** result, size_t * result_len, size_t progr_rate, progress_function * progr_fun);
int mailimap_atom_parse(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * parser_ctx,
    size_t * indx, char ** result, size_t progr_rate, progress_function * progr_fun);
}

namespace {

struct LiveThread {
    int has_uid;
    uint32_t uid;
    clist * children;
};

enum {
    LIVE_PREVIEW = 1,
    LIVE_BINARY = 2,
    LIVE_ESEARCH = 3,
};

struct LiveBlob {
    char * data;
    size_t len;
    int isNil;
};

struct ResyncState {
    uint32_t uidvalidity = 0;
    uint64_t modseq = 0;
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
    bool qresync = false;
    std::map<std::string, ResyncState> resyncByMailbox;

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
    jclass integerCls = nullptr;
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
    jmethodID integerValueOf = nullptr;
    jmethodID onWatch = nullptr;
    jfieldID personal = nullptr;
    jfieldID other = nullptr;
    jfieldID shared = nullptr;
};

JniCache gJni;
std::once_flag gExtOnce;
thread_local LiveSession * tlsWatch = nullptr;
thread_local mailimap * tlsImap = nullptr;

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

static int live_ext_parse(int calling_parser, mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx,
    struct mailimap_extension_data ** result, size_t progr_rate, progress_function * progr_fun);
static void live_ext_free(struct mailimap_extension_data * ext_data);

mailimap_extension_api liveimap_extra_extension = {
    const_cast<char *>("LIVEIMAP"),
    -1,
    live_ext_parse,
    live_ext_free,
};

void registerExtensions() {
    std::call_once(gExtOnce, [] {
        mailimap_extension_register(&mailimap_extension_sort);
        mailimap_extension_register(&mailimap_extension_namespace);
        mailimap_extension_register(&mailimap_extension_uidplus);
        mailimap_extension_register(&mailimap_extension_enable);
        mailimap_extension_register(&mailimap_extension_condstore);
        mailimap_extension_register(&mailimap_extension_qresync);
        mailimap_extension_register(&liveimap_thread_extension);
        mailimap_extension_register(&liveimap_extra_extension);
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
    local = env->FindClass("java/lang/Integer");
    gJni.integerCls = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    gJni.indexRowInit = env->GetMethodID(gJni.indexRow, "<init>",
        "(JILjava/util/Set;JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V");
    gJni.folderInit = env->GetMethodID(gJni.folderEntry, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;ZCLjava/lang/String;Ljava/lang/Integer;Ljava/lang/Integer;)V");
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
    gJni.integerValueOf = env->GetStaticMethodID(gJni.integerCls, "valueOf", "(I)Ljava/lang/Integer;");
    gJni.personal = env->GetStaticFieldID(gJni.nsKind, "Personal",
        "Lorg/dlang/liveimap/session/NamespaceKind;");
    gJni.other = env->GetStaticFieldID(gJni.nsKind, "Other",
        "Lorg/dlang/liveimap/session/NamespaceKind;");
    gJni.shared = env->GetStaticFieldID(gJni.nsKind, "Shared",
        "Lorg/dlang/liveimap/session/NamespaceKind;");
    jclass sessionCls = env->FindClass("org/dlang/liveimap/engine/LibetpanMailSession");
    gJni.onWatch = env->GetMethodID(sessionCls, "onNativeWatch", "(IIJ[Ljava/lang/String;)V");
    env->DeleteLocalRef(sessionCls);
    gJni.ready = gJni.mailFailure != nullptr && gJni.indexRowInit != nullptr &&
        gJni.folderInit != nullptr && gJni.onWatch != nullptr && gJni.integerValueOf != nullptr;
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

struct mailimap_fetch_type * indexFetchType(bool withStructure, bool withPreview) {
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
    if (withPreview) {
        char * name = strdup("PREVIEW");
        struct mailimap_fetch_att * att = name != nullptr
            ? mailimap_fetch_att_new(MAILIMAP_FETCH_ATT_EXTENSION, nullptr, 0, 0, name) : nullptr;
        if (att == nullptr) {
            free(name);
        } else {
            mailimap_fetch_type_new_fetch_att_list_add(fetch, att);
        }
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
    return nullptr;
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
        struct mailimap_flag * flag = nullptr;
        {
            JChars chars(env, js);
            if (strcmp(chars.c(), "$Forwarded") == 0) {
                char * literal = strdup("$Forwarded");
                if (literal != nullptr) {
                    flag = mailimap_flag_new_flag_keyword(literal);
                    if (flag == nullptr) {
                        free(literal);
                    }
                }
            } else {
                flag = flagFromName(chars.c());
            }
        }
        if (js != nullptr) {
            env->DeleteLocalRef(js);
        }
        if (flag == nullptr) {
            mailimap_flag_list_free(*out);
            *out = nullptr;
            return false;
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
    bool toMe = false;
    bool hasAttachment = false;
    std::string preview;
    bool hasPreview = false;
};

bool sameMailbox(const char * mailbox, const char * host, const char * email) {
    if (email == nullptr || email[0] == 0) {
        return false;
    }
    if (mailbox == nullptr || host == nullptr || mailbox[0] == 0 || host[0] == 0) {
        return false;
    }
    std::string combined(mailbox);
    combined.push_back('@');
    combined += host;
    return strcasecmp(combined.c_str(), email) == 0;
}

bool addressListMatches(clist * list, const char * email) {
    if (list == nullptr) {
        return false;
    }
    for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
        auto * addr = static_cast<struct mailimap_address *>(clist_content(cur));
        if (addr != nullptr && sameMailbox(addr->ad_mailbox_name, addr->ad_host_name, email)) {
            return true;
        }
    }
    return false;
}

bool envelopeToMatches(struct mailimap_envelope * env, const char * email) {
    if (env == nullptr || email == nullptr || email[0] == 0) {
        return false;
    }
    if (env->env_to != nullptr && addressListMatches(env->env_to->to_list, email)) {
        return true;
    }
    if (env->env_cc != nullptr && addressListMatches(env->env_cc->cc_list, email)) {
        return true;
    }
    return false;
}

bool countsAsAttachment(const char * mediaType, const char * disposition, const char * filename) {
    if (disposition != nullptr && disposition[0] != 0 && strcasecmp(disposition, "attachment") == 0) {
        return true;
    }
    if (filename != nullptr && filename[0] != 0) {
        return true;
    }
    if (mediaType == nullptr || mediaType[0] == 0) {
        return false;
    }
    if (strcasecmp(mediaType, "text") == 0 || strcasecmp(mediaType, "multipart") == 0) {
        return false;
    }
    return true;
}

bool structureHasAttachment(struct mailimap_body * body) {
    if (body == nullptr) {
        return false;
    }
    if (body->bd_type == MAILIMAP_BODY_MPART && body->bd_data.bd_body_mpart != nullptr) {
        struct mailimap_body_type_mpart * mpart = body->bd_data.bd_body_mpart;
        struct mailimap_body_fld_dsp * disp = nullptr;
        const char * filename = nullptr;
        if (mpart->bd_ext_mpart != nullptr) {
            disp = mpart->bd_ext_mpart->bd_disposition;
            filename = paramValue(mpart->bd_ext_mpart->bd_parameter, "name");
        }
        if (disp != nullptr) {
            if (filename == nullptr) {
                filename = paramValue(disp->dsp_attributes, "filename");
            }
            if (filename == nullptr) {
                filename = paramValue(disp->dsp_attributes, "name");
            }
        }
        const char * dispType = disp != nullptr ? disp->dsp_type : nullptr;
        if (countsAsAttachment("multipart", dispType, filename)) {
            return true;
        }
        if (mpart->bd_list != nullptr) {
            for (clistiter * cur = clist_begin(mpart->bd_list); cur != nullptr; cur = clist_next(cur)) {
                if (structureHasAttachment(static_cast<struct mailimap_body *>(clist_content(cur)))) {
                    return true;
                }
            }
        }
        return false;
    }
    if (body->bd_type != MAILIMAP_BODY_1PART || body->bd_data.bd_body_1part == nullptr) {
        return false;
    }
    struct mailimap_body_type_1part * part = body->bd_data.bd_body_1part;
    std::string type;
    std::string subtype;
    describe1(part, &type, &subtype);
    struct mailimap_body_fields * fields = fieldsOf1(part);
    struct mailimap_body_fld_dsp * disp = part->bd_ext_1part != nullptr ? part->bd_ext_1part->bd_disposition : nullptr;
    const char * filename = filenameOf(fields, disp);
    const char * dispType = disp != nullptr ? disp->dsp_type : nullptr;
    if (countsAsAttachment(type.c_str(), dispType, filename)) {
        return true;
    }
    if (part->bd_type == MAILIMAP_BODY_TYPE_1PART_MSG && part->bd_data.bd_type_msg != nullptr) {
        return structureHasAttachment(part->bd_data.bd_type_msg->bd_body);
    }
    return false;
}

void readAtt(struct mailimap_msg_att * att, Row * row, const char * accountEmail) {
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
                    row->toMe = envelopeToMatches(env, accountEmail);
                }
                break;
            case MAILIMAP_MSG_ATT_BODYSTRUCTURE:
                row->structure = st->att_data.att_bodystructure;
                row->hasAttachment = structureHasAttachment(row->structure);
                break;
            default:
                break;
            }
        } else if (item->att_type == MAILIMAP_MSG_ATT_ITEM_EXTENSION && item->att_data.att_extension_data != nullptr) {
            struct mailimap_extension_data * ext = item->att_data.att_extension_data;
            if (ext->ext_extension == &liveimap_extra_extension && ext->ext_type == LIVE_PREVIEW) {
                auto * blob = static_cast<LiveBlob *>(ext->ext_data);
                row->hasPreview = true;
                if (blob != nullptr && blob->isNil == 0 && blob->data != nullptr) {
                    row->preview.assign(blob->data, blob->len);
                }
            }
        }
    }
}

int fetchRows(mailimap * imap, struct mailimap_set * set, bool uidFetch, bool withStructure, bool withPreview, clist ** out) {
    *out = nullptr;
    struct mailimap_fetch_type * fetch = indexFetchType(withStructure, withPreview);
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
        from, subject, date, prev,
        static_cast<jboolean>(row.toMe), static_cast<jboolean>(row.hasAttachment));
    env->DeleteLocalRef(flags);
    env->DeleteLocalRef(from);
    env->DeleteLocalRef(subject);
    env->DeleteLocalRef(date);
    if (prev != nullptr) env->DeleteLocalRef(prev);
    return obj;
}

std::vector<Row> rowsFromList(clist * list, const char * accountEmail) {
    std::vector<Row> rows;
    if (list == nullptr) {
        return rows;
    }
    for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
        Row row;
        readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row, accountEmail);
        if (row.uid != 0) {
            rows.push_back(row);
        }
    }
    return rows;
}

int selectFullCapture(mailimap * imap, const char * mailbox, uint64_t * modseq);
int selectQresyncCapture(mailimap * imap, const char * mailbox, uint32_t uidvalidity,
    uint64_t knownModseq, uint64_t * modseq);

bool selectMailbox(JNIEnv * env, LiveSession * session, const char * mailbox) {
    int r;
    uint64_t mod = 0;
    std::string name = mailbox != nullptr ? mailbox : "";
    auto stored = session->resyncByMailbox.find(name);
    bool haveStored = stored != session->resyncByMailbox.end()
        && stored->second.uidvalidity != 0 && stored->second.modseq != 0;
    if (session->qresync && haveStored) {
        r = selectQresyncCapture(session->imap, mailbox, stored->second.uidvalidity, stored->second.modseq, &mod);
    } else if (session->qresync) {
        r = selectFullCapture(session->imap, mailbox, &mod);
    } else {
        r = mailimap_select(session->imap, mailbox);
    }
    if (!cmdOk(r)) {
        throwImap(env, session->imap, "select failed");
        return false;
    }
    if (session->qresync) {
        struct mailimap_selection_info * info = session->imap->imap_selection_info;
        if (info != nullptr && info->sel_uidvalidity != 0 && mod != 0) {
            session->resyncByMailbox[name] = ResyncState{info->sel_uidvalidity, mod};
        } else if (haveStored && mod == 0) {
            session->resyncByMailbox.erase(name);
        }
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
    const char * wire = nullptr;
    if (algorithm != nullptr) {
        if (strcasecmp(algorithm, "REFERENCES") == 0) wire = "REFERENCES";
        else if (strcasecmp(algorithm, "ORDEREDSUBJECT") == 0) wire = "ORDEREDSUBJECT";
    }
    if (wire == nullptr) return MAILIMAP_ERROR_INVAL;
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
    r = mailimap_token_send(imap->imap_stream, wire);
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

int taggedCondAt(const char * line) {
    if (line == nullptr || line[0] == 0 || line[0] == '*' || line[0] == '+') return 0;
    const char * p = line;
    while (*p != 0 && *p != ' ' && *p != '\r' && *p != '\n') p++;
    if (p == line || *p != ' ') return 0;
    ++p;
    if (strncasecmp(p, "OK", 2) == 0) {
        char n = p[2];
        if (n == 0 || n == ' ' || n == '\r' || n == '\n') return 1;
    }
    if (strncasecmp(p, "NO", 2) == 0) {
        char n = p[2];
        if (n == 0 || n == ' ' || n == '\r' || n == '\n') return 2;
    }
    if (strncasecmp(p, "BAD", 3) == 0) {
        char n = p[3];
        if (n == 0 || n == ' ' || n == '\r' || n == '\n') return 3;
    }
    return 0;
}

int taggedCondInBuffer(mailimap * imap) {
    if (imap == nullptr || imap->imap_stream_buffer == nullptr || imap->imap_stream_buffer->str == nullptr) return 0;
    const char * p = imap->imap_stream_buffer->str;
    int found = 0;
    while (*p != 0) {
        int kind = taggedCondAt(p);
        if (kind != 0) found = kind;
        const char * nl = strchr(p, '\n');
        if (nl == nullptr) break;
        p = nl + 1;
    }
    return found;
}

int appendLiteralPlus(mailimap * imap, const char * mailbox, const char * bytes, size_t size,
    struct mailimap_flag_list * flags) {
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "APPEND");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_mailbox_send(imap->imap_stream, mailbox);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (flags != nullptr) {
        r = mailimap_space_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_flag_list_send(imap->imap_stream, flags);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
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
    imap->imap_response = nullptr;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    int seen = taggedCondInBuffer(imap);
    // Tagged OK, including APPENDUID, is already buffered. Parsing would read another line.
    if (seen == 1) return MAILIMAP_NO_ERROR;
    if (seen == 2 || seen == 3) return MAILIMAP_ERROR_APPEND;
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(imap, &response);
    if (r != MAILIMAP_NO_ERROR) {
        if (taggedCondInBuffer(imap) == 1) return MAILIMAP_NO_ERROR;
        return r;
    }
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (ok || taggedCondInBuffer(imap) == 1) return MAILIMAP_NO_ERROR;
    return MAILIMAP_ERROR_APPEND;
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
            readAtt(msg->mdt_msg_att, &row, nullptr);
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

const int kConnectTimeoutSec = 60;

std::string serviceLabel(const char * proto, const char * host, int port) {
    std::string label = proto != nullptr ? proto : "";
    label.push_back(' ');
    if (host != nullptr) label += host;
    label.push_back(':');
    label += std::to_string(port);
    return label;
}

std::string errorText(int err) {
    const char * text = strerror(err);
    if (text == nullptr || text[0] == 0) return "connection failed";
    return text;
}

std::string numericAddress(const struct addrinfo * ai) {
    char buf[INET6_ADDRSTRLEN];
    const void * src = nullptr;
    if (ai != nullptr && ai->ai_addr != nullptr && ai->ai_family == AF_INET) {
        src = &reinterpret_cast<const struct sockaddr_in *>(ai->ai_addr)->sin_addr;
    } else if (ai != nullptr && ai->ai_addr != nullptr && ai->ai_family == AF_INET6) {
        src = &reinterpret_cast<const struct sockaddr_in6 *>(ai->ai_addr)->sin6_addr;
    }
    if (src == nullptr || inet_ntop(ai->ai_family, src, buf, sizeof(buf)) == nullptr) {
        return "?";
    }
    return buf;
}

std::string connectFailure(const char * proto, const char * host, int port,
    const std::string & address, const std::string & detail) {
    return serviceLabel(proto, host, port) + " (" + address + "): " + detail;
}

int connectOne(const struct addrinfo * ai, int timeoutSec, std::string * why) {
    if (ai == nullptr || ai->ai_addr == nullptr) {
        *why = "connection failed";
        return -1;
    }
    int fd = socket(ai->ai_family, ai->ai_socktype != 0 ? ai->ai_socktype : SOCK_STREAM, ai->ai_protocol);
    if (fd < 0) {
        *why = errorText(errno);
        return -1;
    }
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        *why = errorText(errno);
        close(fd);
        return -1;
    }
    int rc = connect(fd, ai->ai_addr, ai->ai_addrlen);
    bool ready = rc == 0;
    if (rc < 0 && errno != EINPROGRESS && errno != EINTR) {
        *why = errorText(errno);
        close(fd);
        return -1;
    }
    if (rc < 0) {
        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(timeoutSec);
        while (true) {
            auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now()).count();
            if (left <= 0) {
                *why = "timed out";
                close(fd);
                return -1;
            }
            struct pollfd pfd{};
            pfd.fd = fd;
            pfd.events = POLLOUT;
            int waitMs = left < 60000 ? static_cast<int>(left) : 60000;
            int pr = poll(&pfd, 1, waitMs);
            if (pr == 0) {
                *why = "timed out";
                close(fd);
                return -1;
            }
            if (pr < 0) {
                if (errno == EINTR) continue;
                *why = errorText(errno);
                close(fd);
                return -1;
            }
            if ((pfd.revents & POLLOUT) != 0) ready = true;
            break;
        }
    }
    int soerr = 0;
    socklen_t len = sizeof(soerr);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &len) < 0) {
        *why = errorText(errno);
        close(fd);
        return -1;
    }
    if (soerr != 0) {
        *why = errorText(soerr);
        close(fd);
        return -1;
    }
    if (!ready) {
        *why = "connection failed";
        close(fd);
        return -1;
    }
    // mailstream_socket_open_timeout requires a blocking fd.
    int cur = fcntl(fd, F_GETFL, 0);
    if (cur < 0 || fcntl(fd, F_SETFL, cur & ~O_NONBLOCK) < 0) {
        *why = errorText(errno);
        close(fd);
        return -1;
    }
    return fd;
}

int tcpConnect(const char * proto, const char * host, int port, std::string * address, std::string * error) {
    struct addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    struct addrinfo * res = nullptr;
    std::string portText = std::to_string(port);
    const char * lookup = host != nullptr ? host : "";
    int gai = getaddrinfo(lookup, portText.c_str(), &hints, &res);
    if (gai != 0) {
        const char * text = gai_strerror(gai);
        *error = serviceLabel(proto, host, port) + ": name lookup failed: "
            + (text != nullptr && text[0] != 0 ? text : "unknown");
        return -1;
    }
    std::string failures;
    int fd = -1;
    std::string chosen;
    for (struct addrinfo * ai = res; ai != nullptr; ai = ai->ai_next) {
        std::string numeric = numericAddress(ai);
        std::string why;
        int one = connectOne(ai, kConnectTimeoutSec, &why);
        if (one >= 0) {
            fd = one;
            chosen = std::move(numeric);
            break;
        }
        if (!failures.empty()) failures += "; ";
        failures += connectFailure(proto, host, port, numeric, why);
    }
    if (res != nullptr) freeaddrinfo(res);
    if (fd < 0) {
        *error = failures.empty()
            ? serviceLabel(proto, host, port) + ": name lookup failed: no address"
            : failures;
        return -1;
    }
    *address = std::move(chosen);
    return fd;
}

mailimap * openPlain(const char * host, int port, const char * user, const char * password,
    std::string * error, std::string * connectedAddress) {
    mailimap * imap = mailimap_new(0, nullptr);
    if (imap == nullptr) {
        *error = "imap error";
        return nullptr;
    }
    mailimap_set_timeout(imap, kConnectTimeoutSec);
    std::string address;
    int fd = tcpConnect("IMAP", host, port, &address, error);
    if (fd < 0) {
        mailimap_free(imap);
        return nullptr;
    }
    mailstream * stream = mailstream_socket_open_timeout(fd, kConnectTimeoutSec);
    if (stream == nullptr) {
        close(fd);
        *error = connectFailure("IMAP", host, port, address, "connection failed");
        mailimap_free(imap);
        return nullptr;
    }
    int r = mailimap_connect(imap, stream);
    if (!connectOk(r)) {
        *error = connectFailure("IMAP", host, port, address, imapText(imap, "connection failed"));
        if (imap->imap_stream != nullptr) {
            mailstream_close(imap->imap_stream);
            imap->imap_stream = nullptr;
        } else {
            mailstream_close(stream);
        }
        mailimap_free(imap);
        return nullptr;
    }
    r = mailimap_login(imap, user, password);
    if (!cmdOk(r)) {
        const char * name = user != nullptr ? user : "";
        *error = serviceLabel("IMAP", host, port) + " (" + address + ") user " + name
            + ": login failed: " + imapText(imap, "login failed");
        mailimap_free(imap);
        return nullptr;
    }
    if (connectedAddress != nullptr) *connectedAddress = address;
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

uint64_t highestModseq(mailimap * imap) {
    uint64_t mod = 0;
    if (imap == nullptr || imap->imap_response_info == nullptr || imap->imap_response_info->rsp_extension_list == nullptr) {
        return 0;
    }
    for (clistiter * cur = clist_begin(imap->imap_response_info->rsp_extension_list); cur != nullptr; cur = clist_next(cur)) {
        auto * ext = static_cast<struct mailimap_extension_data *>(clist_content(cur));
        if (ext == nullptr || ext->ext_extension == nullptr) continue;
        if (ext->ext_extension->ext_id != MAILIMAP_EXTENSION_CONDSTORE) continue;
        if (ext->ext_type != MAILIMAP_CONDSTORE_TYPE_RESP_TEXT_CODE) continue;
        auto * code = static_cast<struct mailimap_condstore_resptextcode *>(ext->ext_data);
        if (code == nullptr) continue;
        if (code->cs_type == MAILIMAP_CONDSTORE_RESPTEXTCODE_HIGHESTMODSEQ) {
            mod = code->cs_data.cs_modseq_value;
        } else if (code->cs_type == MAILIMAP_CONDSTORE_RESPTEXTCODE_NOMODSEQ) {
            mod = 0;
        }
    }
    return mod;
}

void freeExtensionList(mailimap * imap) {
    if (imap == nullptr || imap->imap_response_info == nullptr || imap->imap_response_info->rsp_extension_list == nullptr) {
        return;
    }
    clist_foreach(imap->imap_response_info->rsp_extension_list,
        reinterpret_cast<clist_func>(mailimap_extension_data_free), nullptr);
    clist_free(imap->imap_response_info->rsp_extension_list);
    imap->imap_response_info->rsp_extension_list = nullptr;
}

int selectQresyncCapture(mailimap * imap, const char * mailbox, uint32_t uidvalidity,
    uint64_t knownModseq, uint64_t * modseq) {
    *modseq = 0;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "SELECT");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_mailbox_send(imap->imap_stream, mailbox);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_oparenth_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "QRESYNC");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_oparenth_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_number_send(imap->imap_stream, uidvalidity);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_uint64_send(imap->imap_stream, knownModseq);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_cparenth_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_cparenth_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    if (imap->imap_selection_info != nullptr) {
        mailimap_selection_info_free(imap->imap_selection_info);
    }
    imap->imap_selection_info = mailimap_selection_info_new();
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(imap, &response);
    if (r != MAILIMAP_NO_ERROR) return r;
    uint64_t parsed = highestModseq(imap);
    freeExtensionList(imap);
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) {
        if (imap->imap_selection_info != nullptr) {
            mailimap_selection_info_free(imap->imap_selection_info);
            imap->imap_selection_info = nullptr;
        }
        imap->imap_state = MAILIMAP_STATE_AUTHENTICATED;
        return MAILIMAP_ERROR_SELECT;
    }
    imap->imap_state = MAILIMAP_STATE_SELECTED;
    *modseq = parsed;
    return MAILIMAP_NO_ERROR;
}

int selectFullCapture(mailimap * imap, const char * mailbox, uint64_t * modseq) {
    *modseq = 0;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_select_send(imap->imap_stream, mailbox, 0);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    if (imap->imap_selection_info != nullptr) {
        mailimap_selection_info_free(imap->imap_selection_info);
    }
    imap->imap_selection_info = mailimap_selection_info_new();
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(imap, &response);
    if (r != MAILIMAP_NO_ERROR) return r;
    uint64_t mod = highestModseq(imap);
    freeExtensionList(imap);
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) {
        if (imap->imap_selection_info != nullptr) {
            mailimap_selection_info_free(imap->imap_selection_info);
            imap->imap_selection_info = nullptr;
        }
        imap->imap_state = MAILIMAP_STATE_AUTHENTICATED;
        return MAILIMAP_ERROR_SELECT;
    }
    imap->imap_state = MAILIMAP_STATE_SELECTED;
    *modseq = mod;
    return MAILIMAP_NO_ERROR;
}

void enableNamed(LiveSession * session, const char * capName) {
    if (session == nullptr || session->imap == nullptr || capName == nullptr) return;
    const char * wire = nullptr;
    if (strcasecmp(capName, "QRESYNC") == 0) wire = "QRESYNC";
    else if (strcasecmp(capName, "CONDSTORE") == 0) wire = "CONDSTORE";
    if (wire == nullptr) return;
    clist * list = clist_new();
    if (list == nullptr) return;
    char * owned = strdup(wire);
    struct mailimap_capability * cap = owned != nullptr
        ? mailimap_capability_new(MAILIMAP_CAPABILITY_NAME, nullptr, owned) : nullptr;
    if (cap == nullptr || clist_append(list, cap) != 0) {
        if (cap != nullptr) mailimap_capability_free(cap);
        else free(owned);
        clist_free(list);
        return;
    }
    struct mailimap_capability_data * data = mailimap_capability_data_new(list);
    if (data == nullptr) {
        mailimap_capability_free(cap);
        clist_free(list);
        return;
    }
    struct mailimap_capability_data * result = nullptr;
    int r = mailimap_enable(session->imap, data, &result);
    mailimap_capability_data_free(data);
    if (r != MAILIMAP_NO_ERROR) return;
    if (result != nullptr) mailimap_capability_data_free(result);
    if (strcmp(wire, "QRESYNC") == 0) session->qresync = true;
}

void compressSession(LiveSession * session) {
    if (session == nullptr || session->imap == nullptr) return;
    (void)mailimap_compress(session->imap);
}

int sendWord(mailstream * fd, const char * word, bool leadSpace) {
    if (leadSpace) {
        int r = mailimap_space_send(fd);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    return mailimap_token_send(fd, word);
}

struct FolderCounts {
    bool hasMessages = false;
    bool hasUnseen = false;
    int messages = 0;
    int unseen = 0;
};

int decimalValue(const std::string & num) {
    long value = 0;
    for (char c : num) {
        if (c < '0' || c > '9') break;
        value = value * 10 + (c - '0');
        if (value > 2147483647L) return 2147483647;
    }
    return static_cast<int>(value);
}

bool readImapMailbox(const std::string & raw, size_t * index, std::string * box) {
    size_t i = *index;
    if (i >= raw.size()) return false;
    if (raw[i] == '"') {
        ++i;
        std::string out;
        while (i < raw.size()) {
            char c = raw[i++];
            if (c == '\\' && i < raw.size()) {
                out.push_back(raw[i++]);
                continue;
            }
            if (c == '"') break;
            out.push_back(c);
        }
        *box = out;
        *index = i;
        return true;
    }
    if (raw[i] == '{') {
        ++i;
        size_t n = 0;
        while (i < raw.size() && raw[i] >= '0' && raw[i] <= '9') {
            n = n * 10 + static_cast<size_t>(raw[i] - '0');
            ++i;
        }
        if (i < raw.size() && raw[i] == '}') ++i;
        if (i < raw.size() && raw[i] == '\r') ++i;
        if (i < raw.size() && raw[i] == '\n') ++i;
        if (i + n > raw.size()) return false;
        *box = raw.substr(i, n);
        *index = i + n;
        return true;
    }
    size_t start = i;
    while (i < raw.size() && raw[i] != ' ' && raw[i] != '(' && raw[i] != '\r' && raw[i] != '\n') ++i;
    if (i == start) return false;
    *box = raw.substr(start, i - start);
    *index = i;
    return true;
}

void collectStatus(const std::string & raw, std::map<std::string, FolderCounts> * out) {
    size_t i = 0;
    while (i < raw.size()) {
        bool atLine = i == 0 || raw[i - 1] == '\n';
        if (atLine && i + 9 <= raw.size() && raw.compare(i, 9, "* STATUS ") == 0) {
            i += 9;
            std::string box;
            if (!readImapMailbox(raw, &i, &box)) return;
            while (i < raw.size() && raw[i] == ' ') ++i;
            if (i >= raw.size() || raw[i] != '(') continue;
            ++i;
            FolderCounts counts;
            while (i < raw.size() && raw[i] != ')') {
                while (i < raw.size() && raw[i] == ' ') ++i;
                size_t start = i;
                while (i < raw.size() && raw[i] != ' ' && raw[i] != ')') ++i;
                std::string att = raw.substr(start, i - start);
                while (i < raw.size() && raw[i] == ' ') ++i;
                size_t numStart = i;
                while (i < raw.size() && raw[i] >= '0' && raw[i] <= '9') ++i;
                std::string num = raw.substr(numStart, i - numStart);
                if (strcasecmp(att.c_str(), "MESSAGES") == 0 && !num.empty()) {
                    counts.hasMessages = true;
                    counts.messages = decimalValue(num);
                } else if (strcasecmp(att.c_str(), "UNSEEN") == 0 && !num.empty()) {
                    counts.hasUnseen = true;
                    counts.unseen = decimalValue(num);
                }
            }
            (*out)[box] = counts;
        }
        ++i;
    }
}

bool isListAttribute(const char * name) {
    const char * flag = name != nullptr ? name : "";
    if (flag[0] == '\\') ++flag;
    return strcasecmp(flag, "Noinferiors") == 0
        || strcasecmp(flag, "Noselect") == 0
        || strcasecmp(flag, "Marked") == 0
        || strcasecmp(flag, "Unmarked") == 0
        || strcasecmp(flag, "HasChildren") == 0
        || strcasecmp(flag, "HasNoChildren") == 0
        || strcasecmp(flag, "NonExistent") == 0
        || strcasecmp(flag, "Subscribed") == 0
        || strcasecmp(flag, "Remote") == 0;
}

std::string specialUseOf(struct mailimap_mailbox_list * mb) {
    std::string out;
    if (mb == nullptr || mb->mb_flag == nullptr || mb->mb_flag->mbf_oflags == nullptr) return out;
    for (clistiter * cur = clist_begin(mb->mb_flag->mbf_oflags); cur != nullptr; cur = clist_next(cur)) {
        auto * of = static_cast<struct mailimap_mbx_list_oflag *>(clist_content(cur));
        if (of == nullptr || of->of_type != MAILIMAP_MBX_LIST_OFLAG_FLAG_EXT || of->of_flag_ext == nullptr) continue;
        if (isListAttribute(of->of_flag_ext)) continue;
        if (of->of_flag_ext[0] == 0) continue;
        if (!out.empty()) out.push_back(' ');
        if (of->of_flag_ext[0] != '\\') out.push_back('\\');
        out += of->of_flag_ext;
    }
    return out;
}

int listMailboxes(mailimap * imap, const char * reference, bool extended, bool withMessages, bool withUnseen, clist ** result, std::string * raw) {
    *result = nullptr;
    if (raw != nullptr) raw->clear();
    const char * ref = reference != nullptr ? reference : "";
    if (!extended) return mailimap_list(imap, ref, "%", result);
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "LIST", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_mailbox_send(imap->imap_stream, ref);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_list_mailbox_send(imap->imap_stream, "%");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "RETURN", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "(", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "CHILDREN", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "SPECIAL-USE", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (withMessages) {
        r = sendWord(imap->imap_stream, "STATUS", true);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = sendWord(imap->imap_stream, "(", true);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = sendWord(imap->imap_stream, "MESSAGES", false);
        if (r != MAILIMAP_NO_ERROR) return r;
        if (withUnseen) {
            r = sendWord(imap->imap_stream, "UNSEEN", true);
            if (r != MAILIMAP_NO_ERROR) return r;
        }
        r = sendWord(imap->imap_stream, ")", false);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = sendWord(imap->imap_stream, ")", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(imap, &response);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (raw != nullptr && imap->imap_stream_buffer != nullptr && imap->imap_stream_buffer->str != nullptr) {
        *raw = imap->imap_stream_buffer->str;
    }
    bool ok = taggedOk(response);
    if (ok && imap->imap_response_info != nullptr) {
        *result = imap->imap_response_info->rsp_mailbox_list;
        imap->imap_response_info->rsp_mailbox_list = nullptr;
    }
    mailimap_response_free(response);
    return ok ? MAILIMAP_NO_ERROR : MAILIMAP_ERROR_LIST;
}

void freeUidList(clist * list) {
    if (list == nullptr) return;
    clist_foreach(list, reinterpret_cast<clist_func>(free), nullptr);
    clist_free(list);
}

bool appendUid(clist * list, uint32_t uid) {
    if (uid == 0 || list == nullptr) return true;
    auto * n = static_cast<uint32_t *>(malloc(sizeof(uint32_t)));
    if (n == nullptr) return false;
    *n = uid;
    if (clist_append(list, n) != 0) {
        free(n);
        return false;
    }
    return true;
}

uint32_t starUid() {
    if (tlsImap != nullptr && tlsImap->imap_selection_info != nullptr && tlsImap->imap_selection_info->sel_uidnext > 1) {
        return tlsImap->imap_selection_info->sel_uidnext - 1;
    }
    return 0;
}

int parseSeqNumber(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx, uint32_t * out) {
    if (peekChar(buffer, *indx) == '*') {
        *indx += 1;
        *out = starUid();
        return MAILIMAP_NO_ERROR;
    }
    return mailimap_nz_number_parse(fd, buffer, ctx, indx, out);
}

int parseSeqSet(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx, clist * list) {
    for (;;) {
        uint32_t first = 0;
        int r = parseSeqNumber(fd, buffer, ctx, indx, &first);
        if (r != MAILIMAP_NO_ERROR) return r;
        uint32_t last = first;
        if (peekChar(buffer, *indx) == ':') {
            *indx += 1;
            r = parseSeqNumber(fd, buffer, ctx, indx, &last);
            if (r != MAILIMAP_NO_ERROR) return r;
        }
        if (first > last) {
            uint32_t tmp = first;
            first = last;
            last = tmp;
        }
        if (static_cast<uint64_t>(last) - first > 1000000u) {
            if (!appendUid(list, first) || !appendUid(list, last)) return MAILIMAP_ERROR_MEMORY;
        } else {
            uint32_t n = first;
            for (;;) {
                if (!appendUid(list, n)) return MAILIMAP_ERROR_MEMORY;
                if (n == last) break;
                ++n;
            }
        }
        if (peekChar(buffer, *indx) != ',') break;
        *indx += 1;
    }
    return MAILIMAP_NO_ERROR;
}

int parseTagValue(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx) {
    char c = peekChar(buffer, *indx);
    if (c == '"' || c == '{') {
        char * text = nullptr;
        size_t len = 0;
        int r = mailimap_nstring_parse(fd, buffer, ctx, indx, &text, &len, 0, nullptr);
        free(text);
        return r;
    }
    char * atom = nullptr;
    int r = mailimap_atom_parse(fd, buffer, ctx, indx, &atom, 0, nullptr);
    free(atom);
    return r;
}

int parseEsearch(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    struct mailimap_extension_data ** result) {
    size_t cur = *indx;
    int r = mailimap_token_case_insensitive_parse(fd, buffer, &cur, "ESEARCH");
    if (r != MAILIMAP_NO_ERROR) return r;
    clist * uids = clist_new();
    if (uids == nullptr) return MAILIMAP_ERROR_MEMORY;
    for (;;) {
        size_t save = cur;
        r = mailimap_space_parse(fd, buffer, &cur);
        if (r != MAILIMAP_NO_ERROR) {
            cur = save;
            break;
        }
        if (peekChar(buffer, cur) == '(') {
            r = mailimap_oparenth_parse(fd, buffer, ctx, &cur);
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            r = mailimap_token_case_insensitive_parse(fd, buffer, &cur, "TAG");
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            r = parseTagValue(fd, buffer, ctx, &cur);
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            r = mailimap_cparenth_parse(fd, buffer, ctx, &cur);
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            continue;
        }
        char * atom = nullptr;
        r = mailimap_atom_parse(fd, buffer, ctx, &cur, &atom, 0, nullptr);
        if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
        std::string name = atom != nullptr ? atom : "";
        free(atom);
        if (strcasecmp(name.c_str(), "UID") == 0) continue;
        if (strcasecmp(name.c_str(), "ALL") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            r = parseSeqSet(fd, buffer, ctx, &cur, uids);
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            continue;
        }
        if (strcasecmp(name.c_str(), "MIN") == 0 || strcasecmp(name.c_str(), "MAX") == 0
            || strcasecmp(name.c_str(), "COUNT") == 0 || strcasecmp(name.c_str(), "MODSEQ") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) { freeUidList(uids); return r; }
            while (peekChar(buffer, cur) >= '0' && peekChar(buffer, cur) <= '9') cur += 1;
            continue;
        }
        freeUidList(uids);
        return MAILIMAP_ERROR_PARSE;
    }
    struct mailimap_extension_data * ext = mailimap_extension_data_new(&liveimap_extra_extension, LIVE_ESEARCH, uids);
    if (ext == nullptr) {
        freeUidList(uids);
        return MAILIMAP_ERROR_MEMORY;
    }
    *result = ext;
    *indx = cur;
    return MAILIMAP_NO_ERROR;
}

LiveBlob * blobFromNstring(char * text, size_t len) {
    auto * blob = static_cast<LiveBlob *>(calloc(1, sizeof(LiveBlob)));
    if (blob == nullptr) {
        free(text);
        return nullptr;
    }
    if (text == nullptr) {
        blob->isNil = 1;
    } else {
        blob->data = text;
        blob->len = len;
    }
    return blob;
}

int parsePreviewAtt(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    struct mailimap_extension_data ** result) {
    size_t cur = *indx;
    int r = mailimap_token_case_insensitive_parse(fd, buffer, &cur, "PREVIEW");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_parse(fd, buffer, &cur);
    if (r != MAILIMAP_NO_ERROR) return r;
    char * text = nullptr;
    size_t len = 0;
    r = mailimap_nstring_parse(fd, buffer, ctx, &cur, &text, &len, 0, nullptr);
    if (r != MAILIMAP_NO_ERROR) return r;
    LiveBlob * blob = blobFromNstring(text, len);
    if (blob == nullptr) return MAILIMAP_ERROR_MEMORY;
    struct mailimap_extension_data * ext = mailimap_extension_data_new(&liveimap_extra_extension, LIVE_PREVIEW, blob);
    if (ext == nullptr) {
        free(blob->data);
        free(blob);
        return MAILIMAP_ERROR_MEMORY;
    }
    *result = ext;
    *indx = cur;
    return MAILIMAP_NO_ERROR;
}

int parseBinaryAtt(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    struct mailimap_extension_data ** result) {
    size_t cur = *indx;
    int r = mailimap_token_case_insensitive_parse(fd, buffer, &cur, "BINARY");
    if (r != MAILIMAP_NO_ERROR) return r;
    if (peekChar(buffer, cur) != '[') return MAILIMAP_ERROR_PARSE;
    cur += 1;
    while (peekChar(buffer, cur) != 0 && peekChar(buffer, cur) != ']') cur += 1;
    if (peekChar(buffer, cur) != ']') return MAILIMAP_ERROR_PARSE;
    cur += 1;
    if (peekChar(buffer, cur) == '<') {
        cur += 1;
        while (peekChar(buffer, cur) >= '0' && peekChar(buffer, cur) <= '9') cur += 1;
        if (peekChar(buffer, cur) != '>') return MAILIMAP_ERROR_PARSE;
        cur += 1;
    }
    r = mailimap_space_parse(fd, buffer, &cur);
    if (r != MAILIMAP_NO_ERROR) return r;
    char * text = nullptr;
    size_t len = 0;
    r = mailimap_nstring_parse(fd, buffer, ctx, &cur, &text, &len, 0, nullptr);
    if (r != MAILIMAP_NO_ERROR) return r;
    LiveBlob * blob = blobFromNstring(text, len);
    if (blob == nullptr) return MAILIMAP_ERROR_MEMORY;
    struct mailimap_extension_data * ext = mailimap_extension_data_new(&liveimap_extra_extension, LIVE_BINARY, blob);
    if (ext == nullptr) {
        free(blob->data);
        free(blob);
        return MAILIMAP_ERROR_MEMORY;
    }
    *result = ext;
    *indx = cur;
    return MAILIMAP_NO_ERROR;
}

static int live_ext_parse(int calling_parser, mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx,
    struct mailimap_extension_data ** result, size_t, progress_function *) {
    if (calling_parser == MAILIMAP_EXTENDED_PARSER_FETCH_DATA) {
        int r = parsePreviewAtt(fd, buffer, parser_ctx, indx, result);
        if (r != MAILIMAP_ERROR_PARSE) return r;
        return parseBinaryAtt(fd, buffer, parser_ctx, indx, result);
    }
    if (calling_parser == MAILIMAP_EXTENDED_PARSER_RESPONSE_DATA
        || calling_parser == MAILIMAP_EXTENDED_PARSER_MAILBOX_DATA) {
        return parseEsearch(fd, buffer, parser_ctx, indx, result);
    }
    return MAILIMAP_ERROR_PARSE;
}

static void live_ext_free(struct mailimap_extension_data * ext_data) {
    if (ext_data == nullptr) return;
    if (ext_data->ext_type == LIVE_PREVIEW || ext_data->ext_type == LIVE_BINARY) {
        auto * blob = static_cast<LiveBlob *>(ext_data->ext_data);
        if (blob != nullptr) {
            free(blob->data);
            free(blob);
        }
    } else if (ext_data->ext_type == LIVE_ESEARCH) {
        freeUidList(static_cast<clist *>(ext_data->ext_data));
    }
    free(ext_data);
}

clist * takeEsearch(mailimap * imap) {
    clist * uids = nullptr;
    if (imap->imap_response_info != nullptr && imap->imap_response_info->rsp_extension_list != nullptr) {
        for (clistiter * cur = clist_begin(imap->imap_response_info->rsp_extension_list); cur != nullptr; cur = clist_next(cur)) {
            auto * ext = static_cast<struct mailimap_extension_data *>(clist_content(cur));
            if (ext != nullptr && ext->ext_extension == &liveimap_extra_extension && ext->ext_type == LIVE_ESEARCH && uids == nullptr) {
                uids = static_cast<clist *>(ext->ext_data);
                ext->ext_data = nullptr;
                ext->ext_type = -1;
            }
        }
    }
    freeExtensionList(imap);
    if (uids == nullptr) uids = clist_new();
    return uids;
}

clist * takeSort(mailimap * imap) {
    clist * uids = nullptr;
    if (imap->imap_response_info != nullptr && imap->imap_response_info->rsp_extension_list != nullptr) {
        for (clistiter * cur = clist_begin(imap->imap_response_info->rsp_extension_list); cur != nullptr; cur = clist_next(cur)) {
            auto * ext = static_cast<struct mailimap_extension_data *>(clist_content(cur));
            if (ext != nullptr && ext->ext_extension != nullptr && ext->ext_extension->ext_id == MAILIMAP_EXTENSION_SORT && uids == nullptr) {
                uids = static_cast<clist *>(ext->ext_data);
                ext->ext_data = nullptr;
                ext->ext_type = -1;
            }
        }
    }
    freeExtensionList(imap);
    if (uids == nullptr) uids = clist_new();
    return uids;
}

int finishParsed(mailimap * imap, struct mailimap_response ** response) {
    int r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    tlsImap = imap;
    r = mailimap_parse_response(imap, response);
    tlsImap = nullptr;
    return r;
}

int sendUidEsearch(mailimap * imap, struct mailimap_search_key * key, struct mailimap_response ** response) {
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "UID", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "SEARCH", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "RETURN", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "(", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "ALL", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, ")", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "CHARSET", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_astring_send(imap->imap_stream, "UTF-8");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_search_key_send(imap->imap_stream, key);
    if (r != MAILIMAP_NO_ERROR) return r;
    return finishParsed(imap, response);
}

int sendUidSortChoice(mailimap * imap, const char * keyName, bool reverse, bool esort, struct mailimap_response ** response) {
    const char * key = nullptr;
    if (keyName != nullptr) {
        if (strcasecmp(keyName, "DATE") == 0) key = "DATE";
        else if (strcasecmp(keyName, "FROM") == 0) key = "FROM";
        else if (strcasecmp(keyName, "SUBJECT") == 0) key = "SUBJECT";
        else if (strcasecmp(keyName, "TO") == 0) key = "TO";
        else if (strcasecmp(keyName, "CC") == 0) key = "CC";
        else if (strcasecmp(keyName, "SIZE") == 0) key = "SIZE";
        else if (strcasecmp(keyName, "DISPLAY") == 0) key = "DISPLAY";
    }
    if (key == nullptr) return MAILIMAP_ERROR_INVAL;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "UID", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "SORT", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (esort) {
        r = sendWord(imap->imap_stream, "RETURN", true);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = sendWord(imap->imap_stream, "(", true);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = sendWord(imap->imap_stream, "ALL", false);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = sendWord(imap->imap_stream, ")", false);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = sendWord(imap->imap_stream, "(", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (reverse) {
        r = sendWord(imap->imap_stream, "REVERSE", false);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = sendWord(imap->imap_stream, key, reverse);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, ")", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_astring_send(imap->imap_stream, "US-ASCII");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "ALL", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    return finishParsed(imap, response);
}

jobjectArray emptyArray(JNIEnv * env, jclass cls) {
    return env->NewObjectArray(0, cls, nullptr);
}

// libetpan has no mailimap_search_key_new_new; the type constant is that key.
struct mailimap_search_key * searchKeyType(int type) {
    return mailimap_search_key_new(type, NULL, NULL,
        NULL, NULL, NULL, NULL, NULL, NULL,
        NULL, NULL, NULL, NULL, NULL,
        NULL, 0, NULL,
        NULL, NULL, NULL, NULL, NULL,
        0, NULL, NULL, NULL);
}

struct mailimap_search_key * notKey(struct mailimap_search_key * inner) {
    if (inner == nullptr) return nullptr;
    struct mailimap_search_key * key = mailimap_search_key_new_not(inner);
    if (key == nullptr) mailimap_search_key_free(inner);
    return key;
}

struct mailimap_search_key * keyOr(struct mailimap_search_key * left, struct mailimap_search_key * right) {
    if (left == nullptr || right == nullptr) {
        if (left != nullptr) mailimap_search_key_free(left);
        if (right != nullptr) mailimap_search_key_free(right);
        return nullptr;
    }
    struct mailimap_search_key * key = mailimap_search_key_new_or(left, right);
    if (key == nullptr) {
        mailimap_search_key_free(left);
        mailimap_search_key_free(right);
    }
    return key;
}

enum class TextSlot { From, To, Cc, Subject, Text, Keyword, Unkeyword };

struct mailimap_search_key * textKey(TextSlot slot, const char * argument) {
    char * copy = strdup(argument);
    if (copy == nullptr) return nullptr;
    struct mailimap_search_key * key = nullptr;
    switch (slot) {
    case TextSlot::From: key = mailimap_search_key_new_from(copy); break;
    case TextSlot::To: key = mailimap_search_key_new_to(copy); break;
    case TextSlot::Cc: key = mailimap_search_key_new_cc(copy); break;
    case TextSlot::Subject: key = mailimap_search_key_new_subject(copy); break;
    case TextSlot::Text: key = mailimap_search_key_new_text(copy); break;
    case TextSlot::Keyword: key = mailimap_search_key_new_keyword(copy); break;
    case TextSlot::Unkeyword: key = mailimap_search_key_new_unkeyword(copy); break;
    }
    if (key == nullptr) free(copy);
    return key;
}

struct mailimap_search_key * forwardedKey(bool negated) {
    char * literal = strdup("$Forwarded");
    if (literal == nullptr) return nullptr;
    struct mailimap_search_key * key = negated
        ? mailimap_search_key_new_unkeyword(literal)
        : mailimap_search_key_new_keyword(literal);
    if (key == nullptr) free(literal);
    return key;
}

bool sameKind(const char * kind, const char * name) {
    return strcmp(kind, name) == 0;
}

bool positiveUint(const char * text, uint32_t * out) {
    if (text == nullptr || text[0] == '\0') return false;
    uint64_t value = 0;
    for (const char * p = text; *p != '\0'; ++p) {
        if (*p < '0' || *p > '9') return false;
        value = value * 10u + static_cast<uint64_t>(*p - '0');
        if (value > 0xffffffffull) return false;
    }
    if (value == 0) return false;
    *out = static_cast<uint32_t>(value);
    return true;
}

bool calendarDate(const char * text, int * year, int * month, int * day) {
    if (text == nullptr || strlen(text) != 10) return false;
    if (text[4] != '-' || text[7] != '-') return false;
    for (int i = 0; i < 10; ++i) {
        if (i == 4 || i == 7) continue;
        if (text[i] < '0' || text[i] > '9') return false;
    }
    *year = (text[0] - '0') * 1000 + (text[1] - '0') * 100 + (text[2] - '0') * 10 + (text[3] - '0');
    *month = (text[5] - '0') * 10 + (text[6] - '0');
    *day = (text[8] - '0') * 10 + (text[9] - '0');
    if (*month < 1 || *month > 12 || *day < 1) return false;
    static const int mdays[] = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int maxDay = mdays[*month];
    bool leap = (*year % 4 == 0 && *year % 100 != 0) || (*year % 400 == 0);
    if (*month == 2 && leap) maxDay = 29;
    return *day <= maxDay;
}

int daysFromCivil(int y, unsigned m, unsigned d) {
    y -= m <= 2;
    const int era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = static_cast<unsigned>(y - era * 400);
    const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + static_cast<int>(doe) - 719468;
}

void civilFromDays(int z, int * year, int * month, int * day) {
    z += 719468;
    const int era = (z >= 0 ? z : z - 146096) / 146097;
    const unsigned doe = static_cast<unsigned>(z - era * 146097);
    const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    const int y = static_cast<int>(yoe) + era * 400;
    const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const unsigned mp = (5 * doy + 2) / 153;
    const unsigned d = doy - (153 * mp + 2) / 5 + 1;
    const unsigned m = mp < 10 ? mp + 3 : mp - 9;
    *year = y + (m <= 2);
    *month = static_cast<int>(m);
    *day = static_cast<int>(d);
}

bool ageBeforeDate(uint32_t daysBack, int * year, int * month, int * day) {
    std::time_t now = std::time(nullptr);
    if (now == static_cast<std::time_t>(-1)) return false;
    std::tm tm{};
    if (gmtime_r(&now, &tm) == nullptr) return false;
    int64_t z = daysFromCivil(tm.tm_year + 1900, static_cast<unsigned>(tm.tm_mon + 1), static_cast<unsigned>(tm.tm_mday));
    z -= static_cast<int64_t>(daysBack);
    if (z < static_cast<int64_t>(INT_MIN) || z > static_cast<int64_t>(INT_MAX)) return false;
    civilFromDays(static_cast<int>(z), year, month, day);
    return *year >= 0 && *year <= 9999;
}

struct mailimap_search_key * dateKey(int type, int year, int month, int day) {
    struct mailimap_date * date = mailimap_date_new(day, month, year);
    if (date == nullptr) return nullptr;
    struct mailimap_search_key * key = nullptr;
    if (type == MAILIMAP_SEARCH_KEY_SINCE) key = mailimap_search_key_new_since(date);
    else if (type == MAILIMAP_SEARCH_KEY_BEFORE) key = mailimap_search_key_new_before(date);
    else if (type == MAILIMAP_SEARCH_KEY_ON) key = mailimap_search_key_new_on(date);
    if (key == nullptr) mailimap_date_free(date);
    return key;
}

bool emptyText(JNIEnv * env, const char * argument) {
    if (argument[0] != '\0') return false;
    throwFailure(env, "empty search");
    return true;
}

struct mailimap_search_key * criterionKey(JNIEnv * env, const char * kind, const char * argument) {
    if (sameKind(kind, "New")) return searchKeyType(MAILIMAP_SEARCH_KEY_NEW);
    if (sameKind(kind, "NotNew")) return notKey(searchKeyType(MAILIMAP_SEARCH_KEY_NEW));
    if (sameKind(kind, "Deleted")) return searchKeyType(MAILIMAP_SEARCH_KEY_DELETED);
    if (sameKind(kind, "NotDeleted")) return searchKeyType(MAILIMAP_SEARCH_KEY_UNDELETED);
    if (sameKind(kind, "Answered")) return searchKeyType(MAILIMAP_SEARCH_KEY_ANSWERED);
    if (sameKind(kind, "NotAnswered")) return searchKeyType(MAILIMAP_SEARCH_KEY_UNANSWERED);
    if (sameKind(kind, "Important")) return searchKeyType(MAILIMAP_SEARCH_KEY_FLAGGED);
    if (sameKind(kind, "NotImportant")) return searchKeyType(MAILIMAP_SEARCH_KEY_UNFLAGGED);
    if (sameKind(kind, "Forwarded")) return forwardedKey(false);
    if (sameKind(kind, "NotForwarded")) return forwardedKey(true);
    if (sameKind(kind, "From") || sameKind(kind, "To") || sameKind(kind, "Cc") || sameKind(kind, "Subject")
        || sameKind(kind, "Text") || sameKind(kind, "Keyword") || sameKind(kind, "NotKeyword")
        || sameKind(kind, "Recipient") || sameKind(kind, "Participant")) {
        if (emptyText(env, argument)) return nullptr;
        if (sameKind(kind, "From")) return textKey(TextSlot::From, argument);
        if (sameKind(kind, "To")) return textKey(TextSlot::To, argument);
        if (sameKind(kind, "Cc")) return textKey(TextSlot::Cc, argument);
        if (sameKind(kind, "Subject")) return textKey(TextSlot::Subject, argument);
        if (sameKind(kind, "Text")) return textKey(TextSlot::Text, argument);
        if (sameKind(kind, "Keyword")) return textKey(TextSlot::Keyword, argument);
        if (sameKind(kind, "NotKeyword")) return textKey(TextSlot::Unkeyword, argument);
        if (sameKind(kind, "Recipient")) {
            return keyOr(textKey(TextSlot::To, argument), textKey(TextSlot::Cc, argument));
        }
        return keyOr(
            textKey(TextSlot::From, argument),
            keyOr(textKey(TextSlot::To, argument), textKey(TextSlot::Cc, argument)));
    }
    if (sameKind(kind, "Since") || sameKind(kind, "Before") || sameKind(kind, "On")) {
        int year = 0;
        int month = 0;
        int day = 0;
        if (!calendarDate(argument, &year, &month, &day)) {
            throwFailure(env, "bad search");
            return nullptr;
        }
        int type = MAILIMAP_SEARCH_KEY_ON;
        if (sameKind(kind, "Since")) type = MAILIMAP_SEARCH_KEY_SINCE;
        else if (sameKind(kind, "Before")) type = MAILIMAP_SEARCH_KEY_BEFORE;
        return dateKey(type, year, month, day);
    }
    if (sameKind(kind, "Age")) {
        uint32_t days = 0;
        int year = 0;
        int month = 0;
        int day = 0;
        if (!positiveUint(argument, &days) || !ageBeforeDate(days, &year, &month, &day)) {
            throwFailure(env, "bad search");
            return nullptr;
        }
        return dateKey(MAILIMAP_SEARCH_KEY_BEFORE, year, month, day);
    }
    if (sameKind(kind, "Larger") || sameKind(kind, "Smaller")) {
        uint32_t size = 0;
        if (!positiveUint(argument, &size)) {
            throwFailure(env, "bad search");
            return nullptr;
        }
        if (sameKind(kind, "Larger")) return mailimap_search_key_new_larger(size);
        return mailimap_search_key_new_smaller(size);
    }
    throwFailure(env, "bad search");
    return nullptr;
}

jlongArray completeSearch(JNIEnv * env, LiveSession * session, struct mailimap_search_key * key, jboolean useEsearch) {
    if (useEsearch == JNI_TRUE) {
        struct mailimap_response * response = nullptr;
        int r = sendUidEsearch(session->imap, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session->imap, "search failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
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

void dropStatus(mailimap * imap) {
    if (imap->imap_response_info == nullptr || imap->imap_response_info->rsp_status == nullptr) return;
    mailimap_mailbox_data_status_free(imap->imap_response_info->rsp_status);
    imap->imap_response_info->rsp_status = nullptr;
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

// One MESSAGES burst. mailimap_parse_response matches whatever imap_tag is set to.
int statusMessagesPipeline(mailimap * imap, const std::vector<std::string> & mailboxes,
    std::map<std::string, int> * out) {
    out->clear();
    if (mailboxes.empty()) return MAILIMAP_NO_ERROR;
    if (imap == nullptr || imap->imap_stream == nullptr) return MAILIMAP_ERROR_STREAM;
    struct mailimap_status_att_list * atts = mailimap_status_att_list_new_empty();
    if (atts == nullptr) return MAILIMAP_ERROR_MEMORY;
    int r = mailimap_status_att_list_add(atts, MAILIMAP_STATUS_ATT_MESSAGES);
    if (r != MAILIMAP_NO_ERROR) {
        mailimap_status_att_list_free(atts);
        return r;
    }
    int const remembered = imap->imap_tag;
    std::vector<int> tags;
    tags.reserve(mailboxes.size());
    for (size_t i = 0; i < mailboxes.size(); ++i) {
        r = mailimap_send_current_tag(imap);
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_status_att_list_free(atts);
            return r;
        }
        r = mailimap_status_send(imap->imap_stream, mailboxes[i].c_str(), atts);
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_status_att_list_free(atts);
            return r;
        }
        r = mailimap_crlf_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) {
            mailimap_status_att_list_free(atts);
            return r;
        }
        int tag = remembered + static_cast<int>(i) + 1;
        if (imap->imap_tag != tag) tag = imap->imap_tag;
        tags.push_back(tag);
    }
    mailimap_status_att_list_free(atts);
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    for (int tag : tags) {
        dropStatus(imap);
        imap->imap_tag = tag;
        if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
        struct mailimap_response * response = nullptr;
        r = mailimap_parse_response(imap, &response);
        if (r != MAILIMAP_NO_ERROR) {
            dropStatus(imap);
            if (r == MAILIMAP_ERROR_STREAM || r == MAILIMAP_ERROR_FATAL) return r;
            continue;
        }
        if (taggedOk(response)) takeMessages(imap, out);
        else dropStatus(imap);
        mailimap_response_free(response);
    }
    if (!tags.empty()) imap->imap_tag = tags.back();
    return MAILIMAP_NO_ERROR;
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
    std::string address;
    mailimap * imap = openPlain(h.c(), port, u.c(), p.c(), &error, &address);
    if (imap == nullptr) {
        setLastError(error);
        return 0;
    }
    struct mailimap_capability_data * caps = nullptr;
    int r = mailimap_capability(imap, &caps);
    if (!cmdOk(r) || caps == nullptr) {
        setLastError(serviceLabel("IMAP", h.c(), port) + " (" + address + "): capability failed: "
            + imapText(imap, "capability failed"));
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
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeEnable(JNIEnv * env, jobject, jlong handle, jstring capability) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    JChars name(env, capability);
    enableNamed(session, name.c());
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCompress(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    compressSession(session);
    unlockSession(session);
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

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStatusMessages(JNIEnv * env, jobject, jlong handle,
    jobjectArray mailboxes) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    std::vector<std::string> names;
    if (mailboxes != nullptr) {
        jsize n = env->GetArrayLength(mailboxes);
        names.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            jstring item = static_cast<jstring>(env->GetObjectArrayElement(mailboxes, i));
            if (item == nullptr) continue;
            {
                JChars chars(env, item);
                names.emplace_back(chars.c());
            }
            env->DeleteLocalRef(item);
        }
    }
    std::map<std::string, int> counts;
    int r = MAILIMAP_NO_ERROR;
    if (!names.empty()) {
        if (session->imap == nullptr) {
            throwFailure(env, "not connected");
            unlockSession(session);
            return nullptr;
        }
        r = statusMessagesPipeline(session->imap, names, &counts);
    }
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session->imap, "status failed");
        unlockSession(session);
        return nullptr;
    }
    jclass mapClass = env->FindClass("java/util/HashMap");
    if (mapClass == nullptr) {
        unlockSession(session);
        return nullptr;
    }
    jmethodID init = env->GetMethodID(mapClass, "<init>", "()V");
    jmethodID put = env->GetMethodID(mapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    if (init == nullptr || put == nullptr) {
        env->DeleteLocalRef(mapClass);
        unlockSession(session);
        return nullptr;
    }
    jobject map = env->NewObject(mapClass, init);
    if (map == nullptr) {
        env->DeleteLocalRef(mapClass);
        unlockSession(session);
        return nullptr;
    }
    for (const auto & entry : counts) {
        jstring key = newString(env, entry.first.c_str());
        jobject value = env->CallStaticObjectMethod(gJni.integerCls, gJni.integerValueOf, entry.second);
        jobject previous = env->CallObjectMethod(map, put, key, value);
        if (previous != nullptr) env->DeleteLocalRef(previous);
        if (key != nullptr) env->DeleteLocalRef(key);
        if (value != nullptr) env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(map);
            env->DeleteLocalRef(mapClass);
            unlockSession(session);
            return nullptr;
        }
    }
    env->DeleteLocalRef(mapClass);
    unlockSession(session);
    return map;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeListLevel(JNIEnv * env, jobject, jlong handle,
    jstring prefix, jstring parent, jstring kind) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars pref(env, prefix);
    JChars par(env, parent);
    JChars kindChars(env, kind);
    bool extended = strcmp(kindChars.c(), "Plain") != 0;
    bool withMessages = strcmp(kindChars.c(), "ExtendedWithMessages") == 0
        || strcmp(kindChars.c(), "ExtendedWithStatus") == 0;
    bool withUnseen = strcmp(kindChars.c(), "ExtendedWithStatus") == 0;
    std::string reference;
    if (parent != nullptr && par.c()[0] != 0) {
        reference = par.c();
        char delim = delimFor(session, par.c());
        if (delim != 0) reference.push_back(delim);
    } else {
        reference = pref.c();
    }
    clist * list = nullptr;
    std::string raw;
    int r = listMailboxes(session->imap, reference.c_str(), extended, withMessages, withUnseen, &list, &raw);
    if (!cmdOk(r)) {
        if (list != nullptr) mailimap_list_result_free(list);
        throwImap(env, session->imap, "list failed");
        unlockSession(session);
        return nullptr;
    }
    std::map<std::string, FolderCounts> counts;
    if (withMessages) collectStatus(raw, &counts);
    std::vector<jobject> built;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * mb = static_cast<struct mailimap_mailbox_list *>(clist_content(cur));
            if (mb == nullptr || mb->mb_name == nullptr) continue;
            session->delims[mb->mb_name] = mb->mb_delimiter;
            std::string leaf = leafOf(mb->mb_name, mb->mb_delimiter);
            std::string use = extended ? specialUseOf(mb) : "";
            jstring jmb = newString(env, mb->mb_name);
            jstring jleaf = newString(env, leaf.c_str());
            jstring juse = use.empty() ? nullptr : newString(env, use.c_str());
            jobject jmessages = nullptr;
            jobject junseen = nullptr;
            if (withMessages) {
                auto found = counts.find(mb->mb_name);
                if (found != counts.end()) {
                    if (found->second.hasMessages) {
                        jmessages = env->CallStaticObjectMethod(gJni.integerCls, gJni.integerValueOf, found->second.messages);
                    }
                    if (found->second.hasUnseen) {
                        junseen = env->CallStaticObjectMethod(gJni.integerCls, gJni.integerValueOf, found->second.unseen);
                    }
                }
            }
            jobject entry = env->NewObject(gJni.folderEntry, gJni.folderInit, jmb, jleaf,
                static_cast<jboolean>(hasChildren(mb)), static_cast<jchar>(mb->mb_delimiter),
                juse, jmessages, junseen);
            env->DeleteLocalRef(jmb);
            env->DeleteLocalRef(jleaf);
            if (juse != nullptr) env->DeleteLocalRef(juse);
            if (jmessages != nullptr) env->DeleteLocalRef(jmessages);
            if (junseen != nullptr) env->DeleteLocalRef(junseen);
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
    jint previewByteLimit, jboolean preferHtml, jboolean showDeleted, jboolean useServerPreview,
    jstring accountEmail) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars accountEmailChars(env, accountEmail);
    JChars mb(env, mailbox);
    if (!selectMailbox(env, session, mb.c())) {
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_selection_info * info = session->imap->imap_selection_info;
    uint32_t exists = info != nullptr ? info->sel_exists : 0;
    bool byUid = mode == 2;
    bool serverPreview = useServerPreview == JNI_TRUE;
    bool bodyPreview = includePreview == JNI_TRUE && !serverPreview;
    int peekLimit = previewByteLimit;
    if (peekLimit > 2048) peekLimit = 2048;
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
        int r = fetchRows(session->imap, set, false, true, serverPreview, &list);
        mailimap_set_free(set);
        if (!cmdOk(r)) {
            throwImap(env, session->imap, "fetch failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<Row> rows = rowsFromList(list, accountEmailChars.c());
        std::vector<std::string> previews(rows.size());
        std::vector<char> havePreview(rows.size(), 0);
        if (serverPreview) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (!rows[i].hasPreview) continue;
                previews[i] = rows[i].preview;
                havePreview[i] = 1;
            }
        } else if (bodyPreview) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (rows[i].structure == nullptr) continue;
                PartWant want;
                findPreferred(rows[i].structure, "", preferHtml == JNI_TRUE, &want);
                if (!want.found) continue;
                previews[i] = previewText(session->imap, rows[i].uid, want, peekLimit);
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
        int r = fetchRows(session->imap, set, true, true, serverPreview, &list);
        if (set != nullptr) mailimap_set_free(set);
        if (!cmdOk(r)) {
            throwImap(env, session->imap, "fetch failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<Row> rows = rowsFromList(list, accountEmailChars.c());
        std::vector<std::string> previews(rows.size());
        std::vector<char> havePreview(rows.size(), 0);
        if (serverPreview) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (!rows[i].hasPreview) continue;
                previews[i] = rows[i].preview;
                havePreview[i] = 1;
            }
        } else if (bodyPreview) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (rows[i].structure == nullptr) continue;
                PartWant want;
                findPreferred(rows[i].structure, "", preferHtml == JNI_TRUE, &want);
                if (!want.found) continue;
                previews[i] = previewText(session->imap, rows[i].uid, want, peekLimit);
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
            readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row, nullptr);
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

bool numericSection(const std::string & spec) {
    if (spec.empty()) {
        return false;
    }
    bool digit = false;
    for (unsigned char c : spec) {
        if (c >= '0' && c <= '9') {
            digit = true;
        } else if (c != '.') {
            return false;
        }
    }
    return digit;
}

struct mailimap_section * headerPeekSection() {
    struct mailimap_section_msgtext * msgtext =
        mailimap_section_msgtext_new(MAILIMAP_SECTION_MSGTEXT_HEADER, nullptr);
    if (msgtext == nullptr) {
        return nullptr;
    }
    struct mailimap_section_spec * spec = mailimap_section_spec_new(
        MAILIMAP_SECTION_SPEC_SECTION_MSGTEXT, msgtext, nullptr, nullptr);
    if (spec == nullptr) {
        mailimap_section_msgtext_free(msgtext);
        return nullptr;
    }
    struct mailimap_section * section = mailimap_section_new(spec);
    if (section == nullptr) {
        mailimap_section_spec_free(spec);
    }
    return section;
}

struct mailimap_fetch_att * peekSectionAtt(struct mailimap_section * sec, jint offset, jint length) {
    struct mailimap_fetch_att * att;
    if (length > 0) {
        att = mailimap_fetch_att_new_body_peek_section_partial(
            sec, static_cast<uint32_t>(offset), static_cast<uint32_t>(length));
    } else {
        att = mailimap_fetch_att_new_body_peek_section(sec);
    }
    if (att == nullptr) {
        mailimap_section_free(sec);
    }
    return att;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativePeekPart(JNIEnv * env, jobject, jlong handle,
    jlong uid, jstring section, jint offset, jint length, jboolean useBinary) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars spec(env, section);
    const std::string text = spec.c();
    struct mailimap_fetch_att * att = nullptr;
    if (text == "HEADER" || text == "*") {
        struct mailimap_section * sec = text == "HEADER" ? headerPeekSection() : mailimap_section_new(nullptr);
        if (sec != nullptr) {
            att = peekSectionAtt(sec, offset, length);
        }
    } else if (!numericSection(text)) {
        throwFailure(env, "bad section");
        unlockSession(session);
        return nullptr;
    } else if (useBinary == JNI_TRUE) {
        std::string token = "BINARY.PEEK[";
        token += text;
        token += "]";
        if (length > 0) {
            token += "<";
            token += std::to_string(static_cast<unsigned>(offset));
            token += ".";
            token += std::to_string(static_cast<unsigned>(length));
            token += ">";
        }
        char * owned = strdup(token.c_str());
        att = owned != nullptr ? mailimap_fetch_att_new(MAILIMAP_FETCH_ATT_EXTENSION, nullptr, 0, 0, owned) : nullptr;
        if (att == nullptr) free(owned);
    } else {
        struct mailimap_section * sec = sectionFromSpec(spec.c());
        if (length > 0) {
            att = mailimap_fetch_att_new_body_peek_section_partial(sec, static_cast<uint32_t>(offset), static_cast<uint32_t>(length));
        } else {
            att = mailimap_fetch_att_new_body_peek_section(sec);
        }
    }
    struct mailimap_fetch_type * fetch = mailimap_fetch_type_new_fetch_att_list_empty();
    if (att == nullptr || fetch == nullptr) {
        if (fetch != nullptr) mailimap_fetch_type_free(fetch);
        throwFailure(env, "fetch failed");
        unlockSession(session);
        return nullptr;
    }
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
                if (item == nullptr) continue;
                if (item->att_type == MAILIMAP_MSG_ATT_ITEM_STATIC && item->att_data.att_static != nullptr) {
                    struct mailimap_msg_att_static * st = item->att_data.att_static;
                    if (st->att_type == MAILIMAP_MSG_ATT_BODY_SECTION && st->att_data.att_body_section != nullptr) {
                        bytes = st->att_data.att_body_section->sec_body_part;
                        n = st->att_data.att_body_section->sec_length;
                    }
                } else if (item->att_type == MAILIMAP_MSG_ATT_ITEM_EXTENSION && item->att_data.att_extension_data != nullptr) {
                    struct mailimap_extension_data * ext = item->att_data.att_extension_data;
                    if (ext->ext_extension == &liveimap_extra_extension && ext->ext_type == LIVE_BINARY) {
                        auto * blob = static_cast<LiveBlob *>(ext->ext_data);
                        if (blob != nullptr && blob->isNil == 0 && blob->data != nullptr) {
                            bytes = blob->data;
                            n = blob->len;
                        } else if (blob != nullptr) {
                            bytes = "";
                            n = 0;
                        }
                    }
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
        if (addFlags != nullptr) mailimap_flag_list_free(addFlags);
        if (removeFlags != nullptr) mailimap_flag_list_free(removeFlags);
        throwFailure(env, "flag must be one of \\Answered, \\Flagged, \\Deleted, \\Seen, or \\Draft");
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
    jlongArray uids, jstring target, jstring moveKind) {
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
    JChars kindChars(env, moveKind);
    struct mailimap_set * set = setFromUids(values);
    if (strcasecmp(kindChars.c(), "Move") == 0) {
        int r = mailimap_uid_move(session->imap, set, dest.c());
        mailimap_set_free(set);
        if (!cmdOk(r)) throwImap(env, session->imap, "move failed");
        unlockSession(session);
        return;
    }
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
    if (!cmdOk(r)) {
        mailimap_set_free(set);
        throwImap(env, session->imap, "store failed");
        unlockSession(session);
        return;
    }
    r = mailimap_uid_expunge(session->imap, set);
    mailimap_set_free(set);
    if (!cmdOk(r)) throwImap(env, session->imap, "expunge failed");
    unlockSession(session);
}

bool appendFlagsAreDraft(JNIEnv * env, jobjectArray flags) {
    if (flags == nullptr) return true;
    jsize n = env->GetArrayLength(flags);
    for (jsize i = 0; i < n; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(flags, i));
        bool draft = false;
        {
            JChars chars(env, js);
            draft = strcmp(chars.c(), "\\Draft") == 0;
        }
        if (js != nullptr) env->DeleteLocalRef(js);
        if (!draft) return false;
    }
    return true;
}

struct mailimap_flag_list * draftFlagList(JNIEnv * env, jobjectArray flags) {
    if (flags == nullptr) return nullptr;
    jsize n = env->GetArrayLength(flags);
    if (n <= 0) return nullptr;
    struct mailimap_flag_list * list = mailimap_flag_list_new_empty();
    if (list == nullptr) {
        throwFailure(env, "append failed");
        return nullptr;
    }
    struct mailimap_flag * flag = mailimap_flag_new_draft();
    if (flag == nullptr || mailimap_flag_list_add(list, flag) != MAILIMAP_NO_ERROR) {
        if (flag != nullptr) mailimap_flag_free(flag);
        mailimap_flag_list_free(list);
        throwFailure(env, "append failed");
        return nullptr;
    }
    return list;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeAppend(JNIEnv * env, jobject, jlong handle,
    jstring mailbox, jbyteArray message, jobjectArray flags) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    if (!appendFlagsAreDraft(env, flags)) {
        throwFailure(env, "unsupported append flag");
        unlockSession(session);
        return;
    }
    struct mailimap_flag_list * flagList = draftFlagList(env, flags);
    if (env->ExceptionCheck()) {
        unlockSession(session);
        return;
    }
    JChars mb(env, mailbox);
    jsize n = message != nullptr ? env->GetArrayLength(message) : 0;
    jbyte * bytes = n > 0 ? env->GetByteArrayElements(message, nullptr) : nullptr;
    int r = appendLiteralPlus(session->imap, mb.c(),
        bytes != nullptr ? reinterpret_cast<char *>(bytes) : "", static_cast<size_t>(n), flagList);
    if (bytes != nullptr) env->ReleaseByteArrayElements(message, bytes, JNI_ABORT);
    if (flagList != nullptr) mailimap_flag_list_free(flagList);
    if (!cmdOk(r)) throwImap(env, session->imap, "append failed");
    unlockSession(session);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchText(JNIEnv * env, jobject, jlong handle,
    jstring query, jboolean useEsearch) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars q(env, query);
    struct mailimap_search_key * key = mailimap_search_key_new_text(strdup(q.c()));
    if (useEsearch == JNI_TRUE) {
        struct mailimap_response * response = nullptr;
        int r = sendUidEsearch(session->imap, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session->imap, "search failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
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
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchCriterion(JNIEnv * env, jobject, jlong handle,
    jstring kind, jstring argument, jboolean useEsearch) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars kindChars(env, kind);
    JChars argChars(env, argument);
    struct mailimap_search_key * key = criterionKey(env, kindChars.c(), argChars.c());
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    return completeSearch(env, session, key, useEsearch);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSort(JNIEnv * env, jobject, jlong handle,
    jstring keyName, jboolean newestFirst, jboolean useEsort) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars name(env, keyName);
    bool known = strcasecmp(name.c(), "DATE") == 0 || strcasecmp(name.c(), "FROM") == 0
        || strcasecmp(name.c(), "SUBJECT") == 0 || strcasecmp(name.c(), "TO") == 0
        || strcasecmp(name.c(), "CC") == 0 || strcasecmp(name.c(), "SIZE") == 0
        || strcasecmp(name.c(), "DISPLAY") == 0;
    if (!known) {
        throwFailure(env, "use an arrival IndexMode");
        unlockSession(session);
        return nullptr;
    }
    if (useEsort == JNI_TRUE || strcasecmp(name.c(), "DISPLAY") == 0) {
        bool esort = useEsort == JNI_TRUE;
        bool reverse = newestFirst != JNI_TRUE;
        struct mailimap_response * response = nullptr;
        int r = sendUidSortChoice(session->imap, name.c(), reverse, esort, &response);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session->imap, "sort failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = esort ? takeEsearch(session->imap) : takeSort(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            if (result != nullptr) mailimap_sort_result_free(result);
            throwImap(env, session->imap, "sort failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = uidArray(env, result);
        if (result != nullptr) mailimap_sort_result_free(result);
        unlockSession(session);
        return arr;
    }
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
    if (strcasecmp(alg.c(), "REFERENCES") != 0 && strcasecmp(alg.c(), "ORDEREDSUBJECT") != 0) {
        throwFailure(env, "thread algorithm must be REFERENCES or ORDEREDSUBJECT");
        unlockSession(session);
        return nullptr;
    }
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
    mailimap * watch = openPlain(session->host.c_str(), session->imapPort, session->user.c_str(), session->password.c_str(), &error, nullptr);
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
    mailsmtp_set_timeout(smtp, kConnectTimeoutSec);
    std::string address;
    std::string dialError;
    int fd = tcpConnect("SMTP", session->smtpHost.c_str(), session->smtpPort, &address, &dialError);
    if (fd < 0) {
        mailsmtp_free(smtp);
        throwFailure(env, dialError);
        unlockSession(session);
        return;
    }
    mailstream * stream = mailstream_socket_open_timeout(fd, kConnectTimeoutSec);
    if (stream == nullptr) {
        close(fd);
        mailsmtp_free(smtp);
        throwFailure(env, connectFailure("SMTP", session->smtpHost.c_str(), session->smtpPort,
            address, "connection failed"));
        unlockSession(session);
        return;
    }
    std::string where = serviceLabel("SMTP", session->smtpHost.c_str(), session->smtpPort)
        + " (" + address + "): ";
    int r = mailsmtp_connect(smtp, stream);
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = where + asciiSafe(smtp->response, "connection failed");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    r = mailsmtp_helo(smtp);
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = where + asciiSafe(smtp->response, "smtp error");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    r = mailsmtp_mail(smtp, session->from.c_str());
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = where + asciiSafe(smtp->response, "smtp error");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    jsize nrcpt = recipients != nullptr ? env->GetArrayLength(recipients) : 0;
    for (jsize i = 0; i < nrcpt; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(recipients, i));
        {
            JChars rcpt(env, js);
            r = mailsmtp_rcpt(smtp, rcpt.c());
        }
        if (js != nullptr) env->DeleteLocalRef(js);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = where + asciiSafe(smtp->response, "smtp error");
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
        std::string why = where + asciiSafe(smtp->response, "smtp error");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    mailsmtp_quit(smtp);
    mailsmtp_free(smtp);
    unlockSession(session);
}
