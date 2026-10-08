#include <libetpan/libetpan.h>
#include <libetpan/unselect.h>
#include <libetpan/esearch.h>
#include <libetpan/mailimap_socket.h>
#include <libetpan/mailimap_ssl.h>
#include <libetpan/mailstream_ssl.h>

#include "imap_bursts.h"

#include <jni.h>
#include <android/log.h>

#include <poll.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
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
int mailimap_uid_fetch_send(mailstream * fd, struct mailimap_set * set,
    struct mailimap_fetch_type * fetch_type);
int mailimap_status_send(mailstream * fd, const char * mb,
    struct mailimap_status_att_list * status_att_list);
int mailimap_space_send(mailstream * fd);
int mailimap_token_send(mailstream * fd, const char * atom);
int mailimap_mailbox_send(mailstream * fd, const char * mb);
int mailimap_mailbox_parse(mailstream * fd, MMAPString * buffer,
    struct mailimap_parser_context * parser_ctx, size_t * indx, char ** result,
    size_t progr_rate, progress_function * progr_fun);
int mailimap_flag_list_send(mailstream * fd, struct mailimap_flag_list * flag_list);
int mailimap_astring_send(mailstream * fd, const char * astring);
int mailimap_select_send(mailstream * fd, const char * mb, int condstore);
int mailimap_examine_send(mailstream * fd, const char * mb, int condstore);
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

// OpenSSL 1.1.1w symbols. The NDK include path has no openssl headers.
struct ssl_ctx_st;
struct x509_st;
struct x509_store_ctx_st;
struct stack_st;
extern "C" {
long SSL_CTX_ctrl(ssl_ctx_st * ctx, int cmd, long larg, void * parg);
void SSL_CTX_set_verify(ssl_ctx_st * ctx, int mode, int (* callback)(int, x509_store_ctx_st *));
int X509_STORE_CTX_get_error_depth(x509_store_ctx_st * ctx);
x509_st * X509_STORE_CTX_get_current_cert(x509_store_ctx_st * ctx);
stack_st * X509_STORE_CTX_get0_chain(x509_store_ctx_st * ctx);
int OPENSSL_sk_num(const stack_st * sk);
void * OPENSSL_sk_value(const stack_st * sk, int i);
int i2d_X509(x509_st * cert, unsigned char ** out);
}

std::string utf8FromJava(JNIEnv * env, jstring value);
extern "C" void prepareTls(struct mailstream_ssl_context * ssl_context, void * data);

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
    LIVE_SCOPE = 4,
};

struct LiveScopeHit {
    char * mailbox;
    clist * uids;
    int hasAll;
    int hasCount;
    int64_t count;
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
    std::string tlsMode = "None";
    std::string certPin;
    bool allowPlaintextAuth = false;
    std::string smtpHost;
    int smtpPort = 25;
    std::string from;
    bool pipelineCommands = true;
    bool logImapTraffic = false;
    std::string trafficLogPath;
    std::string capabilityLine;
    std::map<std::string, char> delims;
    bool qresync = false;
    std::map<std::string, ResyncState> resyncByMailbox;
    std::string selectedMailbox;
    bool selectedReadWrite = false;

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
    std::vector<uint32_t> copiedUids;
    int64_t esearchCount = -1;
};

JavaVM * gVm = nullptr;
std::mutex gLiveMu;
std::unordered_set<LiveSession *> gLive;
std::mutex gErrMu;
std::string gLastError;
std::mutex gCertOfferMu;
std::vector<std::string> gCertOffer;

void clearCertOffer() {
    std::lock_guard<std::mutex> lock(gCertOfferMu);
    gCertOffer.clear();
}
int (* gKeepUnselect)(mailimap *) = mailimap_unselect;

struct JniCache {
    bool ready = false;
    jclass mailFailure = nullptr;
    jclass connectionLost = nullptr;
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
    jclass sortField = nullptr;
    jclass threadHeader = nullptr;
    jmethodID sortFieldInit = nullptr;
    jmethodID threadHeaderInit = nullptr;
    jclass mailboxUids = nullptr;
    jmethodID mailboxUidsInit = nullptr;
    jmethodID listInit = nullptr;
    jmethodID listAdd = nullptr;
    jmethodID setInit = nullptr;
    jmethodID setAdd = nullptr;
    jmethodID longValueOf = nullptr;
    jmethodID integerValueOf = nullptr;
    jmethodID onWatch = nullptr;
    jclass trafficLog = nullptr;
    jmethodID trafficAccept = nullptr;
    jmethodID trafficFlush = nullptr;
    jmethodID trafficNote = nullptr;
    jfieldID personal = nullptr;
    jfieldID other = nullptr;
    jfieldID shared = nullptr;
};

JniCache gJni;
std::once_flag gExtOnce;
thread_local LiveSession * tlsWatch = nullptr;
thread_local mailimap * tlsImap = nullptr;
thread_local LiveSession * tlsLive = nullptr;
thread_local const char * tlsTrafficId = nullptr;

struct TrafficIdScope {
    const char * previous;
    explicit TrafficIdScope(const char * id) : previous(tlsTrafficId) {
        tlsTrafficId = id != nullptr && id[0] != 0 ? id : "main";
    }
    ~TrafficIdScope() { tlsTrafficId = previous; }
};

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

void cacheConnectionLost(JNIEnv * env) {
    if (gJni.connectionLost != nullptr) {
        return;
    }
    jclass local = env->FindClass("org/dlang/liveimap/session/ConnectionLost");
    if (local != nullptr) {
        gJni.connectionLost = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
    } else {
        env->ExceptionClear();
    }
}

void throwConnectionLost(JNIEnv * env, const std::string & text) {
    if (env->ExceptionCheck()) {
        return;
    }
    cacheConnectionLost(env);
    jclass cls = gJni.connectionLost != nullptr ? gJni.connectionLost : gJni.mailFailure;
    if (cls == nullptr) {
        return;
    }
    env->ThrowNew(cls, text.c_str());
}

void throwImap(JNIEnv * env, LiveSession * session, int r, const char * fallback) {
    if (r == MAILIMAP_ERROR_STREAM || r == MAILIMAP_ERROR_PARSE) {
        std::string host;
        int port = 0;
        if (session != nullptr) {
            host = session->host;
            port = session->imapPort;
            if (session->imap != nullptr) {
                mailimap_free(session->imap);
                session->imap = nullptr;
            }
        }
        const char * why = fallback != nullptr && fallback[0] != 0 ? fallback : "imap error";
        throwConnectionLost(env, "IMAP connection to " + host + ":" + std::to_string(port) + " lost (" + why + ")");
        return;
    }
    mailimap * imap = session != nullptr ? session->imap : nullptr;
    std::string text = imapText(imap, fallback);
    if (text == "Completed") {
        text = fallback != nullptr ? fallback : "imap error";
    }
    throwFailure(env, text);
}

bool ensureJni(JNIEnv * env) {
    if (gJni.ready) {
        return true;
    }
    jclass local;
    local = env->FindClass("org/dlang/liveimap/session/MailFailure");
    gJni.mailFailure = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    cacheConnectionLost(env);
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
        "(JILjava/util/Set;JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZLjava/lang/String;Ljava/lang/String;)V");
    gJni.folderInit = env->GetMethodID(gJni.folderEntry, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;ZCLjava/lang/String;Ljava/lang/Integer;Ljava/lang/Integer;)V");
    gJni.nsInit = env->GetMethodID(gJni.ns, "<init>",
        "(Ljava/lang/String;CLorg/dlang/liveimap/session/NamespaceKind;)V");
    gJni.selectInit = env->GetMethodID(gJni.selectResult, "<init>", "(JJI)V");
    gJni.mimeInit = env->GetMethodID(gJni.mimePart, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/util/List;Ljava/lang/String;Ljava/lang/String;)V");
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
    local = env->FindClass("org/dlang/liveimap/engine/TrafficLog");
    if (local != nullptr) {
        gJni.trafficLog = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        gJni.trafficAccept = env->GetStaticMethodID(
            gJni.trafficLog, "acceptNative", "(Ljava/lang/String;I[B)V");
        gJni.trafficFlush = env->GetStaticMethodID(gJni.trafficLog, "flushNative", "()V");
        gJni.trafficNote = env->GetStaticMethodID(
            gJni.trafficLog, "noteNative", "(Ljava/lang/String;Ljava/lang/String;)V");
    }
    local = env->FindClass("org/dlang/liveimap/session/SortField");
    if (local != nullptr) {
        gJni.sortField = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        gJni.sortFieldInit = env->GetMethodID(gJni.sortField, "<init>", "(JLjava/lang/String;JZ)V");
    }
    local = env->FindClass("org/dlang/liveimap/session/ThreadHeader");
    if (local != nullptr) {
        gJni.threadHeader = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        gJni.threadHeaderInit = env->GetMethodID(
            gJni.threadHeader,
            "<init>",
            "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    }
    local = env->FindClass("org/dlang/liveimap/session/MailboxUids");
    if (local != nullptr) {
        gJni.mailboxUids = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        gJni.mailboxUidsInit = env->GetMethodID(
            gJni.mailboxUids, "<init>", "(Ljava/lang/String;Ljava/util/List;)V");
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    gJni.ready = gJni.mailFailure != nullptr && gJni.indexRowInit != nullptr &&
        gJni.folderInit != nullptr && gJni.onWatch != nullptr && gJni.integerValueOf != nullptr &&
        gJni.trafficAccept != nullptr && gJni.trafficFlush != nullptr && gJni.trafficNote != nullptr &&
        gJni.sortFieldInit != nullptr && gJni.threadHeaderInit != nullptr;
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

void releaseBurstFailure(JNIEnv * env, LiveSession * session, int r, const char * fallback) {
    throwImap(env, session, r, fallback);
    unlockSession(session);
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
    bool html = false;
    std::string section;
    std::string charset;
    std::string encoding;
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

std::string partCharset(struct mailimap_body_fields * fields);
std::string partEncoding(struct mailimap_body_fields * fields);

void rememberText(PartWant * want, const std::string & section, struct mailimap_body_type_1part * part, bool html) {
    struct mailimap_body_fields * fields = fieldsOf1(part);
    want->found = true;
    want->html = html;
    want->section = section;
    want->charset = partCharset(fields);
    want->encoding = partEncoding(fields);
}

bool plainFound(const PartWant * want) {
    return want->found && !want->html;
}

void findPreferred(struct mailimap_body * body, const std::string & prefix, bool preferHtml, PartWant * want) {
    (void)preferHtml;
    if (body == nullptr || plainFound(want)) {
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
            if (plainFound(want)) {
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
    bool plain = strcasecmp(type.c_str(), "text") == 0 && strcasecmp(subtype.c_str(), "plain") == 0;
    bool html = strcasecmp(type.c_str(), "text") == 0 && strcasecmp(subtype.c_str(), "html") == 0;
    if (plain) {
        rememberText(want, section, part, false);
        return;
    }
    if (html && !want->found) {
        rememberText(want, section, part, true);
    }
}

std::string partCharset(struct mailimap_body_fields * fields) {
    const char * charset = fields != nullptr ? paramValue(fields->bd_parameter, "charset") : nullptr;
    return charset != nullptr ? charset : "";
}

std::string partEncoding(struct mailimap_body_fields * fields) {
    if (fields == nullptr || fields->bd_encoding == nullptr) {
        return "";
    }
    switch (fields->bd_encoding->enc_type) {
    case MAILIMAP_BODY_FLD_ENC_BASE64:
        return "base64";
    case MAILIMAP_BODY_FLD_ENC_QUOTED_PRINTABLE:
        return "quoted-printable";
    default:
        return "";
    }
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
    jstring jCharset = newString(env, partCharset(fields).c_str());
    jstring jEncoding = newString(env, partEncoding(fields).c_str());
    jobject obj = env->NewObject(gJni.mimePart, gJni.mimeInit, jSection, jType, jSubtype, jDisp, jFile, size, children,
        jCharset, jEncoding);
    env->DeleteLocalRef(jSection);
    env->DeleteLocalRef(jType);
    env->DeleteLocalRef(jSubtype);
    env->DeleteLocalRef(jDisp);
    if (jFile != nullptr) env->DeleteLocalRef(jFile);
    env->DeleteLocalRef(jCharset);
    env->DeleteLocalRef(jEncoding);
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
        jstring jCharset = newString(env, "");
        jstring jEncoding = newString(env, "");
        jobject obj = env->NewObject(gJni.mimePart, gJni.mimeInit, jSection, jType, jSubtype, jDisp, jFile, 0, children,
            jCharset, jEncoding);
        env->DeleteLocalRef(jSection);
        env->DeleteLocalRef(jType);
        env->DeleteLocalRef(jSubtype);
        env->DeleteLocalRef(jDisp);
        if (jFile != nullptr) env->DeleteLocalRef(jFile);
        env->DeleteLocalRef(jCharset);
        env->DeleteLocalRef(jEncoding);
        env->DeleteLocalRef(children);
        return obj;
    }
    std::string sec = section.empty() ? "1" : section;
    return mimeFrom1(env, body->bd_data.bd_body_1part, sec);
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
    std::string recipients;
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

bool envelopeAddressMatches(struct mailimap_envelope * env, const char * email) {
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

std::string trimmedSpec(const char * start, const char * end) {
    while (start < end && (*start == ' ' || *start == '\t')) start++;
    while (end > start && (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\r')) end--;
    return std::string(start, end);
}

bool envelopeToMatches(struct mailimap_envelope * env, const char * email, const char * altSpecs) {
    if (env == nullptr) {
        return false;
    }
    if (envelopeAddressMatches(env, email)) {
        return true;
    }
    if (altSpecs == nullptr || altSpecs[0] == 0) {
        return false;
    }
    const char * start = altSpecs;
    for (const char * p = altSpecs;; ++p) {
        if (*p != '\n' && *p != '\r' && *p != 0) {
            continue;
        }
        std::string spec = trimmedSpec(start, p);
        if (!spec.empty() && envelopeAddressMatches(env, spec.c_str())) {
            return true;
        }
        if (*p == 0) {
            return false;
        }
        if (*p == '\r' && p[1] == '\n') {
            ++p;
        }
        start = p + 1;
    }
}

std::string toRecipientText(struct mailimap_envelope * env) {
    std::string out;
    if (env == nullptr || env->env_to == nullptr || env->env_to->to_list == nullptr) {
        return out;
    }
    for (clistiter * cur = clist_begin(env->env_to->to_list); cur != nullptr; cur = clist_next(cur)) {
        std::string text = addressText(static_cast<struct mailimap_address *>(clist_content(cur)));
        if (text.empty()) {
            continue;
        }
        if (!out.empty()) {
            out += ", ";
        }
        out += text;
    }
    return out;
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

void readAtt(struct mailimap_msg_att * att, Row * row, const char * accountEmail, const char * altSpecs) {
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
                    row->recipients = toRecipientText(env);
                    row->toMe = envelopeToMatches(env, accountEmail, altSpecs);
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
    jstring recipients = newString(env, row.recipients.c_str());
    jstring mailbox = newString(env, "");
    jobject obj = env->NewObject(gJni.indexRow, gJni.indexRowInit,
        static_cast<jlong>(row.uid), static_cast<jint>(row.sequence), flags,
        static_cast<jlong>(row.epoch), static_cast<jint>(row.size),
        from, subject, date, prev,
        static_cast<jboolean>(row.toMe), static_cast<jboolean>(row.hasAttachment),
        recipients, mailbox);
    env->DeleteLocalRef(flags);
    env->DeleteLocalRef(from);
    env->DeleteLocalRef(subject);
    env->DeleteLocalRef(date);
    if (prev != nullptr) env->DeleteLocalRef(prev);
    if (recipients != nullptr) env->DeleteLocalRef(recipients);
    if (mailbox != nullptr) env->DeleteLocalRef(mailbox);
    return obj;
}

std::vector<Row> rowsFromList(clist * list, const char * accountEmail, const char * altSpecs) {
    std::vector<Row> rows;
    if (list == nullptr) {
        return rows;
    }
    for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
        Row row;
        readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row, accountEmail, altSpecs);
        if (row.uid != 0) {
            rows.push_back(row);
        }
    }
    return rows;
}

int selectFullCapture(mailimap * imap, const char * mailbox, uint64_t * modseq, const char * command);
int selectQresyncCapture(mailimap * imap, const char * mailbox, uint32_t uidvalidity,
    uint64_t knownModseq, uint64_t * modseq, const char * command);

bool selectMailbox(JNIEnv * env, LiveSession * session, const char * mailbox, bool readWrite) {
    int r;
    uint64_t mod = 0;
    const char * command = readWrite ? "SELECT" : "EXAMINE";
    std::string name = mailbox != nullptr ? mailbox : "";
    auto stored = session->resyncByMailbox.find(name);
    bool haveStored = stored != session->resyncByMailbox.end()
        && stored->second.uidvalidity != 0 && stored->second.modseq != 0;
    if (session->qresync && haveStored) {
        r = selectQresyncCapture(session->imap, mailbox, stored->second.uidvalidity, stored->second.modseq, &mod, command);
    } else if (session->qresync) {
        r = selectFullCapture(session->imap, mailbox, &mod, command);
    } else if (readWrite) {
        r = mailimap_select(session->imap, mailbox);
    } else {
        r = mailimap_examine(session->imap, mailbox);
    }
    if (!cmdOk(r)) {
        throwImap(env, session, r, "select failed");
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
    session->selectedMailbox = name;
    session->selectedReadWrite = readWrite;
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
            readAtt(msg->mdt_msg_att, &row, nullptr, nullptr);
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
    bool watchLost = false;
    while (!session->watchStop.load()) {
        if (mailimap_idle_get_done_delay(session->watch) == 0) {
            int doneR = mailimap_idle_done(session->watch);
            int idleR = doneR == MAILIMAP_NO_ERROR ? mailimap_idle(session->watch) : doneR;
            if (doneR != MAILIMAP_NO_ERROR || idleR != MAILIMAP_NO_ERROR) {
                emitWatch(session, 4, session->watchExists, 0, nullptr);
                watchLost = true;
                break;
            }
            fd = mailimap_idle_get_fd(session->watch);
        }
        struct pollfd pfd{};
        pfd.fd = fd;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 500);
        if (session->watchStop.load()) break;
        if (pr <= 0) continue;
        if (mailimap_read_line(session->watch) == nullptr) {
            if (!session->watchStop.load()) {
                emitWatch(session, 4, session->watchExists, 0, nullptr);
            }
            watchLost = true;
            break;
        }
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
    if (!watchLost) {
        mailimap_idle_done(session->watch);
    }
    tlsWatch = nullptr;
    gVm->DetachCurrentThread();
}

const int kConnectTimeoutSec = 30;
const int kReadTimeoutSec = 60;

void enableKeepalive(int fd) {
    int on = 1;
    if (setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof(on)) < 0) {
        __android_log_print(ANDROID_LOG_INFO, "LiveIMAP", "SO_KEEPALIVE failed: %s", strerror(errno));
    }
    int idle = 120;
    if (setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle)) < 0) {
        __android_log_print(ANDROID_LOG_INFO, "LiveIMAP", "TCP_KEEPIDLE failed: %s", strerror(errno));
    }
    int interval = 30;
    if (setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval)) < 0) {
        __android_log_print(ANDROID_LOG_INFO, "LiveIMAP", "TCP_KEEPINTVL failed: %s", strerror(errno));
    }
    int count = 4;
    if (setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count)) < 0) {
        __android_log_print(ANDROID_LOG_INFO, "LiveIMAP", "TCP_KEEPCNT failed: %s", strerror(errno));
    }
}

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
    enableKeepalive(fd);
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

std::string trafficConnectionId(LiveSession * live, mailimap * imap, mailstream * stream) {
    if (live != nullptr) {
        if (imap != nullptr && imap == live->watch) return "watch";
        if (stream != nullptr && live->watch != nullptr && live->watch->imap_stream == stream) return "watch";
        if (imap != nullptr && imap == live->imap) return "main";
        if (stream != nullptr && live->imap != nullptr && live->imap->imap_stream == stream) return "main";
    }
    if (tlsTrafficId != nullptr && tlsTrafficId[0] != 0) return tlsTrafficId;
    return "main";
}

bool beginTrafficJni(JNIEnv ** env, bool * attached) {
    *env = nullptr;
    *attached = false;
    if (gVm == nullptr || !gJni.ready || gJni.trafficLog == nullptr) return false;
    JNIEnv * local = nullptr;
    jint got = gVm->GetEnv(reinterpret_cast<void **>(&local), JNI_VERSION_1_6);
    if (got == JNI_EDETACHED) {
        if (gVm->AttachCurrentThread(&local, nullptr) != 0) return false;
        *attached = true;
    } else if (got != JNI_OK) {
        return false;
    }
    *env = local;
    return local != nullptr;
}

void endTrafficJni(bool attached) {
    if (attached && gVm != nullptr) gVm->DetachCurrentThread();
}

void postTrafficBytes(const std::string & connectionId, int logType, const char * data, size_t size) {
    if (data == nullptr || size == 0 || gJni.trafficAccept == nullptr) return;
    if (size > static_cast<size_t>(INT_MAX)) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (!beginTrafficJni(&env, &attached)) return;
    jstring id = env->NewStringUTF(connectionId.c_str());
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(size));
    if (id != nullptr && bytes != nullptr) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
        env->CallStaticVoidMethod(gJni.trafficLog, gJni.trafficAccept, id, logType, bytes);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (id != nullptr) env->DeleteLocalRef(id);
    if (bytes != nullptr) env->DeleteLocalRef(bytes);
    endTrafficJni(attached);
}

void postTrafficNote(const std::string & connectionId, const std::string & text) {
    if (gJni.trafficNote == nullptr || text.empty()) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (!beginTrafficJni(&env, &attached)) return;
    jstring id = env->NewStringUTF(connectionId.c_str());
    jstring line = env->NewStringUTF(text.c_str());
    if (id != nullptr && line != nullptr) {
        env->CallStaticVoidMethod(gJni.trafficLog, gJni.trafficNote, id, line);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (id != nullptr) env->DeleteLocalRef(id);
    if (line != nullptr) env->DeleteLocalRef(line);
    endTrafficJni(attached);
}

void postTrafficFlush() {
    if (gJni.trafficFlush == nullptr) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (!beginTrafficJni(&env, &attached)) return;
    env->CallStaticVoidMethod(gJni.trafficLog, gJni.trafficFlush);
    if (env->ExceptionCheck()) env->ExceptionClear();
    endTrafficJni(attached);
}

void appendTrafficLine(const std::string & path, const std::string & line) {
    (void)path;
    postTrafficNote("main", line);
}

void emitTrafficLine(const std::string & line, const std::string & path) {
    __android_log_print(ANDROID_LOG_DEBUG, "LiveIMAP", "%s", line.c_str());
    __android_log_print(ANDROID_LOG_INFO, "LiveIMAP", "%s", line.c_str());
    appendTrafficLine(path, line);
}

// Sent and received stay separate streams. Kotlin owns the file.
void logImapBuffer(LiveSession * live, int log_type, const char * str, size_t size,
    const std::string & connectionId) {
    if (live == nullptr || str == nullptr || size == 0) return;
    if (log_type != MAILSTREAM_LOG_TYPE_DATA_SENT_PRIVATE &&
        log_type != MAILSTREAM_LOG_TYPE_DATA_SENT &&
        log_type != MAILSTREAM_LOG_TYPE_DATA_RECEIVED) {
        return;
    }
    postTrafficBytes(connectionId, log_type, str, size);
}

void flushTrafficTail(LiveSession * live) {
    (void)live;
    postTrafficFlush();
}

void noteParsedRows(LiveSession * session, size_t count) {
    if (session == nullptr || !session->logImapTraffic) return;
    emitTrafficLine("parsed " + std::to_string(count), session->trafficLogPath);
}

bool abortEmptyIndexFetch(JNIEnv * env, LiveSession * session, clist * list) {
    if (list != nullptr) mailimap_fetch_list_free(list);
    if (session != nullptr && session->imap != nullptr) {
        // Tagged OK text would replace this fallback.
        session->imap->imap_response = nullptr;
    }
    throwImap(env, session, MAILIMAP_NO_ERROR, "fetch returned no rows");
    unlockSession(session);
    return true;
}

void imapTrafficLogger(mailimap * session, int log_type, const char * str, size_t size, void * context) {
    auto * live = static_cast<LiveSession *>(context);
    if (live == nullptr || !live->logImapTraffic) return;
    logImapBuffer(live, log_type, str, size, trafficConnectionId(live, session, nullptr));
}

void streamTrafficLogger(mailstream * stream, int log_type, const char * str, size_t size, void * context) {
    auto * live = static_cast<LiveSession *>(context);
    if (live == nullptr || !live->logImapTraffic) return;
    logImapBuffer(live, log_type, str, size, trafficConnectionId(live, nullptr, stream));
}

void setImapTrafficLogger(mailimap * imap, bool on, LiveSession * live) {
    if (imap == nullptr) return;
    if (!on) flushTrafficTail(live);
    if (on) {
        mailimap_set_logger(imap, imapTrafficLogger, live);
        if (imap->imap_stream != nullptr) {
            mailstream_set_logger(imap->imap_stream, streamTrafficLogger, live);
        }
    } else {
        mailimap_set_logger(imap, nullptr, nullptr);
        if (imap->imap_stream != nullptr) {
            mailstream_set_logger(imap->imap_stream, nullptr, nullptr);
        }
    }
}

struct TlsCapture {
    std::vector<std::vector<unsigned char>> ders;
};

thread_local TlsCapture * tlsCapture = nullptr;

const int kSslCtrlSetMinProtoVersion = 123;
const long kTls12Version = 0x0303;
const int kSslVerifyPeer = 1;

bool encodeDer(x509_st * cert, std::vector<unsigned char> * out) {
    if (cert == nullptr || out == nullptr) return false;
    int len = i2d_X509(cert, nullptr);
    if (len <= 0) return false;
    out->assign(static_cast<size_t>(len), 0);
    unsigned char * cursor = out->data();
    return i2d_X509(cert, &cursor) == len;
}

bool hasStartTls(struct mailimap_capability_data * cap) {
    if (cap == nullptr || cap->cap_list == nullptr) return false;
    for (clistiter * cur = clist_begin(cap->cap_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_capability *>(clist_content(cur));
        if (item == nullptr || item->cap_data.cap_name == nullptr) continue;
        if (strcasecmp(item->cap_data.cap_name, "STARTTLS") == 0) return true;
    }
    return false;
}

bool promptableTrust(const std::string & text) {
    return text == "certificate untrusted" || text == "certificate changed";
}

void storePeerOffer(JNIEnv * env, jclass cls, jobject list) {
    jmethodID method = env->GetStaticMethodID(cls, "offer", "(Ljava/util/List;)[Ljava/lang/String;");
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        clearCertOffer();
        return;
    }
    jobject result = env->CallStaticObjectMethod(cls, method, list);
    if (env->ExceptionCheck() || result == nullptr) {
        env->ExceptionClear();
        if (result != nullptr) env->DeleteLocalRef(result);
        clearCertOffer();
        return;
    }
    auto * arr = static_cast<jobjectArray>(result);
    jsize count = env->GetArrayLength(arr);
    std::vector<std::string> lines;
    if (count == 5) {
        for (jsize i = 0; i < count; ++i) {
            jobject item = env->GetObjectArrayElement(arr, i);
            if (item == nullptr || env->ExceptionCheck()) {
                env->ExceptionClear();
                if (item != nullptr) env->DeleteLocalRef(item);
                lines.clear();
                break;
            }
            lines.push_back(utf8FromJava(env, static_cast<jstring>(item)));
            env->DeleteLocalRef(item);
        }
    }
    env->DeleteLocalRef(result);
    std::lock_guard<std::mutex> lock(gCertOfferMu);
    if (lines.size() == 5) gCertOffer = std::move(lines);
    else gCertOffer.clear();
}

std::string peerTrustCheck(JNIEnv * env, const char * host, const std::vector<std::vector<unsigned char>> & ders,
    const char * pin, bool publish) {
    struct OfferGate {
        bool publish;
        bool keep = false;
        ~OfferGate() {
            if (publish && !keep) clearCertOffer();
        }
    } gate{publish};
    if (env == nullptr) return "certificate rejected";
    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass cls = env->FindClass("org/dlang/liveimap/engine/PeerTrust");
    if (cls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return "certificate rejected";
    }
    jmethodID method = env->GetStaticMethodID(
        cls, "check", "(Ljava/lang/String;Ljava/util/List;Ljava/lang/String;)Ljava/lang/String;");
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return "certificate rejected";
    }
    jobject list = newList(env);
    if (list == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (list != nullptr) env->DeleteLocalRef(list);
        env->DeleteLocalRef(cls);
        return "certificate rejected";
    }
    for (const std::vector<unsigned char> & der : ders) {
        if (der.size() > static_cast<size_t>(INT_MAX)) {
            env->DeleteLocalRef(list);
            env->DeleteLocalRef(cls);
            return "certificate rejected";
        }
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(der.size()));
        if (bytes == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            if (bytes != nullptr) env->DeleteLocalRef(bytes);
            env->DeleteLocalRef(list);
            env->DeleteLocalRef(cls);
            return "certificate rejected";
        }
        if (!der.empty()) {
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(der.size()),
                reinterpret_cast<const jbyte *>(der.data()));
        }
        listAdd(env, list, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(list);
            env->DeleteLocalRef(cls);
            return "certificate rejected";
        }
    }
    jstring jhost = newString(env, host != nullptr ? host : "");
    if (jhost == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (jhost != nullptr) env->DeleteLocalRef(jhost);
        env->DeleteLocalRef(list);
        env->DeleteLocalRef(cls);
        return "certificate rejected";
    }
    jstring jpin = newString(env, pin != nullptr ? pin : "");
    if (jpin == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (jpin != nullptr) env->DeleteLocalRef(jpin);
        env->DeleteLocalRef(jhost);
        env->DeleteLocalRef(list);
        env->DeleteLocalRef(cls);
        return "certificate rejected";
    }
    jobject result = env->CallStaticObjectMethod(cls, method, jhost, list, jpin);
    std::string text = "certificate rejected";
    if (env->ExceptionCheck() || result == nullptr) {
        env->ExceptionClear();
    } else {
        text = utf8FromJava(env, static_cast<jstring>(result));
        env->DeleteLocalRef(result);
    }
    if (gate.publish && promptableTrust(text)) {
        storePeerOffer(env, cls, list);
        gate.keep = true;
    }
    env->DeleteLocalRef(jpin);
    env->DeleteLocalRef(jhost);
    env->DeleteLocalRef(list);
    env->DeleteLocalRef(cls);
    return text;
}

void rejectImap(mailimap * imap) {
    if (imap == nullptr) return;
    mailimap_free(imap);
}

mailimap * openTcp(const char * host, int port, LiveSession * live, std::string * error, std::string * address) {
    mailimap * imap = mailimap_new(0, nullptr);
    if (imap == nullptr) {
        *error = "imap error";
        return nullptr;
    }
    mailimap_set_timeout(imap, kReadTimeoutSec);
    int fd = tcpConnect("IMAP", host, port, address, error);
    if (fd < 0) {
        mailimap_free(imap);
        return nullptr;
    }
    mailstream * stream = mailstream_socket_open_timeout(fd, kReadTimeoutSec);
    if (stream == nullptr) {
        close(fd);
        *error = connectFailure("IMAP", host, port, *address, "connection failed");
        mailimap_free(imap);
        return nullptr;
    }
    if (live != nullptr && live->logImapTraffic) {
        mailimap_set_logger(imap, imapTrafficLogger, live);
    }
    int r = mailimap_connect(imap, stream);
    if (!connectOk(r)) {
        *error = connectFailure("IMAP", host, port, *address, imapText(imap, "connection failed"));
        if (imap->imap_stream != nullptr) {
            mailstream_close(imap->imap_stream);
            imap->imap_stream = nullptr;
        } else {
            mailstream_close(stream);
        }
        mailimap_free(imap);
        return nullptr;
    }
    return imap;
}

bool loginImap(JNIEnv * env, mailimap * imap, const char * host, int port, const char * user,
    const char * password, const std::string & address, std::string * error, bool tls, bool plaintextOk) {
    struct mailimap_capability_data * caps = nullptr;
    int r = mailimap_capability(imap, &caps);
    if (!cmdOk(r) || caps == nullptr) {
        if (caps != nullptr) mailimap_capability_data_free(caps);
        *error = serviceLabel("IMAP", host, port) + " (" + address + "): capability failed: "
            + imapText(imap, "capability failed");
        return false;
    }
    std::string line = joinCapabilities(caps);
    mailimap_capability_data_free(caps);
    jclass cls = env->FindClass("org/dlang/liveimap/session/ImapAuthKt");
    if (cls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (cls != nullptr) env->DeleteLocalRef(cls);
        *error = "auth choice failed";
        return false;
    }
    jmethodID method = env->GetStaticMethodID(cls, "chooseImapAuth",
        "(Ljava/lang/String;ZZ)Ljava/lang/String;");
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        *error = "auth choice failed";
        return false;
    }
    jstring jline = newString(env, line.c_str());
    if (jline == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (jline != nullptr) env->DeleteLocalRef(jline);
        env->DeleteLocalRef(cls);
        *error = "auth choice failed";
        return false;
    }
    jvalue args[3];
    args[0].l = jline;
    args[1].z = tls ? JNI_TRUE : JNI_FALSE;
    args[2].z = plaintextOk ? JNI_TRUE : JNI_FALSE;
    jobject result = env->CallStaticObjectMethodA(cls, method, args);
    env->DeleteLocalRef(jline);
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (result != nullptr) env->DeleteLocalRef(result);
        *error = "auth choice failed";
        return false;
    }
    std::string mechanism;
    if (result != nullptr) {
        mechanism = utf8FromJava(env, static_cast<jstring>(result));
        env->DeleteLocalRef(result);
    }
    const char * name = user != nullptr ? user : "";
    if (!tls && !plaintextOk && mechanism.empty() && name[0] != '\0') {
        *error = "plaintext login needs confirmation";
        return false;
    }
    if (mechanism.empty() || name[0] == '\0') {
        r = mailimap_login(imap, user, password);
    } else {
        r = mailimap_authenticate(imap, mechanism.c_str(), host, nullptr, nullptr, user, user,
            password, nullptr);
    }
    if (!cmdOk(r)) {
        *error = serviceLabel("IMAP", host, port) + " (" + address + ") user " + name
            + ": login failed: " + imapText(imap, "login failed");
        return false;
    }
    return true;
}

mailimap * openPlain(JNIEnv * env, const char * host, int port, const char * user, const char * password,
    LiveSession * live, std::string * error, std::string * connectedAddress) {
    std::string address;
    mailimap * imap = openTcp(host, port, live, error, &address);
    if (imap == nullptr) return nullptr;
    if (!loginImap(env, imap, host, port, user, password, address, error, false,
            live != nullptr && live->allowPlaintextAuth)) {
        mailimap_free(imap);
        return nullptr;
    }
    if (connectedAddress != nullptr) *connectedAddress = address;
    return imap;
}

mailimap * openTls(JNIEnv * env, const char * mode, const char * host, int port, const char * user,
    const char * password, LiveSession * live, std::string * error, std::string * connectedAddress,
    const char * pin, bool publishOffer) {
    TlsCapture capture;
    tlsCapture = &capture;
    std::string address;
    mailimap * imap = nullptr;
    bool tlsOk = false;
    if (strcmp(mode, "StartTls") == 0) {
        imap = openTcp(host, port, live, error, &address);
        if (imap == nullptr) {
            tlsCapture = nullptr;
            return nullptr;
        }
        struct mailimap_capability_data * caps = nullptr;
        int r = mailimap_capability(imap, &caps);
        bool advertised = cmdOk(r) && hasStartTls(caps);
        if (caps != nullptr) mailimap_capability_data_free(caps);
        if (!cmdOk(r)) {
            *error = serviceLabel("IMAP", host, port) + " (" + address + "): capability failed: "
                + imapText(imap, "capability failed");
            rejectImap(imap);
            tlsCapture = nullptr;
            return nullptr;
        }
        if (!advertised) {
            *error = serviceLabel("IMAP", host, port) + " (" + address + "): STARTTLS is not advertised";
            rejectImap(imap);
            tlsCapture = nullptr;
            return nullptr;
        }
        r = mailimap_socket_starttls_with_server_name_callback(imap, host, prepareTls, nullptr);
        tlsOk = cmdOk(r);
        if (!tlsOk) {
            *error = serviceLabel("IMAP", host, port) + " (" + address + "): STARTTLS failed: "
                + imapText(imap, "STARTTLS failed");
        }
    } else if (strcmp(mode, "Implicit") == 0) {
        imap = mailimap_new(0, nullptr);
        if (imap == nullptr) {
            *error = "imap error";
            tlsCapture = nullptr;
            return nullptr;
        }
        mailimap_set_timeout(imap, kReadTimeoutSec);
        if (live != nullptr && live->logImapTraffic) {
            mailimap_set_logger(imap, imapTrafficLogger, live);
        }
        address = host != nullptr ? host : "";
        if (port < 0 || port > 65535) {
            *error = connectFailure("IMAP", host, port, address, "connection failed");
            rejectImap(imap);
            tlsCapture = nullptr;
            return nullptr;
        }
        int r = mailimap_ssl_connect_with_callback(
            imap, host, static_cast<uint16_t>(port), prepareTls, nullptr);
        tlsOk = connectOk(r);
        if (!tlsOk) {
            *error = connectFailure("IMAP", host, port, address, imapText(imap, "connection failed"));
        }
    } else {
        *error = "unknown tls mode";
        tlsCapture = nullptr;
        return nullptr;
    }
    if (capture.ders.empty()) {
        *error = "certificate rejected";
        rejectImap(imap);
        tlsCapture = nullptr;
        return nullptr;
    }
    if (!tlsOk) {
        rejectImap(imap);
        tlsCapture = nullptr;
        return nullptr;
    }
    std::string trust = peerTrustCheck(env, host, capture.ders, pin, publishOffer);
    tlsCapture = nullptr;
    if (!trust.empty()) {
        *error = trust;
        rejectImap(imap);
        return nullptr;
    }
    if (!loginImap(env, imap, host, port, user, password, address, error, true, true)) {
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
    session->selectedMailbox.clear();
    session->selectedReadWrite = false;
    flushTrafficTail(session);
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
    uint64_t knownModseq, uint64_t * modseq, const char * command) {
    *modseq = 0;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, command);
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

int selectFullCapture(mailimap * imap, const char * mailbox, uint64_t * modseq, const char * command) {
    *modseq = 0;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (command != nullptr && strcmp(command, "EXAMINE") == 0) {
        r = mailimap_examine_send(imap->imap_stream, mailbox, 0);
    } else {
        r = mailimap_select_send(imap->imap_stream, mailbox, 0);
    }
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
    if (!session->logImapTraffic || session->imap->imap_stream == nullptr) return;
    mailstream_set_logger(session->imap->imap_stream, streamTrafficLogger, session);
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

void freeScopeHit(LiveScopeHit * hit) {
    if (hit == nullptr) return;
    free(hit->mailbox);
    freeUidList(hit->uids);
    free(hit);
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

bool parseDecimal(MMAPString * buffer, size_t * indx, int64_t * out) {
    size_t cur = *indx;
    char c = peekChar(buffer, cur);
    if (c < '0' || c > '9') return false;
    int64_t value = 0;
    while (c >= '0' && c <= '9') {
        int digit = c - '0';
        if (value > (INT64_MAX - digit) / 10) return false;
        value = value * 10 + digit;
        cur += 1;
        c = peekChar(buffer, cur);
    }
    *out = value;
    *indx = cur;
    return true;
}

int failEsearch(clist * uids, char * mailbox, int r) {
    freeUidList(uids);
    free(mailbox);
    return r;
}

int parseCorrelatorGroup(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    char ** mailbox, bool * sawMailbox) {
    size_t cur = *indx;
    int r = mailimap_oparenth_parse(fd, buffer, ctx, &cur);
    if (r != MAILIMAP_NO_ERROR) return r;
    bool sawTag = false;
    bool sawBox = false;
    bool sawValidity = false;
    bool any = false;
    char * box = nullptr;
    for (;;) {
        if (peekChar(buffer, cur) == ')') break;
        if (any) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                free(box);
                return r;
            }
        }
        char * atom = nullptr;
        r = mailimap_atom_parse(fd, buffer, ctx, &cur, &atom, 0, nullptr);
        if (r != MAILIMAP_NO_ERROR) {
            free(box);
            return r;
        }
        std::string name = atom != nullptr ? atom : "";
        free(atom);
        any = true;
        if (strcasecmp(name.c_str(), "TAG") == 0) {
            if (sawTag) {
                free(box);
                return MAILIMAP_ERROR_PARSE;
            }
            sawTag = true;
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                free(box);
                return r;
            }
            r = parseTagValue(fd, buffer, ctx, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                free(box);
                return r;
            }
            continue;
        }
        if (strcasecmp(name.c_str(), "MAILBOX") == 0) {
            if (sawBox || *sawMailbox) {
                free(box);
                return MAILIMAP_ERROR_PARSE;
            }
            sawBox = true;
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                free(box);
                return r;
            }
            char * parsed = nullptr;
            r = mailimap_mailbox_parse(fd, buffer, ctx, &cur, &parsed, 0, nullptr);
            if (r != MAILIMAP_NO_ERROR || parsed == nullptr) {
                free(parsed);
                free(box);
                return r != MAILIMAP_NO_ERROR ? r : MAILIMAP_ERROR_PARSE;
            }
            free(box);
            box = parsed;
            continue;
        }
        if (strcasecmp(name.c_str(), "UIDVALIDITY") == 0) {
            if (sawValidity) {
                free(box);
                return MAILIMAP_ERROR_PARSE;
            }
            sawValidity = true;
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                free(box);
                return r;
            }
            uint32_t number = 0;
            r = mailimap_nz_number_parse(fd, buffer, ctx, &cur, &number);
            if (r != MAILIMAP_NO_ERROR) {
                free(box);
                return r;
            }
            (void)number;
            continue;
        }
        free(box);
        return MAILIMAP_ERROR_PARSE;
    }
    if (!any) {
        free(box);
        return MAILIMAP_ERROR_PARSE;
    }
    r = mailimap_cparenth_parse(fd, buffer, ctx, &cur);
    if (r != MAILIMAP_NO_ERROR) {
        free(box);
        return r;
    }
    if (sawBox) {
        free(*mailbox);
        *mailbox = box;
        *sawMailbox = true;
    } else {
        free(box);
    }
    *indx = cur;
    return MAILIMAP_NO_ERROR;
}

int parseEsearch(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    struct mailimap_extension_data ** result) {
    size_t cur = *indx;
    int r = mailimap_token_case_insensitive_parse(fd, buffer, &cur, "ESEARCH");
    if (r != MAILIMAP_NO_ERROR) return r;
    clist * uids = clist_new();
    if (uids == nullptr) return MAILIMAP_ERROR_MEMORY;
    char * mailbox = nullptr;
    bool sawMailbox = false;
    bool hasAll = false;
    bool sawCount = false;
    int64_t countValue = 0;
    for (;;) {
        size_t save = cur;
        r = mailimap_space_parse(fd, buffer, &cur);
        if (r != MAILIMAP_NO_ERROR) {
            cur = save;
            break;
        }
        if (peekChar(buffer, cur) == '(') {
            r = parseCorrelatorGroup(fd, buffer, ctx, &cur, &mailbox, &sawMailbox);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
            continue;
        }
        char * atom = nullptr;
        r = mailimap_atom_parse(fd, buffer, ctx, &cur, &atom, 0, nullptr);
        if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
        std::string name = atom != nullptr ? atom : "";
        free(atom);
        if (strcasecmp(name.c_str(), "UID") == 0) continue;
        if (strcasecmp(name.c_str(), "ALL") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
            r = parseSeqSet(fd, buffer, ctx, &cur, uids);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
            hasAll = true;
            continue;
        }
        if (strcasecmp(name.c_str(), "MIN") == 0 || strcasecmp(name.c_str(), "MAX") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
            uint32_t number = 0;
            r = mailimap_nz_number_parse(fd, buffer, ctx, &cur, &number);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
            if (!appendUid(uids, number)) return failEsearch(uids, mailbox, MAILIMAP_ERROR_MEMORY);
            continue;
        }
        if (strcasecmp(name.c_str(), "COUNT") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
            int64_t number = 0;
            if (!parseDecimal(buffer, &cur, &number)) return failEsearch(uids, mailbox, MAILIMAP_ERROR_PARSE);
            sawCount = true;
            countValue = number;
            continue;
        }
        if (strcasecmp(name.c_str(), "MODSEQ") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(uids, mailbox, r);
            while (peekChar(buffer, cur) >= '0' && peekChar(buffer, cur) <= '9') cur += 1;
            continue;
        }
        return failEsearch(uids, mailbox, MAILIMAP_ERROR_PARSE);
    }
    if (!sawMailbox && sawCount && tlsImap != nullptr && tlsLive != nullptr && tlsLive->imap == tlsImap) {
        tlsLive->esearchCount = countValue;
    }
    if (sawMailbox) {
        auto * hit = static_cast<LiveScopeHit *>(calloc(1, sizeof(LiveScopeHit)));
        if (hit == nullptr) return failEsearch(uids, mailbox, MAILIMAP_ERROR_MEMORY);
        hit->mailbox = mailbox;
        hit->uids = uids;
        hit->hasAll = hasAll ? 1 : 0;
        hit->hasCount = sawCount ? 1 : 0;
        hit->count = countValue;
        struct mailimap_extension_data * ext = mailimap_extension_data_new(&liveimap_extra_extension, LIVE_SCOPE, hit);
        if (ext == nullptr) {
            freeScopeHit(hit);
            return MAILIMAP_ERROR_MEMORY;
        }
        *result = ext;
        *indx = cur;
        return MAILIMAP_NO_ERROR;
    }
    free(mailbox);
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
    } else if (ext_data->ext_type == LIVE_SCOPE) {
        freeScopeHit(static_cast<LiveScopeHit *>(ext_data->ext_data));
    }
    free(ext_data);
}

clist * takeEsearch(mailimap * imap) {
    clist * uids = nullptr;
    struct mailimap_esearch_result * esearch = nullptr;
    if (imap->imap_response_info != nullptr && imap->imap_response_info->rsp_extension_list != nullptr) {
        for (clistiter * cur = clist_begin(imap->imap_response_info->rsp_extension_list); cur != nullptr; cur = clist_next(cur)) {
            auto * ext = static_cast<struct mailimap_extension_data *>(clist_content(cur));
            if (ext == nullptr) continue;
            if (ext->ext_extension == &liveimap_extra_extension && ext->ext_type == LIVE_ESEARCH && uids == nullptr) {
                uids = static_cast<clist *>(ext->ext_data);
                ext->ext_data = nullptr;
                ext->ext_type = -1;
            } else if (esearch == nullptr && ext->ext_extension == &mailimap_extension_esearch && ext->ext_data != nullptr) {
                esearch = static_cast<struct mailimap_esearch_result *>(ext->ext_data);
            }
        }
    }
    if (uids == nullptr && esearch != nullptr) {
        uids = esearch->msg_list;
        esearch->msg_list = nullptr;
        if (uids == nullptr) uids = clist_new();
        if (uids != nullptr && clist_begin(uids) == nullptr) {
            if (esearch->has_min) appendUid(uids, esearch->min);
            if (esearch->has_max) appendUid(uids, esearch->max);
        }
    }
    freeExtensionList(imap);
    if (uids == nullptr) uids = clist_new();
    return uids;
}

bool scopeSource(const char * scopeName, const char ** source, bool * subtree);
int sendEsearchIn(mailimap * imap, const char * scopeName, const char * home, const char * ret, bool withCharset,
    struct mailimap_search_key * key, struct mailimap_response ** response);

void freeScopeHits(clist * hits) {
    if (hits == nullptr) return;
    for (clistiter * cur = clist_begin(hits); cur != nullptr; cur = clist_next(cur)) {
        freeScopeHit(static_cast<LiveScopeHit *>(clist_content(cur)));
    }
    clist_free(hits);
}

clist * takeScopeHits(mailimap * imap) {
    clist * hits = clist_new();
    if (hits == nullptr) {
        freeExtensionList(imap);
        return nullptr;
    }
    if (imap->imap_response_info != nullptr && imap->imap_response_info->rsp_extension_list != nullptr) {
        for (clistiter * cur = clist_begin(imap->imap_response_info->rsp_extension_list); cur != nullptr; cur = clist_next(cur)) {
            auto * ext = static_cast<struct mailimap_extension_data *>(clist_content(cur));
            if (ext == nullptr) continue;
            if (ext->ext_extension == &liveimap_extra_extension && ext->ext_type == LIVE_SCOPE) {
                if (clist_append(hits, ext->ext_data) != 0) {
                    freeScopeHits(hits);
                    freeExtensionList(imap);
                    return nullptr;
                }
                ext->ext_data = nullptr;
                ext->ext_type = -1;
            }
        }
    }
    freeExtensionList(imap);
    return hits;
}

jobject scopeUidList(JNIEnv * env, clist * uids) {
    jobject list = env->NewObject(gJni.arrayList, gJni.listInit);
    if (list == nullptr || uids == nullptr) return list;
    for (clistiter * cur = clist_begin(uids); cur != nullptr; cur = clist_next(cur)) {
        auto * n = static_cast<uint32_t *>(clist_content(cur));
        if (n == nullptr) continue;
        jobject boxed = env->CallStaticObjectMethod(gJni.longCls, gJni.longValueOf, static_cast<jlong>(*n));
        if (boxed == nullptr) continue;
        env->CallBooleanMethod(list, gJni.listAdd, boxed);
        env->DeleteLocalRef(boxed);
    }
    return list;
}

jobjectArray scopeUidArray(JNIEnv * env, clist * hits) {
    std::vector<jobject> built;
    if (hits != nullptr) {
        for (clistiter * cur = clist_begin(hits); cur != nullptr; cur = clist_next(cur)) {
            auto * hit = static_cast<LiveScopeHit *>(clist_content(cur));
            if (hit == nullptr || hit->mailbox == nullptr || !hit->hasAll) continue;
            jstring name = newString(env, hit->mailbox);
            jobject list = scopeUidList(env, hit->uids);
            jobject obj = env->NewObject(gJni.mailboxUids, gJni.mailboxUidsInit, name, list);
            if (name != nullptr) env->DeleteLocalRef(name);
            if (list != nullptr) env->DeleteLocalRef(list);
            if (env->ExceptionCheck() || obj == nullptr) {
                for (jobject old : built) env->DeleteLocalRef(old);
                return nullptr;
            }
            built.push_back(obj);
        }
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(built.size()), gJni.mailboxUids, nullptr);
    if (arr == nullptr) {
        for (jobject old : built) env->DeleteLocalRef(old);
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(built.size()); ++i) {
        env->SetObjectArrayElement(arr, i, built[static_cast<size_t>(i)]);
        env->DeleteLocalRef(built[static_cast<size_t>(i)]);
    }
    return arr;
}

jlong scopeCountOf(clist * hits) {
    int64_t sum = 0;
    if (hits == nullptr) return 0;
    for (clistiter * cur = clist_begin(hits); cur != nullptr; cur = clist_next(cur)) {
        auto * hit = static_cast<LiveScopeHit *>(clist_content(cur));
        if (hit == nullptr || !hit->hasCount) continue;
        if (hit->count < 0 || sum > INT64_MAX - hit->count) return -1;
        sum += hit->count;
    }
    return static_cast<jlong>(sum);
}

bool scopeSendable(const char * scope, const char * home) {
    const char * source = nullptr;
    bool subtree = false;
    if (!scopeSource(scope, &source, &subtree)) return false;
    if (subtree && (home == nullptr || home[0] == 0)) return false;
    return true;
}

jobjectArray runScopeSearch(JNIEnv * env, LiveSession * session, const char * scope, const char * home,
    struct mailimap_search_key * key, jboolean withCharset) {
    if (key == nullptr || !scopeSendable(scope, home) || gJni.mailboxUids == nullptr || gJni.mailboxUidsInit == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_response * response = nullptr;
    int r = sendEsearchIn(session->imap, scope, home, "ALL", withCharset == JNI_TRUE, key, &response);
    mailimap_search_key_free(key);
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return nullptr;
    }
    clist * hits = takeScopeHits(session->imap);
    bool ok = response != nullptr && taggedOk(response);
    mailimap_response_free(response);
    if (!ok || hits == nullptr) {
        freeScopeHits(hits);
        throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    jobjectArray arr = scopeUidArray(env, hits);
    freeScopeHits(hits);
    if (env->ExceptionCheck() || arr == nullptr) {
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    unlockSession(session);
    return arr;
}

jlong runScopeCount(JNIEnv * env, LiveSession * session, const char * scope, const char * home,
    struct mailimap_search_key * key, jboolean withCharset) {
    if (key == nullptr || !scopeSendable(scope, home)) {
        if (key != nullptr) mailimap_search_key_free(key);
        throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    struct mailimap_response * response = nullptr;
    int r = sendEsearchIn(session->imap, scope, home, "COUNT", withCharset == JNI_TRUE, key, &response);
    mailimap_search_key_free(key);
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return -1;
    }
    clist * hits = takeScopeHits(session->imap);
    bool ok = response != nullptr && taggedOk(response);
    mailimap_response_free(response);
    if (!ok || hits == nullptr) {
        freeScopeHits(hits);
        throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    jlong sum = scopeCountOf(hits);
    freeScopeHits(hits);
    if (sum < 0) {
        throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    unlockSession(session);
    return sum;
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

int sendEsearch(mailimap * imap, bool byUid, const char * ret, bool withCharset,
    struct mailimap_search_key * key, struct mailimap_response ** response) {
    if (ret == nullptr) return MAILIMAP_ERROR_INVAL;
    if (strcasecmp(ret, "ALL") != 0 && strcasecmp(ret, "MIN") != 0 && strcasecmp(ret, "MAX") != 0
        && strcasecmp(ret, "COUNT") != 0) {
        return MAILIMAP_ERROR_INVAL;
    }
    if (strcasecmp(ret, "COUNT") == 0 && tlsLive != nullptr && tlsLive->imap == imap) {
        tlsLive->esearchCount = -1;
    }
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (byUid) {
        r = sendWord(imap->imap_stream, "UID", false);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = sendWord(imap->imap_stream, "SEARCH", byUid);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "RETURN", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "(", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, ret, false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, ")", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (withCharset) {
        r = sendWord(imap->imap_stream, "CHARSET", true);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_space_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_astring_send(imap->imap_stream, "UTF-8");
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_search_key_send(imap->imap_stream, key);
    if (r != MAILIMAP_NO_ERROR) return r;
    return finishParsed(imap, response);
}

bool scopeSource(const char * scopeName, const char ** source, bool * subtree) {
    *source = nullptr;
    *subtree = false;
    if (scopeName == nullptr) return false;
    if (strcmp(scopeName, "Subtree") == 0) {
        *source = "subtree";
        *subtree = true;
        return true;
    }
    if (strcmp(scopeName, "Subscribed") == 0) {
        *source = "subscribed";
        return true;
    }
    if (strcmp(scopeName, "All") == 0) {
        *source = "personal";
        return true;
    }
    return false;
}

int sendEsearchIn(mailimap * imap, const char * scopeName, const char * home, const char * ret, bool withCharset,
    struct mailimap_search_key * key, struct mailimap_response ** response) {
    if (ret == nullptr || (strcmp(ret, "ALL") != 0 && strcmp(ret, "COUNT") != 0)) return MAILIMAP_ERROR_INVAL;
    const char * source = nullptr;
    bool subtree = false;
    if (!scopeSource(scopeName, &source, &subtree)) return MAILIMAP_ERROR_INVAL;
    if (subtree && (home == nullptr || home[0] == 0)) return MAILIMAP_ERROR_INVAL;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "ESEARCH", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "IN", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "(", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, source, false);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (subtree) {
        r = mailimap_space_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_mailbox_send(imap->imap_stream, home);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = sendWord(imap->imap_stream, ")", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "RETURN", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "(", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, ret, false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, ")", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (withCharset) {
        r = sendWord(imap->imap_stream, "CHARSET", true);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_space_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_astring_send(imap->imap_stream, "UTF-8");
        if (r != MAILIMAP_NO_ERROR) return r;
    }
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
        else if (strcasecmp(keyName, "DISPLAYFROM") == 0) key = "DISPLAYFROM";
        else if (strcasecmp(keyName, "DISPLAYTO") == 0) key = "DISPLAYTO";
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

struct mailimap_search_key * andKey(struct mailimap_search_key * left, struct mailimap_search_key * right) {
    if (left == nullptr || right == nullptr) {
        if (left != nullptr) mailimap_search_key_free(left);
        if (right != nullptr) mailimap_search_key_free(right);
        return nullptr;
    }
    struct mailimap_search_key * multi = mailimap_search_key_new_multiple_empty();
    if (multi == nullptr) {
        mailimap_search_key_free(left);
        mailimap_search_key_free(right);
        return nullptr;
    }
    if (mailimap_search_key_multiple_add(multi, left) != MAILIMAP_NO_ERROR) {
        mailimap_search_key_free(left);
        mailimap_search_key_free(right);
        mailimap_search_key_free(multi);
        return nullptr;
    }
    if (mailimap_search_key_multiple_add(multi, right) != MAILIMAP_NO_ERROR) {
        mailimap_search_key_free(right);
        mailimap_search_key_free(multi);
        return nullptr;
    }
    return multi;
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

enum class TextSlot { From, To, Cc, Subject, Text, Body, Keyword, Unkeyword };

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
    case TextSlot::Body: key = mailimap_search_key_new_body(copy); break;
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

struct mailimap_search_key * startRuleKey(const char * rule) {
    if (sameKind(rule, "FirstUnseen")) {
        return andKey(searchKeyType(MAILIMAP_SEARCH_KEY_UNDELETED), searchKeyType(MAILIMAP_SEARCH_KEY_UNSEEN));
    }
    if (sameKind(rule, "FirstRecent")) {
        return andKey(searchKeyType(MAILIMAP_SEARCH_KEY_UNDELETED), searchKeyType(MAILIMAP_SEARCH_KEY_NEW));
    }
    if (sameKind(rule, "FirstImportant")) {
        return andKey(searchKeyType(MAILIMAP_SEARCH_KEY_UNDELETED), searchKeyType(MAILIMAP_SEARCH_KEY_FLAGGED));
    }
    if (sameKind(rule, "FirstImportantOrUnseen")) {
        return andKey(
            searchKeyType(MAILIMAP_SEARCH_KEY_UNDELETED),
            keyOr(searchKeyType(MAILIMAP_SEARCH_KEY_FLAGGED), searchKeyType(MAILIMAP_SEARCH_KEY_UNSEEN)));
    }
    if (sameKind(rule, "FirstImportantOrRecent")) {
        return andKey(
            searchKeyType(MAILIMAP_SEARCH_KEY_UNDELETED),
            keyOr(searchKeyType(MAILIMAP_SEARCH_KEY_FLAGGED), searchKeyType(MAILIMAP_SEARCH_KEY_NEW)));
    }
    if (sameKind(rule, "First") || sameKind(rule, "Last")) {
        return searchKeyType(MAILIMAP_SEARCH_KEY_UNDELETED);
    }
    return nullptr;
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
        || sameKind(kind, "Text") || sameKind(kind, "Body") || sameKind(kind, "Keyword") || sameKind(kind, "NotKeyword")
        || sameKind(kind, "Recipient") || sameKind(kind, "Participant")) {
        if (emptyText(env, argument)) return nullptr;
        if (sameKind(kind, "From")) return textKey(TextSlot::From, argument);
        if (sameKind(kind, "To")) return textKey(TextSlot::To, argument);
        if (sameKind(kind, "Cc")) return textKey(TextSlot::Cc, argument);
        if (sameKind(kind, "Subject")) return textKey(TextSlot::Subject, argument);
        if (sameKind(kind, "Text")) return textKey(TextSlot::Text, argument);
        if (sameKind(kind, "Body")) return textKey(TextSlot::Body, argument);
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

jlongArray completeSearch(JNIEnv * env, LiveSession * session, struct mailimap_search_key * key,
    jboolean useEsearch, jboolean withCharset) {
    if (useEsearch == JNI_TRUE) {
        struct mailimap_response * response = nullptr;
        int r = sendEsearch(session->imap, true, "ALL", withCharset == JNI_TRUE, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            if (result != nullptr) mailimap_search_result_free(result);
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = uidArray(env, result);
        if (result != nullptr) mailimap_search_result_free(result);
        unlockSession(session);
        return arr;
    }
    clist * result = nullptr;
    const char * charset = withCharset == JNI_TRUE ? "UTF-8" : nullptr;
    int r = mailimap_uid_search(session->imap, charset, key, &result);
    mailimap_search_key_free(key);
    if (!cmdOk(r)) {
        if (result != nullptr) mailimap_search_result_free(result);
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return nullptr;
    }
    jlongArray arr = uidArray(env, result);
    if (result != nullptr) mailimap_search_result_free(result);
    unlockSession(session);
    return arr;
}

jlongArray runEdgeSearch(JNIEnv * env, LiveSession * session, struct mailimap_search_key * key,
    bool byUid, const char * edge, bool useEsearch) {
    if (key == nullptr) {
        throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    const char * ret = "ALL";
    if (edge != nullptr && strcmp(edge, "Min") == 0) ret = "MIN";
    else if (edge != nullptr && strcmp(edge, "Max") == 0) ret = "MAX";
    else if (edge != nullptr && strcmp(edge, "All") != 0) {
        mailimap_search_key_free(key);
        throwFailure(env, "bad search");
        unlockSession(session);
        return nullptr;
    }
    if (useEsearch) {
        struct mailimap_response * response = nullptr;
        int r = sendEsearch(session->imap, byUid, ret, false, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            if (result != nullptr) mailimap_search_result_free(result);
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = uidArray(env, result);
        if (result != nullptr) mailimap_search_result_free(result);
        unlockSession(session);
        return arr;
    }
    clist * result = nullptr;
    int r = byUid
        ? mailimap_uid_search(session->imap, nullptr, key, &result)
        : mailimap_search(session->imap, nullptr, key, &result);
    mailimap_search_key_free(key);
    if (!cmdOk(r)) {
        if (result != nullptr) mailimap_search_result_free(result);
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return nullptr;
    }
    jlongArray arr = uidArray(env, result);
    if (result != nullptr) mailimap_search_result_free(result);
    unlockSession(session);
    return arr;
}

bool mailboxNoselect(struct mailimap_mailbox_list * mb) {
    if (mb == nullptr || mb->mb_flag == nullptr) return false;
    if (mb->mb_flag->mbf_type == MAILIMAP_MBX_LIST_FLAGS_SFLAG
        && mb->mb_flag->mbf_sflag == MAILIMAP_MBX_LIST_SFLAG_NOSELECT) {
        return true;
    }
    if (mb->mb_flag->mbf_oflags == nullptr) return false;
    for (clistiter * cur = clist_begin(mb->mb_flag->mbf_oflags); cur != nullptr; cur = clist_next(cur)) {
        auto * of = static_cast<struct mailimap_mbx_list_oflag *>(clist_content(cur));
        if (of == nullptr || of->of_flag_ext == nullptr) continue;
        const char * flag = of->of_flag_ext;
        if (flag[0] == '\\') ++flag;
        if (strcasecmp(flag, "Noselect") == 0) return true;
    }
    return false;
}

int listSubscribed(mailimap * imap, clist ** result) {
    *result = nullptr;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "LIST", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "(", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "SUBSCRIBED", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, ")", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_mailbox_send(imap->imap_stream, "");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_list_mailbox_send(imap->imap_stream, "*");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(imap, &response);
    if (r != MAILIMAP_NO_ERROR) return r;
    bool ok = taggedOk(response);
    if (ok && imap->imap_response_info != nullptr) {
        *result = imap->imap_response_info->rsp_mailbox_list;
        imap->imap_response_info->rsp_mailbox_list = nullptr;
    }
    mailimap_response_free(response);
    return ok ? MAILIMAP_NO_ERROR : MAILIMAP_ERROR_LIST;
}

jlong completeCount(JNIEnv * env, LiveSession * session, struct mailimap_search_key * key, jboolean withCharset) {
    session->esearchCount = -1;
    tlsLive = session;
    struct mailimap_response * response = nullptr;
    int r = sendEsearch(session->imap, true, "COUNT", withCharset == JNI_TRUE, key, &response);
    tlsLive = nullptr;
    mailimap_search_key_free(key);
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return -1;
    }
    clist * result = takeEsearch(session->imap);
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (result != nullptr) mailimap_search_result_free(result);
    if (!ok) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return -1;
    }
    jlong count = session->esearchCount;
    unlockSession(session);
    return count;
}

}  // namespace

extern "C" int recordPeerCerts(int, x509_store_ctx_st * store) {
    if (tlsCapture == nullptr || store == nullptr) return 1;
    if (X509_STORE_CTX_get_error_depth(store) != 0) return 1;
    tlsCapture->ders.clear();
    stack_st * chain = X509_STORE_CTX_get0_chain(store);
    int count = chain != nullptr ? OPENSSL_sk_num(chain) : 0;
    // OpenSSL keeps the peer certificate at index 0.
    for (int i = 0; i < count; ++i) {
        auto * cert = static_cast<x509_st *>(OPENSSL_sk_value(chain, i));
        std::vector<unsigned char> der;
        if (encodeDer(cert, &der)) tlsCapture->ders.push_back(std::move(der));
    }
    if (tlsCapture->ders.empty()) {
        std::vector<unsigned char> der;
        if (encodeDer(X509_STORE_CTX_get_current_cert(store), &der)) tlsCapture->ders.push_back(std::move(der));
    }
    return 1;
}

extern "C" void prepareTls(struct mailstream_ssl_context * ssl_context, void *) {
    if (ssl_context == nullptr) return;
    auto * ctx = static_cast<ssl_ctx_st *>(mailstream_ssl_get_openssl_ssl_ctx(ssl_context));
    if (ctx == nullptr) return;
    SSL_CTX_ctrl(ctx, kSslCtrlSetMinProtoVersion, kTls12Version, nullptr);
    SSL_CTX_set_verify(ctx, kSslVerifyPeer, recordPeerCerts);
}

std::string utf8FromJava(JNIEnv * env, jstring value) {
    if (value == nullptr) return std::string();
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return std::string();
    jsize length = env->GetStringLength(value);
    std::string out;
    out.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000u + (((cp - 0xD800u) << 10) | (low - 0xDC00u));
                ++i;
            }
        }
        if (cp < 0x80u) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800u) {
            out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else if (cp < 0x10000u) {
            out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else {
            out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

std::string decodePreviewBytes(const char * bytes, size_t length, const std::string & charset,
    const std::string & encoding, bool html) {
    if (gVm == nullptr || bytes == nullptr || length == 0 || length > static_cast<size_t>(INT_MAX)) {
        return std::string();
    }
    JNIEnv * env = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return std::string();
    }
    jclass cls = env->FindClass("org/dlang/liveimap/ui/reader/PartText");
    if (cls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return std::string();
    }
    jmethodID method = env->GetStaticMethodID(
        cls, "previewText", "([BLjava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;");
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return std::string();
    }
    jbyteArray array = env->NewByteArray(static_cast<jsize>(length));
    jstring jCharset = env->NewStringUTF(charset.c_str());
    jstring jEncoding = env->NewStringUTF(encoding.c_str());
    if (array == nullptr || jCharset == nullptr || jEncoding == nullptr) {
        env->ExceptionClear();
        if (array != nullptr) env->DeleteLocalRef(array);
        if (jCharset != nullptr) env->DeleteLocalRef(jCharset);
        if (jEncoding != nullptr) env->DeleteLocalRef(jEncoding);
        env->DeleteLocalRef(cls);
        return std::string();
    }
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(bytes));
    jobject result = env->CallStaticObjectMethod(
        cls, method, array, jCharset, jEncoding, html ? JNI_TRUE : JNI_FALSE);
    std::string text;
    if (env->ExceptionCheck() || result == nullptr) {
        env->ExceptionClear();
    } else {
        text = utf8FromJava(env, static_cast<jstring>(result));
        env->DeleteLocalRef(result);
    }
    env->DeleteLocalRef(array);
    env->DeleteLocalRef(jCharset);
    env->DeleteLocalRef(jEncoding);
    env->DeleteLocalRef(cls);
    return text;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    gVm = vm;
    gKeepUnselect = mailimap_unselect;
    registerExtensions();
    setPreviewDecoder(decodePreviewBytes);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeTakeError(JNIEnv * env, jclass) {
    registerExtensions();
    if (!ensureJni(env)) return nullptr;
    return newString(env, takeLastError().c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeTakeCertOffer(JNIEnv * env, jclass) {
    if (!ensureJni(env)) return nullptr;
    std::vector<std::string> lines;
    {
        std::lock_guard<std::mutex> lock(gCertOfferMu);
        lines = gCertOffer;
    }
    if (lines.size() != 5) return nullptr;
    jclass stringCls = env->FindClass("java/lang/String");
    if (stringCls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    jobjectArray arr = env->NewObjectArray(5, stringCls, nullptr);
    env->DeleteLocalRef(stringCls);
    if (arr == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    for (jsize i = 0; i < 5; ++i) {
        jstring js = newString(env, lines[static_cast<size_t>(i)].c_str());
        if (js == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(arr);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeOpen(JNIEnv * env, jobject,
    jstring host, jint port, jstring user, jstring password, jstring smtpHost, jint smtpPort, jstring from,
    jboolean pipelineFlag, jboolean logFlag, jstring logPath, jstring tlsMode, jstring certPin,
    jboolean allowPlaintext) {
    registerExtensions();
    if (!ensureJni(env)) return 0;
    JChars h(env, host);
    JChars u(env, user);
    JChars p(env, password);
    JChars sh(env, smtpHost);
    JChars fr(env, from);
    JChars path(env, logPath);
    JChars mode(env, tlsMode);
    JChars pin(env, certPin);
    clearCertOffer();
    auto * session = new LiveSession();
    session->host = h.c();
    session->imapPort = port;
    session->user = u.c();
    session->password = p.c();
    session->smtpHost = sh.c();
    session->smtpPort = smtpPort;
    session->from = fr.c()[0] != 0 ? fr.c() : u.c();
    session->pipelineCommands = pipelineFlag == JNI_TRUE;
    session->logImapTraffic = logFlag == JNI_TRUE;
    session->trafficLogPath = path.c();
    session->tlsMode = mode.c();
    session->certPin = pin.c();
    session->allowPlaintextAuth = allowPlaintext == JNI_TRUE;
    TrafficIdScope idScope("main");
    std::string error;
    std::string address;
    mailimap * imap = nullptr;
    if (strcmp(mode.c(), "None") == 0) {
        imap = openPlain(env, h.c(), port, u.c(), p.c(), session, &error, &address);
    } else {
        imap = openTls(env, mode.c(), h.c(), port, u.c(), p.c(), session, &error, &address, session->certPin.c_str(), true);
    }
    if (imap == nullptr) {
        flushTrafficTail(session);
        delete session;
        setLastError(error);
        return 0;
    }
    struct mailimap_capability_data * caps = nullptr;
    int r = mailimap_capability(imap, &caps);
    if (!cmdOk(r) || caps == nullptr) {
        setLastError(serviceLabel("IMAP", h.c(), port) + " (" + address + "): capability failed: "
            + imapText(imap, "capability failed"));
        mailimap_free(imap);
        flushTrafficTail(session);
        delete session;
        return 0;
    }
    session->imap = imap;
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
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSetSessionFlags(JNIEnv * env, jobject, jlong handle,
    jboolean pipelineFlag, jboolean logFlag, jstring logPath) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    JChars path(env, logPath);
    session->pipelineCommands = pipelineFlag == JNI_TRUE;
    session->logImapTraffic = logFlag == JNI_TRUE;
    session->trafficLogPath = path.c();
    setImapTrafficLogger(session->imap, session->logImapTraffic, session);
    setImapTrafficLogger(session->watch, session->logImapTraffic, session);
    unlockSession(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSessionDead(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return JNI_TRUE;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return JNI_TRUE;
    jboolean dead = session->imap == nullptr ? JNI_TRUE : JNI_FALSE;
    unlockSession(session);
    return dead;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeNoop(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return -1;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return -1;
    if (session->imap == nullptr) {
        throwImap(env, session, MAILIMAP_ERROR_STREAM, "noop failed");
        unlockSession(session);
        return -1;
    }
    int r = mailimap_noop(session->imap);
    if (!cmdOk(r)) {
        throwImap(env, session, r, "noop failed");
        unlockSession(session);
        return -1;
    }
    struct mailimap_selection_info * info = session->imap->imap_selection_info;
    jint exists = info != nullptr ? static_cast<jint>(info->sel_exists) : 0;
    unlockSession(session);
    return exists;
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
        throwImap(env, session, r, "namespace failed");
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

extern "C" JNIEXPORT jchar JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeHierarchyDelimiter(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return 0;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return 0;
    if (session->imap == nullptr) {
        throwFailure(env, "not connected");
        unlockSession(session);
        return 0;
    }
    clist * list = nullptr;
    int r = mailimap_list(session->imap, "", "", &list);
    if (!cmdOk(r)) {
        if (list != nullptr) mailimap_list_result_free(list);
        throwImap(env, session, r, "list failed");
        unlockSession(session);
        return 0;
    }
    char delim = 0;
    bool found = false;
    if (list != nullptr && clist_begin(list) != nullptr) {
        auto * mb = static_cast<struct mailimap_mailbox_list *>(clist_content(clist_begin(list)));
        if (mb != nullptr) {
            delim = mb->mb_delimiter;
            found = true;
        }
    }
    if (list != nullptr) mailimap_list_result_free(list);
    if (!found) {
        throwFailure(env, "list failed");
        unlockSession(session);
        return 0;
    }
    unlockSession(session);
    return static_cast<jchar>(delim);
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
        r = statusMessagesPipeline(session->imap, names, &counts, session->pipelineCommands);
    }
    if (r != MAILIMAP_NO_ERROR) {
        releaseBurstFailure(env, session, r, "status failed");
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
        throwImap(env, session, r, "list failed");
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

extern "C" JNIEXPORT jboolean JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeMailboxListed(JNIEnv * env, jobject, jlong handle,
    jstring mailbox) {
    if (!ensureJni(env)) {
        throwFailure(env, "list failed");
        return JNI_FALSE;
    }
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return JNI_FALSE;
    JChars name(env, mailbox);
    clist * list = nullptr;
    int r = mailimap_list(session->imap, "", name.c(), &list);
    if (!cmdOk(r)) {
        if (list != nullptr) mailimap_list_result_free(list);
        throwImap(env, session, r, "list failed");
        unlockSession(session);
        return JNI_FALSE;
    }
    bool found = false;
    bool inbox = strcasecmp(name.c(), "INBOX") == 0;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * mb = static_cast<struct mailimap_mailbox_list *>(clist_content(cur));
            if (mb == nullptr || mb->mb_name == nullptr) continue;
            bool same = inbox ? strcasecmp(mb->mb_name, "INBOX") == 0 : strcmp(mb->mb_name, name.c()) == 0;
            if (!same || mailboxNoselect(mb)) continue;
            found = true;
            break;
        }
        mailimap_list_result_free(list);
    }
    unlockSession(session);
    return found ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSubscribedMailboxes(JNIEnv * env, jobject, jlong handle,
    jboolean extended) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    if (session->imap == nullptr) {
        throwFailure(env, "not connected");
        unlockSession(session);
        return nullptr;
    }
    clist * list = nullptr;
    int r = extended == JNI_TRUE
        ? listSubscribed(session->imap, &list)
        : mailimap_lsub(session->imap, "", "*", &list);
    if (!cmdOk(r)) {
        if (list != nullptr) mailimap_list_result_free(list);
        throwImap(env, session, r, "list failed");
        unlockSession(session);
        return nullptr;
    }
    std::vector<std::string> names;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * mb = static_cast<struct mailimap_mailbox_list *>(clist_content(cur));
            if (mb == nullptr || mb->mb_name == nullptr || mb->mb_name[0] == 0) continue;
            if (mailboxNoselect(mb)) continue;
            names.emplace_back(mb->mb_name);
        }
        mailimap_list_result_free(list);
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), stringClass, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(names.size()); ++i) {
        jstring name = newString(env, names[static_cast<size_t>(i)].c_str());
        env->SetObjectArrayElement(arr, i, name);
        if (name != nullptr) env->DeleteLocalRef(name);
    }
    if (stringClass != nullptr) env->DeleteLocalRef(stringClass);
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSelect(JNIEnv * env, jobject, jlong handle, jstring mailbox,
    jboolean readWrite) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars mb(env, mailbox);
    if (!selectMailbox(env, session, mb.c(), readWrite == JNI_TRUE)) {
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
        throwImap(env, session, r, "unselect failed");
    } else {
        session->selectedMailbox.clear();
        session->selectedReadWrite = false;
    }
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCloseMailbox(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    if (session->selectedMailbox.empty() || session->imap == nullptr) {
        throwFailure(env, "close failed");
        unlockSession(session);
        return;
    }
    int r = mailimap_close(session->imap);
    if (!cmdOk(r)) {
        throwImap(env, session, r, "close failed");
    } else {
        session->selectedMailbox.clear();
        session->selectedReadWrite = false;
    }
    unlockSession(session);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchIndex(JNIEnv * env, jobject, jlong handle,
    jstring mailbox, jint mode, jint firstSequence, jint lastSequence, jlongArray uids, jint limit,
    jint prefetch, jboolean includePreview, jint previewByteLimit, jboolean preferHtml, jboolean showDeleted,
    jboolean useServerPreview, jstring accountEmail, jstring altAddresses) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars accountEmailChars(env, accountEmail);
    JChars altChars(env, altAddresses);
    JChars mb(env, mailbox);
    if (session->selectedMailbox != mb.c()) {
        if (!selectMailbox(env, session, mb.c(), false)) {
            unlockSession(session);
            return nullptr;
        }
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
        if (mode == 3) {
            uint32_t reqFirst = firstSequence < 1 ? 1 : static_cast<uint32_t>(firstSequence);
            uint32_t reqLast = lastSequence < 1 ? 1 : static_cast<uint32_t>(lastSequence);
            if (reqFirst > exists) reqFirst = exists;
            if (reqLast > exists) reqLast = exists;
            first = reqFirst;
            last = reqLast;
            if (first > last) {
                uint32_t swap = first;
                first = last;
                last = swap;
            }
        } else if (mode == 0) {
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
            throwImap(env, session, r, "fetch failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<Row> rows = rowsFromList(list, accountEmailChars.c(), altChars.c());
        noteParsedRows(session, rows.size());
        if (exists > 0 && first <= last && rows.empty()) {
            abortEmptyIndexFetch(env, session, list);
            return nullptr;
        }
        std::vector<std::string> previews(rows.size());
        std::vector<char> havePreview(rows.size(), 0);
        if (serverPreview) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (!rows[i].hasPreview) continue;
                previews[i] = rows[i].preview;
                havePreview[i] = 1;
            }
        } else if (bodyPreview) {
            std::vector<PreviewAsk> asks;
            std::vector<size_t> at;
            for (size_t i = 0; i < rows.size(); ++i) {
                if (rows[i].structure == nullptr) continue;
                PartWant want;
                findPreferred(rows[i].structure, "", preferHtml == JNI_TRUE, &want);
                if (!want.found) continue;
                asks.push_back(PreviewAsk{rows[i].uid, want.section, want.charset, want.encoding, want.html});
                at.push_back(i);
                havePreview[i] = 1;
            }
            std::vector<std::string> got;
            int br = previewBurst(session->imap, asks, peekLimit, got, session->pipelineCommands);
            if (br != MAILIMAP_NO_ERROR) {
                if (list != nullptr) mailimap_fetch_list_free(list);
                releaseBurstFailure(env, session, br, "fetch failed");
                return nullptr;
            }
            for (size_t n = 0; n < at.size() && n < got.size(); ++n) {
                previews[at[n]] = got[n];
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
            throwImap(env, session, r, "fetch failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<Row> rows = rowsFromList(list, accountEmailChars.c(), altChars.c());
        noteParsedRows(session, rows.size());
        if (exists > 0 && !page.empty() && rows.empty()) {
            abortEmptyIndexFetch(env, session, list);
            return nullptr;
        }
        std::vector<std::string> previews(rows.size());
        std::vector<char> havePreview(rows.size(), 0);
        if (serverPreview) {
            for (size_t i = 0; i < rows.size(); ++i) {
                if (!rows[i].hasPreview) continue;
                previews[i] = rows[i].preview;
                havePreview[i] = 1;
            }
        } else if (bodyPreview) {
            std::vector<PreviewAsk> asks;
            std::vector<size_t> at;
            for (size_t i = 0; i < rows.size(); ++i) {
                if (rows[i].structure == nullptr) continue;
                PartWant want;
                findPreferred(rows[i].structure, "", preferHtml == JNI_TRUE, &want);
                if (!want.found) continue;
                asks.push_back(PreviewAsk{rows[i].uid, want.section, want.charset, want.encoding, want.html});
                at.push_back(i);
                havePreview[i] = 1;
            }
            std::vector<std::string> got;
            int br = previewBurst(session->imap, asks, peekLimit, got, session->pipelineCommands);
            if (br != MAILIMAP_NO_ERROR) {
                if (list != nullptr) mailimap_fetch_list_free(list);
                releaseBurstFailure(env, session, br, "fetch failed");
                return nullptr;
            }
            for (size_t n = 0; n < at.size() && n < got.size(); ++n) {
                previews[at[n]] = got[n];
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
        throwImap(env, session, r, "fetch failed");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_body * body = nullptr;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr && body == nullptr; cur = clist_next(cur)) {
            Row row;
            readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row, nullptr, nullptr);
            body = row.structure;
        }
    }
    jobject obj = mimeFromBody(env, body, "");
    if (list != nullptr) mailimap_fetch_list_free(list);
    if (obj == nullptr && !env->ExceptionCheck()) {
        throwImap(env, session, r, "fetch failed");
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
        throwImap(env, session, r, "fetch failed");
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
        throwImap(env, session, r, "fetch failed");
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

bool applyFlagChange(JNIEnv * env, LiveSession * session, struct mailimap_set * set,
    jobjectArray add, jobjectArray remove) {
    struct mailimap_flag_list * addFlags = nullptr;
    struct mailimap_flag_list * removeFlags = nullptr;
    if (!flagListFromArray(env, add, &addFlags) || !flagListFromArray(env, remove, &removeFlags)) {
        if (addFlags != nullptr) mailimap_flag_list_free(addFlags);
        if (removeFlags != nullptr) mailimap_flag_list_free(removeFlags);
        throwFailure(env, "flag must be one of \\Answered, \\Flagged, \\Deleted, \\Seen, or \\Draft");
        return false;
    }
    if (add != nullptr && env->GetArrayLength(add) > 0) {
        struct mailimap_store_att_flags * att = mailimap_store_att_flags_new_add_flags(addFlags);
        addFlags = nullptr;
        int r = mailimap_uid_store(session->imap, set, att);
        mailimap_store_att_flags_free(att);
        if (!cmdOk(r)) {
            if (removeFlags != nullptr) mailimap_flag_list_free(removeFlags);
            throwImap(env, session, r, "store failed");
            return false;
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
            throwImap(env, session, r, "store failed");
            return false;
        }
    } else if (removeFlags != nullptr) {
        mailimap_flag_list_free(removeFlags);
    }
    return true;
}

void rememberDestUids(LiveSession * session, struct mailimap_set * dest) {
    session->copiedUids.clear();
    if (dest == nullptr || dest->set_list == nullptr) return;
    for (clistiter * cur = clist_begin(dest->set_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_set_item *>(clist_content(cur));
        if (item == nullptr || item->set_last == 0) continue;
        uint32_t first = item->set_first;
        uint32_t last = item->set_last;
        if (last < first) {
            uint32_t swap = first;
            first = last;
            last = swap;
        }
        for (uint32_t uid = first;; ++uid) {
            if (uid != 0) session->copiedUids.push_back(uid);
            if (uid == last) break;
        }
    }
}

void freeUidSets(struct mailimap_set * source, struct mailimap_set * dest) {
    if (source != nullptr) mailimap_set_free(source);
    if (dest != nullptr) mailimap_set_free(dest);
}

bool copyOrMoveSet(JNIEnv * env, LiveSession * session, struct mailimap_set * set,
    const char * destMailbox, const char * kind) {
    session->copiedUids.clear();
    uint32_t uidvalidity = 0;
    struct mailimap_set * source = nullptr;
    struct mailimap_set * copied = nullptr;
    bool move = kind != nullptr && strcasecmp(kind, "Move") == 0;
    bool copyOnly = kind != nullptr && strcasecmp(kind, "Copy") == 0;
    int r = move
        ? mailimap_uidplus_uid_move(session->imap, set, destMailbox, &uidvalidity, &source, &copied)
        : mailimap_uidplus_uid_copy(session->imap, set, destMailbox, &uidvalidity, &source, &copied);
    if (!cmdOk(r)) {
        freeUidSets(source, copied);
        throwImap(env, session, r, move ? "move failed" : "copy failed");
        return false;
    }
    rememberDestUids(session, copied);
    freeUidSets(source, copied);
    if (move || copyOnly) return true;
    struct mailimap_flag_list * flags = mailimap_flag_list_new_empty();
    mailimap_flag_list_add(flags, mailimap_flag_new_deleted());
    struct mailimap_store_att_flags * att = mailimap_store_att_flags_new_add_flags(flags);
    r = mailimap_uid_store(session->imap, set, att);
    mailimap_store_att_flags_free(att);
    if (!cmdOk(r)) {
        throwImap(env, session, r, "store failed");
        return false;
    }
    return true;
}

std::vector<uint32_t> uidsFromArray(JNIEnv * env, jlongArray uids) {
    std::vector<uint32_t> values;
    if (uids == nullptr) return values;
    jsize n = env->GetArrayLength(uids);
    std::vector<jlong> raw(static_cast<size_t>(n));
    if (n > 0) env->GetLongArrayRegion(uids, 0, n, raw.data());
    for (jlong value : raw) if (value > 0) values.push_back(static_cast<uint32_t>(value));
    return values;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStoreFlags(JNIEnv * env, jobject, jlong handle,
    jlongArray uids, jobjectArray add, jobjectArray remove) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    std::vector<uint32_t> values = uidsFromArray(env, uids);
    if (values.empty()) {
        unlockSession(session);
        return;
    }
    struct mailimap_set * set = setFromUids(values);
    if (set == nullptr) {
        throwFailure(env, "store failed");
        unlockSession(session);
        return;
    }
    applyFlagChange(env, session, set, add, remove);
    mailimap_set_free(set);
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStoreFlagsAll(JNIEnv * env, jobject, jlong handle,
    jobjectArray add, jobjectArray remove) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    struct mailimap_set * set = mailimap_set_new_interval(1, 0);
    if (set == nullptr) {
        throwFailure(env, "store failed");
        unlockSession(session);
        return;
    }
    applyFlagChange(env, session, set, add, remove);
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
    if (!cmdOk(r)) throwImap(env, session, r, "expunge failed");
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeUidExpunge(JNIEnv * env, jobject, jlong handle,
    jlongArray uids) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    std::vector<uint32_t> values = uidsFromArray(env, uids);
    if (values.empty()) {
        unlockSession(session);
        return;
    }
    struct mailimap_set * set = setFromUids(values);
    if (set == nullptr) {
        throwFailure(env, "expunge failed");
        unlockSession(session);
        return;
    }
    int r = mailimap_uid_expunge(session->imap, set);
    mailimap_set_free(set);
    if (!cmdOk(r)) throwImap(env, session, r, "expunge failed");
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCopyThenDelete(JNIEnv * env, jobject, jlong handle,
    jlongArray uids, jstring target, jstring moveKind) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    std::vector<uint32_t> values = uidsFromArray(env, uids);
    if (values.empty()) {
        unlockSession(session);
        return;
    }
    JChars dest(env, target);
    JChars kindChars(env, moveKind);
    struct mailimap_set * set = setFromUids(values);
    if (set == nullptr) {
        throwFailure(env, "copy failed");
        unlockSession(session);
        return;
    }
    copyOrMoveSet(env, session, set, dest.c(), kindChars.c());
    mailimap_set_free(set);
    unlockSession(session);
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCopyAllThenDelete(JNIEnv * env, jobject, jlong handle,
    jstring target, jstring moveKind) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    struct mailimap_set * set = mailimap_set_new_interval(1, 0);
    if (set == nullptr) {
        throwFailure(env, "copy failed");
        unlockSession(session);
        return;
    }
    JChars dest(env, target);
    JChars kindChars(env, moveKind);
    copyOrMoveSet(env, session, set, dest.c(), kindChars.c());
    mailimap_set_free(set);
    unlockSession(session);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeTakeCopiedUids(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    std::vector<jlong> raw;
    raw.reserve(session->copiedUids.size());
    for (uint32_t uid : session->copiedUids) raw.push_back(static_cast<jlong>(uid));
    session->copiedUids.clear();
    jlongArray arr = env->NewLongArray(static_cast<jsize>(raw.size()));
    if (arr != nullptr && !raw.empty()) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(raw.size()), raw.data());
    }
    unlockSession(session);
    return arr;
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

jlong appendUidInText(const char * text) {
    if (text == nullptr) return 0;
    jlong found = 0;
    const char * p = text;
    while (*p != 0) {
        if (strncasecmp(p, "APPENDUID", 9) == 0) {
            const char * q = p + 9;
            while (*q == ' ' || *q == '\t') ++q;
            if (*q >= '0' && *q <= '9') {
                while (*q >= '0' && *q <= '9') ++q;
                while (*q == ' ' || *q == '\t') ++q;
                if (*q >= '0' && *q <= '9') {
                    char * end = nullptr;
                    unsigned long long value = strtoull(q, &end, 10);
                    if (end != q) found = static_cast<jlong>(value);
                }
            }
        }
        ++p;
    }
    return found;
}

jlong appendUidFromBuffer(mailimap * imap) {
    if (imap == nullptr || imap->imap_stream_buffer == nullptr) return 0;
    return appendUidInText(imap->imap_stream_buffer->str);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeAppend(JNIEnv * env, jobject, jlong handle,
    jstring mailbox, jbyteArray message, jobjectArray flags) {
    if (!ensureJni(env)) return 0;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return 0;
    if (!appendFlagsAreDraft(env, flags)) {
        throwFailure(env, "unsupported append flag");
        unlockSession(session);
        return 0;
    }
    struct mailimap_flag_list * flagList = draftFlagList(env, flags);
    if (env->ExceptionCheck()) {
        unlockSession(session);
        return 0;
    }
    JChars mb(env, mailbox);
    jsize n = message != nullptr ? env->GetArrayLength(message) : 0;
    jbyte * bytes = n > 0 ? env->GetByteArrayElements(message, nullptr) : nullptr;
    int r = appendLiteralPlus(session->imap, mb.c(),
        bytes != nullptr ? reinterpret_cast<char *>(bytes) : "", static_cast<size_t>(n), flagList);
    if (bytes != nullptr) env->ReleaseByteArrayElements(message, bytes, JNI_ABORT);
    if (flagList != nullptr) mailimap_flag_list_free(flagList);
    jlong uid = 0;
    if (!cmdOk(r)) {
        throwImap(env, session, r, "append failed");
    } else {
        uid = appendUidFromBuffer(session->imap);
    }
    unlockSession(session);
    return uid;
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
        int r = sendEsearch(session->imap, true, "ALL", true, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            if (result != nullptr) mailimap_search_result_free(result);
            throwImap(env, session, r, "search failed");
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
        throwImap(env, session, r, "search failed");
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
    jstring kind, jstring argument, jboolean useEsearch, jboolean withCharset) {
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
    return completeSearch(env, session, key, useEsearch, withCharset);
}

struct mailimap_search_key * advancedKey(JNIEnv * env, const char * combiner, jbooleanArray negated,
    jobjectArray kinds, jobjectArray arguments) {
    if (!sameKind(combiner, "And") && !sameKind(combiner, "Or")) {
        throwFailure(env, "bad search");
        return nullptr;
    }
    if (kinds == nullptr || arguments == nullptr || negated == nullptr) {
        throwFailure(env, "bad search");
        return nullptr;
    }
    jsize n = env->GetArrayLength(kinds);
    if (n <= 0 || env->GetArrayLength(arguments) != n || env->GetArrayLength(negated) != n) {
        throwFailure(env, "bad search");
        return nullptr;
    }
    jboolean * flags = env->GetBooleanArrayElements(negated, nullptr);
    if (flags == nullptr) {
        throwFailure(env, "bad search");
        return nullptr;
    }
    const bool foldAnd = sameKind(combiner, "And");
    struct mailimap_search_key * folded = nullptr;
    for (jsize i = 0; i < n; ++i) {
        jstring kindJs = static_cast<jstring>(env->GetObjectArrayElement(kinds, i));
        jstring argJs = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        struct mailimap_search_key * step = nullptr;
        {
            JChars kindChars(env, kindJs);
            JChars argChars(env, argJs);
            step = criterionKey(env, kindChars.c(), argChars.c());
        }
        if (kindJs != nullptr) env->DeleteLocalRef(kindJs);
        if (argJs != nullptr) env->DeleteLocalRef(argJs);
        if (env->ExceptionCheck() || step == nullptr) {
            if (step != nullptr) mailimap_search_key_free(step);
            if (folded != nullptr) mailimap_search_key_free(folded);
            env->ReleaseBooleanArrayElements(negated, flags, JNI_ABORT);
            if (!env->ExceptionCheck()) throwFailure(env, "search failed");
            return nullptr;
        }
        if (flags[i] == JNI_TRUE) {
            step = notKey(step);
            if (step == nullptr) {
                if (folded != nullptr) mailimap_search_key_free(folded);
                env->ReleaseBooleanArrayElements(negated, flags, JNI_ABORT);
                throwFailure(env, "search failed");
                return nullptr;
            }
        }
        if (folded == nullptr) {
            folded = step;
            continue;
        }
        folded = foldAnd ? andKey(folded, step) : keyOr(folded, step);
        if (folded == nullptr) {
            env->ReleaseBooleanArrayElements(negated, flags, JNI_ABORT);
            throwFailure(env, "search failed");
            return nullptr;
        }
    }
    env->ReleaseBooleanArrayElements(negated, flags, JNI_ABORT);
    if (folded == nullptr) {
        throwFailure(env, "bad search");
        return nullptr;
    }
    return folded;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchAdvanced(JNIEnv * env, jobject, jlong handle,
    jstring combiner, jbooleanArray negated, jobjectArray kinds, jobjectArray arguments,
    jboolean useEsearch, jboolean withCharset) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars combinerChars(env, combiner);
    struct mailimap_search_key * key = advancedKey(env, combinerChars.c(), negated, kinds, arguments);
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    return completeSearch(env, session, key, useEsearch, withCharset);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchCriterionCount(JNIEnv * env, jobject, jlong handle,
    jstring kind, jstring argument, jboolean withCharset) {
    if (!ensureJni(env)) return -1;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return -1;
    JChars kindChars(env, kind);
    JChars argChars(env, argument);
    struct mailimap_search_key * key = criterionKey(env, kindChars.c(), argChars.c());
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    return completeCount(env, session, key, withCharset);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchAdvancedCount(JNIEnv * env, jobject, jlong handle,
    jstring combiner, jbooleanArray negated, jobjectArray kinds, jobjectArray arguments, jboolean withCharset) {
    if (!ensureJni(env)) return -1;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return -1;
    JChars combinerChars(env, combiner);
    struct mailimap_search_key * key = advancedKey(env, combinerChars.c(), negated, kinds, arguments);
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    return completeCount(env, session, key, withCharset);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchScope(JNIEnv * env, jobject, jlong handle,
    jstring scope, jstring home, jstring combiner, jbooleanArray negated, jobjectArray kinds, jobjectArray arguments,
    jboolean withCharset) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars scopeChars(env, scope);
    JChars homeChars(env, home);
    JChars combinerChars(env, combiner);
    struct mailimap_search_key * key = advancedKey(env, combinerChars.c(), negated, kinds, arguments);
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    return runScopeSearch(env, session, scopeChars.c(), homeChars.c(), key, withCharset);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchScopeCount(JNIEnv * env, jobject, jlong handle,
    jstring scope, jstring home, jstring combiner, jbooleanArray negated, jobjectArray kinds, jobjectArray arguments,
    jboolean withCharset) {
    if (!ensureJni(env)) return -1;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return -1;
    JChars scopeChars(env, scope);
    JChars homeChars(env, home);
    JChars combinerChars(env, combiner);
    struct mailimap_search_key * key = advancedKey(env, combinerChars.c(), negated, kinds, arguments);
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    return runScopeCount(env, session, scopeChars.c(), homeChars.c(), key, withCharset);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchStart(JNIEnv * env, jobject, jlong handle,
    jstring rule, jboolean byUid, jstring edge, jboolean useEsearch) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars ruleChars(env, rule);
    JChars edgeChars(env, edge);
    if (sameKind(ruleChars.c(), "Newest")) {
        unlockSession(session);
        return env->NewLongArray(0);
    }
    struct mailimap_search_key * key = startRuleKey(ruleChars.c());
    if (key == nullptr) {
        throwFailure(env, "bad search");
        unlockSession(session);
        return nullptr;
    }
    return runEdgeSearch(env, session, key, byUid == JNI_TRUE, edgeChars.c(), useEsearch == JNI_TRUE);
}

struct mailimap_search_key * uidOnlyKey(uint32_t uid) {
    struct mailimap_set * set = mailimap_set_new_single(uid);
    if (set == nullptr) return nullptr;
    struct mailimap_search_key * key = mailimap_search_key_new_uid(set);
    if (key == nullptr) mailimap_set_free(set);
    return key;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeLocateUid(JNIEnv * env, jobject, jlong handle,
    jlong uid, jboolean useEsearch) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    if (uid <= 0 || uid > 0xffffffffll) {
        throwFailure(env, "bad search");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_search_key * key = uidOnlyKey(static_cast<uint32_t>(uid));
    const char * edge = useEsearch == JNI_TRUE ? "Min" : "All";
    return runEdgeSearch(env, session, key, false, edge, useEsearch == JNI_TRUE);
}

void freeCStringList(clist * hdrs) {
    if (hdrs == nullptr) return;
    for (clistiter * cur = clist_begin(hdrs); cur != nullptr; cur = clist_next(cur)) {
        free(clist_content(cur));
    }
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

clist * uidFetchAll(JNIEnv * env, LiveSession * session, struct mailimap_fetch_type * fetch) {
    if (fetch == nullptr) {
        throwFailure(env, "fetch failed");
        return nullptr;
    }
    struct mailimap_set * set = mailimap_set_new_interval(1, 0);
    if (set == nullptr) {
        mailimap_fetch_type_free(fetch);
        throwFailure(env, "fetch failed");
        return nullptr;
    }
    clist * list = nullptr;
    int r = mailimap_uid_fetch(session->imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    if (!cmdOk(r)) {
        if (list != nullptr) mailimap_fetch_list_free(list);
        throwImap(env, session, r, "fetch failed");
        return nullptr;
    }
    return list;
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

std::string trimHeaderValue(const std::string & value) {
    size_t a = 0;
    size_t b = value.size();
    while (a < b && (value[a] == ' ' || value[a] == '\t')) ++a;
    while (b > a && (value[b - 1] == ' ' || value[b - 1] == '\t' || value[b - 1] == '\r')) --b;
    return value.substr(a, b - a);
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
        if (strcasecmp(trimHeaderValue(line.substr(0, colon)).c_str(), name) != 0) continue;
        return trimHeaderValue(line.substr(colon + 1));
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

struct SortRow {
    jlong uid;
    std::string text;
    jlong number;
    bool empty;
};

bool readSortRow(struct mailimap_msg_att * att, const char * field, SortRow * row) {
    uint32_t uid = attUid(att);
    if (uid == 0 || att == nullptr || att->att_list == nullptr) return false;
    row->uid = static_cast<jlong>(uid);
    row->text.clear();
    row->number = 0;
    row->empty = true;
    bool sawDate = false;
    bool dateMissing = true;
    bool sawSize = false;
    std::string block = firstBodyText(att);
    for (clistiter * cur = clist_begin(att->att_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(cur));
        if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC) continue;
        struct mailimap_msg_att_static * st = item->att_data.att_static;
        if (st == nullptr) continue;
        if (st->att_type == MAILIMAP_MSG_ATT_INTERNALDATE) {
            sawDate = true;
            if (st->att_data.att_internal_date == nullptr) {
                dateMissing = true;
                row->number = 0;
            } else {
                dateMissing = false;
                row->number = internalDateEpoch(st->att_data.att_internal_date);
            }
        } else if (st->att_type == MAILIMAP_MSG_ATT_RFC822_SIZE) {
            sawSize = true;
            row->number = static_cast<jlong>(st->att_data.att_rfc822_size);
        }
    }
    if (strcasecmp(field, "DATE") == 0) {
        row->empty = !sawDate || dateMissing;
        if (row->empty) row->number = 0;
        return true;
    }
    if (strcasecmp(field, "SIZE") == 0) {
        row->empty = !sawSize;
        if (row->empty) row->number = 0;
        return true;
    }
    const char * name = "Subject";
    if (strcasecmp(field, "FROM") == 0) name = "From";
    else if (strcasecmp(field, "TO") == 0) name = "To";
    else if (strcasecmp(field, "CC") == 0) name = "Cc";
    row->text = headerField(block, name);
    row->number = 0;
    row->empty = row->text.empty();
    return true;
}

jstring jStringOrEmpty(JNIEnv * env, const std::string & text) {
    jstring value = newString(env, text.c_str());
    if (value != nullptr) return value;
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchSortFields(JNIEnv * env, jobject, jlong handle,
    jstring fieldName) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars fieldChars(env, fieldName);
    const char * field = fieldChars.c();
    bool known = strcasecmp(field, "DATE") == 0 || strcasecmp(field, "FROM") == 0
        || strcasecmp(field, "SUBJECT") == 0 || strcasecmp(field, "TO") == 0
        || strcasecmp(field, "CC") == 0 || strcasecmp(field, "SIZE") == 0;
    if (!known) {
        throwFailure(env, "use an arrival IndexMode");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_fetch_att * extra = nullptr;
    if (strcasecmp(field, "DATE") == 0) extra = mailimap_fetch_att_new_internaldate();
    else if (strcasecmp(field, "SIZE") == 0) extra = mailimap_fetch_att_new_rfc822_size();
    else {
        const char * name = "Subject";
        if (strcasecmp(field, "FROM") == 0) name = "From";
        else if (strcasecmp(field, "TO") == 0) name = "To";
        else if (strcasecmp(field, "CC") == 0) name = "Cc";
        const char * names[] = { name };
        extra = peekHeaderFields(names, 1);
    }
    clist * list = uidFetchAll(env, session, uidPlusExtra(extra));
    if (env->ExceptionCheck()) {
        unlockSession(session);
        return nullptr;
    }
    std::vector<SortRow> rows;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            SortRow row;
            if (readSortRow(static_cast<struct mailimap_msg_att *>(clist_content(cur)), field, &row)) {
                rows.push_back(row);
            }
        }
        mailimap_fetch_list_free(list);
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(rows.size()), gJni.sortField, nullptr);
    if (arr == nullptr) {
        unlockSession(session);
        return nullptr;
    }
    for (size_t i = 0; i < rows.size(); ++i) {
        if (env->PushLocalFrame(8) < 0) {
            unlockSession(session);
            return nullptr;
        }
        jstring text = jStringOrEmpty(env, rows[i].text);
        jobject obj = env->NewObject(
            gJni.sortField,
            gJni.sortFieldInit,
            rows[i].uid,
            text,
            rows[i].number,
            rows[i].empty ? JNI_TRUE : JNI_FALSE);
        if (obj == nullptr) {
            env->PopLocalFrame(nullptr);
            unlockSession(session);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), obj);
        env->PopLocalFrame(nullptr);
    }
    unlockSession(session);
    return arr;
}

struct HeaderRow {
    jlong uid;
    std::string messageId;
    std::string references;
    std::string inReplyTo;
    std::string subject;
};

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchThreadHeaders(JNIEnv * env, jobject, jlong handle) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    const char * names[] = { "Message-ID", "References", "In-Reply-To", "Subject" };
    clist * list = uidFetchAll(env, session, uidPlusExtra(peekHeaderFields(names, 4)));
    if (env->ExceptionCheck()) {
        unlockSession(session);
        return nullptr;
    }
    std::vector<HeaderRow> rows;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * att = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            uint32_t uid = attUid(att);
            if (uid == 0) continue;
            std::string block = firstBodyText(att);
            HeaderRow row;
            row.uid = static_cast<jlong>(uid);
            row.messageId = headerField(block, "Message-ID");
            row.references = headerField(block, "References");
            row.inReplyTo = headerField(block, "In-Reply-To");
            row.subject = headerField(block, "Subject");
            rows.push_back(row);
        }
        mailimap_fetch_list_free(list);
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(rows.size()), gJni.threadHeader, nullptr);
    if (arr == nullptr) {
        unlockSession(session);
        return nullptr;
    }
    for (size_t i = 0; i < rows.size(); ++i) {
        if (env->PushLocalFrame(16) < 0) {
            unlockSession(session);
            return nullptr;
        }
        jstring messageId = jStringOrEmpty(env, rows[i].messageId);
        jstring references = jStringOrEmpty(env, rows[i].references);
        jstring inReplyTo = jStringOrEmpty(env, rows[i].inReplyTo);
        jstring subject = jStringOrEmpty(env, rows[i].subject);
        jobject obj = env->NewObject(
            gJni.threadHeader,
            gJni.threadHeaderInit,
            rows[i].uid,
            messageId,
            references,
            inReplyTo,
            subject);
        if (obj == nullptr) {
            env->PopLocalFrame(nullptr);
            unlockSession(session);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), obj);
        env->PopLocalFrame(nullptr);
    }
    unlockSession(session);
    return arr;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSort(JNIEnv * env, jobject, jlong handle,
    jstring keyName, jboolean newestFirst, jboolean useEsort) {
    if (!ensureJni(env)) return nullptr;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars name(env, keyName);
    bool displayName = strcasecmp(name.c(), "DISPLAYFROM") == 0 || strcasecmp(name.c(), "DISPLAYTO") == 0;
    bool known = strcasecmp(name.c(), "DATE") == 0 || strcasecmp(name.c(), "FROM") == 0
        || strcasecmp(name.c(), "SUBJECT") == 0 || strcasecmp(name.c(), "TO") == 0
        || strcasecmp(name.c(), "CC") == 0 || strcasecmp(name.c(), "SIZE") == 0
        || displayName;
    if (!known) {
        throwFailure(env, "use an arrival IndexMode");
        unlockSession(session);
        return nullptr;
    }
    if (useEsort == JNI_TRUE || displayName) {
        bool esort = useEsort == JNI_TRUE;
        bool reverse = newestFirst == JNI_TRUE;
        struct mailimap_response * response = nullptr;
        int r = sendUidSortChoice(session->imap, name.c(), reverse, esort, &response);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "sort failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = esort ? takeEsearch(session->imap) : takeSort(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            if (result != nullptr) mailimap_sort_result_free(result);
            throwImap(env, session, r, "sort failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = uidArray(env, result);
        if (result != nullptr) mailimap_sort_result_free(result);
        unlockSession(session);
        return arr;
    }
    int rev = newestFirst == JNI_TRUE ? 1 : 0;
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
        throwImap(env, session, r, "sort failed");
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
        throwImap(env, session, r, "thread failed");
        unlockSession(session);
        return nullptr;
    }
    LiveThread * tree = detachThread(session->imap);
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) {
        live_thread_free(tree);
        throwImap(env, session, r, "thread failed");
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
    TrafficIdScope idScope("watch");
    std::string error;
    mailimap * watch = nullptr;
    if (session->tlsMode == "None") {
        watch = openPlain(env, session->host.c_str(), session->imapPort, session->user.c_str(), session->password.c_str(), session, &error, nullptr);
    } else {
        watch = openTls(env, session->tlsMode.c_str(), session->host.c_str(), session->imapPort, session->user.c_str(), session->password.c_str(), session, &error, nullptr, session->certPin.c_str(), false);
    }
    if (watch == nullptr) {
        throwFailure(env, error);
        unlockSession(session);
        return;
    }
    int r = mailimap_examine(watch, mb.c());
    if (!cmdOk(r)) {
        if (r == MAILIMAP_ERROR_STREAM || r == MAILIMAP_ERROR_PARSE) {
            mailimap_free(watch);
            throwImap(env, session, r, "select failed");
        } else {
            mailimap * main = session->imap;
            session->imap = watch;
            throwImap(env, session, r, "select failed");
            session->imap = main;
            mailimap_free(watch);
        }
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

bool lineIs8bitCte(const char * line, size_t n) {
    const char prefix[] = "content-transfer-encoding:";
    const size_t prefixLen = sizeof(prefix) - 1;
    if (n < prefixLen) return false;
    for (size_t i = 0; i < prefixLen; ++i) {
        unsigned char c = static_cast<unsigned char>(line[i]);
        if (c >= 'A' && c <= 'Z') c = static_cast<unsigned char>(c - 'A' + 'a');
        if (c != static_cast<unsigned char>(prefix[i])) return false;
    }
    size_t begin = prefixLen;
    while (begin < n && (line[begin] == ' ' || line[begin] == '\t')) begin++;
    size_t end = n;
    while (end > begin && (line[end - 1] == ' ' || line[end - 1] == '\t')) end--;
    if (end - begin != 4) return false;
    const char eight[] = "8bit";
    for (size_t i = 0; i < 4; ++i) {
        unsigned char c = static_cast<unsigned char>(line[begin + i]);
        if (c >= 'A' && c <= 'Z') c = static_cast<unsigned char>(c - 'A' + 'a');
        if (c != static_cast<unsigned char>(eight[i])) return false;
    }
    return true;
}

std::string smtpAuthOffered(const char * response) {
    std::string offered;
    if (response == nullptr) return offered;
    const char * p = response;
    while (*p != '\0') {
        const char * line = p;
        while (*p != '\0' && *p != '\n' && *p != '\r') p++;
        size_t n = static_cast<size_t>(p - line);
        while (*p == '\n' || *p == '\r') p++;
        if (n < 5 || strncasecmp(line, "AUTH", 4) != 0) continue;
        if (line[4] != ' ' && line[4] != '=') continue;
        size_t i = 5;
        while (i < n) {
            while (i < n && (line[i] == ' ' || line[i] == '\t')) i++;
            size_t start = i;
            while (i < n && line[i] != ' ' && line[i] != '\t') i++;
            if (i == start) continue;
            if (!offered.empty()) offered.push_back(' ');
            offered.append("AUTH=");
            offered.append(line + start, i - start);
        }
    }
    return offered;
}

bool offeredPlainOrLogin(const std::string & offered) {
    size_t i = 0;
    while (i < offered.size()) {
        while (i < offered.size() && offered[i] == ' ') i++;
        if (i >= offered.size()) break;
        size_t start = i;
        while (i < offered.size() && offered[i] != ' ') i++;
        size_t n = i - start;
        if (n <= 5 || strncasecmp(offered.c_str() + start, "AUTH=", 5) != 0) continue;
        const char * name = offered.c_str() + start + 5;
        size_t nameLen = n - 5;
        if (nameLen == 5 && strncasecmp(name, "PLAIN", 5) == 0) return true;
        if (nameLen == 5 && strncasecmp(name, "LOGIN", 5) == 0) return true;
    }
    return false;
}

bool chooseSmtpAuth(JNIEnv * env, const std::string & offered, bool tls, bool plaintextOk,
    std::string * mechanism) {
    mechanism->clear();
    jclass cls = env->FindClass("org/dlang/liveimap/session/ImapAuthKt");
    if (cls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (cls != nullptr) env->DeleteLocalRef(cls);
        return false;
    }
    jmethodID method = env->GetStaticMethodID(cls, "chooseImapAuth",
        "(Ljava/lang/String;ZZ)Ljava/lang/String;");
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return false;
    }
    jstring jline = newString(env, offered.c_str());
    if (jline == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (jline != nullptr) env->DeleteLocalRef(jline);
        env->DeleteLocalRef(cls);
        return false;
    }
    jvalue args[3];
    args[0].l = jline;
    args[1].z = tls ? JNI_TRUE : JNI_FALSE;
    args[2].z = plaintextOk ? JNI_TRUE : JNI_FALSE;
    jobject result = env->CallStaticObjectMethodA(cls, method, args);
    env->DeleteLocalRef(jline);
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (result != nullptr) env->DeleteLocalRef(result);
        return false;
    }
    if (result != nullptr) {
        *mechanism = utf8FromJava(env, static_cast<jstring>(result));
        env->DeleteLocalRef(result);
    }
    return true;
}

bool messageHas8bitCte(const char * bytes, size_t n) {
    size_t start = 0;
    for (size_t i = 0; i <= n; ++i) {
        if (i == n || bytes[i] == '\n') {
            size_t end = i;
            if (end > start && bytes[end - 1] == '\r') end--;
            if (end > start && lineIs8bitCte(bytes + start, end - start)) return true;
            start = i + 1;
        }
    }
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSmtp(JNIEnv * env, jobject, jlong handle,
    jbyteArray message, jobjectArray recipients, jstring smtpUsername, jstring smtpPassword) {
    if (!ensureJni(env)) return;
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    std::string authUser = utf8FromJava(env, smtpUsername);
    if (authUser.empty()) authUser = session->user;
    std::string authPass = utf8FromJava(env, smtpPassword);
    if (authPass.empty()) authPass = session->password;
    const std::string & mode = session->tlsMode;
    if (mode != "None" && mode != "StartTls" && mode != "Implicit") {
        throwFailure(env, "unknown tls mode");
        unlockSession(session);
        return;
    }
    mailsmtp * smtp = mailsmtp_new(0, nullptr);
    if (smtp == nullptr) {
        throwFailure(env, "smtp error");
        unlockSession(session);
        return;
    }
    mailsmtp_set_timeout(smtp, kReadTimeoutSec);
    const char * host = session->smtpHost.c_str();
    int port = session->smtpPort;
    std::string address;
    std::string where;
    TlsCapture capture;
    bool secured = false;
    if (mode == "Implicit") {
        address = session->smtpHost;
        if (port < 1 || port > 65535) {
            mailsmtp_free(smtp);
            throwFailure(env, connectFailure("SMTP", host, port, address, "connection failed"));
            unlockSession(session);
            return;
        }
        tlsCapture = &capture;
        int r = mailsmtp_ssl_connect_with_callback(
            smtp, host, static_cast<uint16_t>(port), prepareTls, nullptr);
        tlsCapture = nullptr;
        if (r != MAILSMTP_NO_ERROR) {
            mailsmtp_free(smtp);
            throwFailure(env, connectFailure("SMTP", host, port, address,
                asciiSafe(smtp->response, "connection failed")));
            unlockSession(session);
            return;
        }
        if (capture.ders.empty()) {
            mailsmtp_free(smtp);
            throwFailure(env, "certificate rejected");
            unlockSession(session);
            return;
        }
        secured = true;
        where = serviceLabel("SMTP", host, port) + " (" + address + "): ";
    } else {
        std::string dialError;
        int fd = tcpConnect("SMTP", host, port, &address, &dialError);
        if (fd < 0) {
            mailsmtp_free(smtp);
            throwFailure(env, dialError);
            unlockSession(session);
            return;
        }
        mailstream * stream = mailstream_socket_open_timeout(fd, kReadTimeoutSec);
        if (stream == nullptr) {
            close(fd);
            mailsmtp_free(smtp);
            throwFailure(env, connectFailure("SMTP", host, port, address, "connection failed"));
            unlockSession(session);
            return;
        }
        where = serviceLabel("SMTP", host, port) + " (" + address + "): ";
        int r = mailsmtp_connect(smtp, stream);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = where + asciiSafe(smtp->response, "connection failed");
            mailsmtp_free(smtp);
            throwFailure(env, why);
            unlockSession(session);
            return;
        }
        if (mode == "StartTls") {
            r = mailesmtp_ehlo(smtp);
            if (r == MAILSMTP_NO_ERROR) {
                if ((smtp->esmtp & MAILSMTP_ESMTP_STARTTLS) == 0) {
                    mailsmtp_free(smtp);
                    throwFailure(env, "STARTTLS is not advertised");
                    unlockSession(session);
                    return;
                }
            } else if (r == MAILSMTP_ERROR_NOT_IMPLEMENTED ||
                r == MAILSMTP_ERROR_UNEXPECTED_CODE ||
                r == MAILSMTP_ERROR_ACTION_NOT_TAKEN) {
                mailsmtp_free(smtp);
                throwFailure(env, "STARTTLS is not advertised");
                unlockSession(session);
                return;
            } else {
                std::string why = where + asciiSafe(smtp->response, "smtp error");
                mailsmtp_free(smtp);
                throwFailure(env, why);
                unlockSession(session);
                return;
            }
            tlsCapture = &capture;
            r = mailsmtp_socket_starttls_with_server_name_callback(smtp, host, prepareTls, nullptr);
            tlsCapture = nullptr;
            if (r == MAILSMTP_ERROR_STARTTLS_NOT_SUPPORTED) {
                mailsmtp_free(smtp);
                throwFailure(env, "STARTTLS is not advertised");
                unlockSession(session);
                return;
            }
            if (r != MAILSMTP_NO_ERROR) {
                std::string response = asciiSafe(smtp->response, "");
                std::string why = response.empty() ? std::string("STARTTLS failed")
                    : std::string("STARTTLS failed: ") + response;
                mailsmtp_free(smtp);
                throwFailure(env, why);
                unlockSession(session);
                return;
            }
            if (capture.ders.empty()) {
                mailsmtp_free(smtp);
                throwFailure(env, "certificate rejected");
                unlockSession(session);
                return;
            }
            secured = true;
        }
    }
    if (secured) {
        const char * pin = "";
        if (!session->host.empty() && !session->smtpHost.empty() &&
            strcmp(session->host.c_str(), host) == 0) {
            pin = session->certPin.c_str();
        }
        std::string trust = peerTrustCheck(env, host, capture.ders, pin, false);
        if (!trust.empty()) {
            mailsmtp_free(smtp);
            throwFailure(env, trust);
            unlockSession(session);
            return;
        }
    }
    bool allow8bit = false;
    std::string offeredAuth;
    int r = mailesmtp_ehlo(smtp);
    if (r == MAILSMTP_NO_ERROR) {
        allow8bit = (smtp->esmtp & MAILSMTP_ESMTP_8BITMIME) != 0;
        offeredAuth = smtpAuthOffered(smtp->response);
    } else if (r == MAILSMTP_ERROR_NOT_IMPLEMENTED ||
        r == MAILSMTP_ERROR_UNEXPECTED_CODE ||
        r == MAILSMTP_ERROR_ACTION_NOT_TAKEN) {
        r = mailsmtp_helo(smtp);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = where + asciiSafe(smtp->response, "smtp error");
            mailsmtp_free(smtp);
            throwFailure(env, why);
            unlockSession(session);
            return;
        }
    } else {
        std::string why = where + asciiSafe(smtp->response, "smtp error");
        mailsmtp_free(smtp);
        throwFailure(env, why);
        unlockSession(session);
        return;
    }
    const bool smtpTls = mode == "StartTls" || mode == "Implicit";
    std::string mechanism;
    if (!chooseSmtpAuth(env, offeredAuth, smtpTls, session->allowPlaintextAuth, &mechanism)) {
        mailsmtp_free(smtp);
        throwFailure(env, "auth choice failed");
        unlockSession(session);
        return;
    }
    if (!mechanism.empty()) {
        if (authUser.empty()) {
            mailsmtp_free(smtp);
            throwFailure(env, "no usable SMTP authentication");
            unlockSession(session);
            return;
        }
        r = mailesmtp_auth_sasl(smtp, mechanism.c_str(), session->smtpHost.c_str(), nullptr,
            nullptr, authUser.c_str(), authUser.c_str(), authPass.c_str(),
            nullptr);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = asciiSafe(smtp->response, "smtp error");
            mailsmtp_free(smtp);
            throwFailure(env, why);
            unlockSession(session);
            return;
        }
    } else if (!offeredAuth.empty()) {
        if (!authUser.empty() && !smtpTls && !session->allowPlaintextAuth &&
            offeredPlainOrLogin(offeredAuth)) {
            mailsmtp_free(smtp);
            throwFailure(env, "The password was not sent.");
            unlockSession(session);
            return;
        }
        mailsmtp_free(smtp);
        throwFailure(env, "no usable SMTP authentication");
        unlockSession(session);
        return;
    }
    jsize nmsg = message != nullptr ? env->GetArrayLength(message) : 0;
    jbyte * raw = nmsg > 0 ? env->GetByteArrayElements(message, nullptr) : nullptr;
    if (env->ExceptionCheck()) {
        if (raw != nullptr) env->ReleaseByteArrayElements(message, raw, JNI_ABORT);
        mailsmtp_free(smtp);
        unlockSession(session);
        return;
    }
    if (!allow8bit && raw != nullptr &&
        messageHas8bitCte(reinterpret_cast<const char *>(raw), static_cast<size_t>(nmsg))) {
        env->ReleaseByteArrayElements(message, raw, JNI_ABORT);
        mailsmtp_free(smtp);
        throwFailure(env, "8bitmime");
        unlockSession(session);
        return;
    }
    if (raw != nullptr) env->ReleaseByteArrayElements(message, raw, JNI_ABORT);
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
