#include <algorithm>
#include <libetpan/libetpan.h>
#include <libetpan/charconv.h>
#include <libetpan/unselect.h>
#include <libetpan/esearch.h>
#include <libetpan/mailimap_socket.h>
#include <libetpan/mailimap_ssl.h>
#include <libetpan/mailstream_ssl.h>

#include "imap_bursts.h"
#include "keychain_key.h"

#include <jni.h>
#include <android/log.h>

#include <poll.h>
#include <sys/eventfd.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/uio.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <climits>
#include <cstring>
#include <ctime>
#include <strings.h>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <random>
#include <string>
#include <thread>
#include <type_traits>
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
struct ssl_st;
struct x509_st;
struct x509_store_ctx_st;
struct stack_st;
extern "C" {
long SSL_CTX_ctrl(ssl_ctx_st * ctx, int cmd, long larg, void * parg);
unsigned long ERR_get_error(void);
void ERR_clear_error(void);
void SSL_CTX_set_security_level(ssl_ctx_st * ctx, int level);
int SSL_CTX_set_cipher_list(ssl_ctx_st * ctx, const char * list);
void SSL_CTX_set_info_callback(ssl_ctx_st * ctx, void (* cb)(const ssl_st *, int, int));
const char * SSL_alert_desc_string_long(int value);
const char * SSL_state_string_long(const ssl_st * s);
void ERR_error_string_n(unsigned long e, char * buf, size_t len);
void SSL_CTX_set_verify(ssl_ctx_st * ctx, int mode, int (* callback)(int, x509_store_ctx_st *));
int X509_STORE_CTX_get_error_depth(x509_store_ctx_st * ctx);
x509_st * X509_STORE_CTX_get_current_cert(x509_store_ctx_st * ctx);
stack_st * X509_STORE_CTX_get0_chain(x509_store_ctx_st * ctx);
stack_st * X509_STORE_CTX_get0_untrusted(x509_store_ctx_st * ctx);
int OPENSSL_sk_num(const stack_st * sk);
void * OPENSSL_sk_value(const stack_st * sk, int i);
int i2d_X509(x509_st * cert, unsigned char ** out);
int SSL_version(const ssl_st * ssl);
int SSL_pending(const ssl_st * ssl);
size_t SSL_get_peer_finished(const ssl_st * ssl, void * buf, size_t count);
size_t SSL_get_finished(const ssl_st * ssl, void * buf, size_t count);
int SSL_export_keying_material(ssl_st * ssl, unsigned char * out, size_t olen,
    const char * label, size_t llen, const unsigned char * context,
    size_t contextlen, int use_context);
const void * SSL_get_current_cipher(const ssl_st * ssl);
const char * SSL_CIPHER_get_name(const void * cipher);
const char * SSL_get_version(const ssl_st * ssl);
struct evp_pkey_st;
int SSL_CTX_use_certificate(ssl_ctx_st * ctx, x509_st * x);
int SSL_CTX_use_PrivateKey(ssl_ctx_st * ctx, evp_pkey_st * pkey);
int SSL_CTX_check_private_key(const ssl_ctx_st * ctx);
void X509_free(x509_st * x);
void EVP_PKEY_free(evp_pkey_st * pkey);
void OPENSSL_sk_pop_free(stack_st * sk, void (* fn)(void *));
}

// Negotiated TLS of this login, returned on the open that produced it.
static std::string negotiatedTls(mailstream * stream, const char * bindingName) {
    if (stream == nullptr) return {};
    ssl_st * ssl = static_cast<ssl_st *>(mailstream_ssl_get_openssl_ssl(stream));
    if (ssl == nullptr) return {};
    const void * cipher = SSL_get_current_cipher(ssl);
    return std::string(SSL_get_version(ssl)) + " " + (cipher ? SSL_CIPHER_get_name(cipher) : "?") +
        " binding=" + (bindingName ? bindingName : "none");
}

static void fillTlsChannelBinding(mailstream * stream, unsigned char * binding,
    size_t * bindingLen, const char ** bindingName) {
    *bindingLen = 0;
    *bindingName = nullptr;
    if (stream == nullptr) return;
    ssl_st * ssl = static_cast<ssl_st *>(mailstream_ssl_get_openssl_ssl(stream));
    if (ssl == nullptr) return;
    int version = SSL_version(ssl);
    if (version == 0x0304) {
        // RFC 9266 label is 24 octets. The NUL is not part of it.
        static const char label[] = "EXPORTER-Channel-Binding";
        int exported = SSL_export_keying_material(
            ssl, binding, 32, label, sizeof(label) - 1, nullptr, 0, 1);
        if (exported == 1) {
            *bindingName = "tls-exporter";
            *bindingLen = 32;
        }
    } else if (version == 0x0303) {
        // RFC 5929: tls-unique is the first Finished message of the handshake. In a full handshake that is
        // the client's own Finished (SSL_get_finished), not the server's; Cyrus rejected the peer one.
        size_t n = SSL_get_finished(ssl, binding, 64);
        if (n > 0 && n <= 64) {
            *bindingName = "tls-unique";
            *bindingLen = n;
        }
    }
}

std::optional<std::string> utf8FromJava(JNIEnv * env, jstring value);
extern "C" void prepareTls(struct mailstream_ssl_context * ssl_context, void * data);
const char * sniName(const char * host);

extern std::atomic<bool> gLabMaxTls12;
std::string tlsFailureReason();

extern thread_local JNIEnv * mimeEnv;

thread_local uint64_t tlsMaxResultUids = 0;
thread_local bool tlsResultUidCap = false;
uint64_t resultUidCap();

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

// UIDs parsed by this file, not a libetpan clist. One allocation for the whole set.
struct UidBuf {
    std::vector<uint32_t> uids;
};

struct LiveScopeHit {
    char * mailbox;
    UidBuf * uids;
    uint32_t uidValidity;
    int hasValidity;
    int hasAll;
    int hasCount;
    int64_t count;
};

struct LiveBlob {
    char * data;
    size_t len;
    int isNil;
};

extern JavaVM * gVm;

// Overwrite a secret before its memory is released (F21); volatile so the stores are not optimized away.
static void wipeSecret(std::string & secret) {
    volatile char * p = secret.empty() ? nullptr : &secret[0];
    for (size_t i = 0; i < secret.size(); ++i) p[i] = 0;
    secret.clear();
    secret.shrink_to_fit();
}

/* F21: XOR [text] with [mask] (same length) in place; masking and unmasking are the same operation. */
static void xorMask(std::string & text, const std::string & mask) {
    for (size_t i = 0; i < text.size() && i < mask.size(); ++i) text[i] = static_cast<char>(text[i] ^ mask[i]);
}

/* F21: a fresh random mask of [n] bytes. */
static std::string randomMask(size_t n) {
    std::random_device rd;
    std::string mask(n, '\0');
    for (size_t i = 0; i < n; ++i) mask[i] = static_cast<char>(rd() & 0xff);
    return mask;
}

struct SecretWipe {
    std::string & secret;
    ~SecretWipe() { wipeSecret(secret); }
};

// One IDLE event held until nativeWatch publishes that generation or clears it.
struct WatchNote {
    int kind = 0;
    int exists = 0;
    jlong uid = 0;
    std::vector<std::string> flags;
};


struct TrafficFile {
    int fd = -1;
    int64_t bytes = 0;
    std::string dir;
    std::string conn;
    int64_t rollBytes = 0;
    bool failed = false;
    std::string path;
    TrafficFile() = default;
    TrafficFile(const TrafficFile &) = delete;
    TrafficFile & operator=(const TrafficFile &) = delete;
    ~TrafficFile() {
        if (fd >= 0) ::close(fd);
        fd = -1;
    }
};

std::atomic<uint64_t> gTrafficSerial{1};

void trafficClose(TrafficFile * file) {
    if (file == nullptr || file->fd < 0) return;
    ::close(file->fd);
    file->fd = -1;
    file->bytes = 0;
}

void trafficFail(TrafficFile * file) {
    if (file == nullptr || file->failed) return;
    file->failed = true;
    __android_log_print(ANDROID_LOG_WARN, "LiveIMAP", "traffic write failed for %s",
        file->conn.empty() ? "?" : file->conn.c_str());
    trafficClose(file);
}

bool trafficOpen(TrafficFile * file) {
    if (file == nullptr || file->failed || file->dir.empty() || file->conn.empty()) return false;
    trafficClose(file);
    if (!file->dir.empty() && file->dir.back() != '/') file->dir.push_back('/');
    mkdir(file->dir.c_str(), 0700);
    uint64_t serial = gTrafficSerial.fetch_add(1);
    long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    char name[160];
    snprintf(name, sizeof(name), "%lld-%s-%llu.log", ms, file->conn.c_str(),
        static_cast<unsigned long long>(serial));
    std::string path = file->dir + name;
    // O_WRONLY: an open with no access mode is O_RDONLY, and writev then fails with EBADF.
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) {
        trafficFail(file);
        return false;
    }
    file->fd = fd;
    file->path = std::move(path);
    file->bytes = 0;
    return true;
}

void trafficEnsure(TrafficFile * file) {
    if (file == nullptr || file->fd >= 0 || file->failed || file->dir.empty()) return;
    trafficOpen(file);
}

void trafficCheckLink(TrafficFile * file) {
    if (file == nullptr || file->fd < 0) return;
    struct stat st;
    if (fstat(file->fd, &st) != 0 || st.st_nlink == 0) {
        trafficClose(file);
        trafficOpen(file);
    }
}

void trafficMaybeRoll(TrafficFile * file) {
    if (file == nullptr || file->fd < 0) return;
    if (file->rollBytes > 0 && file->bytes > file->rollBytes) {
        trafficClose(file);
        trafficOpen(file);
    }
}

void trafficWrite(TrafficFile * file, char dir, const char * data, size_t size) {
    if (file == nullptr || file->failed || file->dir.empty() || data == nullptr) return;
    trafficEnsure(file);
    trafficCheckLink(file);
    if (file->fd < 0) return;
    long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    char header[192];
    int hlen = snprintf(header, sizeof(header), "%lld %s %c %zu\n", ms, file->conn.c_str(), dir, size);
    if (hlen <= 0 || hlen >= static_cast<int>(sizeof(header))) {
        trafficFail(file);
        return;
    }
    char nl = '\n';
    struct iovec iov[3];
    iov[0].iov_base = header;
    iov[0].iov_len = static_cast<size_t>(hlen);
    iov[1].iov_base = const_cast<char *>(data);
    iov[1].iov_len = size;
    iov[2].iov_base = &nl;
    iov[2].iov_len = 1;
    int niov = 3;
    if (size == 0) niov = 2;
    ssize_t n = ::writev(file->fd, iov, niov);
    if (n < 0) {
        trafficFail(file);
        return;
    }
    file->bytes += n;
    trafficMaybeRoll(file);
}

void trafficPrivate(TrafficFile * file, size_t n) {
    char buf[64];
    int len = snprintf(buf, sizeof(buf), "<private %zu bytes>", n);
    if (len <= 0) return;
    trafficWrite(file, '>', buf, static_cast<size_t>(len));
}

void trafficNote(TrafficFile * file, const std::string & text) {
    if (text.empty()) return;
    trafficWrite(file, '!', text.data(), text.size());
}

void trafficFromLibetpan(TrafficFile * file, int log_type, const char * str, size_t size) {
    if (file == nullptr || str == nullptr || size == 0) return;
    if (log_type == MAILSTREAM_LOG_TYPE_DATA_SENT_PRIVATE) {
        trafficPrivate(file, size);
        return;
    }
    if (log_type == MAILSTREAM_LOG_TYPE_DATA_SENT) {
        trafficWrite(file, '>', str, size);
        return;
    }
    if (log_type == MAILSTREAM_LOG_TYPE_DATA_RECEIVED) {
        trafficWrite(file, '<', str, size);
    }
}

void fileTrafficLogger(mailimap *, int log_type, const char * str, size_t size, void * context) {
    trafficFromLibetpan(static_cast<TrafficFile *>(context), log_type, str, size);
}

void smtpFileLogger(mailsmtp *, int log_type, const char * str, size_t size, void * context) {
    trafficFromLibetpan(static_cast<TrafficFile *>(context), log_type, str, size);
}

void bindTraffic(TrafficFile * file, const std::string & dir, const char * conn, int64_t roll) {
    if (file == nullptr) return;
    if (file->dir != dir || file->rollBytes != roll) trafficClose(file);
    file->dir = dir;
    file->conn = conn;
    file->rollBytes = roll;
    file->failed = false;
}

struct OwnerJob;

struct LiveSession {
    // Intrusive published-session list. Publish and unlink CAS under the reclaim flag.
    std::atomic<LiveSession *> next{nullptr};
    std::shared_ptr<LiveSession> selfHold;
    // Long-search side connection: INPROGRESS (RFC 9585) progress, read without the session lock, and the socket
    // that Cancel shuts down from another thread.
    std::atomic<int64_t> progressDone{-1};
    std::atomic<int64_t> progressTotal{-1};
    std::atomic<int64_t> progressLines{0};
    std::atomic<int> abortFd{-1};
    // In-flight SMTP socket, beside abortFd, so a close or abort can shut it down without waiting for QUIT.
    std::atomic<int> smtpFd{-1};
    // Credentials are stored and the watch connection is the only login. imap stays null.
    bool stagedWatch = false;
    bool watchProgress = false;
    mailimap * imap = nullptr;
    std::string host;
    int imapPort = 143;
    std::string user;
    // F21: kept for watch logins, but never in clear: XOR-masked with a per-session random key (passwordMask),
    // unmasked only into a temporary that the caller wipes. Both wiped in the destructor.
    std::string password;
    std::string passwordMask;
    std::string tlsMode = "None";
    std::string smtpTlsMode;
    std::string certPin;
    bool allowPlaintextAuth = false;
    // Advanced, per account, both off by default: accept a certificate that fails validation (like
    // pine /novalidate-cert), and allow TLS 1.0/1.1, small DH and legacy ciphers (security level 0).
    bool acceptInvalidCert = false;
    bool allowOldTls = false;
    // Advanced: force one IMAP login method. "" = automatic, "IMAP-LOGIN" = the IMAP LOGIN command, else a SASL name.
    std::string authMechanism;
    // Android KeyChain certificate: chain (DER, leaf first) and a global ref to the keystore PrivateKey.
    std::vector<std::string> keyChainChain;
    jobject keyChainKey = nullptr;
    ~LiveSession() {
        int fd = jobFd.exchange(-1);
        if (fd >= 0) close(fd);
        JNIEnv * env = nullptr;
        if (keyChainKey != nullptr && gVm != nullptr
            && gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
            env->DeleteGlobalRef(keyChainKey);
        }
        wipeSecret(password);
        wipeSecret(passwordMask);
    }
    std::string smtpHost;
    int smtpPort = 25;
    std::string from;
    bool pipelineCommands = true;
    int connectTimeoutSec = 30;
    int readTimeoutSec = 60;
    int idleRenewSec = 9 * 60;
    int watchWakeMs = 5000;
    std::string trafficDir;
    int64_t trafficRollBytes = 0;
    TrafficFile imapTraffic;
    TrafficFile watchTraffic;
    std::string capabilityLine;
    std::map<std::string, char> delims;
    bool qresync = false;
    // ENABLE UTF8=ACCEPT accepted (RFC 6855): mailbox names on the wire are UTF-8, not modified UTF-7.
    bool utf8 = false;
    std::string selectedMailbox;
    bool selectedReadWrite = false;
    // Highest modseq from the last CONDSTORE select of the selected mailbox only.
    uint64_t selectedModseq = 0;

    mailimap * watch = nullptr;
    // Logged-in watch connection waiting for the capability gate, before EXAMINE and IDLE.
    mailimap * stagedReady = nullptr;
    std::thread owner;
    std::atomic<int> jobFd{-1};
    std::atomic<OwnerJob *> jobHead{nullptr};
    std::atomic<int> jobKick{0};
    std::atomic<bool> ownerStop{false};
    std::atomic<bool> ownerFinished{false};
    // Set before the queue is closed when AttachCurrentThread failed, so skipped jobs say so.
    std::atomic<bool> ownerAttachFailed{false};
    std::atomic<bool> watchStop{false};
    // 0 stopped, 2 IDLE running, 3 stopping. Waited with atomic::wait.
    std::atomic<int> watchPhase{0};
    int startState = 0;
    std::string startError;
    // 0 queue, 1 deliver to the published generation, 2 drop. Owner thread only.
    int watchGate = 0;
    std::vector<WatchNote> watchQueue;
    bool watchLostBeforePublish = false;
    bool watchEnded = false;
    jobject selfGlobal = nullptr;
    uint32_t watchUidValidity = 0;
    int watchExists = 0;
    // At most one ShownFlags (kind 5) per network IDLE wake.
    bool watchShownFlagsSent = false;
    // F38: written by a stop request so the IDLE poll wakes at once; the poll timeout is only a backstop.
    int watchWakeFd = -1;
    // Callers between retain and unlock. freeSession waits until this is zero.
    std::atomic<int> users{0};
    std::atomic<uint64_t> watchGeneration{0};
    std::atomic<bool> closed{false};
    std::atomic<uint64_t> maxResultUids{2000000};
    std::chrono::steady_clock::time_point watchIdleSince{};
    int watchPollFd = -1;
    bool watchLost = false;
    int64_t esearchCount = -1;
};

JavaVM * gVm = nullptr;
std::atomic<LiveSession *> gLiveHead{nullptr};
std::atomic<int> gWalkers{0};
std::atomic<bool> gReclaiming{false};
int (* gKeepUnselect)(mailimap *) = mailimap_unselect;

struct JniCache {
    jclass mailFailure = nullptr;
    jclass connectionLost = nullptr;
    jclass indexRow = nullptr;
    jclass folderEntry = nullptr;
    jclass ns = nullptr;
    jclass nsKind = nullptr;
    jclass selectResult = nullptr;
    jclass mimePart = nullptr;
    jclass mimeTextResult = nullptr;
    jclass messageRef = nullptr;
    jclass arrayList = nullptr;
    jclass hashSet = nullptr;
    jclass longCls = nullptr;
    jclass integerCls = nullptr;
    jclass sessionOpen = nullptr;
    jclass copyMove = nullptr;
    jmethodID indexRowInit = nullptr;
    jmethodID folderInit = nullptr;
    jmethodID nsInit = nullptr;
    jmethodID selectInit = nullptr;
    jmethodID mimeInit = nullptr;
    jmethodID mimeTextResultInit = nullptr;
    jmethodID messageRefInit = nullptr;
    jmethodID listInit = nullptr;
    jmethodID listAdd = nullptr;
    jmethodID listSize = nullptr;
    jmethodID listGet = nullptr;
    jmethodID setInit = nullptr;
    jmethodID setAdd = nullptr;
    jmethodID longValueOf = nullptr;
    jmethodID integerValueOf = nullptr;
    jmethodID sessionOpenInit = nullptr;
    jmethodID copyMoveInit = nullptr;
    jmethodID onWatch = nullptr;
    jmethodID publishWatch = nullptr;
    jmethodID clearPublishedWatch = nullptr;
    jfieldID personal = nullptr;
    jfieldID other = nullptr;
    jfieldID shared = nullptr;
};

JniCache gJni;
std::atomic<int> gExtOnce{0};
thread_local LiveSession * tlsWatch = nullptr;

int streamFd(mailstream * stream) {
    if (stream == nullptr) return -1;
    mailstream_low * low = mailstream_get_low(stream);
    if (low == nullptr) return -1;
    return mailstream_low_get_fd(low);
}

void shutdownFd(int fd) {
    if (fd >= 0) shutdown(fd, SHUT_RDWR);
}

void shutdownWatchSocket(LiveSession * session) {
    if (session == nullptr || session->watch == nullptr) return;
    shutdownFd(streamFd(session->watch->imap_stream));
}

void shutdownMainSocket(LiveSession * session) {
    if (session == nullptr || session->imap == nullptr) return;
    shutdownFd(streamFd(session->imap->imap_stream));
}

void signalWatchStop(LiveSession * session) {
    session->watchStop.store(true);
    int fd = session->watchWakeFd;
    if (fd >= 0) {
        uint64_t one = 1;
        ssize_t ignored = write(fd, &one, sizeof(one));
        (void)ignored;
    }
    shutdownWatchSocket(session);
}
thread_local mailimap * tlsImap = nullptr;
// The IMAP connection this thread holds via lockImapSession, so a user UID set can see whether a UID is in use.
thread_local mailimap * tlsLockedImap = nullptr;
thread_local LiveSession * tlsLive = nullptr;
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
    int expected = 0;
    if (!gExtOnce.compare_exchange_strong(expected, 1)) {
        while (gExtOnce.load() != 2) gExtOnce.wait(1);
        return;
    }
    mailimap_extension_register(&mailimap_extension_sort);
    mailimap_extension_register(&mailimap_extension_namespace);
    mailimap_extension_register(&mailimap_extension_uidplus);
    mailimap_extension_register(&mailimap_extension_enable);
    mailimap_extension_register(&mailimap_extension_condstore);
    mailimap_extension_register(&mailimap_extension_qresync);
    mailimap_extension_register(&liveimap_thread_extension);
    mailimap_extension_register(&liveimap_extra_extension);
    (void)gKeepUnselect;
    gExtOnce.store(2);
    gExtOnce.notify_all();
}

static void appendModifiedUtf8(std::string & out, uint32_t cp) {
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
        cp -= 0x10000u;
        appendModifiedUtf8(out, 0xD800u + (cp >> 10));
        appendModifiedUtf8(out, 0xDC00u + (cp & 0x3FFu));
    }
}

std::string asciiSafe(const char * text, const char * fallback) {
    std::string out;
    if (text != nullptr) {
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
                cp = 0xFFFDu;
                ++p;
                appendModifiedUtf8(out, cp);
                continue;
            }
            const bool overlong = (n == 3 && cp < 0x800) || (n == 4 && cp < 0x10000);
            if (overlong || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                cp = 0xFFFDu;
            }
            p += n;
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                appendModifiedUtf8(out, ' ');
                continue;
            }
            if (cp < 0x20 || cp == 0x7F) {
                continue;
            }
            appendModifiedUtf8(out, cp);
        }
    }
    if (out.empty()) {
        return fallback != nullptr ? fallback : "";
    }
    return out;
}

std::string imapText(mailimap * session, const char * fallback) {
    if (session != nullptr && session->imap_response != nullptr && session->imap_response[0] != 0) {
        return asciiSafe(session->imap_response, fallback);
    }
    return fallback != nullptr ? fallback : "";
}

const char * imapErrorName(int r) {
    switch (r) {
    case MAILIMAP_NO_ERROR: return "MAILIMAP_NO_ERROR";
    case MAILIMAP_NO_ERROR_AUTHENTICATED: return "MAILIMAP_NO_ERROR_AUTHENTICATED";
    case MAILIMAP_NO_ERROR_NON_AUTHENTICATED: return "MAILIMAP_NO_ERROR_NON_AUTHENTICATED";
    case MAILIMAP_ERROR_BAD_STATE: return "MAILIMAP_ERROR_BAD_STATE";
    case MAILIMAP_ERROR_STREAM: return "MAILIMAP_ERROR_STREAM";
    case MAILIMAP_ERROR_PARSE: return "MAILIMAP_ERROR_PARSE";
    case MAILIMAP_ERROR_CONNECTION_REFUSED: return "MAILIMAP_ERROR_CONNECTION_REFUSED";
    case MAILIMAP_ERROR_MEMORY: return "MAILIMAP_ERROR_MEMORY";
    case MAILIMAP_ERROR_FATAL: return "MAILIMAP_ERROR_FATAL";
    case MAILIMAP_ERROR_PROTOCOL: return "MAILIMAP_ERROR_PROTOCOL";
    case MAILIMAP_ERROR_DONT_ACCEPT_CONNECTION: return "MAILIMAP_ERROR_DONT_ACCEPT_CONNECTION";
    case MAILIMAP_ERROR_APPEND: return "MAILIMAP_ERROR_APPEND";
    case MAILIMAP_ERROR_NOOP: return "MAILIMAP_ERROR_NOOP";
    case MAILIMAP_ERROR_LOGOUT: return "MAILIMAP_ERROR_LOGOUT";
    case MAILIMAP_ERROR_CAPABILITY: return "MAILIMAP_ERROR_CAPABILITY";
    case MAILIMAP_ERROR_CHECK: return "MAILIMAP_ERROR_CHECK";
    case MAILIMAP_ERROR_CLOSE: return "MAILIMAP_ERROR_CLOSE";
    case MAILIMAP_ERROR_EXPUNGE: return "MAILIMAP_ERROR_EXPUNGE";
    case MAILIMAP_ERROR_COPY: return "MAILIMAP_ERROR_COPY";
    case MAILIMAP_ERROR_UID_COPY: return "MAILIMAP_ERROR_UID_COPY";
    case MAILIMAP_ERROR_MOVE: return "MAILIMAP_ERROR_MOVE";
    case MAILIMAP_ERROR_UID_MOVE: return "MAILIMAP_ERROR_UID_MOVE";
    case MAILIMAP_ERROR_CREATE: return "MAILIMAP_ERROR_CREATE";
    case MAILIMAP_ERROR_DELETE: return "MAILIMAP_ERROR_DELETE";
    case MAILIMAP_ERROR_EXAMINE: return "MAILIMAP_ERROR_EXAMINE";
    case MAILIMAP_ERROR_FETCH: return "MAILIMAP_ERROR_FETCH";
    case MAILIMAP_ERROR_UID_FETCH: return "MAILIMAP_ERROR_UID_FETCH";
    case MAILIMAP_ERROR_LIST: return "MAILIMAP_ERROR_LIST";
    case MAILIMAP_ERROR_LOGIN: return "MAILIMAP_ERROR_LOGIN";
    case MAILIMAP_ERROR_LSUB: return "MAILIMAP_ERROR_LSUB";
    case MAILIMAP_ERROR_RENAME: return "MAILIMAP_ERROR_RENAME";
    case MAILIMAP_ERROR_SEARCH: return "MAILIMAP_ERROR_SEARCH";
    case MAILIMAP_ERROR_UID_SEARCH: return "MAILIMAP_ERROR_UID_SEARCH";
    case MAILIMAP_ERROR_SELECT: return "MAILIMAP_ERROR_SELECT";
    case MAILIMAP_ERROR_STATUS: return "MAILIMAP_ERROR_STATUS";
    case MAILIMAP_ERROR_STORE: return "MAILIMAP_ERROR_STORE";
    case MAILIMAP_ERROR_UID_STORE: return "MAILIMAP_ERROR_UID_STORE";
    case MAILIMAP_ERROR_SUBSCRIBE: return "MAILIMAP_ERROR_SUBSCRIBE";
    case MAILIMAP_ERROR_UNSUBSCRIBE: return "MAILIMAP_ERROR_UNSUBSCRIBE";
    case MAILIMAP_ERROR_STARTTLS: return "MAILIMAP_ERROR_STARTTLS";
    case MAILIMAP_ERROR_INVAL: return "MAILIMAP_ERROR_INVAL";
    case MAILIMAP_ERROR_EXTENSION: return "MAILIMAP_ERROR_EXTENSION";
    case MAILIMAP_ERROR_SASL: return "MAILIMAP_ERROR_SASL";
    case MAILIMAP_ERROR_SSL: return "MAILIMAP_ERROR_SSL";
    case MAILIMAP_ERROR_NEEDS_MORE_DATA: return "MAILIMAP_ERROR_NEEDS_MORE_DATA";
    case MAILIMAP_ERROR_CUSTOM_COMMAND: return "MAILIMAP_ERROR_CUSTOM_COMMAND";
    case MAILIMAP_ERROR_CLIENTID: return "MAILIMAP_ERROR_CLIENTID";
    case MAILIMAP_ERROR_UNSELECT: return "MAILIMAP_ERROR_UNSELECT";
    default: return "MAILIMAP_ERROR";
    }
}

const char * smtpErrorName(int r) {
    switch (r) {
    case MAILSMTP_NO_ERROR: return "MAILSMTP_NO_ERROR";
    case MAILSMTP_ERROR_UNEXPECTED_CODE: return "MAILSMTP_ERROR_UNEXPECTED_CODE";
    case MAILSMTP_ERROR_SERVICE_NOT_AVAILABLE: return "MAILSMTP_ERROR_SERVICE_NOT_AVAILABLE";
    case MAILSMTP_ERROR_STREAM: return "MAILSMTP_ERROR_STREAM";
    case MAILSMTP_ERROR_HOSTNAME: return "MAILSMTP_ERROR_HOSTNAME";
    case MAILSMTP_ERROR_NOT_IMPLEMENTED: return "MAILSMTP_ERROR_NOT_IMPLEMENTED";
    case MAILSMTP_ERROR_ACTION_NOT_TAKEN: return "MAILSMTP_ERROR_ACTION_NOT_TAKEN";
    case MAILSMTP_ERROR_EXCEED_STORAGE_ALLOCATION: return "MAILSMTP_ERROR_EXCEED_STORAGE_ALLOCATION";
    case MAILSMTP_ERROR_IN_PROCESSING: return "MAILSMTP_ERROR_IN_PROCESSING";
    case MAILSMTP_ERROR_INSUFFICIENT_SYSTEM_STORAGE: return "MAILSMTP_ERROR_INSUFFICIENT_SYSTEM_STORAGE";
    case MAILSMTP_ERROR_MAILBOX_UNAVAILABLE: return "MAILSMTP_ERROR_MAILBOX_UNAVAILABLE";
    case MAILSMTP_ERROR_MAILBOX_NAME_NOT_ALLOWED: return "MAILSMTP_ERROR_MAILBOX_NAME_NOT_ALLOWED";
    case MAILSMTP_ERROR_BAD_SEQUENCE_OF_COMMAND: return "MAILSMTP_ERROR_BAD_SEQUENCE_OF_COMMAND";
    case MAILSMTP_ERROR_USER_NOT_LOCAL: return "MAILSMTP_ERROR_USER_NOT_LOCAL";
    case MAILSMTP_ERROR_TRANSACTION_FAILED: return "MAILSMTP_ERROR_TRANSACTION_FAILED";
    case MAILSMTP_ERROR_MEMORY: return "MAILSMTP_ERROR_MEMORY";
    case MAILSMTP_ERROR_AUTH_NOT_SUPPORTED: return "MAILSMTP_ERROR_AUTH_NOT_SUPPORTED";
    case MAILSMTP_ERROR_AUTH_LOGIN: return "MAILSMTP_ERROR_AUTH_LOGIN";
    case MAILSMTP_ERROR_AUTH_REQUIRED: return "MAILSMTP_ERROR_AUTH_REQUIRED";
    case MAILSMTP_ERROR_AUTH_TOO_WEAK: return "MAILSMTP_ERROR_AUTH_TOO_WEAK";
    case MAILSMTP_ERROR_AUTH_TRANSITION_NEEDED: return "MAILSMTP_ERROR_AUTH_TRANSITION_NEEDED";
    case MAILSMTP_ERROR_AUTH_TEMPORARY_FAILTURE: return "MAILSMTP_ERROR_AUTH_TEMPORARY_FAILTURE";
    case MAILSMTP_ERROR_AUTH_ENCRYPTION_REQUIRED: return "MAILSMTP_ERROR_AUTH_ENCRYPTION_REQUIRED";
    case MAILSMTP_ERROR_STARTTLS_TEMPORARY_FAILURE: return "MAILSMTP_ERROR_STARTTLS_TEMPORARY_FAILURE";
    case MAILSMTP_ERROR_STARTTLS_NOT_SUPPORTED: return "MAILSMTP_ERROR_STARTTLS_NOT_SUPPORTED";
    case MAILSMTP_ERROR_CONNECTION_REFUSED: return "MAILSMTP_ERROR_CONNECTION_REFUSED";
    case MAILSMTP_ERROR_AUTH_AUTHENTICATION_FAILED: return "MAILSMTP_ERROR_AUTH_AUTHENTICATION_FAILED";
    case MAILSMTP_ERROR_SSL: return "MAILSMTP_ERROR_SSL";
    case MAILSMTP_ERROR_CLIENTID_NOT_SUPPORTED: return "MAILSMTP_ERROR_CLIENTID_NOT_SUPPORTED";
    default: return "MAILSMTP_ERROR";
    }
}

std::string protocolError(const char * command, int code, const char * name, const char * response) {
    const char * cmd = command != nullptr && command[0] != 0 ? command : "command";
    const char * nm = name != nullptr ? name : "ERROR";
    std::string text = std::string(cmd) + ": " + nm + " (" + std::to_string(code) + ")";
    if (response != nullptr && response[0] != 0) {
        std::string line = asciiSafe(response, "");
        if (!line.empty()) text += " " + line;
    }
    return text;
}

std::string smtpDetail(const char * command, int code, const char * response) {
    return protocolError(command, code, smtpErrorName(code), response);
}

void throwFailure(JNIEnv * env, const std::string & text) {
    if (env->ExceptionCheck()) {
        return;
    }
    env->ThrowNew(gJni.mailFailure, text.c_str());
}

void throwConnectionLost(JNIEnv * env, const std::string & text) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass cls = gJni.connectionLost != nullptr ? gJni.connectionLost : gJni.mailFailure;
    if (cls == nullptr) {
        return;
    }
    env->ThrowNew(cls, text.c_str());
}

void throwImap(JNIEnv * env, LiveSession * session, int r, const char * fallback) {
    const char * command = fallback != nullptr && fallback[0] != 0 ? fallback : "IMAP";
    const char * response = nullptr;
    if (session != nullptr && session->imap != nullptr) response = session->imap->imap_response;
    std::string detail = protocolError(command, r, imapErrorName(r), response);
    if (r == MAILIMAP_ERROR_STREAM || r == MAILIMAP_ERROR_PARSE) {
        std::string host;
        int port = 0;
        if (session != nullptr) {
            host = session->host;
            port = session->imapPort;
            if (session->imap != nullptr) {
                mailimap_free(session->imap);
                session->imap = nullptr;
                trafficClose(&session->imapTraffic);
            }
        }
        std::string lost = "IMAP connection to " + host + ":" + std::to_string(port) + " lost (" + detail + ")";
        if (tlsResultUidCap) {
            lost += ": maxResultUids";
            tlsResultUidCap = false;
        }
        throwConnectionLost(env, lost);
        return;
    }
    throwFailure(env, detail);
}

static bool requireClass(JNIEnv * env, jclass * slot, const char * name) {
    jclass local = env->FindClass(name);
    if (local == nullptr) return false;
    *slot = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (*slot == nullptr) {
        jclass err = env->FindClass("java/lang/NoClassDefFoundError");
        if (err != nullptr) env->ThrowNew(err, name);
        return false;
    }
    return true;
}

static bool requireMethod(JNIEnv * env, jmethodID * slot, jclass cls, const char * name, const char * sig) {
    *slot = env->GetMethodID(cls, name, sig);
    return *slot != nullptr;
}

static bool requireStatic(JNIEnv * env, jmethodID * slot, jclass cls, const char * name, const char * sig) {
    *slot = env->GetStaticMethodID(cls, name, sig);
    return *slot != nullptr;
}

static bool requireField(JNIEnv * env, jfieldID * slot, jclass cls, const char * name, const char * sig) {
    *slot = env->GetStaticFieldID(cls, name, sig);
    return *slot != nullptr;
}

// Every id used from native code. A failed FindClass, GetMethodID, or GetFieldID leaves its exception
// pending (the class or member name) and JNI_OnLoad returns JNI_ERR.
bool resolveJni(JNIEnv * env) {
    if (!requireClass(env, &gJni.mailFailure, "org/dlang/liveimap/session/MailFailure")) return false;
    if (!requireClass(env, &gJni.connectionLost, "org/dlang/liveimap/session/ConnectionLost")) return false;
    if (!requireClass(env, &gJni.indexRow, "org/dlang/liveimap/session/IndexRow")) return false;
    if (!requireClass(env, &gJni.folderEntry, "org/dlang/liveimap/session/FolderEntry")) return false;
    if (!requireClass(env, &gJni.ns, "org/dlang/liveimap/session/Namespace")) return false;
    if (!requireClass(env, &gJni.nsKind, "org/dlang/liveimap/session/NamespaceKind")) return false;
    if (!requireClass(env, &gJni.selectResult, "org/dlang/liveimap/session/SelectResult")) return false;
    if (!requireClass(env, &gJni.mimePart, "org/dlang/liveimap/session/MimePart")) return false;
    if (!requireClass(env, &gJni.mimeTextResult, "org/dlang/liveimap/ui/reader/MimeTextResult")) return false;
    if (!requireClass(env, &gJni.messageRef, "org/dlang/liveimap/session/MessageRef")) return false;
    if (!requireClass(env, &gJni.arrayList, "java/util/ArrayList")) return false;
    if (!requireClass(env, &gJni.hashSet, "java/util/HashSet")) return false;
    if (!requireClass(env, &gJni.longCls, "java/lang/Long")) return false;
    if (!requireClass(env, &gJni.integerCls, "java/lang/Integer")) return false;
    if (!requireClass(env, &gJni.sessionOpen, "org/dlang/liveimap/engine/SessionOpen")) return false;
    if (!requireClass(env, &gJni.copyMove, "org/dlang/liveimap/engine/CopyMoveResult")) return false;

    if (!requireMethod(env, &gJni.indexRowInit, gJni.indexRow, "<init>",
        "(Lorg/dlang/liveimap/session/MessageRef;ILjava/util/Set;JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZLjava/lang/String;Ljava/lang/String;Z)V")) return false;
    if (!requireMethod(env, &gJni.folderInit, gJni.folderEntry, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;ZCLjava/lang/String;Ljava/lang/Integer;Ljava/lang/Integer;)V")) return false;
    if (!requireMethod(env, &gJni.nsInit, gJni.ns, "<init>",
        "(Ljava/lang/String;CLorg/dlang/liveimap/session/NamespaceKind;)V")) return false;
    if (!requireMethod(env, &gJni.selectInit, gJni.selectResult, "<init>", "(JJI)V")) return false;
    if (!requireMethod(env, &gJni.mimeInit, gJni.mimePart, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/util/List;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V")) return false;
    if (!requireMethod(env, &gJni.mimeTextResultInit, gJni.mimeTextResult, "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;ZZ)V")) return false;
    if (!requireMethod(env, &gJni.messageRefInit, gJni.messageRef, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JJ)V")) return false;
    if (!requireMethod(env, &gJni.listInit, gJni.arrayList, "<init>", "()V")) return false;
    if (!requireMethod(env, &gJni.listAdd, gJni.arrayList, "add", "(Ljava/lang/Object;)Z")) return false;
    if (!requireMethod(env, &gJni.listSize, gJni.arrayList, "size", "()I")) return false;
    if (!requireMethod(env, &gJni.listGet, gJni.arrayList, "get", "(I)Ljava/lang/Object;")) return false;
    if (!requireMethod(env, &gJni.setInit, gJni.hashSet, "<init>", "()V")) return false;
    if (!requireMethod(env, &gJni.setAdd, gJni.hashSet, "add", "(Ljava/lang/Object;)Z")) return false;
    if (!requireStatic(env, &gJni.longValueOf, gJni.longCls, "valueOf", "(J)Ljava/lang/Long;")) return false;
    if (!requireStatic(env, &gJni.integerValueOf, gJni.integerCls, "valueOf", "(I)Ljava/lang/Integer;")) return false;
    if (!requireMethod(env, &gJni.sessionOpenInit, gJni.sessionOpen, "<init>",
        "(JLjava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")) return false;
    if (!requireMethod(env, &gJni.copyMoveInit, gJni.copyMove, "<init>", "([JJ)V")) return false;
    if (!requireField(env, &gJni.personal, gJni.nsKind, "Personal",
        "Lorg/dlang/liveimap/session/NamespaceKind;")) return false;
    if (!requireField(env, &gJni.other, gJni.nsKind, "Other",
        "Lorg/dlang/liveimap/session/NamespaceKind;")) return false;
    if (!requireField(env, &gJni.shared, gJni.nsKind, "Shared",
        "Lorg/dlang/liveimap/session/NamespaceKind;")) return false;
    jclass sessionCls = env->FindClass("org/dlang/liveimap/engine/LibetpanMailSession");
    if (sessionCls == nullptr) return false;
    if (!requireMethod(env, &gJni.onWatch, sessionCls, "onNativeWatch", "(JIIJ[Ljava/lang/String;)V")) {
        env->DeleteLocalRef(sessionCls);
        return false;
    }
    if (!requireMethod(env, &gJni.publishWatch, sessionCls, "publishWatch", "(JLjava/lang/Object;)V")) {
        env->DeleteLocalRef(sessionCls);
        return false;
    }
    if (!requireMethod(env, &gJni.clearPublishedWatch, sessionCls, "clearPublishedWatch", "(J)V")) {
        env->DeleteLocalRef(sessionCls);
        return false;
    }
    env->DeleteLocalRef(sessionCls);
    return true;
}

/* Standard UTF-8 (not JNI modified UTF-8) because these strings go on the wire (F41): a supplementary character
 * must be one 4-byte sequence, not two encoded surrogates. The copy is wiped on release (it may be a password). */
struct JChars {
    std::optional<std::string> s;
    JChars(JNIEnv * e, jstring js) : s(utf8FromJava(e, js)) {}
    ~JChars() { if (s) wipeSecret(*s); }
    JChars(const JChars &) = delete;
    JChars & operator=(const JChars &) = delete;
    // A failed copy is nullopt, not "". Callers that only test emptiness would send that as a real
    // empty password or query. A line break is refused before it is written on the wire.
    const char * c() const { return s ? s->c_str() : "\n"; }
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
        // F111: overlong 3/4-byte forms (E0 80..9F, F0 80..8F) would decode to '.', '/', NUL... and alias another
        // name when sent back in shortest form; they become '?' like any other invalid sequence.
        const bool overlong = (n == 3 && cp < 0x800) || (n == 4 && cp < 0x10000);
        if (overlong || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
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

struct OwnerJob {
    std::function<void()> fn;
    std::atomic<bool> done{false};
    bool skipped = false;
    OwnerJob * next = nullptr;
};

// jobHead becomes this when the owner will run no further commands.
OwnerJob * const kJobClosed = reinterpret_cast<OwnerJob *>(static_cast<uintptr_t>(1));

thread_local LiveSession * tlsOwner = nullptr;
thread_local JNIEnv * tlsOwnerEnv = nullptr;

void enterReclaim() {
    for (;;) {
        bool expected = false;
        if (gReclaiming.compare_exchange_weak(expected, true)) break;
        gReclaiming.wait(true);
    }
    for (;;) {
        int w = gWalkers.load();
        if (w == 0) break;
        gWalkers.wait(w);
    }
}

void leaveReclaim() {
    gReclaiming.store(false);
    gReclaiming.notify_all();
}

void publishSession(LiveSession * session) {
    enterReclaim();
    LiveSession * expected = gLiveHead.load();
    do {
        session->next.store(expected);
    } while (!gLiveHead.compare_exchange_weak(expected, session));
    leaveReclaim();
}

bool unlinkSession(LiveSession * target) {
    LiveSession * prev = nullptr;
    LiveSession * cur = gLiveHead.load();
    while (cur != nullptr) {
        LiveSession * nxt = cur->next.load();
        if (cur == target) {
            if (prev == nullptr) gLiveHead.store(nxt);
            else prev->next.store(nxt);
            return true;
        }
        prev = cur;
        cur = nxt;
    }
    return false;
}

// Walk the published list. A match bumps the use count and copies the shared_ptr.
std::shared_ptr<LiveSession> retainSession(LiveSession * want) {
    if (want == nullptr) return nullptr;
    for (;;) {
        gWalkers.fetch_add(1);
        if (gReclaiming.load()) {
            if (gWalkers.fetch_sub(1) == 1) gWalkers.notify_all();
            while (gReclaiming.load()) gReclaiming.wait(true);
            continue;
        }
        std::shared_ptr<LiveSession> found;
        for (LiveSession * cur = gLiveHead.load(); cur != nullptr; cur = cur->next.load()) {
            if (cur != want || cur->closed.load()) continue;
            cur->users.fetch_add(1);
            found = cur->selfHold;
            if (!found) {
                int left = cur->users.fetch_sub(1) - 1;
                if (left == 0) cur->users.notify_all();
            }
            break;
        }
        if (gWalkers.fetch_sub(1) == 1) gWalkers.notify_all();
        return found;
    }
}

void releaseUsers(LiveSession * session) {
    if (session == nullptr) return;
    int left = session->users.fetch_sub(1) - 1;
    if (left == 0) session->users.notify_all();
}

void kickOwner(LiveSession * session) {
    if (session == nullptr) return;
    int fd = session->jobFd.load();
    if (fd >= 0) {
        uint64_t one = 1;
        ssize_t ignored = write(fd, &one, sizeof(one));
        (void)ignored;
    }
    session->jobKick.fetch_add(1);
    session->jobKick.notify_one();
}

void postJob(LiveSession * session, OwnerJob * job) {
    OwnerJob * head = session->jobHead.load();
    do {
        if (head == kJobClosed) {
            job->skipped = true;
            job->done.store(true);
            job->done.notify_one();
            return;
        }
        job->next = head;
    } while (!session->jobHead.compare_exchange_weak(head, job));
    kickOwner(session);
}

void failQueuedJobs(OwnerJob * head) {
    while (head != nullptr && head != kJobClosed) {
        OwnerJob * nxt = head->next;
        head->skipped = true;
        head->done.store(true);
        head->done.notify_one();
        head = nxt;
    }
}

// After this, postJob completes new commands without running them.
void closeJobQueue(LiveSession * session) {
    OwnerJob * head = session->jobHead.exchange(kJobClosed);
    failQueuedJobs(head);
}

OwnerJob * takeJobs(LiveSession * session) {
    OwnerJob * head = session->jobHead.load();
    for (;;) {
        if (head == nullptr || head == kJobClosed) return nullptr;
        if (session->jobHead.compare_exchange_weak(head, nullptr)) break;
    }
    OwnerJob * fifo = nullptr;
    while (head != nullptr) {
        OwnerJob * nxt = head->next;
        head->next = fifo;
        fifo = head;
        head = nxt;
    }
    return fifo;
}

void ensureOwnerEnv() {
    if (tlsOwnerEnv != nullptr || gVm == nullptr) return;
    JNIEnv * env = nullptr;
    if (gVm->AttachCurrentThread(&env, nullptr) != 0) return;
    tlsOwnerEnv = env;
}

void ownerLoop(std::shared_ptr<LiveSession> session);
void finishIdleStop(LiveSession * session);
bool watchStep(LiveSession * session);

void runReadyJobs(LiveSession * session) {
    OwnerJob * job = takeJobs(session);
    while (job != nullptr) {
        OwnerJob * nxt = job->next;
        if (job->fn) job->fn();
        job->done.store(true);
        job->done.notify_one();
        job = nxt;
    }
}

const char * jobSkipReason(LiveSession * session) {
    if (session != nullptr && session->ownerAttachFailed.load()) {
        return "owner thread could not attach to the JVM";
    }
    return "not connected";
}

void ownerLoop(std::shared_ptr<LiveSession> session) {
    // Last step on every exit, including the attach-failure return after the queue is closed.
    struct OwnerJvmDetach {
        ~OwnerJvmDetach() {
            if (gVm != nullptr) gVm->DetachCurrentThread();
            tlsOwnerEnv = nullptr;
        }
    } ownerJvmDetach;
    (void)ownerJvmDetach;
    tlsOwner = session.get();
    JNIEnv * env = nullptr;
    if (gVm == nullptr || gVm->AttachCurrentThread(&env, nullptr) != 0 || env == nullptr) {
        session->ownerAttachFailed.store(true);
        closeJobQueue(session.get());
        tlsOwner = nullptr;
        tlsMaxResultUids = 0;
        // Not attached. selfHold stays until an attached caller drops it after joinOwner
        // joins, so this parameter is not the last ref and ~std::thread is not joinable.
        // Detach would let that caller clear selfHold while this parameter is still last.
        session->ownerFinished.store(true);
        session->ownerFinished.notify_all();
        return;
    }
    tlsOwnerEnv = env;
    while (!session->ownerStop.load()) {
        OwnerJob * queued = session->jobHead.load();
        if (queued != nullptr && queued != kJobClosed) {
            runReadyJobs(session.get());
            continue;
        }
        if (session->watchPhase.load() == 3 || session->watchStop.load()) {
            finishIdleStop(session.get());
            continue;
        }
        if (session->watchPhase.load() == 2 && session->watch != nullptr) {
            watchStep(session.get());
            continue;
        }
        int kick = session->jobKick.load();
        queued = session->jobHead.load();
        if ((queued != nullptr && queued != kJobClosed) || session->ownerStop.load() || session->watchPhase.load() == 3) continue;
        session->jobKick.wait(kick);
    }
    runReadyJobs(session.get());
    finishIdleStop(session.get());
    if (session->imap != nullptr) {
        mailimap_free(session->imap);
        session->imap = nullptr;
        trafficClose(&session->imapTraffic);
        ERR_clear_error();
    }
    if (session->stagedReady != nullptr) {
        mailimap_free(session->stagedReady);
        session->stagedReady = nullptr;
        ERR_clear_error();
    }
    tlsOwner = nullptr;
    tlsMaxResultUids = 0;
    closeJobQueue(session.get());
    session->selfHold.reset();
    session->ownerFinished.store(true);
    session->ownerFinished.notify_all();
    // Drop this ref while still attached, so an owner-thread close can DeleteGlobalRef.
    session.reset();
}

void startOwner(const std::shared_ptr<LiveSession> & session) {
    if (session->jobFd.load() < 0) session->jobFd.store(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    session->owner = std::thread(ownerLoop, session);
}

void joinOwner(LiveSession * session) {
    if (session == nullptr) return;
    session->ownerStop.store(true);
    kickOwner(session);
    if (tlsOwner == session) return;
    while (!session->ownerFinished.load()) session->ownerFinished.wait(false);
    if (session->owner.joinable()) session->owner.join();
}

void discardUnpublished(std::shared_ptr<LiveSession> session) {
    if (!session) return;
    joinOwner(session.get());
    while (session->users.load() != 0) {
        int pending = session->users.load();
        if (pending == 0) break;
        session->users.wait(pending);
    }
    session->selfHold.reset();
}

// A nested call restores the caller's env. The outermost return clears it.
struct MimeEnvScope {
    JNIEnv * prev;
    explicit MimeEnvScope(JNIEnv * env) : prev(mimeEnv) { mimeEnv = env; }
    ~MimeEnvScope() { mimeEnv = prev; }
    MimeEnvScope(const MimeEnvScope &) = delete;
    MimeEnvScope & operator=(const MimeEnvScope &) = delete;
};

template <typename R, typename F>
R withOwner(JNIEnv * caller, jlong handle, F && fn) {
    auto failed = []() -> R {
        if constexpr (std::is_void_v<R>) return;
        else return R{};
    };
    if (handle == 0) {
        throwFailure(caller, "not connected");
        return failed();
    }
    auto * raw = reinterpret_cast<LiveSession *>(handle);
    if (tlsOwner == raw) {
        MimeEnvScope scope(caller);
        if constexpr (std::is_void_v<R>) {
            fn(caller);
            return;
        } else {
            return fn(caller);
        }
    }
    std::shared_ptr<LiveSession> kept = retainSession(raw);
    if (!kept) {
        throwFailure(caller, "not connected");
        return failed();
    }
    std::conditional_t<std::is_void_v<R>, int, R> value{};
    jobject exc = nullptr;
    jobject promoted = nullptr;
    OwnerJob job;
    job.fn = [&]() {
        ensureOwnerEnv();
        JNIEnv * env = tlsOwnerEnv;
        if (env == nullptr) return;
        MimeEnvScope scope(env);
        if constexpr (std::is_void_v<R>) fn(env);
        else value = fn(env);
        if (env->ExceptionCheck()) {
            jthrowable thrown = env->ExceptionOccurred();
            env->ExceptionClear();
            if (thrown != nullptr) {
                exc = env->NewGlobalRef(thrown);
                env->DeleteLocalRef(thrown);
            }
            if constexpr (!std::is_void_v<R>) value = R{};
        } else if constexpr (std::is_pointer_v<R>) {
            if (value != nullptr) {
                promoted = env->NewGlobalRef(reinterpret_cast<jobject>(value));
                env->DeleteLocalRef(reinterpret_cast<jobject>(value));
            }
        }
    };
    postJob(raw, &job);
    while (!job.done.load()) job.done.wait(false);
    if (job.skipped) {
        releaseUsers(raw);
        throwFailure(caller, jobSkipReason(raw));
        return failed();
    }
    releaseUsers(raw);
    if (exc != nullptr) {
        auto local = static_cast<jthrowable>(caller->NewLocalRef(exc));
        caller->DeleteGlobalRef(exc);
        if (local != nullptr) caller->Throw(local);
        return failed();
    }
    if constexpr (std::is_void_v<R>) return;
    else if constexpr (std::is_pointer_v<R>) {
        if (promoted == nullptr) return failed();
        auto local = static_cast<R>(caller->NewLocalRef(promoted));
        caller->DeleteGlobalRef(promoted);
        return local;
    } else {
        return value;
    }
}

template <typename F>
auto withOwnerCall(JNIEnv * caller, jlong handle, F && fn) -> decltype(fn(caller)) {
    return withOwner<decltype(fn(caller))>(caller, handle, std::forward<F>(fn));
}

// Session is not published yet. The owner is already running.
template <typename R, typename F>
R runFresh(JNIEnv * caller, LiveSession * raw, F && fn) {
    auto failed = []() -> R {
        if constexpr (std::is_void_v<R>) return;
        else return R{};
    };
    if (tlsOwner == raw) {
        if constexpr (std::is_void_v<R>) { fn(caller); return; }
        else return fn(caller);
    }
    std::conditional_t<std::is_void_v<R>, int, R> value{};
    jobject exc = nullptr;
    jobject promoted = nullptr;
    OwnerJob job;
    job.fn = [&]() {
        ensureOwnerEnv();
        JNIEnv * env = tlsOwnerEnv;
        if (env == nullptr) return;
        if constexpr (std::is_void_v<R>) fn(env);
        else value = fn(env);
        if (env->ExceptionCheck()) {
            jthrowable thrown = env->ExceptionOccurred();
            env->ExceptionClear();
            if (thrown != nullptr) {
                exc = env->NewGlobalRef(thrown);
                env->DeleteLocalRef(thrown);
            }
            if constexpr (!std::is_void_v<R>) value = {};
        } else if constexpr (std::is_pointer_v<R>) {
            if (value != nullptr) {
                promoted = env->NewGlobalRef(reinterpret_cast<jobject>(value));
                env->DeleteLocalRef(reinterpret_cast<jobject>(value));
            }
        }
    };
    postJob(raw, &job);
    while (!job.done.load()) job.done.wait(false);
    if (job.skipped) {
        throwFailure(caller, jobSkipReason(raw));
        return failed();
    }
    if (exc != nullptr) {
        auto local = static_cast<jthrowable>(caller->NewLocalRef(exc));
        caller->DeleteGlobalRef(exc);
        if (local != nullptr) caller->Throw(local);
        return failed();
    }
    if constexpr (std::is_void_v<R>) return;
    else if constexpr (std::is_pointer_v<R>) {
        if (promoted == nullptr) return failed();
        auto local = static_cast<R>(caller->NewLocalRef(promoted));
        caller->DeleteGlobalRef(promoted);
        return local;
    } else {
        return value;
    }
}

// The owner thread is already the only libetpan caller. No session mutex.
LiveSession * lockSession(JNIEnv * env, jlong handle) {
    if (handle == 0) {
        throwFailure(env, "not connected");
        return nullptr;
    }
    auto * session = reinterpret_cast<LiveSession *>(handle);
    if (tlsOwner != session || session->closed.load()) {
        throwFailure(env, "not connected");
        return nullptr;
    }
    tlsMaxResultUids = session->maxResultUids.load();
    session->users.fetch_add(1);
    // OpenSSL keeps one error queue per thread, and libetpan's SSL read/write calls SSL_get_error without
    // clearing it. A stale entry (freeing a shut-down TLS watch on this thread: its LOGOUT and SSL_shutdown
    // fail) turns the next main-connection WANT_READ into SSL_ERROR_SSL: the read fails at once, the
    // connection is dropped and reconnected (lab StartTls idle -> idleRenew). Start every call clean.
    ERR_clear_error();
    return session;
}

void unlockSession(LiveSession * session) {
    tlsLockedImap = nullptr;
    releaseUsers(session);
}

/* F110: after a stream/parse error throwImap NULLs session->imap but keeps the session; an IMAP entry point must
 * not hand that NULL to libetpan. */
LiveSession * lockImapSession(JNIEnv * env, jlong handle) {
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    if (session->imap == nullptr) {
        unlockSession(session);
        throwConnectionLost(env, "IMAP connection lost");
        return nullptr;
    }
    tlsLockedImap = session->imap;
    return session;
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

struct mailimap_set * setFromUids(const std::vector<uint32_t> & uids, bool * hadZero = nullptr) {
    struct mailimap_set * set = mailimap_set_new_empty();
    if (set == nullptr) {
        return nullptr;
    }
    for (uint32_t uid : uids) {
        if (uid == 0) {
            if (hadZero != nullptr) *hadZero = true;
            mailimap_set_free(set);
            return nullptr;
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

bool flagKeywordAtomOk(const char * name) {
    if (name == nullptr || name[0] == 0) return false;
    const char * body = name;
    if (body[0] == '\\') ++body;
    if (body[0] == 0) return false;
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(body); *p != 0; ++p) {
        unsigned char c = *p;
        if (c < 0x21 || c > 0x7e) return false;
        if (c == '(' || c == ')' || c == '{' || c == ' ' || c == '%' || c == '*' || c == '"' || c == '\\' || c == ']') {
            return false;
        }
    }
    return true;
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
                if (flag == nullptr && flagKeywordAtomOk(chars.c())) {
                    char * literal = strdup(chars.c());
                    if (literal != nullptr) {
                        flag = mailimap_flag_new_flag_keyword(literal);
                        if (flag == nullptr) free(literal);
                    }
                }
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

extern "C" int mime_decode_phrase(const char * text, size_t len, const char * fromcode,
    char ** out_text, char ** out_note, int * status);

struct PhraseUtf8 {
    std::string text;
    std::string note;
};

static PhraseUtf8 phraseUtf8(const char * text, const char * fallback) {
    PhraseUtf8 out;
    if (text == nullptr || text[0] == 0) return out;
    const char * from = fallback != nullptr && fallback[0] != 0 ? fallback : "ISO-8859-1";
    char * decoded = nullptr;
    char * note = nullptr;
    int status = 0;
    mime_decode_phrase(text, strlen(text), from, &decoded, &note, &status);
    if (decoded != nullptr) out.text = decoded;
    if (note != nullptr) out.note = note;
    std::free(decoded);
    std::free(note);
    return out;
}

std::string addressText(struct mailimap_address * addr, const char * fallback) {
    if (addr == nullptr) {
        return "";
    }
    if (addr->ad_personal_name != nullptr && addr->ad_personal_name[0] != 0) {
        return phraseUtf8(addr->ad_personal_name, fallback).text;
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

bool preferredFound(const PartWant * want, bool preferHtml) {
    return want->found && want->html == preferHtml;
}

void findPreferred(struct mailimap_body * body, const std::string & prefix, bool preferHtml, PartWant * want);

// enterRfc822 is false while the message's own parts are still being considered. A message/rfc822
// attachment is entered only on the second walk, after that first walk found no text part. Text
// remembered inside an earlier attachment does not skip a later message/rfc822 sibling.
void findPreferred(struct mailimap_body * body, const std::string & prefix, bool preferHtml, PartWant * want,
    bool enterRfc822) {
    if (body == nullptr) {
        return;
    }
    if (preferredFound(want, preferHtml)) {
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
            findPreferred(static_cast<struct mailimap_body *>(clist_content(cur)), section, preferHtml, want, enterRfc822);
            if (preferredFound(want, preferHtml)) {
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
        if (!enterRfc822) return;
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
    if (plain && !preferHtml) {
        rememberText(want, section, part, false);
        return;
    }
    if (html && (preferHtml || !want->found)) {
        rememberText(want, section, part, true);
        if (preferHtml) return;
    }
    if (plain && !want->found) {
        rememberText(want, section, part, false);
    }
}

void findPreferred(struct mailimap_body * body, const std::string & prefix, bool preferHtml, PartWant * want) {
    findPreferred(body, prefix, preferHtml, want, false);
    if (!want->found) {
        findPreferred(body, prefix, preferHtml, want, true);
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

// A sender controls the MIME tree, and the server's BODYSTRUCTURE mirrors it. One conversion goes at most this deep;
// a deeper part becomes a "nested part" entry carrying its real section, which the reader opens with
// nativeFetchStructureAt (the next 64 levels from there). Nothing is dropped.
const int kMaxMimeDepth = 64;

jobject mimeFromBody(JNIEnv * env, struct mailimap_body * body, const std::string & section, int depth = 0);

jobject tooDeepPart(JNIEnv * env, const std::string & section) {
    jobject children = newList(env);
    jstring jSection = newString(env, section.c_str());
    jstring jType = newString(env, "application");
    jstring jSubtype = newString(env, "x-liveimap-too-deep");
    jstring jDisp = newString(env, "attachment");
    jstring jFile = newString(env, "nested part");
    jstring jEmpty = newString(env, "");
    jobject obj = env->NewObject(gJni.mimePart, gJni.mimeInit, jSection, jType, jSubtype, jDisp, jFile, 0, children,
        jEmpty, jEmpty, jEmpty, JNI_FALSE);
    env->DeleteLocalRef(jSection);
    env->DeleteLocalRef(jType);
    env->DeleteLocalRef(jSubtype);
    env->DeleteLocalRef(jDisp);
    env->DeleteLocalRef(jFile);
    env->DeleteLocalRef(jEmpty);
    env->DeleteLocalRef(children);
    return obj;
}

jobject mimeFrom1(JNIEnv * env, struct mailimap_body_type_1part * part, const std::string & section, int depth) {
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
        // F112 (RFC 3501 6.4.5): a multipart body of message/rfc822 has no number of its own; its children are
        // <section>.1, <section>.2 ... Only a single-part body is <section>.1 (as findPreferred does).
        const std::string innerSection = inner->bd_type == MAILIMAP_BODY_MPART ? section : section + ".1";
        jobject child = mimeFromBody(env, inner, innerSection, depth + 1);
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
    // RFC 3676: format=flowed and DelSp=yes, so the reader can unwrap flowed text.
    const char * format = fields != nullptr ? paramValue(fields->bd_parameter, "format") : nullptr;
    const char * delsp = fields != nullptr ? paramValue(fields->bd_parameter, "delsp") : nullptr;
    jstring jFormat = newString(env, format != nullptr ? format : "");
    jboolean jDelSp = delsp != nullptr && strcasecmp(delsp, "yes") == 0 ? JNI_TRUE : JNI_FALSE;
    jobject obj = env->NewObject(gJni.mimePart, gJni.mimeInit, jSection, jType, jSubtype, jDisp, jFile, size, children,
        jCharset, jEncoding, jFormat, jDelSp);
    env->DeleteLocalRef(jFormat);
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

jobject mimeFromBody(JNIEnv * env, struct mailimap_body * body, const std::string & section, int depth) {
    if (body == nullptr) {
        return nullptr;
    }
    if (depth >= kMaxMimeDepth) {
        return tooDeepPart(env, section.empty() ? "1" : section);
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
                jobject child = mimeFromBody(env, static_cast<struct mailimap_body *>(clist_content(cur)), childSection,
                    depth + 1);
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
            jCharset, jEncoding, jCharset, JNI_FALSE);
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
    return mimeFrom1(env, body->bd_data.bd_body_1part, sec, depth);
}

struct Row {
    uint32_t uid = 0;
    uint32_t sequence = 0;
    std::vector<std::string> flags;
    // FLAGS was in the FETCH, including FLAGS (). An empty list still has no names.
    bool sawFlags = false;
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
    std::string headerNote;
    bool previewFailed = false;
    bool hasEnvelope = false; // F113: index rows need it; an unsolicited flags-only FETCH has none
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

std::string toRecipientText(struct mailimap_envelope * env, const char * fallback) {
    std::string out;
    if (env == nullptr || env->env_to == nullptr || env->env_to->to_list == nullptr) {
        return out;
    }
    for (clistiter * cur = clist_begin(env->env_to->to_list); cur != nullptr; cur = clist_next(cur)) {
        std::string text = addressText(static_cast<struct mailimap_address *>(clist_content(cur)), fallback);
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

void readAtt(struct mailimap_msg_att * att, Row * row, const char * accountEmail, const char * altSpecs,
    const char * unlabeled) {
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
        if (item->att_type == MAILIMAP_MSG_ATT_ITEM_DYNAMIC && item->att_data.att_dyn != nullptr) {
            row->sawFlags = true;
            if (item->att_data.att_dyn->att_list != nullptr) {
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
                    row->hasEnvelope = true;
                    struct mailimap_envelope * env = st->att_data.att_env;
                    if (env->env_subject != nullptr) {
                        PhraseUtf8 phrase = phraseUtf8(env->env_subject, unlabeled);
                        row->subject = std::move(phrase.text);
                        row->headerNote = std::move(phrase.note);
                    }
                    if (env->env_date != nullptr) row->date = env->env_date;
                    if (env->env_from != nullptr && env->env_from->frm_list != nullptr) {
                        clistiter * a = clist_begin(env->env_from->frm_list);
                        if (a != nullptr) {
                            row->from = addressText(static_cast<struct mailimap_address *>(clist_content(a)), unlabeled);
                        }
                    }
                    row->recipients = toRecipientText(env, unlabeled);
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
                if (blob != nullptr && blob->isNil == 0 && blob->data != nullptr && blob->len > 0) {
                    row->preview.assign(blob->data, blob->len);
                    row->hasPreview = !row->preview.empty();
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

jobject rowObject(JNIEnv * env, const Row & row, const std::string & preview, bool hasPreview,
    jstring accountId, const char * mailbox, jlong uidValidity) {
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
    jstring box = newString(env, mailbox != nullptr ? mailbox : "");
    jstring headerNote = newString(env, row.headerNote.c_str());
    jobject ref = env->NewObject(gJni.messageRef, gJni.messageRefInit, accountId, box, uidValidity,
        static_cast<jlong>(row.uid));
    jobject obj = ref == nullptr ? nullptr : env->NewObject(gJni.indexRow, gJni.indexRowInit,
        ref, static_cast<jint>(row.sequence), flags,
        static_cast<jlong>(row.epoch), static_cast<jint>(row.size),
        from, subject, date, prev,
        static_cast<jboolean>(row.toMe), static_cast<jboolean>(row.hasAttachment),
        recipients, headerNote, static_cast<jboolean>(row.previewFailed));
    if (ref != nullptr) env->DeleteLocalRef(ref);
    env->DeleteLocalRef(flags);
    env->DeleteLocalRef(from);
    env->DeleteLocalRef(subject);
    env->DeleteLocalRef(date);
    if (prev != nullptr) env->DeleteLocalRef(prev);
    if (recipients != nullptr) env->DeleteLocalRef(recipients);
    if (box != nullptr) env->DeleteLocalRef(box);
    if (headerNote != nullptr) env->DeleteLocalRef(headerNote);
    return obj;
}

void releaseRow(Row & row) {
    row.flags.clear();
    row.flags.shrink_to_fit();
    auto wipe = [](std::string & s) {
        s.clear();
        s.shrink_to_fit();
    };
    wipe(row.from);
    wipe(row.subject);
    wipe(row.date);
    wipe(row.preview);
    wipe(row.recipients);
    wipe(row.headerNote);
    row.structure = nullptr;
}

jobjectArray indexRowsToArray(JNIEnv * env, jobject list) {
    jint n = env->CallIntMethod(list, gJni.listSize);
    if (env->ExceptionCheck()) return nullptr;
    jobjectArray arr = env->NewObjectArray(n, gJni.indexRow, nullptr);
    if (arr == nullptr) return nullptr;
    for (jint i = 0; i < n; ++i) {
        jobject row = env->CallObjectMethod(list, gJni.listGet, i);
        if (row == nullptr || env->ExceptionCheck()) {
            env->DeleteLocalRef(arr);
            if (row != nullptr) env->DeleteLocalRef(row);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, i, row);
        env->DeleteLocalRef(row);
    }
    return arr;
}

int selectFullCapture(mailimap * imap, const char * mailbox, uint64_t * modseq, const char * command, bool condstore = false);

bool wireOk(const char * text);
static bool capabilityLineOffersCondstore(const std::string & line) {
    if (line.empty()) return false;
    size_t pos = 0;
    while (pos < line.size()) {
        while (pos < line.size() && line[pos] == ' ') pos++;
        if (pos >= line.size()) break;
        size_t end = pos;
        while (end < line.size() && line[end] != ' ') end++;
        const std::string token = line.substr(pos, end - pos);
        if (strcasecmp(token.c_str(), "CONDSTORE") == 0 || strcasecmp(token.c_str(), "QRESYNC") == 0) return true;
        pos = end;
    }
    return false;
}

bool selectMailbox(JNIEnv * env, LiveSession * session, const char * mailbox, bool readWrite) {
    if (!wireOk(mailbox)) {  // F42 native backstop (also covers nativeFetchIndex's own re-select)
        throwFailure(env, "text with a line break cannot be sent to the server");
        return false;
    }
    int r;
    uint64_t mod = 0;
    const char * command = readWrite ? "SELECT" : "EXAMINE";
    std::string name = mailbox != nullptr ? mailbox : "";
    const bool condstore = capabilityLineOffersCondstore(session->capabilityLine);
    if (condstore) {
        r = selectFullCapture(session->imap, mailbox, &mod, command, true);
    } else if (readWrite) {
        r = mailimap_select(session->imap, mailbox);
    } else {
        r = mailimap_examine(session->imap, mailbox);
    }
    if (!cmdOk(r)) {
        throwImap(env, session, r, "select failed");
        return false;
    }
    // One mailbox: the modseq just returned by this select, not a per-folder resync map.
    session->selectedModseq = condstore ? mod : 0;
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

jlongArray uidArray(JNIEnv * env, const std::vector<uint32_t> & list) {
    std::vector<jlong> values(list.size());
    for (size_t i = 0; i < list.size(); ++i) values[i] = static_cast<jlong>(list[i]);
    jlongArray arr = env->NewLongArray(static_cast<jsize>(values.size()));
    if (arr != nullptr && !values.empty()) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(values.size()), values.data());
    }
    return arr;
}

void live_thread_free(LiveThread * node) {
    std::vector<LiveThread *> stack;
    if (node != nullptr) stack.push_back(node);
    while (!stack.empty()) {
        LiveThread * n = stack.back();
        stack.pop_back();
        if (n->children != nullptr) {
            for (clistiter * cur = clist_begin(n->children); cur != nullptr; cur = clist_next(cur)) {
                stack.push_back(static_cast<LiveThread *>(clist_content(cur)));
            }
            clist_free(n->children);
        }
        free(n);
    }
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

// THREAD nesting comes from References chains (sender-controlled) and from the server: a plain chain
// "(1 2 3 ...)" nests one level per message, so a long mailing-list thread is a very deep tree. Parsing, freeing
// and conversion are all iterative (explicit stacks), with no depth limit; only the total node count is bounded
// against pathological responses.
// One top-level "( ... )" thread list (RFC 5256 thread-list), parsed without recursion. Members of a chain each
// attach under the previous member; nested lists attach under the last member; a list that starts with "(" gets a
// placeholder parent (has_uid 0) for its sub-lists.
int parseThreadList(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx,
    size_t * indx, LiveThread * root, size_t * nodes) {
    std::vector<LiveThread *> stack;
    LiveThread * cur = root;
    do {
        char c = peekChar(buffer, *indx);
        int r = MAILIMAP_NO_ERROR;
        if (c == '(') {
            r = mailimap_oparenth_parse(fd, buffer, ctx, indx);
            if (r != MAILIMAP_NO_ERROR) return r;
            stack.push_back(cur);
            if (peekChar(buffer, *indx) == '(') {
                if (++*nodes > resultUidCap()) { tlsResultUidCap = true; return MAILIMAP_ERROR_PARSE; }
                LiveThread * holder = newThread(0, 0);
                if (holder == nullptr) return MAILIMAP_ERROR_MEMORY;
                clist_append(cur->children, holder);
                cur = holder;
            }
        } else if (c == ')') {
            if (stack.empty()) return MAILIMAP_ERROR_PARSE;
            r = mailimap_cparenth_parse(fd, buffer, ctx, indx);
            if (r != MAILIMAP_NO_ERROR) return r;
            cur = stack.back();
            stack.pop_back();
        } else if (c == ' ') {
            r = mailimap_space_parse(fd, buffer, indx);
            if (r != MAILIMAP_NO_ERROR) return r;
        } else if (c >= '1' && c <= '9') {
            uint32_t number = 0;
            r = mailimap_nz_number_parse(fd, buffer, ctx, indx, &number);
            if (r != MAILIMAP_NO_ERROR) return r;
            if (++*nodes > resultUidCap()) { tlsResultUidCap = true; return MAILIMAP_ERROR_PARSE; }
            LiveThread * node = newThread(1, number);
            if (node == nullptr) return MAILIMAP_ERROR_MEMORY;
            clist_append(cur->children, node);
            cur = node;
        } else {
            return MAILIMAP_ERROR_PARSE;
        }
    } while (!stack.empty());
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
        size_t nodes = 0;
        while (peekChar(buffer, cur) == '(') {
            r = parseThreadList(fd, buffer, parser_ctx, &cur, root, &nodes);
            if (r != MAILIMAP_NO_ERROR) {
                live_thread_free(root);
                return r;
            }
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

// AUTHENTICATE EXTERNAL = (RFC 4959 SASL-IR, empty authorization identity) over a TLS connection that
// presented a client certificate. Returns MAILIMAP_NO_ERROR on a tagged OK (what cmdOk expects).
int authenticateExternal(mailimap * imap) {
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_token_send(imap->imap_stream, "AUTHENTICATE EXTERNAL =");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(imap, &response);
    if (r != MAILIMAP_NO_ERROR) return r;
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) return MAILIMAP_ERROR_LOGIN;
    imap->imap_state = MAILIMAP_STATE_AUTHENTICATED;
    return MAILIMAP_NO_ERROR;
}

int sendUidThread(mailimap * imap, const char * algorithm, struct mailimap_response ** response,
    struct mailimap_search_key * criteria = nullptr, bool utf8 = false) {
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
    r = mailimap_astring_send(imap->imap_stream, utf8 ? "UTF-8" : "US-ASCII");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    // The filter chain's criteria travel with THREAD, so the server threads only the matching messages.
    r = criteria != nullptr ? mailimap_search_key_send(imap->imap_stream, criteria)
                            : mailimap_token_send(imap->imap_stream, "ALL");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
    return mailimap_parse_response(imap, response);
}

/* The tag of the command in flight, as libetpan sent it (format_tag in mailimap.c is static). */
std::string currentTagText(mailimap * imap) {
    char tag[16];
    if (mailimap_is_163_workaround_enabled(imap)) {
        snprintf(tag, sizeof(tag), "C%i", imap->imap_tag);
    } else {
        snprintf(tag, sizeof(tag), "%i", imap->imap_tag);
    }
    return tag;
}

/* F31: 1/2/3 for "<tag> OK|NO|BAD" only when <tag> is the expected one; a stale or pipelined tagged reply, or a
 * literal line that looks like one, does not count. */
int taggedCondAt(const char * line, const std::string & tag) {
    if (line == nullptr || line[0] == 0 || line[0] == '*' || line[0] == '+') return 0;
    const char * p = line;
    while (*p != 0 && *p != ' ' && *p != '\r' && *p != '\n') p++;
    if (p == line || *p != ' ') return 0;
    if (static_cast<size_t>(p - line) != tag.size() || strncasecmp(line, tag.c_str(), tag.size()) != 0) return 0;
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
    const std::string tag = currentTagText(imap);
    const char * p = imap->imap_stream_buffer->str;
    while (*p != 0) {
        int kind = taggedCondAt(p, tag);
        if (kind != 0) return kind;  // the first line with our tag is the completion
        const char * nl = strchr(p, '\n');
        if (nl == nullptr) break;
        p = nl + 1;
    }
    return 0;
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
    // Non-synchronizing literal only when the server allows it: LITERAL+, or LITERAL- / IMAP4rev2 up to
    // 4096 bytes (RFC 7888). Courier 6 offers neither; it answered "{n+}" with NO and then ran each message
    // line as a command (lab IDLE step).
    bool plus = mailimap_has_extension(imap, const_cast<char *>("LITERAL+"))
        || (size <= 4096 && (mailimap_has_extension(imap, const_cast<char *>("LITERAL-"))
            || mailimap_has_extension(imap, const_cast<char *>("IMAP4rev2"))));
    char marker[64];
    snprintf(marker, sizeof(marker), plus ? "{%zu+}" : "{%zu}", size);
    r = mailimap_token_send(imap->imap_stream, marker);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(imap->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    if (!plus) {
        // Synchronizing literal: wait for "+" before the bytes; a tagged reply here ends the command.
        if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
        const char * got = imap->imap_stream_buffer->str;
        while (strncmp(got, "* ", 2) == 0) {  // untagged data may arrive first
            if (mailimap_read_line(imap) == nullptr) return MAILIMAP_ERROR_STREAM;
            got = imap->imap_stream_buffer->str;
        }
        if (got[0] != '+') return MAILIMAP_ERROR_APPEND;
    }
    size_t off = 0;
    while (off < size) {
        ssize_t wrote = mailstream_write(imap->imap_stream, bytes + off, size - off);
        if (wrote <= 0) return MAILIMAP_ERROR_STREAM;
        off += static_cast<size_t>(wrote);
    }
    r = mailimap_crlf_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
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
    // F31: the tagged completion must carry this APPEND's tag.
    if (ok) {
        struct mailimap_response_done * done = response->rsp_resp_done;
        const char * got = (done != nullptr && done->rsp_type == MAILIMAP_RESP_DONE_TYPE_TAGGED &&
            done->rsp_data.rsp_tagged != nullptr) ? done->rsp_data.rsp_tagged->rsp_tag : nullptr;
        ok = got != nullptr && strcasecmp(got, currentTagText(imap).c_str()) == 0;
    }
    mailimap_response_free(response);
    if (ok || taggedCondInBuffer(imap) == 1) return MAILIMAP_NO_ERROR;
    return MAILIMAP_ERROR_APPEND;
}

/* F136: a watch setup that connected without the lock installs itself only if nothing superseded it. */
bool watchSetupSuperseded(bool closed, uint64_t currentGeneration, uint64_t setupGeneration, bool watchInstalled) {
    return closed || currentGeneration != setupGeneration || watchInstalled;
}

// Process-wide, so a reconnect's new LiveSession never reuses a generation at or below the floor the Kotlin
// session kept from the old handle (publishWatch would drop it and every event of that watch).
std::atomic<uint64_t> gWatchGenerationSeq{0};

uint64_t bumpWatchGeneration(LiveSession * session) {
    uint64_t next = gWatchGenerationSeq.fetch_add(1) + 1;
    session->watchGeneration.store(next);
    return next;
}

// Owner thread. Leaves IDLE, frees the watch connection, and publishes phase 0.
void finishIdleStop(LiveSession * session) {
    if (session == nullptr) return;
    if (session->watch != nullptr && !session->watchLost && !session->watchStop.load()) {
        mailimap_idle_done(session->watch);
    }
    if (session->watchWakeFd >= 0) {
        close(session->watchWakeFd);
        session->watchWakeFd = -1;
    }
    if (session->watch != nullptr) {
        mailimap_free(session->watch);
        session->watch = nullptr;
        trafficClose(&session->watchTraffic);
        ERR_clear_error();
    }
    JNIEnv * env = tlsOwnerEnv;
    if (session->selfGlobal != nullptr && env != nullptr) {
        env->DeleteGlobalRef(session->selfGlobal);
        session->selfGlobal = nullptr;
    }
    session->watchStop.store(false);
    session->watchLost = false;
    session->watchPollFd = -1;
    session->startState = 0;
    tlsWatch = nullptr;
    int phase = session->watchPhase.exchange(0);
    if (phase != 0) session->watchPhase.notify_all();
    else session->watchPhase.notify_all();
}

void emitWatch(LiveSession * session, int kind, int exists, jlong uid, jobjectArray flags, jlong generation) {
    if (session->watchPhase.load() != 2) return;
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
    env->CallVoidMethod(session->selfGlobal, gJni.onWatch, generation, kind, exists, uid, flags);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        signalWatchStop(session);
    }
    if (flags != nullptr) {
        env->DeleteLocalRef(flags);
    }
    if (attached) {
        gVm->DetachCurrentThread();
    }
}

jobjectArray flagsArray(JNIEnv * env, const std::vector<std::string> & flags);

void flushQueuedWatch(LiveSession * session, jlong generation, const std::vector<WatchNote> & ready) {
    for (const WatchNote & note : ready) {
        jobjectArray flags = nullptr;
        if (note.kind == 2 && gVm != nullptr) {
            JNIEnv * env = nullptr;
            gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
            if (env != nullptr) flags = flagsArray(env, note.flags);
        }
        emitWatch(session, note.kind, note.exists, note.uid, flags, generation);
    }
}

// Queue while nativeWatch has not published this generation. Drop after a clear. Deliver after a publish.
void watchTraceQueued(LiveSession * session, int kind, int exists);

void queueWatch(LiveSession * session, int kind, int exists, jlong uid, std::vector<std::string> flags, jlong generation) {
    std::vector<WatchNote> ready;
    // Trace before the queue: the note calls into Kotlin.
    if (kind == 0 || kind == 4) watchTraceQueued(session, kind, exists);
    if (session->watchGate == 2) return;
    if (session->watchGate == 0) {
        if (kind == 4) session->watchLostBeforePublish = true;
        session->watchQueue.push_back(WatchNote{kind, exists, uid, std::move(flags)});
        return;
    }
    ready.swap(session->watchQueue);
    ready.push_back(WatchNote{kind, exists, uid, std::move(flags)});
    flushQueuedWatch(session, generation, ready);
}

void drainWatchQueue(LiveSession * session, jlong generation) {
    if (session->watchGate != 1 || session->watchQueue.empty()) return;
    std::vector<WatchNote> ready;
    ready.swap(session->watchQueue);
    flushQueuedWatch(session, generation, ready);
}

void markWatchEnded(LiveSession * session) {
    session->watchEnded = true;
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

void handleUntagged(LiveSession * session, struct mailimap_response_data * data, jlong generation) {
    if (data == nullptr) {
        return;
    }
    if (data->rsp_type == MAILIMAP_RESP_DATA_TYPE_MAILBOX_DATA && data->rsp_data.rsp_mailbox_data != nullptr) {
        struct mailimap_mailbox_data * mb = data->rsp_data.rsp_mailbox_data;
        if (mb->mbd_type == MAILIMAP_MAILBOX_DATA_EXISTS) {
            session->watchExists = static_cast<int>(mb->mbd_data.mbd_exists);
            queueWatch(session, 0, session->watchExists, 0, {}, generation);
        }
    } else if (data->rsp_type == MAILIMAP_RESP_DATA_TYPE_MESSAGE_DATA && data->rsp_data.rsp_message_data != nullptr) {
        struct mailimap_message_data * msg = data->rsp_data.rsp_message_data;
        if (msg->mdt_type == MAILIMAP_MESSAGE_DATA_EXPUNGE) {
            if (session->watchExists > 0) session->watchExists -= 1;
            queueWatch(session, 1, session->watchExists, 0, {}, generation);
        } else if (msg->mdt_type == MAILIMAP_MESSAGE_DATA_FETCH) {
            Row row;
            readAtt(msg->mdt_msg_att, &row, nullptr, nullptr, "ISO-8859-1");
            if (row.uid != 0) {
                queueWatch(session, 2, session->watchExists, static_cast<jlong>(row.uid), std::move(row.flags), generation);
            } else if (row.sawFlags && !session->watchShownFlagsSent) {
                session->watchShownFlagsSent = true;
                queueWatch(session, 5, session->watchExists, 0, {}, generation);
            }
        }
    } else if (data->rsp_type == MAILIMAP_RESP_DATA_TYPE_COND_STATE && data->rsp_data.rsp_cond_state != nullptr) {
        struct mailimap_resp_text * text = data->rsp_data.rsp_cond_state->rsp_text;
        if (text != nullptr && text->rsp_code != nullptr
            && text->rsp_code->rc_type == MAILIMAP_RESP_TEXT_CODE_UIDVALIDITY) {
            uint32_t uv = text->rsp_code->rc_data.rc_uidvalidity;
            if (session->watchUidValidity != 0 && uv != session->watchUidValidity) {
                session->watchUidValidity = uv;
                queueWatch(session, 3, session->watchExists, 0, {}, generation);
            }
        }
    }
    if (session->watch != nullptr && session->watch->imap_selection_info != nullptr) {
        uint32_t uv = session->watch->imap_selection_info->sel_uidvalidity;
        if (session->watchUidValidity != 0 && uv != 0 && uv != session->watchUidValidity) {
            session->watchUidValidity = uv;
            queueWatch(session, 3, session->watchExists, 0, {}, generation);
        }
    }
}

// IDLE renew and the poll backstop live on the session (nativeSetSessionLimits).

/* F38: poll timeout for the watch loop. With a wake fd, wait until the renew or the DONE delay is due (at least 1 s,
   at most 30 min); without one, the configured short wake so a stop is noticed. */
int watchPollMs(bool hasWakeFd, long renewLeftSec, long doneDelaySec, int wakeMs) {
    if (!hasWakeFd) return wakeMs;
    long left = renewLeftSec;
    if (doneDelaySec > 0 && doneDelaySec < left) left = doneDelaySec;
    if (left < 1) left = 1;
    if (left > 1800) left = 1800;
    return (int) (left * 1000);
}
std::atomic<int> gIdleRenews{0};

/* Watch trace (lab): one note per watch-loop decision, only while this account's traffic directory is set. */
void watchTrace(LiveSession * session, const std::string & text) {
    if (session == nullptr || session->trafficDir.empty()) return;
    trafficNote(&session->watchTraffic, "watch " + text);
}

/* IDLE renew DONE. libetpan's mailimap_idle_done() parses the DONE response with mailimap_parse_response(),
   which stores untagged EXISTS/EXPUNGE/FETCH in selection info and never reaches handleUntagged: Courier
   reports a new message only between DONE and the tagged OK, so every renew dropped it. Read the response
   here: each untagged line goes through handleUntagged like the IDLE loop, then the tagged line is parsed. */
int watchRenewDone(LiveSession * session, jlong generation) {
    mailimap * w = session->watch;
    int r = mailimap_token_send(w->imap_stream, "DONE");
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_crlf_send(w->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (mailstream_flush(w->imap_stream) == -1) return MAILIMAP_ERROR_STREAM;
    while (true) {
        if (mailimap_read_line(w) == nullptr) return MAILIMAP_ERROR_STREAM;
        const char * line = w->imap_stream_buffer->str;
        if (w->imap_stream_buffer->len < 2 || line[0] != '*' || line[1] != ' ') break;
        size_t indx = 0;
        struct mailimap_parser_context * ctx = mailimap_parser_context_new(w);
        struct mailimap_response_data * data = nullptr;
        int prs = mailimap_response_data_parse(w->imap_stream, w->imap_stream_buffer, ctx, &indx, &data, 0, nullptr);
        if (prs == MAILIMAP_NO_ERROR) {
            watchTrace(session, "done untagged " + std::to_string(data != nullptr ? data->rsp_type : -1));
            handleUntagged(session, data, generation);
        } else {
            watchTrace(session, "done untagged parse error " + std::to_string(prs));
        }
        if (data != nullptr) mailimap_response_data_free(data);
        if (ctx != nullptr) mailimap_parser_context_free(ctx);
        if (prs != MAILIMAP_NO_ERROR) return prs;
    }
    struct mailimap_response * response = nullptr;
    r = mailimap_parse_response(w, &response);
    if (r != MAILIMAP_NO_ERROR) return r;
    int result = MAILIMAP_ERROR_EXTENSION;
    if (response->rsp_resp_done != nullptr && response->rsp_resp_done->rsp_type == MAILIMAP_RESP_DONE_TYPE_TAGGED
        && response->rsp_resp_done->rsp_data.rsp_tagged->rsp_cond_state->rsp_type == MAILIMAP_RESP_COND_STATE_OK) {
        result = MAILIMAP_NO_ERROR;
    }
    mailimap_response_free(response);
    return result;
}

void watchTraceQueued(LiveSession * session, int kind, int exists) {
    watchTrace(session, "queue kind=" + std::to_string(kind) + " exists=" + std::to_string(exists));
}

bool beginWatchIdle(LiveSession * session) {
    if (session == nullptr || session->watch == nullptr) return false;
    ensureOwnerEnv();
    tlsWatch = session;
    session->watchLost = false;
    session->watchEnded = false;
    session->watchIdleSince = std::chrono::steady_clock::now();
    int r = mailimap_idle(session->watch);
    if (r != MAILIMAP_NO_ERROR) {
        session->startState = -1;
        session->watchEnded = true;
        session->startError = r == MAILIMAP_ERROR_STREAM ? std::string("IDLE dropped by server")
                                                        : imapText(session->watch, "idle failed");
        tlsWatch = nullptr;
        return false;
    }
    session->startState = 1;
    session->watchPollFd = mailimap_idle_get_fd(session->watch);
    session->watchPhase.store(2);
    session->watchPhase.notify_all();
    return true;
}

// One IDLE poll on the owner. A queued command wakes jobFd and returns so that command runs next.
bool watchStep(LiveSession * session) {
    if (session != nullptr) tlsMaxResultUids = session->maxResultUids.load();
    if (session == nullptr || session->watch == nullptr || session->watchPhase.load() != 2) return false;
    if (session->ownerStop.load() || session->watchStop.load() || session->watchPhase.load() == 3) {
        finishIdleStop(session);
        return false;
    }
    jlong generation = static_cast<jlong>(session->watchGeneration.load());
    drainWatchQueue(session, generation);
    long idleAge = (long) std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::steady_clock::now() - session->watchIdleSince).count();
    if (!session->watchStop.load() &&
        (mailimap_idle_get_done_delay(session->watch) == 0 || idleAge >= session->idleRenewSec)) {
        session->watchIdleSince = std::chrono::steady_clock::now();
        gIdleRenews.fetch_add(1);
        int doneR = watchRenewDone(session, generation);
        int idleR = doneR == MAILIMAP_NO_ERROR ? mailimap_idle(session->watch) : doneR;
        if (session->watchStop.load() || session->watchPhase.load() == 3) {
            finishIdleStop(session);
            return false;
        }
        if (doneR != MAILIMAP_NO_ERROR || idleR != MAILIMAP_NO_ERROR) {
            queueWatch(session, 4, session->watchExists, 0, {}, generation);
            session->watchLost = true;
            finishIdleStop(session);
            return false;
        }
        session->watchPollFd = mailimap_idle_get_fd(session->watch);
        watchTrace(session, "renew fd=" + std::to_string(session->watchPollFd) + " buffered="
            + std::to_string(session->watch->imap_stream != nullptr ? session->watch->imap_stream->read_buffer_len : 0));
    }
    int fd = session->watchPollFd;
    mailstream * ws = session->watch->imap_stream;
    bool buffered = ws != nullptr && ws->read_buffer_len > 0;
    bool newNetworkData = false;
    if (!buffered && ws != nullptr && mailstream_get_low(ws) != nullptr
        && mailstream_get_low(ws)->driver != mailstream_socket_driver) {
        ssl_st * wssl = static_cast<ssl_st *>(mailstream_ssl_get_openssl_ssl(ws));
        buffered = wssl != nullptr && SSL_pending(wssl) > 0;
    }
    if (buffered) watchTrace(session, "buffered read_buffer=" + std::to_string(ws->read_buffer_len));
    if (!buffered) {
        struct pollfd pfd[3]{};
        int nfd = 0;
        pfd[nfd].fd = fd;
        pfd[nfd].events = POLLIN;
        int watchSlot = nfd++;
        int wakeSlot = -1;
        int jobSlot = -1;
        if (session->watchWakeFd >= 0) {
            wakeSlot = nfd;
            pfd[nfd].fd = session->watchWakeFd;
            pfd[nfd].events = POLLIN;
            nfd++;
        }
        if (session->jobFd.load() >= 0) {
            jobSlot = nfd;
            pfd[nfd].fd = session->jobFd.load();
            pfd[nfd].events = POLLIN;
            nfd++;
        }
        long renewLeft = session->idleRenewSec - (long) std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::steady_clock::now() - session->watchIdleSince).count();
        int pollMs = watchPollMs(session->watchWakeFd >= 0 || session->jobFd.load() >= 0, renewLeft,
            (long) mailimap_idle_get_done_delay(session->watch), session->watchWakeMs);
        int pr = poll(pfd, nfd, pollMs);
        if (pr > 0 && wakeSlot >= 0 && (pfd[wakeSlot].revents & POLLIN) != 0) {
            uint64_t drained = 0;
            ssize_t ignored = read(session->watchWakeFd, &drained, sizeof(drained));
            (void)ignored;
        }
        bool jobReady = pr > 0 && jobSlot >= 0 && (pfd[jobSlot].revents & POLLIN) != 0;
        if (jobReady) {
            uint64_t drained = 0;
            ssize_t ignored = read(session->jobFd.load(), &drained, sizeof(drained));
            (void)ignored;
        }
        if (session->watchStop.load() || session->watchPhase.load() == 3 || session->ownerStop.load()) {
            finishIdleStop(session);
            return false;
        }
        if (jobReady) return true;
        if (pr <= 0 || (pfd[watchSlot].revents == 0)) return true;
        newNetworkData = (pfd[watchSlot].revents & POLLIN) != 0;
    }
    if (newNetworkData) session->watchShownFlagsSent = false;
    if (mailimap_read_line(session->watch) == nullptr) {
        if (!session->watchStop.load()) {
            queueWatch(session, 4, session->watchExists, 0, {}, generation);
        }
        session->watchLost = true;
        finishIdleStop(session);
        return false;
    }
    size_t indx = 0;
    struct mailimap_parser_context * ctx = mailimap_parser_context_new(session->watch);
    struct mailimap_response_data * data = nullptr;
    int prs = mailimap_response_data_parse(session->watch->imap_stream, session->watch->imap_stream_buffer,
        ctx, &indx, &data, 0, nullptr);
    if (prs == MAILIMAP_NO_ERROR) {
        watchTrace(session, "untagged " + std::to_string(data != nullptr ? data->rsp_type : -1));
        handleUntagged(session, data, generation);
    } else {
        watchTrace(session, "untagged parse error " + std::to_string(prs) + ": "
            + std::string(session->watch->imap_stream_buffer->str).substr(0, 60));
    }
    if (data != nullptr) mailimap_response_data_free(data);
    if (ctx != nullptr) mailimap_parser_context_free(ctx);
    return true;
}

void enableKeepalive(int fd) {
    int on = 1;
    // IMAP is many small command lines: send each at once instead of waiting for Nagle to coalesce.
    if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on)) < 0) {
        __android_log_print(ANDROID_LOG_INFO, "LiveIMAP", "TCP_NODELAY failed: %s", strerror(errno));
    }
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

int connectStart(const struct addrinfo * ai, std::string * why) {
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
    if (rc == 0) return fd;
    if (rc < 0 && errno != EINPROGRESS && errno != EINTR) {
        *why = errorText(errno);
        close(fd);
        return -1;
    }
    return fd;
}

bool connectFinish(int fd, std::string * why) {
    struct pollfd pfd{};
    pfd.fd = fd;
    pfd.events = POLLOUT;
    int pr = poll(&pfd, 1, 0);
    if (pr < 0) {
        if (errno == EINTR) return false;
        *why = errorText(errno);
        close(fd);
        return true;
    }
    if (pr == 0) return false;
    int soerr = 0;
    socklen_t len = sizeof(soerr);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &len) < 0) {
        *why = errorText(errno);
        close(fd);
        return true;
    }
    if (soerr != 0) {
        *why = errorText(soerr);
        close(fd);
        return true;
    }
    int cur = fcntl(fd, F_GETFL, 0);
    if (cur < 0 || fcntl(fd, F_SETFL, cur & ~O_NONBLOCK) < 0) {
        *why = errorText(errno);
        close(fd);
        return true;
    }
    enableKeepalive(fd);
    return true;
}

int connectOne(const struct addrinfo * ai, int timeoutSec, std::string * why) {
    int fd = connectStart(ai, why);
    if (fd < 0) return -1;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(timeoutSec);
    for (;;) {
        std::string finishWhy;
        if (connectFinish(fd, &finishWhy)) {
            if (finishWhy.empty()) return fd;
            *why = finishWhy;
            return -1;
        }
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
        if (pr < 0 && errno != EINTR) {
            *why = errorText(errno);
            close(fd);
            return -1;
        }
    }
}

const int kConnectStaggerMs = 250;

int tcpConnect(const char * proto, const char * host, int port, std::string * address, std::string * error, int timeoutSec) {
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
    std::vector<struct addrinfo *> candidates;
    for (struct addrinfo * ai = res; ai != nullptr; ai = ai->ai_next) candidates.push_back(ai);
    if (candidates.empty()) {
        freeaddrinfo(res);
        *error = serviceLabel(proto, host, port) + ": name lookup failed: no address";
        return -1;
    }
    struct Pending {
        int fd = -1;
        std::string numeric;
        std::string why;
    };
    std::vector<Pending> pending;
    std::string failures;
    size_t nextStart = 0;
    auto raceStart = std::chrono::steady_clock::now();
    if (timeoutSec < 1) timeoutSec = 1;
    auto deadline = raceStart + std::chrono::seconds(timeoutSec);
    int winner = -1;
    std::string chosen;
    while (winner < 0) {
        auto now = std::chrono::steady_clock::now();
        if (now >= deadline) break;
        while (nextStart < candidates.size()) {
            auto due = raceStart + std::chrono::milliseconds(kConnectStaggerMs * nextStart);
            if (now < due) break;
            Pending p;
            p.numeric = numericAddress(candidates[nextStart]);
            p.fd = connectStart(candidates[nextStart], &p.why);
            if (p.fd < 0) {
                if (!failures.empty()) failures += "; ";
                failures += connectFailure(proto, host, port, p.numeric, p.why);
            } else {
                pending.push_back(std::move(p));
            }
            ++nextStart;
        }
        if (pending.empty() && nextStart >= candidates.size()) break;
        int waitMs = 50;
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
        if (left > 0 && left < waitMs) waitMs = static_cast<int>(left);
        if (!pending.empty()) {
            std::vector<struct pollfd> pfds;
            for (const Pending & p : pending) {
                if (p.fd < 0) continue;
                struct pollfd one{};
                one.fd = p.fd;
                one.events = POLLOUT;
                pfds.push_back(one);
            }
            if (!pfds.empty()) {
                int pr = poll(pfds.data(), static_cast<nfds_t>(pfds.size()), waitMs);
                if (pr > 0) {
                    size_t pi = 0;
                    for (size_t fi = 0; fi < pending.size(); ++fi) {
                        if (pending[fi].fd < 0) continue;
                        short revents = pfds[pi++].revents;
                        if ((revents & (POLLOUT | POLLERR | POLLHUP | POLLNVAL)) == 0) continue;
                        std::string finishWhy;
                        if (connectFinish(pending[fi].fd, &finishWhy) && finishWhy.empty()) {
                            winner = pending[fi].fd;
                            chosen = pending[fi].numeric;
                            for (size_t j = 0; j < pending.size(); ++j) {
                                if (pending[j].fd >= 0 && pending[j].fd != winner) close(pending[j].fd);
                            }
                            break;
                        }
                        if (!finishWhy.empty()) {
                            if (!failures.empty()) failures += "; ";
                            failures += connectFailure(proto, host, port, pending[fi].numeric, finishWhy);
                            pending[fi].fd = -1;
                        }
                    }
                }
            }
        } else if (waitMs > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(waitMs));
        }
        pending.erase(std::remove_if(pending.begin(), pending.end(),
            [](const Pending & p) { return p.fd < 0; }), pending.end());
    }
    if (winner < 0) {
        for (Pending & p : pending) {
            if (p.fd < 0) continue;
            close(p.fd);
            p.fd = -1;
            if (!failures.empty()) failures += "; ";
            failures += connectFailure(proto, host, port, p.numeric, "timed out");
        }
    }
    freeaddrinfo(res);
    if (winner < 0) {
        *error = failures.empty()
            ? serviceLabel(proto, host, port) + ": name lookup failed: no address"
            : failures;
        return -1;
    }
    *address = std::move(chosen);
    return winner;
}

void noteParsedRows(LiveSession * session, size_t count) {
    if (session == nullptr || session->trafficDir.empty()) return;
    std::string line = "parsed " + std::to_string(count);
    __android_log_print(ANDROID_LOG_DEBUG, "LiveIMAP", "%s", line.c_str());
    trafficNote(&session->imapTraffic, line);
}

bool abortEmptyIndexFetch(JNIEnv * env, LiveSession * session, clist * list) {
    if (list != nullptr) mailimap_fetch_list_free(list);
    if (session != nullptr && session->imap != nullptr) {
        session->imap->imap_response = nullptr;
    }
    throwImap(env, session, MAILIMAP_NO_ERROR, "fetch returned no rows");
    unlockSession(session);
    return true;
}

void noteProgress(LiveSession * live, const char * str, size_t size) {
    std::string chunk(str, size);
    size_t at = 0;
    while ((at = chunk.find("[INPROGRESS (", at)) != std::string::npos) {
        at += 13;
        size_t close = chunk.find(')', at);
        if (close == std::string::npos) return;
        std::string inner = chunk.substr(at, close - at);
        size_t sp = inner.rfind(' ');
        size_t sp0 = sp == std::string::npos || sp == 0 ? std::string::npos : inner.rfind(' ', sp - 1);
        if (sp0 == std::string::npos) continue;
        auto num = [](const std::string & t) -> int64_t {
            if (t.empty() || t.size() > 18) return -1;
            for (char c : t) if (c < '0' || c > '9') return -1;
            return std::stoll(t);
        };
        live->progressDone.store(num(inner.substr(sp0 + 1, sp - sp0 - 1)));
        live->progressTotal.store(num(inner.substr(sp + 1)));
        live->progressLines.fetch_add(1);
    }
}

void streamTrafficLogger(mailstream * stream, int log_type, const char * str, size_t size, void * context) {
    auto * live = static_cast<LiveSession *>(context);
    if (live == nullptr) return;
    if (live->watchProgress && log_type == MAILSTREAM_LOG_TYPE_DATA_RECEIVED && str != nullptr) {
        noteProgress(live, str, size);
    }
    TrafficFile * file = &live->imapTraffic;
    if (stream != nullptr && live->watch != nullptr && live->watch->imap_stream == stream) file = &live->watchTraffic;
    trafficFromLibetpan(file, log_type, str, size);
}

void attachTrafficLogger(mailimap * imap, TrafficFile * traffic) {
    if (imap == nullptr || traffic == nullptr || traffic->dir.empty()) return;
    mailimap_set_logger(imap, fileTrafficLogger, traffic);
}

struct TlsCapture {
    std::vector<std::vector<unsigned char>> ders;
    bool allowOldTls = false;
    const std::vector<std::string> * keyChainChain = nullptr;
    jobject keyChainKey = nullptr;
    std::string alert;
    std::string state;
};

// True when prepareTls loaded a client certificate into the current connection (same thread, cleared per TLS
// setup); lets the auth choice prefer SASL EXTERNAL.
thread_local bool gClientCertLoaded = false;
// Why the last client certificate could not be loaded ("" = loaded or none configured).
thread_local std::string gClientCertError;

// Certificate verdicts that the accept-invalid-certificate setting overrides; connection failures are never overridden.
bool certificateVerdict(const std::string & text) {
    return text == "name mismatch" || text == "certificate expired" || text == "certificate untrusted"
        || text == "certificate changed" || text == "certificate rejected";
}

thread_local TlsCapture * tlsCapture = nullptr;

const int kSslCtrlSetMinProtoVersion = 123;
const long kTls12Version = 0x0303;
const long kTls10Version = 0x0301;
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

bool storePeerOffer(JNIEnv * env, jclass cls, jobject list, std::vector<std::string> * offer) {
    jmethodID method = env->GetStaticMethodID(cls, "offer", "(Ljava/util/List;)[Ljava/lang/String;");
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    jobject result = env->CallStaticObjectMethod(cls, method, list);
    if (env->ExceptionCheck() || result == nullptr) {
        env->ExceptionClear();
        if (result != nullptr) env->DeleteLocalRef(result);
        return false;
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
            auto line = utf8FromJava(env, static_cast<jstring>(item));
            env->DeleteLocalRef(item);
            if (!line) {
                lines.clear();
                break;
            }
            lines.push_back(std::move(*line));
        }
    }
    env->DeleteLocalRef(result);
    if (lines.size() != 5 || offer == nullptr) return false;
    *offer = std::move(lines);
    return true;
}

std::string peerTrustCheck(JNIEnv * env, const char * host, const std::vector<std::vector<unsigned char>> & ders,
    const char * pin, bool publish, std::vector<std::string> * offer = nullptr) {
    struct OfferGate {
        bool publish;
        bool keep = false;
        std::vector<std::string> * offer;
        std::vector<std::string> pending;
        ~OfferGate() {
            if (publish && keep && offer != nullptr) *offer = std::move(pending);
        }
    } gate{publish, false, offer, {}};
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
        auto got = utf8FromJava(env, static_cast<jstring>(result));
        env->DeleteLocalRef(result);
        if (got) text = std::move(*got);
    }
    if (gate.publish && promptableTrust(text)) {
        gate.keep = storePeerOffer(env, cls, list, &gate.pending);
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

mailimap * openTcp(const char * host, int port, std::string * error, std::string * address,
    TrafficFile * traffic, int connectSec, int readSec) {
    mailimap * imap = mailimap_new(0, nullptr);
    if (imap == nullptr) {
        *error = protocolError("IMAP", MAILIMAP_ERROR_MEMORY, "MAILIMAP_ERROR_MEMORY", nullptr);
        return nullptr;
    }
    if (readSec < 1) readSec = 1;
    if (connectSec < 1) connectSec = 1;
    mailimap_set_timeout(imap, readSec);
    int fd = tcpConnect("IMAP", host, port, address, error, connectSec);
    if (fd < 0) {
        mailimap_free(imap);
        return nullptr;
    }
    mailstream * stream = mailstream_socket_open_timeout(fd, readSec);
    if (stream == nullptr) {
        close(fd);
        *error = connectFailure("IMAP", host, port, *address, "connection failed");
        mailimap_free(imap);
        return nullptr;
    }
    attachTrafficLogger(imap, traffic);
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

extern "C" const char ** sasl_global_listmech(void);
extern "C" void mailsasl_ref(void);
extern "C" int sasl_client_init(const void *);
typedef int (*SaslPlugInit)(void *, int, int *, void **, int *);
extern "C" int sasl_client_add_plugin(const char *, SaslPlugInit);
extern "C" int plain_client_plug_init(void *, int, int *, void **, int *);
extern "C" int login_client_plug_init(void *, int, int *, void **, int *);
extern "C" int crammd5_client_plug_init(void *, int, int *, void **, int *);
extern "C" int scram_client_plug_init(void *, int, int *, void **, int *);
std::string gSaslSetup;

// The SASL library is linked statically and finds no plugin directory on Android.
// Initialise it once and register the client mechanisms the app may choose.
void setupSaslClient() {
    int init = sasl_client_init(nullptr);
    if (init != 0) {
        gSaslSetup = "sasl_client_init=" + std::to_string(init);
        return;
    }
    mailsasl_ref();
    struct { const char * name; SaslPlugInit init; } plugins[] = {
        {"scram", scram_client_plug_init},
        {"crammd5", crammd5_client_plug_init},
        {"plain", plain_client_plug_init},
        {"login", login_client_plug_init},
    };
    for (auto & p : plugins) {
        int r = sasl_client_add_plugin(p.name, p.init);
        if (r != 0) gSaslSetup += std::string(gSaslSetup.empty() ? "" : " ") + p.name + "=" + std::to_string(r);
    }
}

// David, 2026-10-12: only mechanisms this client really supports (and has the credentials for) are ever offered
// to the choice or named in an error. The linked library also registers ANONYMOUS, OTP, DIGEST-MD5 and more
// SCRAM variants; those are never used. EXTERNAL is sent directly (authenticateExternal), not through SASL.
static const char * const kSupportedSasl[] = {"SCRAM-SHA-256", "SCRAM-SHA-1", "PLAIN", "LOGIN", "CRAM-MD5"};

std::string saslClientMechs(bool havePassword = true) {
    const char ** list = sasl_global_listmech();
    std::string out;
    if (havePassword) {
        for (const char * want : kSupportedSasl) {
            for (int i = 0; list != nullptr && list[i] != nullptr; i++) {
                if (strcmp(list[i], want) != 0) continue;
                if (!out.empty()) out += ' ';
                out += want;
                break;
            }
        }
    }
    // EXTERNAL needs no password and no SASL plugin.
    if (!out.empty()) out += ' ';
    out += "EXTERNAL";
    return out;
}

/* F42 native backstop: text sent as an IMAP quoted string / atom must not carry CR or LF (NUL ends a C string). */
bool wireOk(const char * text) {
    if (text == nullptr) return true;
    for (const char * p = text; *p != 0; ++p) {
        if (*p == '\r' || *p == '\n') return false;
    }
    return true;
}

bool loginImap(JNIEnv * env, mailimap * imap, const char * host, int port, const char * user,
    const char * password, const std::string & address, std::string * error, bool tls, bool plaintextOk,
    const std::string & forced, bool * authRejected = nullptr, std::string * tlsOut = nullptr) {
    if (authRejected != nullptr) *authRejected = false;
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
    unsigned char binding[64];
    size_t bindingLen = 0;
    const char * bindingName = nullptr;
    if (tls) {
        fillTlsChannelBinding(imap->imap_stream, binding, &bindingLen, &bindingName);
        if (tlsOut != nullptr) *tlsOut = negotiatedTls(imap->imap_stream, bindingName);
    }
    jclass cls = env->FindClass("org/dlang/liveimap/session/ImapAuthKt");
    if (cls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (cls != nullptr) env->DeleteLocalRef(cls);
        *error = "auth choice failed";
        return false;
    }
    jmethodID method = env->GetStaticMethodID(cls, "imapAuthOrderLine",
        "(Ljava/lang/String;ZZZLjava/lang/String;Z)Ljava/lang/String;");
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
    bool havePassword = password != nullptr && password[0] != 0;
    jstring jclient = newString(env, saslClientMechs(havePassword).c_str());
    jvalue args[6];
    args[0].l = jline;
    args[1].z = tls ? JNI_TRUE : JNI_FALSE;
    args[2].z = plaintextOk ? JNI_TRUE : JNI_FALSE;
    args[3].z = bindingLen > 0 ? JNI_TRUE : JNI_FALSE;
    args[4].l = jclient;
    args[5].z = tls && gClientCertLoaded ? JNI_TRUE : JNI_FALSE;
    jobject result = env->CallStaticObjectMethodA(cls, method, args);
    env->DeleteLocalRef(jline);
    if (jclient != nullptr) env->DeleteLocalRef(jclient);
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (result != nullptr) env->DeleteLocalRef(result);
        *error = "auth choice failed";
        return false;
    }
    // Mechanisms best first (imapAuthOrder); the first is tried, the rest only after a local failure.
    std::vector<std::string> order;
    if (result != nullptr) {
        auto got = utf8FromJava(env, static_cast<jstring>(result));
        env->DeleteLocalRef(result);
        if (!got) {
            *error = "auth choice failed";
            return false;
        }
        std::string joined = std::move(*got);
        size_t at = 0;
        while (at < joined.size()) {
            size_t sp = joined.find(' ', at);
            if (sp == std::string::npos) sp = joined.size();
            if (sp > at) order.push_back(joined.substr(at, sp - at));
            at = sp + 1;
        }
    }
    std::string mechanism = order.empty() ? std::string() : order.front();
    const char * name = user != nullptr ? user : "";
    if (!forced.empty()) {
        // A forced method is used as is, but only when the server advertises it; clear-text methods on a
        // plaintext connection still need the plaintext confirmation.
        std::string words = " " + line + " ";
        for (auto & ch : words) ch = static_cast<char>(toupper(static_cast<unsigned char>(ch)));
        bool clearText = forced == "IMAP-LOGIN" || forced == "LOGIN" || forced == "PLAIN";
        if (!tls && !plaintextOk && clearText) {
            *error = "plaintext login needs confirmation";
            return false;
        }
        if (forced == "IMAP-LOGIN") {
            if (words.find(" LOGINDISABLED ") != std::string::npos) {
                *error = serviceLabel("IMAP", host, port) + " (" + address + "): server disables the LOGIN command";
                return false;
            }
            mechanism.clear();
        } else {
            if (words.find(" AUTH=" + forced + " ") == std::string::npos) {
                *error = serviceLabel("IMAP", host, port) + " (" + address + "): server does not advertise AUTH="
                    + forced;
                return false;
            }
            mechanism = forced;
        }
        if (!tls && !plaintextOk && mechanism.empty()) {
            *error = "plaintext login needs confirmation";
            return false;
        }
    }
    // F100: an empty user never skips the guard (LOGIN "" "<password>" would go out in clear) and never logs in.
    // F42: libetpan quotes LOGIN arguments without escaping CR/LF; a line break would end the command.
    if (!wireOk(name) || (password != nullptr && !wireOk(password))) {
        *error = "user name or password contains a line break";
        return false;
    }
    if (name[0] == '\0' && mechanism != "EXTERNAL") {
        *error = "user name is empty";
        return false;
    }
    if (!tls && !plaintextOk && mechanism.empty()) {
        *error = "plaintext login needs confirmation";
        return false;
    }
    std::string capsUpper = " " + line + " ";
    for (auto & ch : capsUpper) ch = static_cast<char>(toupper(static_cast<unsigned char>(ch)));
    if (!forced.empty()) {
        order.clear();
        if (!mechanism.empty()) order.push_back(mechanism);
    } else if (havePassword && (tls || plaintextOk) && capsUpper.find(" LOGINDISABLED ") == std::string::npos
        && !order.empty()) {
        // After every SASL mechanism failed locally, the plain IMAP LOGIN command is the last resort.
        order.push_back(std::string());
    }
    std::string localFailures;
    for (size_t attempt = 0;; attempt++) {
        if (!order.empty()) mechanism = order[attempt];
        bool plus = mechanism.size() >= 5
            && mechanism.compare(mechanism.size() - 5, 5, "-PLUS") == 0;
        if (plus && bindingLen > 0) {
            int br = mailimap_set_channel_binding(imap, bindingName, binding, bindingLen);
            if (br != 0) {
                *error = "channel binding failed";
                return false;
            }
        } else {
            mailimap_set_channel_binding(imap, nullptr, nullptr, 0);
        }
        // libetpan sends the tag only after sasl_client_start succeeded: an unchanged tag means nothing was sent.
        uint32_t tagBefore = imap->imap_tag;
        if (mechanism == "EXTERNAL" && capsUpper.find(" SASL-IR ") != std::string::npos) {
            // Sent directly: Cyrus's client EXTERNAL fails locally here. Empty authzid ("="): the server takes
            // the identity from the verified client certificate.
            r = authenticateExternal(imap);
        } else if (mechanism.empty()) {
            r = mailimap_login(imap, user, password);
        } else {
            // Authorization id: empty, since a=<user> breaks Dovecot 2.2 SCRAM. Except DIGEST-MD5, where Cyrus's
            // client omits an authzid equal to the user but sends an empty one (authzid=""), which Dovecot rejects.
            r = mailimap_authenticate(imap, mechanism.c_str(), host, nullptr, nullptr,
                "", user, password, nullptr);
        }
        if (cmdOk(r)) return true;
        bool local = r == MAILIMAP_ERROR_LOGIN && !mechanism.empty() && mechanism != "EXTERNAL"
            && imap->imap_tag == tagBefore;
        if (local && attempt + 1 < order.size()) {
            // Nothing reached the server: try the next mechanism on the same connection.
            localFailures += (localFailures.empty() ? "" : ", ") + mechanism;
            continue;
        }
        if (local) {
            localFailures += (localFailures.empty() ? "" : ", ") + mechanism;
            *error = serviceLabel("IMAP", host, port) + " (" + address + ") user " + name
                + ": login failed before contacting the server: SASL " + localFailures
                + " failed locally (supported: " + saslClientMechs(havePassword)
                + (gSaslSetup.empty() ? "" : "; plugin errors: " + gSaslSetup) + ")";
            return false;
        }
        if (authRejected != nullptr) *authRejected = true;
        *error = serviceLabel("IMAP", host, port) + " (" + address + ") user " + name
            + ": login failed: " + imapText(imap, "login failed")
            + (mechanism.empty() ? std::string(" (server rejected LOGIN)") : " (server rejected SASL " + mechanism + ")");
        if (!localFailures.empty()) *error += " (failed locally first: " + localFailures + ")";
        if (!gClientCertError.empty()) *error += " (" + gClientCertError + ")";
        return false;
    }
}

// Owning copies of the fields a watch connect must not read from the live session after mu is released.
// The main connection's open leaves owned null and still reads the session, whose mutex it holds.
struct ImapOpenMaterial {
    bool allowPlaintextAuth = false;
    std::string authMechanism;
    bool allowOldTls = false;
    bool acceptInvalidCert = false;
    std::vector<std::string> keyChainChain;
    jobject keyChainKey = nullptr;
    TrafficFile * traffic = nullptr;
    int connectTimeoutSec = 30;
    int readTimeoutSec = 60;
    JNIEnv * env = nullptr;
    ImapOpenMaterial() = default;
    ImapOpenMaterial(const ImapOpenMaterial &) = delete;
    ImapOpenMaterial & operator=(const ImapOpenMaterial &) = delete;
    ~ImapOpenMaterial() {
        if (keyChainKey != nullptr && env != nullptr) env->DeleteGlobalRef(keyChainKey);
    }
};

mailimap * openPlain(JNIEnv * env, const char * host, int port, const char * user, const char * password,
    LiveSession * live, std::string * error, std::string * connectedAddress, const ImapOpenMaterial * owned = nullptr,
    bool * authRejected = nullptr, std::string * tlsOut = nullptr) {
    std::string address;
    int connectSec = owned != nullptr ? owned->connectTimeoutSec : (live != nullptr ? live->connectTimeoutSec : 30);
    int readSec = owned != nullptr ? owned->readTimeoutSec : (live != nullptr ? live->readTimeoutSec : 60);
    TrafficFile * traffic = owned != nullptr ? owned->traffic : (live != nullptr ? &live->imapTraffic : nullptr);
    if (live != nullptr && owned == nullptr) bindTraffic(&live->imapTraffic, live->trafficDir, "imap", live->trafficRollBytes);
    bool plaintextOk = owned != nullptr ? owned->allowPlaintextAuth : (live != nullptr && live->allowPlaintextAuth);
    std::string mechanism = owned != nullptr ? owned->authMechanism : (live != nullptr ? live->authMechanism : std::string());
    mailimap * imap = openTcp(host, port, error, &address, traffic, connectSec, readSec);
    if (imap == nullptr) return nullptr;
    if (!loginImap(env, imap, host, port, user, password, address, error, false, plaintextOk, mechanism,
            authRejected, tlsOut)) {
        mailimap_free(imap);
        return nullptr;
    }
    if (connectedAddress != nullptr) *connectedAddress = address;
    return imap;
}

mailimap * openTls(JNIEnv * env, const char * mode, const char * host, int port, const char * user,
    const char * password, LiveSession * live, std::string * error, std::string * connectedAddress,
    const char * pin, bool publishOffer, const ImapOpenMaterial * owned = nullptr,
    bool * authRejected = nullptr, std::string * tlsOut = nullptr, std::vector<std::string> * certOffer = nullptr) {
    TlsCapture capture;
    TrafficFile * traffic = nullptr;
    int connectSec = 30;
    int readSec = 60;
    std::string mechanism;
    bool acceptInvalid = false;
    if (owned != nullptr) {
        capture.allowOldTls = owned->allowOldTls;
        capture.keyChainChain = &owned->keyChainChain;
        capture.keyChainKey = owned->keyChainKey;
        traffic = owned->traffic;
        connectSec = owned->connectTimeoutSec;
        readSec = owned->readTimeoutSec;
        mechanism = owned->authMechanism;
        acceptInvalid = owned->acceptInvalidCert;
    } else if (live != nullptr) {
        capture.allowOldTls = live->allowOldTls;
        capture.keyChainChain = &live->keyChainChain;
        capture.keyChainKey = live->keyChainKey;
        bindTraffic(&live->imapTraffic, live->trafficDir, "imap", live->trafficRollBytes);
        traffic = &live->imapTraffic;
        connectSec = live->connectTimeoutSec;
        readSec = live->readTimeoutSec;
        mechanism = live->authMechanism;
        acceptInvalid = live->acceptInvalidCert;
    }
    tlsCapture = &capture;
    std::string address;
    mailimap * imap = nullptr;
    bool tlsOk = false;
    if (strcmp(mode, "StartTls") == 0) {
        imap = openTcp(host, port, error, &address, traffic, connectSec, readSec);
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
            *error = serviceLabel("IMAP", host, port) + " (" + address + "): server does not offer STARTTLS";
            rejectImap(imap);
            tlsCapture = nullptr;
            return nullptr;
        }
        r = mailimap_socket_starttls_with_server_name_callback(imap, sniName(host), prepareTls, nullptr);
        tlsOk = cmdOk(r);
        if (!tlsOk) {
            *error = serviceLabel("IMAP", host, port) + " (" + address + "): STARTTLS failed: "
                + imapText(imap, "STARTTLS failed");
        }
    } else if (strcmp(mode, "Implicit") == 0) {
        imap = mailimap_new(0, nullptr);
        if (imap == nullptr) {
            *error = protocolError("IMAP", MAILIMAP_ERROR_MEMORY, "MAILIMAP_ERROR_MEMORY", nullptr);
            tlsCapture = nullptr;
            return nullptr;
        }
        if (readSec < 1) readSec = 1;
        mailimap_set_timeout(imap, readSec);
        attachTrafficLogger(imap, traffic);
        if (port < 0 || port > 65535) {
            *error = connectFailure("IMAP", host, port, host != nullptr ? host : "", "connection failed");
            rejectImap(imap);
            tlsCapture = nullptr;
            return nullptr;
        }
        std::string dialError;
        int fd = tcpConnect("IMAP", host, port, &address, &dialError, connectSec);
        if (fd < 0) {
            *error = dialError;
            rejectImap(imap);
            tlsCapture = nullptr;
            return nullptr;
        }
        mailstream * stream = mailstream_ssl_open_with_server_name_callback_timeout(
            fd, readSec, sniName(host), prepareTls, nullptr);
        if (stream == nullptr) {
            close(fd);
            *error = connectFailure("IMAP", host, port, address, "connection failed");
            rejectImap(imap);
            tlsCapture = nullptr;
            return nullptr;
        }
        int r = mailimap_connect(imap, stream);
        tlsOk = connectOk(r);
        if (!tlsOk) {
            *error = connectFailure("IMAP", host, port, address, imapText(imap, "connection failed"));
        }
    } else {
        *error = "unknown tls mode";
        tlsCapture = nullptr;
        return nullptr;
    }
    // A handshake that failed before any certificate arrived reports the TLS failure, not a certificate verdict.
    if (!tlsOk) {
        std::string reason = tlsFailureReason();
        if (error->empty()) *error = "TLS handshake failed";
        *error += reason.empty() ? " (TLS handshake failed)" : " (TLS handshake failed: " + reason + ")";
        rejectImap(imap);
        tlsCapture = nullptr;
        return nullptr;
    }
    if (capture.ders.empty()) {
        *error = "certificate rejected";
        rejectImap(imap);
        tlsCapture = nullptr;
        return nullptr;
    }
    std::string trust = peerTrustCheck(env, host, capture.ders, pin, publishOffer, certOffer);
    tlsCapture = nullptr;
    if (!trust.empty() && acceptInvalid && certificateVerdict(trust) && trust != "certificate changed") {
        if (certOffer != nullptr) certOffer->clear();
        trust.clear();
    }
    if (!trust.empty()) {
        *error = trust;
        rejectImap(imap);
        return nullptr;
    }
    if (!loginImap(env, imap, host, port, user, password, address, error, true, true, mechanism,
            authRejected, tlsOut)) {
        mailimap_free(imap);
        return nullptr;
    }
    if (connectedAddress != nullptr) *connectedAddress = address;
    return imap;
}

void freeSession(LiveSession * session, JNIEnv *) {
    if (session == nullptr || !session->selfHold) return;
    std::shared_ptr<LiveSession> keep = session->selfHold;
    enterReclaim();
    bool removed = unlinkSession(session);
    session->closed.store(true);
    leaveReclaim();
    if (!removed) return;
    shutdownMainSocket(session);
    shutdownWatchSocket(session);
    shutdownFd(session->smtpFd.load());
    session->watchPhase.store(3);
    signalWatchStop(session);
    trafficClose(&session->imapTraffic);
    trafficClose(&session->watchTraffic);
    session->ownerStop.store(true);
    kickOwner(session);
    // Closing from a watch callback runs on the owner. Joining it here would deadlock.
    // Phase 0 lets a stop release its use count. The closed job queue lets queued commands do the same.
    // The watch connection stays until the owner loop leaves the callback and calls finishIdleStop.
    if (tlsOwner == session) {
        closeJobQueue(session);
        session->watchPhase.store(0);
        session->watchPhase.notify_all();
        while (session->users.load() != 0) {
            int pending = session->users.load();
            if (pending == 0) break;
            session->users.wait(pending);
        }
        if (session->owner.joinable()) session->owner.detach();
        return;
    }
    joinOwner(session);
    while (session->users.load() != 0) {
        int pending = session->users.load();
        if (pending == 0) break;
        session->users.wait(pending);
    }
    session->selfHold.reset();
}

std::string leafOf(const char * mailbox, char delim, bool utf8) {
    std::string raw = mailbox != nullptr ? mailbox : "";
    std::string comp = raw;
    if (delim != 0) {
        auto pos = raw.find_last_of(delim);
        if (pos != std::string::npos) {
            comp = raw.substr(pos + 1);
        }
    }
    if (utf8) return comp;  // UTF8=ACCEPT: already UTF-8; '&' is a plain character
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

// One delimiter per namespace root. Folder names are not keys. "" is the personal root when NAMESPACE has not
// named one yet. A longer root wins over "".
char delimFor(LiveSession * session, const char * parent) {
    if (session == nullptr || parent == nullptr) return 0;
    auto it = session->delims.find(parent);
    if (it != session->delims.end()) return it->second;
    std::string name = parent;
    char best = 0;
    size_t bestLen = 0;
    bool found = false;
    for (const auto & item : session->delims) {
        if (name.compare(0, item.first.size(), item.first) != 0) continue;
        if (!item.first.empty() && name.size() > item.first.size() && name[item.first.size()] != item.second) continue;
        if (found && item.first.size() <= bestLen) continue;
        bestLen = item.first.size();
        best = item.second;
        found = true;
    }
    return found ? best : 0;
}

// Replace the delimiter of the namespace root that contains [mailbox]. A new root is recorded only as "".
void noteDelim(LiveSession * session, const char * mailbox, char delim) {
    if (session == nullptr || delim == 0) return;
    std::string name = mailbox != nullptr ? mailbox : "";
    std::string best;
    bool found = false;
    size_t bestLen = 0;
    for (const auto & item : session->delims) {
        if (name.compare(0, item.first.size(), item.first) != 0) continue;
        if (!item.first.empty() && name.size() > item.first.size() && name[item.first.size()] != item.second
            && name[item.first.size()] != delim) {
            continue;
        }
        if (found && item.first.size() <= bestLen) continue;
        best = item.first;
        bestLen = item.first.size();
        found = true;
    }
    if (!found) session->delims[""] = delim;
    else session->delims[best] = delim;
}

// Delimiter not seen yet (children listed before the top level): ask the server. LIST "" <parent> gives the
// parent's own delimiter; LIST "" "" the hierarchy delimiter. Without this, LIST "<parent>" "%" returned the
// parent itself instead of its children. The answer is stored on the namespace root, not on the folder.
char learnDelim(LiveSession * session, const char * parent) {
    char d = delimFor(session, parent);
    if (d != 0) return d;
    const char * patterns[] = { parent != nullptr ? parent : "", "" };
    for (const char * pat : patterns) {
        clist * l = nullptr;
        int r = mailimap_list(session->imap, "", pat, &l);
        if (cmdOk(r) && l != nullptr) {
            for (clistiter * cur = clist_begin(l); cur != nullptr; cur = clist_next(cur)) {
                auto * mb = static_cast<struct mailimap_mailbox_list *>(clist_content(cur));
                if (mb == nullptr || mb->mb_delimiter == 0) continue;
                noteDelim(session, mb->mb_name, mb->mb_delimiter);
                d = mb->mb_delimiter;
            }
        }
        if (l != nullptr) mailimap_list_result_free(l);
        if (d != 0) return d;
    }
    return 0;
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

int selectFullCapture(mailimap * imap, const char * mailbox, uint64_t * modseq, const char * command, bool condstore) {
    *modseq = 0;
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    const int cond = condstore ? 1 : 0;
    if (command != nullptr && strcmp(command, "EXAMINE") == 0) {
        r = mailimap_examine_send(imap->imap_stream, mailbox, cond);
    } else {
        r = mailimap_select_send(imap->imap_stream, mailbox, cond);
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
    if (strcasecmp(capName, "CONDSTORE") == 0) wire = "CONDSTORE";
    else if (strcasecmp(capName, "UTF8=ACCEPT") == 0) wire = "UTF8=ACCEPT";
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
    bool granted = false;
    if (result != nullptr && result->cap_list != nullptr) {
        for (clistiter * it = clist_begin(result->cap_list); it != nullptr; it = clist_next(it)) {
            auto * c = static_cast<struct mailimap_capability *>(clist_content(it));
            const char * n = c != nullptr && c->cap_type == MAILIMAP_CAPABILITY_NAME ? c->cap_data.cap_name : nullptr;
            if (n != nullptr && strcasecmp(n, wire) == 0) granted = true;
        }
    }
    if (result != nullptr) mailimap_capability_data_free(result);
    // F30 condition: a resync extension counts only when the ENABLED response names it.
    // Only an ENABLED response naming UTF8=ACCEPT switches name handling (RFC 5161: the server may ignore it).
    if (strcmp(wire, "UTF8=ACCEPT") == 0 && granted) session->utf8 = true;
}

void compressSession(LiveSession * session) {
    if (session == nullptr || session->imap == nullptr) return;
    int r = mailimap_compress(session->imap);
    if (r == MAILIMAP_NO_ERROR) {
        if (session->imap->imap_stream == nullptr) return;
        if (!session->trafficDir.empty() || session->watchProgress) {
            mailstream_set_logger(session->imap->imap_stream, streamTrafficLogger, session);
        }
        return;
    }
    // Stay up, compression off, only when a tagged refusal left the stream in step.
    if (r == MAILIMAP_ERROR_EXTENSION && mailimap_is_desynchronized(session->imap) == 0) return;
    shutdownMainSocket(session);
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
        // {n}\r\n then n bytes (F11): at most 9 digits, the closing brace and CRLF required, n within the rest.
        size_t n = 0;
        size_t digits = 0;
        while (i < raw.size() && raw[i] >= '0' && raw[i] <= '9') {
            if (++digits > 9) return false;
            n = n * 10 + static_cast<size_t>(raw[i] - '0');
            ++i;
        }
        if (digits == 0) return false;
        if (i < raw.size() && raw[i] == '+') ++i;  // LITERAL+ form
        if (i >= raw.size() || raw[i] != '}') return false;
        ++i;
        if (i + 1 >= raw.size() || raw[i] != '\r' || raw[i + 1] != '\n') return false;
        i += 2;
        if (n > raw.size() - i) return false;
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

void freeUidBuf(UidBuf * list) {
    delete list;
}

// F48: strings from libetpan's quoted/literal parsers are MMAPStrings registered with mmap_string_ref; a plain
// free() leaves a stale entry in libetpan's ref table (double free later). mailimap_astring_free unrefs those
// and falls back to free() for plain malloc'd atoms, so it is right for every string these parsers return.
static void imapStrFree(char * s) {
    if (s != nullptr) mailimap_astring_free(s);
}

void freeScopeHit(LiveScopeHit * hit) {
    if (hit == nullptr) return;
    imapStrFree(hit->mailbox);
    freeUidBuf(hit->uids);
    free(hit);
}

bool appendUid(std::vector<uint32_t> * list, uint32_t uid) {
    if (uid == 0 || list == nullptr) return true;
    list->push_back(uid);
    return true;
}

// F99: set when a '*' was read. '*' is not rewritten to uidnext-1 and is not expanded into the list.
thread_local bool tlsSeqSawStar = false;
// A non-mailbox set contained '*'. Refuse the command after the tagged completion is read.
thread_local bool tlsStarCommandRefused = false;
// A MAILBOX hit was dropped because its set contained '*'. Refuse after the tagged completion is read.
thread_local bool tlsStarDroppedHit = false;

int parseSeqNumber(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    uint32_t * out, bool * star) {
    if (star != nullptr) *star = false;
    if (peekChar(buffer, *indx) == '*') {
        *indx += 1;
        tlsSeqSawStar = true;
        if (star != nullptr) *star = true;
        *out = 0;
        // The largest UID in use is not uidnext-1. The set is refused after the line is consumed.
        return MAILIMAP_NO_ERROR;
    }
    return mailimap_nz_number_parse(fd, buffer, ctx, indx, out);
}

int parseSeqSet(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    std::vector<uint32_t> * list) {
    uint64_t total = list == nullptr ? 0 : static_cast<uint64_t>(list->size());
    for (;;) {
        uint32_t first = 0;
        bool firstStar = false;
        int r = parseSeqNumber(fd, buffer, ctx, indx, &first, &firstStar);
        if (r != MAILIMAP_NO_ERROR) return r;
        uint32_t last = first;
        bool lastStar = firstStar;
        if (peekChar(buffer, *indx) == ':') {
            *indx += 1;
            r = parseSeqNumber(fd, buffer, ctx, indx, &last, &lastStar);
            if (r != MAILIMAP_NO_ERROR) return r;
        }
        if (!firstStar && !lastStar) {
            if (first > last) {
                uint32_t tmp = first;
                first = last;
                last = tmp;
            }
            // F49: never squeeze a wide range to its endpoints (select-all would then act on two messages). Every
            // range is expanded; past the result UID cap in total the answer fails clearly instead of being lossy.
            total += static_cast<uint64_t>(last) - first + 1;
            if (total > resultUidCap()) { tlsResultUidCap = true; return MAILIMAP_ERROR_PARSE; }
            if (list != nullptr) list->reserve(static_cast<size_t>(total));
            {
                uint32_t n = first;
                for (;;) {
                    if (!appendUid(list, n)) return MAILIMAP_ERROR_MEMORY;
                    if (n == last) break;
                    ++n;
                }
            }
        }
        if (peekChar(buffer, *indx) != ',') break;
        *indx += 1;
    }
    return MAILIMAP_NO_ERROR;
}

int parseTagValue(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    std::string * out = nullptr) {
    char c = peekChar(buffer, *indx);
    if (c == '"' || c == '{') {
        char * text = nullptr;
        size_t len = 0;
        int r = mailimap_nstring_parse(fd, buffer, ctx, indx, &text, &len, 0, nullptr);
        if (r == MAILIMAP_NO_ERROR && out != nullptr && text != nullptr) out->assign(text, len);
        imapStrFree(text);
        return r;
    }
    char * atom = nullptr;
    int r = mailimap_atom_parse(fd, buffer, ctx, indx, &atom, 0, nullptr);
    if (r == MAILIMAP_NO_ERROR && out != nullptr && atom != nullptr) out->assign(atom);
    imapStrFree(atom);
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

int failEsearch(char * mailbox, int r) {
    imapStrFree(mailbox);
    return r;
}

int parseCorrelatorGroup(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    char ** mailbox, bool * sawMailbox, uint32_t * uidValidity, bool * sawValidity, std::string * tagOut, bool * tagSeen) {
    size_t cur = *indx;
    int r = mailimap_oparenth_parse(fd, buffer, ctx, &cur);
    if (r != MAILIMAP_NO_ERROR) return r;
    bool sawTag = false;
    bool sawBox = false;
    bool any = false;
    char * box = nullptr;
    for (;;) {
        if (peekChar(buffer, cur) == ')') break;
        if (any) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                imapStrFree(box);
                return r;
            }
        }
        char * atom = nullptr;
        r = mailimap_atom_parse(fd, buffer, ctx, &cur, &atom, 0, nullptr);
        if (r != MAILIMAP_NO_ERROR) {
            imapStrFree(box);
            return r;
        }
        std::string name = atom != nullptr ? atom : "";
        imapStrFree(atom);
        any = true;
        if (strcasecmp(name.c_str(), "TAG") == 0) {
            if (sawTag) {
                imapStrFree(box);
                return MAILIMAP_ERROR_PARSE;
            }
            sawTag = true;
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                imapStrFree(box);
                return r;
            }
            r = parseTagValue(fd, buffer, ctx, &cur, tagOut);
            if (r != MAILIMAP_NO_ERROR) {
                imapStrFree(box);
                return r;
            }
            *tagSeen = true;
            continue;
        }
        if (strcasecmp(name.c_str(), "MAILBOX") == 0) {
            if (sawBox || *sawMailbox) {
                imapStrFree(box);
                return MAILIMAP_ERROR_PARSE;
            }
            sawBox = true;
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                imapStrFree(box);
                return r;
            }
            char * parsed = nullptr;
            r = mailimap_mailbox_parse(fd, buffer, ctx, &cur, &parsed, 0, nullptr);
            if (r != MAILIMAP_NO_ERROR || parsed == nullptr) {
                imapStrFree(parsed);
                imapStrFree(box);
                return r != MAILIMAP_NO_ERROR ? r : MAILIMAP_ERROR_PARSE;
            }
            imapStrFree(box);
            box = parsed;
            continue;
        }
        if (strcasecmp(name.c_str(), "UIDVALIDITY") == 0) {
            if (sawValidity != nullptr && *sawValidity) {
                imapStrFree(box);
                return MAILIMAP_ERROR_PARSE;
            }
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) {
                imapStrFree(box);
                return r;
            }
            uint32_t number = 0;
            r = mailimap_nz_number_parse(fd, buffer, ctx, &cur, &number);
            if (r != MAILIMAP_NO_ERROR) {
                imapStrFree(box);
                return r;
            }
            if (uidValidity != nullptr) *uidValidity = number;
            if (sawValidity != nullptr) *sawValidity = true;
            continue;
        }
        imapStrFree(box);
        return MAILIMAP_ERROR_PARSE;
    }
    if (!any) {
        imapStrFree(box);
        return MAILIMAP_ERROR_PARSE;
    }
    r = mailimap_cparenth_parse(fd, buffer, ctx, &cur);
    if (r != MAILIMAP_NO_ERROR) {
        imapStrFree(box);
        return r;
    }
    if (sawBox) {
        imapStrFree(*mailbox);
        *mailbox = box;
        *sawMailbox = true;
    } else {
        imapStrFree(box);
    }
    *indx = cur;
    return MAILIMAP_NO_ERROR;
}

// Sequence numbers and UIDs are different sets. Appending both into one list would search the wrong messages.
bool claimNumberKind(bool asUid, bool * sawSeq, bool * sawUid) {
    if (asUid) {
        if (*sawSeq) return false;
        *sawUid = true;
        return true;
    }
    if (*sawUid) return false;
    *sawSeq = true;
    return true;
}

int parseEsearch(mailstream * fd, MMAPString * buffer, struct mailimap_parser_context * ctx, size_t * indx,
    struct mailimap_extension_data ** result) {
    size_t cur = *indx;
    int r = mailimap_token_case_insensitive_parse(fd, buffer, &cur, "ESEARCH");
    if (r != MAILIMAP_NO_ERROR) return r;
    std::vector<uint32_t> uids;
    char * mailbox = nullptr;
    bool sawMailbox = false;
    uint32_t uidValidity = 0;
    bool sawValidity = false;
    bool hasAll = false;
    bool sawCount = false;
    int64_t countValue = 0;
    std::string tag;
    bool tagSeen = false;
    bool uidTag = false;
    bool sawSeq = false;
    bool sawUid = false;
    tlsSeqSawStar = false;
    for (;;) {
        size_t save = cur;
        r = mailimap_space_parse(fd, buffer, &cur);
        if (r != MAILIMAP_NO_ERROR) {
            cur = save;
            break;
        }
        if (peekChar(buffer, cur) == '(') {
            r = parseCorrelatorGroup(fd, buffer, ctx, &cur, &mailbox, &sawMailbox, &uidValidity, &sawValidity, &tag, &tagSeen);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            continue;
        }
        char * atom = nullptr;
        r = mailimap_atom_parse(fd, buffer, ctx, &cur, &atom, 0, nullptr);
        if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
        std::string name = atom != nullptr ? atom : "";
        imapStrFree(atom);
        if (strcasecmp(name.c_str(), "UID") == 0) {
            uidTag = true;
            continue;
        }
        if (strcasecmp(name.c_str(), "ALL") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            if (!claimNumberKind(uidTag, &sawSeq, &sawUid)) return failEsearch(mailbox, MAILIMAP_ERROR_PARSE);
            r = parseSeqSet(fd, buffer, ctx, &cur, &uids);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            hasAll = true;
            continue;
        }
        if (strcasecmp(name.c_str(), "MIN") == 0 || strcasecmp(name.c_str(), "MAX") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            uint32_t number = 0;
            r = mailimap_nz_number_parse(fd, buffer, ctx, &cur, &number);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            if (!claimNumberKind(uidTag, &sawSeq, &sawUid)) return failEsearch(mailbox, MAILIMAP_ERROR_PARSE);
            if (!appendUid(&uids, number)) return failEsearch(mailbox, MAILIMAP_ERROR_MEMORY);
            continue;
        }
        if (strcasecmp(name.c_str(), "COUNT") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            int64_t number = 0;
            if (!parseDecimal(buffer, &cur, &number)) return failEsearch(mailbox, MAILIMAP_ERROR_PARSE);
            sawCount = true;
            countValue = number;
            continue;
        }
        if (strcasecmp(name.c_str(), "PARTIAL") == 0) {
            // RFC 9394: PARTIAL (<lo>:<hi> <uid set or NIL>), uids in result order.
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            if (peekChar(buffer, cur) != '(') return failEsearch(mailbox, MAILIMAP_ERROR_PARSE);
            cur += 1;
            while (cur < buffer->len && buffer->str[cur] != ' ' && buffer->str[cur] != ')') cur += 1;
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            if (cur + 3 <= buffer->len && strncasecmp(buffer->str + cur, "NIL", 3) == 0) {
                cur += 3;
            } else {
                if (!claimNumberKind(uidTag, &sawSeq, &sawUid)) return failEsearch(mailbox, MAILIMAP_ERROR_PARSE);
                r = parseSeqSet(fd, buffer, ctx, &cur, &uids);
                if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            }
            if (peekChar(buffer, cur) != ')') return failEsearch(mailbox, MAILIMAP_ERROR_PARSE);
            cur += 1;
            hasAll = true;
            continue;
        }
        if (strcasecmp(name.c_str(), "MODSEQ") == 0) {
            r = mailimap_space_parse(fd, buffer, &cur);
            if (r != MAILIMAP_NO_ERROR) return failEsearch(mailbox, r);
            while (peekChar(buffer, cur) >= '0' && peekChar(buffer, cur) <= '9') cur += 1;
            continue;
        }
        return failEsearch(mailbox, MAILIMAP_ERROR_PARSE);
    }
    // F99: an ESEARCH tagged for another command (left over from an aborted or pipelined one) is not this
    // command's result. It is parsed (so the stream stays in step) and then dropped.
    if (tagSeen && tlsImap != nullptr && tag != currentTagText(tlsImap)) {
        imapStrFree(mailbox);
        struct mailimap_extension_data * ignored =
            mailimap_extension_data_new(&liveimap_extra_extension, LIVE_ESEARCH, nullptr);
        if (ignored == nullptr) return MAILIMAP_ERROR_MEMORY;
        ignored->ext_type = -1;
        *result = ignored;
        *indx = cur;
        return MAILIMAP_NO_ERROR;
    }
    if (!sawMailbox && sawCount && tlsImap != nullptr && tlsLive != nullptr && tlsLive->imap == tlsImap) {
        tlsLive->esearchCount = countValue;
    }
    if (sawMailbox && tlsSeqSawStar) {
        // A mailbox set containing '*' cannot be resolved to a UID that is in use. Accept the line so the
        // tagged completion is read, then refuse the command even if another mailbox hit is present.
        // Do not rewrite '*' to uidnext-1.
        imapStrFree(mailbox);
        tlsStarDroppedHit = true;
        struct mailimap_extension_data * ignored =
            mailimap_extension_data_new(&liveimap_extra_extension, LIVE_ESEARCH, nullptr);
        if (ignored == nullptr) return MAILIMAP_ERROR_MEMORY;
        ignored->ext_type = -1;
        *result = ignored;
        *indx = cur;
        return MAILIMAP_NO_ERROR;
    }
    if (!sawMailbox && tlsSeqSawStar) {
        // The set cannot be resolved to a UID that is in use. Accept the line so the tagged completion is
        // read, then refuse the command. Do not rewrite '*' to uidnext-1, n:*, or 1..n.
        imapStrFree(mailbox);
        if (tlsLive != nullptr && tlsImap != nullptr && tlsLive->imap == tlsImap) tlsLive->esearchCount = -1;
        tlsStarCommandRefused = true;
        struct mailimap_extension_data * ignored =
            mailimap_extension_data_new(&liveimap_extra_extension, LIVE_ESEARCH, nullptr);
        if (ignored == nullptr) return MAILIMAP_ERROR_MEMORY;
        ignored->ext_type = -1;
        *result = ignored;
        *indx = cur;
        return MAILIMAP_NO_ERROR;
    }
    if (sawMailbox) {
        auto * hit = static_cast<LiveScopeHit *>(calloc(1, sizeof(LiveScopeHit)));
        if (hit == nullptr) return failEsearch(mailbox, MAILIMAP_ERROR_MEMORY);
        hit->mailbox = mailbox;
        hit->uids = new UidBuf();
        hit->uids->uids.swap(uids);
        hit->uidValidity = uidValidity;
        hit->hasValidity = sawValidity ? 1 : 0;
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
    imapStrFree(mailbox);
    auto * owned = new UidBuf();
    owned->uids.swap(uids);
    struct mailimap_extension_data * ext = mailimap_extension_data_new(&liveimap_extra_extension, LIVE_ESEARCH, owned);
    if (ext == nullptr) {
        delete owned;
        return MAILIMAP_ERROR_MEMORY;
    }
    *result = ext;
    *indx = cur;
    return MAILIMAP_NO_ERROR;
}

LiveBlob * blobFromNstring(char * text, size_t len) {
    auto * blob = static_cast<LiveBlob *>(calloc(1, sizeof(LiveBlob)));
    if (blob == nullptr) {
        imapStrFree(text);
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
        imapStrFree(blob->data);
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
        imapStrFree(blob->data);
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
            imapStrFree(blob->data);
            free(blob);
        }
    } else if (ext_data->ext_type == LIVE_ESEARCH) {
        delete static_cast<UidBuf *>(ext_data->ext_data);
    } else if (ext_data->ext_type == LIVE_SCOPE) {
        freeScopeHit(static_cast<LiveScopeHit *>(ext_data->ext_data));
    }
    free(ext_data);
}

// COUNT from an ESEARCH that libetpan's own esearch extension parsed (it claims some responses before ours).
thread_local int64_t tlsEsearchExtCount = -1;

std::vector<uint32_t> takeEsearch(mailimap * imap) {
    bool have = false;
    std::vector<uint32_t> out;
    struct mailimap_esearch_result * esearch = nullptr;
    if (imap->imap_response_info != nullptr && imap->imap_response_info->rsp_extension_list != nullptr) {
        for (clistiter * cur = clist_begin(imap->imap_response_info->rsp_extension_list); cur != nullptr; cur = clist_next(cur)) {
            auto * ext = static_cast<struct mailimap_extension_data *>(clist_content(cur));
            if (ext == nullptr) continue;
            if (ext->ext_extension == &liveimap_extra_extension && ext->ext_type == LIVE_ESEARCH && !have) {
                have = true;
                auto * buf = static_cast<UidBuf *>(ext->ext_data);
                if (buf != nullptr) {
                    out.swap(buf->uids);
                    delete buf;
                }
                ext->ext_data = nullptr;
                ext->ext_type = -1;
            } else if (esearch == nullptr && ext->ext_extension == &mailimap_extension_esearch && ext->ext_data != nullptr) {
                esearch = static_cast<struct mailimap_esearch_result *>(ext->ext_data);
            }
        }
    }
    if (esearch != nullptr && esearch->has_count) tlsEsearchExtCount = esearch->count;
    if (!have && esearch != nullptr) {
        clist * msg = esearch->msg_list;
        esearch->msg_list = nullptr;
        if (msg != nullptr) {
            for (clistiter * cur = clist_begin(msg); cur != nullptr; cur = clist_next(cur)) {
                auto * n = static_cast<uint32_t *>(clist_content(cur));
                if (n != nullptr) appendUid(&out, *n);
            }
            mailimap_search_result_free(msg);
        }
        if (out.empty()) {
            if (esearch->has_min) appendUid(&out, esearch->min);
            if (esearch->has_max) appendUid(&out, esearch->max);
        }
    }
    freeExtensionList(imap);
    return out;
}

bool scopeSource(const char * scopeName, const char ** source, bool * subtree);
int sendEsearchIn(mailimap * imap, const char * scopeName, const char * home, const char * ret, bool withCharset,
    bool fuzzy, struct mailimap_search_key * key, struct mailimap_response ** response);

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

jobjectArray scopeUidArray(JNIEnv * env, clist * hits, jstring accountId) {
    size_t n = 0;
    if (hits != nullptr) {
        for (clistiter * cur = clist_begin(hits); cur != nullptr; cur = clist_next(cur)) {
            auto * hit = static_cast<LiveScopeHit *>(clist_content(cur));
            if (hit == nullptr || hit->mailbox == nullptr || !hit->hasAll || hit->uids == nullptr) continue;
            n += hit->uids->uids.size();
        }
    }
    if (n > static_cast<size_t>(INT32_MAX)) return nullptr;
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(n), gJni.messageRef, nullptr);
    if (arr == nullptr) return nullptr;
    jsize at = 0;
    if (hits != nullptr) {
        for (clistiter * cur = clist_begin(hits); cur != nullptr; cur = clist_next(cur)) {
            auto * hit = static_cast<LiveScopeHit *>(clist_content(cur));
            if (hit == nullptr || hit->mailbox == nullptr || !hit->hasAll || hit->uids == nullptr) continue;
            jlong epoch = hit->hasValidity ? static_cast<jlong>(hit->uidValidity) : 0;
            for (uint32_t uid : hit->uids->uids) {
                jstring box = newString(env, hit->mailbox);
                jobject ref = env->NewObject(gJni.messageRef, gJni.messageRefInit, accountId, box, epoch,
                    static_cast<jlong>(uid));
                if (box != nullptr) env->DeleteLocalRef(box);
                if (env->ExceptionCheck() || ref == nullptr) {
                    if (ref != nullptr) env->DeleteLocalRef(ref);
                    env->DeleteLocalRef(arr);
                    return nullptr;
                }
                env->SetObjectArrayElement(arr, at, ref);
                env->DeleteLocalRef(ref);
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(arr);
                    return nullptr;
                }
                ++at;
            }
        }
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
    struct mailimap_search_key * key, jboolean withCharset, jboolean fuzzy, jstring accountId) {
    if (key == nullptr || !scopeSendable(scope, home) || gJni.messageRef == nullptr || accountId == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_response * response = nullptr;
    int r = sendEsearchIn(session->imap, scope, home, "ALL", withCharset == JNI_TRUE, fuzzy == JNI_TRUE, key, &response);
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
    jobjectArray arr = scopeUidArray(env, hits, accountId);
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
    struct mailimap_search_key * key, jboolean withCharset, jboolean fuzzy) {
    if (key == nullptr || !scopeSendable(scope, home)) {
        if (key != nullptr) mailimap_search_key_free(key);
        throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    struct mailimap_response * response = nullptr;
    int r = sendEsearchIn(session->imap, scope, home, "COUNT", withCharset == JNI_TRUE, fuzzy == JNI_TRUE, key, &response);
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
    tlsStarCommandRefused = false;
    tlsStarDroppedHit = false;
    tlsImap = imap;
    r = mailimap_parse_response(imap, response);
    tlsImap = nullptr;
    // A set containing '*' that cannot be resolved is refused, including when another mailbox hit is
    // present. libetpan may have kept that mailbox's UIDs and dropped its name; that object is not a
    // result to return. The tagged line is already consumed; PARSE would drop the connection.
    bool refuseStar = tlsStarCommandRefused || tlsStarDroppedHit;
    tlsStarCommandRefused = false;
    tlsStarDroppedHit = false;
    if (r == MAILIMAP_NO_ERROR && refuseStar) {
        // The tagged line is already consumed. PARSE would drop the connection and hide the refusal.
        freeExtensionList(imap);
        if (response != nullptr && *response != nullptr) {
            mailimap_response_free(*response);
            *response = nullptr;
        }
        if (imap != nullptr) imap->imap_response = nullptr;
        if (tlsLive != nullptr && tlsLive->imap == imap) tlsLive->esearchCount = -1;
        return MAILIMAP_ERROR_SEARCH;
    }
    return r;
}

// "PARTIAL <lo>:<hi>" or "PARTIAL <lo>:<hi> COUNT", lo/hi nonzero, both positive or both negative (RFC 9394).
bool validPartialReturn(const char * ret) {
    long lo = 0;
    long hi = 0;
    char tail[8] = {0};
    int n = 0;
    if (sscanf(ret, "PARTIAL %ld:%ld%n", &lo, &hi, &n) != 2) return false;
    if (lo == 0 || hi == 0 || (lo > 0) != (hi > 0)) return false;
    const char * rest = ret + n;
    if (*rest == 0) return true;
    return sscanf(rest, " %7s", tail) == 1 && strcmp(tail, "COUNT") == 0 && strcmp(rest, " COUNT") == 0;
}

int sendEsearch(mailimap * imap, bool byUid, const char * ret, bool withCharset, bool fuzzy,
    struct mailimap_search_key * key, struct mailimap_response ** response) {
    if (ret == nullptr) return MAILIMAP_ERROR_INVAL;
    if (strcasecmp(ret, "ALL") != 0 && strcasecmp(ret, "MIN") != 0 && strcasecmp(ret, "MAX") != 0
        && strcasecmp(ret, "COUNT") != 0 && !validPartialReturn(ret)) {
        return MAILIMAP_ERROR_INVAL;
    }
    if (strstr(ret, "COUNT") != nullptr && tlsLive != nullptr && tlsLive->imap == imap) {
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
    if (fuzzy) {
        r = sendWord(imap->imap_stream, "FUZZY", true);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_search_key_send(imap->imap_stream, key);
    if (r != MAILIMAP_NO_ERROR) return r;
    return finishParsed(imap, response);
}

int sendUidSearch(mailimap * imap, bool withCharset, struct mailimap_search_key * key,
    struct mailimap_response ** response) {
    int r = mailimap_send_current_tag(imap);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "UID", false);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = sendWord(imap->imap_stream, "SEARCH", true);
    if (r != MAILIMAP_NO_ERROR) return r;
    if (withCharset) {
        r = sendWord(imap->imap_stream, "CHARSET", true);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_space_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_astring_send(imap->imap_stream, "UTF-8");
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = sendWord(imap->imap_stream, "FUZZY", true);
    if (r != MAILIMAP_NO_ERROR) return r;
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
    bool fuzzy, struct mailimap_search_key * key, struct mailimap_response ** response) {
    if (ret == nullptr || (strcmp(ret, "ALL") != 0 && strcmp(ret, "COUNT") != 0)) return MAILIMAP_ERROR_INVAL;
    const char * source = nullptr;
    bool subtree = false;
    if (!scopeSource(scopeName, &source, &subtree)) return MAILIMAP_ERROR_INVAL;
    if (subtree && (home == nullptr || home[0] == 0)) return MAILIMAP_ERROR_INVAL;
    if (!wireOk(home)) return MAILIMAP_ERROR_INVAL;  // F42 native backstop
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
    if (fuzzy) {
        r = sendWord(imap->imap_stream, "FUZZY", true);
        if (r != MAILIMAP_NO_ERROR) return r;
    }
    r = mailimap_space_send(imap->imap_stream);
    if (r != MAILIMAP_NO_ERROR) return r;
    r = mailimap_search_key_send(imap->imap_stream, key);
    if (r != MAILIMAP_NO_ERROR) return r;
    return finishParsed(imap, response);
}

int sendUidSortChoice(mailimap * imap, const char * keyName, bool reverse, bool esort, struct mailimap_response ** response,
    const char * ret = "ALL", struct mailimap_search_key * criteria = nullptr, bool utf8 = false) {
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
        r = sendWord(imap->imap_stream, ret, false);
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
    r = mailimap_astring_send(imap->imap_stream, utf8 ? "UTF-8" : "US-ASCII");
    if (r != MAILIMAP_NO_ERROR) return r;
    if (criteria != nullptr) {
        // The filter chain's criteria travel with SORT, so the server sorts only the matching messages.
        r = mailimap_space_send(imap->imap_stream);
        if (r != MAILIMAP_NO_ERROR) return r;
        r = mailimap_search_key_send(imap->imap_stream, criteria);
    } else {
        r = sendWord(imap->imap_stream, "ALL", true);
    }
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

bool lineSafeArgument(const char * text); // F118, defined before criterionKey

enum class TextSlot { From, To, Cc, Subject, Text, Body, Keyword, Unkeyword };

struct mailimap_search_key * textKey(TextSlot slot, const char * argument) {
    if (!lineSafeArgument(argument)) return nullptr; // F118: also checked here for every direct caller
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
    case TextSlot::Keyword:
        if (!flagKeywordAtomOk(argument)) { free(copy); return nullptr; }
        key = mailimap_search_key_new_keyword(copy);
        break;
    case TextSlot::Unkeyword:
        if (!flagKeywordAtomOk(argument)) { free(copy); return nullptr; }
        key = mailimap_search_key_new_unkeyword(copy);
        break;
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
    if (localtime_r(&now, &tm) == nullptr) return false;
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

struct mailimap_search_key * criterionKey(JNIEnv * env, const char * kind, const char * argument);

// True when the selected mailbox has a message, so '*' names a UID that is in use.
bool selectedHasUid() {
    mailimap * imap = tlsLockedImap != nullptr ? tlsLockedImap : tlsImap;
    if (imap == nullptr || imap->imap_selection_info == nullptr) return false;
    return imap->imap_selection_info->sel_exists > 0;
}

// One bound of a user UID set. '*' is the star (libetpan sends 0 as '*'), not an empty bound and not uidnext-1.
bool readUidBound(const char *& p, uint32_t * number, bool * star) {
    *star = false;
    *number = 0;
    if (*p == '*') {
        ++p;
        *star = true;
        return true;
    }
    if (*p < '0' || *p > '9') return false;
    uint64_t value = 0;
    int digits = 0;
    while (*p >= '0' && *p <= '9') {
        if (digits >= 10) return false;
        value = value * 10u + static_cast<uint64_t>(*p - '0');
        ++p;
        ++digits;
        if (value > 0xffffffffull) return false;
    }
    if (digits == 0 || value == 0) return false;
    *number = static_cast<uint32_t>(value);
    return true;
}

// "Uid" argument: an IMAP UID set such as 1,3:5,9 or 5:*. Null when a bound is empty, zero, or above 0xFFFFFFFF,
// or when '*' cannot name a UID that is in use. The star is sent as '*', not expanded and not rewritten.
static struct mailimap_search_key * uidSetKey(const char * text) {
    if (text == nullptr || *text == 0) return nullptr;
    struct mailimap_set * set = mailimap_set_new_empty();
    if (set == nullptr) return nullptr;
    const char * p = text;
    while (*p != 0) {
        bool firstStar = false;
        bool lastStar = false;
        uint32_t first = 0;
        uint32_t last = 0;
        if (!readUidBound(p, &first, &firstStar)) { mailimap_set_free(set); return nullptr; }
        if (firstStar) {
            if (!selectedHasUid()) { mailimap_set_free(set); return nullptr; }
            first = 0;
        }
        last = first;
        lastStar = firstStar;
        if (*p == ':') {
            ++p;
            if (!readUidBound(p, &last, &lastStar)) { mailimap_set_free(set); return nullptr; }
            if (lastStar) {
                if (!selectedHasUid()) { mailimap_set_free(set); return nullptr; }
                last = 0;
            }
        }
        if (mailimap_set_add_interval(set, first, last) != MAILIMAP_NO_ERROR) {
            mailimap_set_free(set);
            return nullptr;
        }
        if (*p == ',') { ++p; if (*p == 0) { mailimap_set_free(set); return nullptr; } }
        else if (*p != 0) { mailimap_set_free(set); return nullptr; }
    }
    struct mailimap_search_key * key = mailimap_search_key_new_uid(set);
    if (key == nullptr) mailimap_set_free(set);
    return key;
}

// "Group" argument: a nested query (IndexModel.groupArgument): "And"/"Or", then steps "Yes|Not US kind US argument",
// separated by RS. Sibling groups are allowed. A Group step whose enclosing depth is greater than 0 is refused.
// Throws "bad search" when malformed.
static struct mailimap_search_key * groupKeySep(JNIEnv * env, const char * text, int depth, char lineSep, char fieldSep) {
    if (text == nullptr || depth > 8) { throwFailure(env, "bad search"); return nullptr; }
    std::vector<std::string> lines;
    std::string cur;
    for (const char * p = text; ; ++p) {
        if (*p == lineSep || *p == 0) { lines.push_back(cur); cur.clear(); if (*p == 0) break; }
        else cur.push_back(*p);
    }
    if (lines.size() < 2 || (lines[0] != "And" && lines[0] != "Or")) { throwFailure(env, "bad search"); return nullptr; }
    const bool isAnd = lines[0] == "And";
    struct mailimap_search_key * folded = nullptr;
    for (size_t i = 1; i < lines.size(); ++i) {
        size_t a = lines[i].find(fieldSep);
        size_t b = a == std::string::npos ? a : lines[i].find(fieldSep, a + 1);
        if (b == std::string::npos) {
            if (folded != nullptr) mailimap_search_key_free(folded);
            throwFailure(env, "bad search");
            return nullptr;
        }
        std::string mark = lines[i].substr(0, a), kind = lines[i].substr(a + 1, b - a - 1), arg = lines[i].substr(b + 1);
        if (kind == "Group" && depth > 0) {
            if (folded != nullptr) mailimap_search_key_free(folded);
            throwFailure(env, "bad search");
            return nullptr;
        }
        struct mailimap_search_key * step = kind == "Group" ? groupKeySep(env, arg.c_str(), depth + 1, 0x1e, 0x1f)
                                                            : criterionKey(env, kind.c_str(), arg.c_str());
        if (env->ExceptionCheck() || step == nullptr) {
            if (step != nullptr) mailimap_search_key_free(step);
            if (folded != nullptr) mailimap_search_key_free(folded);
            if (!env->ExceptionCheck()) throwFailure(env, "bad search");
            return nullptr;
        }
        if (mark == "Not") step = notKey(step);
        folded = folded == nullptr ? step : (isAnd ? andKey(folded, step) : keyOr(folded, step));
        if (folded == nullptr) { throwFailure(env, "search failed"); return nullptr; }
    }
    return folded;
}

static struct mailimap_search_key * groupKey(JNIEnv * env, const char * text, int depth) {
    return groupKeySep(env, text, depth, 0x1e, 0x1f);
}

// F118: a search argument goes on the wire inside a quoted string or literal; CR, LF or NUL there would end the
// command or smuggle a new one. JNI hands NUL over as modified UTF-8 (C0 80), so that is refused too.
bool lineSafeArgument(const char * text) {
    if (text == nullptr) return true;
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(text); *p != 0; ++p) {
        if (*p == '\r' || *p == '\n') return false;
        if (*p == 0xC0 && p[1] == 0x80) return false;
    }
    return true;
}

struct mailimap_search_key * criterionKey(JNIEnv * env, const char * kind, const char * argument) {
    // Group / Advanced carry their own RS/US or LF/TAB structure; their leaves come back through here.
    if (!sameKind(kind, "Group") && !sameKind(kind, "Advanced") && !lineSafeArgument(argument)) {
        throwFailure(env, "bad search");
        return nullptr;
    }
    if (sameKind(kind, "Uid")) {
        struct mailimap_search_key * key = uidSetKey(argument);
        if (key == nullptr) throwFailure(env, "bad search");
        return key;
    }
    // A Group argument is parsed at depth 1, so a Group step inside it is the depth check above.
    if (sameKind(kind, "Group")) return groupKey(env, argument, 1);
    if (sameKind(kind, "All")) return mailimap_search_key_new_all();
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
    jboolean useEsearch, jboolean withCharset, jboolean fuzzy) {
    if (useEsearch == JNI_TRUE) {
        struct mailimap_response * response = nullptr;
        int r = sendEsearch(session->imap, true, "ALL", withCharset == JNI_TRUE, fuzzy == JNI_TRUE, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<uint32_t> result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = uidArray(env, result);
        unlockSession(session);
        return arr;
    }
    if (fuzzy == JNI_TRUE) {
        struct mailimap_response * response = nullptr;
        int r = sendUidSearch(session->imap, withCharset == JNI_TRUE, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        clist * result = nullptr;
        if (session->imap != nullptr && session->imap->imap_response_info != nullptr) {
            result = session->imap->imap_response_info->rsp_search_result;
            session->imap->imap_response_info->rsp_search_result = nullptr;
        }
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
        int r = sendEsearch(session->imap, byUid, ret, false, false, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<uint32_t> result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = uidArray(env, result);
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
        if (strcasecmp(flag, "Noselect") == 0 || strcasecmp(flag, "NonExistent") == 0) return true;
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

jlong completeCount(JNIEnv * env, LiveSession * session, struct mailimap_search_key * key, jboolean withCharset,
    jboolean fuzzy) {
    session->esearchCount = -1;
    tlsEsearchExtCount = -1;
    tlsLive = session;
    struct mailimap_response * response = nullptr;
    int r = sendEsearch(session->imap, true, "COUNT", withCharset == JNI_TRUE, fuzzy == JNI_TRUE, key, &response);
    tlsLive = nullptr;
    mailimap_search_key_free(key);
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return -1;
    }
    std::vector<uint32_t> result = takeEsearch(session->imap);
    (void)result;
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return -1;
    }
    jlong count = session->esearchCount;
    if (count < 0) count = tlsEsearchExtCount;
    tlsEsearchExtCount = -1;
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
    // F18: when OpenSSL could not build the whole chain, the built chain can lack intermediates the server
    // did send. Append those so the Java trust manager sees everything the peer offered.
    stack_st * sent = X509_STORE_CTX_get0_untrusted(store);
    int extra = sent != nullptr ? OPENSSL_sk_num(sent) : 0;
    for (int i = 0; i < extra; ++i) {
        std::vector<unsigned char> der;
        if (!encodeDer(static_cast<x509_st *>(OPENSSL_sk_value(sent, i)), &der)) continue;
        bool have = false;
        for (const auto & d : tlsCapture->ders) if (d == der) { have = true; break; }
        if (!have) tlsCapture->ders.push_back(std::move(der));
    }
    return 1;
}

// RFC 6066 3: SNI carries host names only, never IP literals. GnuTLS servers (Courier) abort the handshake on an IP.
const char * sniName(const char * host) {
    if (host == nullptr || *host == 0) return nullptr;
    unsigned char buf[16];
    if (inet_pton(AF_INET, host, buf) == 1 || inet_pton(AF_INET6, host, buf) == 1) return nullptr;
    return host;
}

// Last TLS alert or handshake state on this handshake's capture, returned by the call that failed.
std::atomic<bool> gLabMaxTls12{false};

// The reason a TLS handshake failed: the OpenSSL error (e.g. "dh key too small"), else the alert, else the last state.
std::string tlsFailureReason() {
    std::string reason;
    for (unsigned long e = ERR_get_error(); e != 0; e = ERR_get_error()) {
        char buf[256];
        ERR_error_string_n(e, buf, sizeof buf);
        reason = buf;  // keep the last (most specific) entry
    }
    if (reason.empty() && tlsCapture != nullptr) reason = tlsCapture->alert;
    if (reason.empty() && tlsCapture != nullptr) reason = tlsCapture->state;
    return reason;
}

void recordTlsInfo(const ssl_st * s, int where, int ret) {
    if (tlsCapture == nullptr) return;
    constexpr int kAlert = 0x4000, kRead = 0x04, kExit = 0x02;
    if (where & kAlert) {
        tlsCapture->alert = std::string((where & kRead) ? "server alert: " : "client alert: ") + SSL_alert_desc_string_long(ret);
    } else if ((where & kExit) && ret <= 0) {
        // Kept as the last handshake state; used only when the handshake really failed and nothing better is known.
        tlsCapture->state = std::string("stopped in ") + SSL_state_string_long(s);
    }
}

extern "C" void prepareTls(struct mailstream_ssl_context * ssl_context, void *) {
    gClientCertLoaded = false;
    gClientCertError.clear();
    if (ssl_context == nullptr) return;
    if (tlsCapture != nullptr) {
        tlsCapture->alert.clear();
        tlsCapture->state.clear();
    }
    auto * ctx = static_cast<ssl_ctx_st *>(mailstream_ssl_get_openssl_ssl_ctx(ssl_context));
    if (ctx == nullptr) return;
    if (tlsCapture != nullptr && tlsCapture->allowOldTls) {
        // Allow old TLS and weak keys for this connection only: TLS 1.0+, security level 0 (small DH, legacy ciphers).
        SSL_CTX_ctrl(ctx, kSslCtrlSetMinProtoVersion, kTls10Version, nullptr);
        SSL_CTX_set_security_level(ctx, 0);
        SSL_CTX_set_cipher_list(ctx, "DEFAULT:@SECLEVEL=0");
    } else {
        SSL_CTX_ctrl(ctx, kSslCtrlSetMinProtoVersion, kTls12Version, nullptr);
    }
    // Lab-test hook (debug builds only, set from LabTestHooks): cap at TLS 1.2 to test tls-unique servers.
    if (gLabMaxTls12.load()) SSL_CTX_ctrl(ctx, 124 /* SSL_CTRL_SET_MAX_PROTO_VERSION */, kTls12Version, nullptr);
    SSL_CTX_set_verify(ctx, kSslVerifyPeer, recordPeerCerts);
    SSL_CTX_set_info_callback(ctx, recordTlsInfo);
    if (tlsCapture != nullptr && tlsCapture->keyChainKey != nullptr && tlsCapture->keyChainChain != nullptr) {
        gClientCertLoaded = useKeyChainKey(ctx, *tlsCapture->keyChainChain, tlsCapture->keyChainKey, &gClientCertError);
    }
}

std::optional<std::string> utf8FromJava(JNIEnv * env, jstring value) {
    if (env->ExceptionCheck()) return std::nullopt;
    if (value == nullptr) return std::string();
    jsize length = env->GetStringLength(value);
    if (env->ExceptionCheck()) return std::nullopt;
    if (length <= 0) return std::string();
    std::vector<jchar> units(static_cast<size_t>(length));
    auto wipeOwned = [&]() {
        volatile jchar * wipe = units.data();
        for (jsize i = 0; i < length; ++i) wipe[static_cast<size_t>(i)] = 0;
    };
    // Copy into this function's buffer. Never write the Java string (GetStringChars / GetStringCritical).
    env->GetStringRegion(value, 0, length, units.data());
    if (env->ExceptionCheck()) {
        wipeOwned();
        return std::nullopt;
    }
    std::string out;
    // F97: worst case up front (4 bytes per UTF-16 unit), so a password never reallocates and leaves an unwiped copy.
    out.reserve(static_cast<size_t>(length) * 4u + 1u);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = units[static_cast<size_t>(i)];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < length) {
                uint32_t low = units[static_cast<size_t>(i + 1)];
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    cp = 0x10000u + (((cp - 0xD800u) << 10) | (low - 0xDC00u));
                    ++i;
                } else {
                    cp = 0xFFFDu;
                }
            } else {
                cp = 0xFFFDu;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFDu;
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
    wipeOwned();
    return out;
}

struct MimeStream;
struct MimeXfer;
struct MimeHeaders {
    char * name;
    char * value;
    char * raw;
};

extern thread_local JNIEnv * mimeEnv;

extern "C" {
void mime_decode_install(void);
int mime_decode_text(const char * bytes, size_t len, const char * declared, const char * fallback,
    const char * encoding, char ** text, char ** charset_used, int * status, int * unknown_charset, int * unknown);
struct MimeStream * mime_stream_open(const char * declared, const char * fallback, const char * encoding);
char * mime_stream_take(struct MimeStream * stream, const char * bytes, size_t len, int final,
    char ** charset_used, int * status, int * unknown_charset, int * unknown);
void mime_stream_close(struct MimeStream * stream);
struct MimeXfer * mime_xfer_open(const char * encoding);
char * mime_xfer_take(struct MimeXfer * xfer, const char * bytes, size_t len, int final, size_t * out_len,
    int * status);
void mime_xfer_close(struct MimeXfer * xfer);
int mime_decode_phrase(const char * text, size_t len, const char * fromcode,
    char ** out_text, char ** out_note, int * status);
char * mime_decode_2231(const char * raw, size_t len, const char * fallback);
int mime_parse_headers(const char * bytes, size_t len, struct MimeHeaders ** out, int * count, const char * fallback);
void mime_headers_free(struct MimeHeaders * rows, int count);
}

static std::string bytesOf(JNIEnv * env, jbyteArray array) {
    if (array == nullptr) return std::string();
    jsize n = env->GetArrayLength(array);
    if (n <= 0) return std::string();
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

static jobjectArray stringTriple(JNIEnv * env, const char * a, const char * b, const char * c) {
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray arr = cls != nullptr ? env->NewObjectArray(3, cls, nullptr) : nullptr;
    if (cls != nullptr) env->DeleteLocalRef(cls);
    if (arr == nullptr) return nullptr;
    jstring s0 = newString(env, a != nullptr ? a : "");
    jstring s1 = newString(env, b != nullptr ? b : "");
    jstring s2 = newString(env, c != nullptr ? c : "");
    env->SetObjectArrayElement(arr, 0, s0);
    env->SetObjectArrayElement(arr, 1, s1);
    env->SetObjectArrayElement(arr, 2, s2);
    if (s0 != nullptr) env->DeleteLocalRef(s0);
    if (s1 != nullptr) env->DeleteLocalRef(s1);
    if (s2 != nullptr) env->DeleteLocalRef(s2);
    return arr;
}

static jobject newMimeText(JNIEnv * env, int status, const char * text, const char * charset, int unknownCharset,
    int unknown) {
    jstring jText = newString(env, text != nullptr ? text : "");
    jstring jCharset = newString(env, charset != nullptr ? charset : "");
    jobject obj = env->NewObject(gJni.mimeTextResult, gJni.mimeTextResultInit,
        status == 0 ? JNI_TRUE : JNI_FALSE, jText, jCharset,
        unknownCharset != 0 ? JNI_TRUE : JNI_FALSE, unknown != 0 ? JNI_TRUE : JNI_FALSE);
    if (jText != nullptr) env->DeleteLocalRef(jText);
    if (jCharset != nullptr) env->DeleteLocalRef(jCharset);
    return obj;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_text(JNIEnv * env, jclass, jbyteArray bytes, jstring declared,
    jstring fallback, jstring encoding) {
    MimeEnvScope scope(env);
    std::string in = bytesOf(env, bytes);
    auto declaredUtf = utf8FromJava(env, declared);
    auto fallbackUtf = utf8FromJava(env, fallback);
    auto encodingUtf = utf8FromJava(env, encoding);
    char * text = nullptr;
    char * charset = nullptr;
    int status = 1;
    int unknownCharset = 0;
    int unknown = 0;
    mime_decode_text(in.data(), in.size(),
        declaredUtf ? declaredUtf->c_str() : "",
        fallbackUtf ? fallbackUtf->c_str() : "",
        encodingUtf ? encodingUtf->c_str() : "",
        &text, &charset, &status, &unknownCharset, &unknown);
    jobject obj = newMimeText(env, status, text, charset, unknownCharset, unknown);
    std::free(text);
    std::free(charset);
    return obj;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_streamOpen(JNIEnv * env, jclass, jstring declared, jstring fallback,
    jstring encoding) {
    MimeEnvScope scope(env);
    auto declaredUtf = utf8FromJava(env, declared);
    auto fallbackUtf = utf8FromJava(env, fallback);
    auto encodingUtf = utf8FromJava(env, encoding);
    MimeStream * stream = mime_stream_open(
        declaredUtf ? declaredUtf->c_str() : "",
        fallbackUtf ? fallbackUtf->c_str() : "ISO-8859-1",
        encodingUtf ? encodingUtf->c_str() : "");
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(stream));
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_streamTake(JNIEnv * env, jclass, jlong handle, jbyteArray chunk,
    jboolean finalChunk) {
    MimeEnvScope scope(env);
    std::string in = bytesOf(env, chunk);
    char * charset = nullptr;
    int status = 1;
    int unknownCharset = 0;
    int unknown = 0;
    char * text = mime_stream_take(reinterpret_cast<MimeStream *>(static_cast<uintptr_t>(handle)),
        in.data(), in.size(), finalChunk == JNI_TRUE ? 1 : 0, &charset, &status, &unknownCharset, &unknown);
    jobject obj = newMimeText(env, status, text, charset, unknownCharset, unknown);
    std::free(text);
    std::free(charset);
    return obj;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_streamClose(JNIEnv *, jclass, jlong handle) {
    mime_stream_close(reinterpret_cast<MimeStream *>(static_cast<uintptr_t>(handle)));
}

static void throwMimeDecode(JNIEnv * env, const char * text) {
    jclass ex = env->FindClass("org/dlang/liveimap/ui/compose/MimeDecodeException");
    if (ex != nullptr) {
        env->ThrowNew(ex, text);
        env->DeleteLocalRef(ex);
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_multipartParts(JNIEnv * env, jclass, jbyteArray bytes) {
    std::string in = bytesOf(env, bytes);
    size_t indx = 0;
    struct mailmime * mime = nullptr;
    int parsed = mailmime_parse(in.empty() ? "" : in.data(), in.size(), &indx, &mime);
    if (parsed != MAILIMF_NO_ERROR || mime == nullptr || mime->mm_type != MAILMIME_MULTIPLE ||
        mime->mm_data.mm_multipart.mm_mp_list == nullptr) {
        if (mime != nullptr) mailmime_free(mime);
        throwMimeDecode(env, "multipart has no boundary");
        return nullptr;
    }
    clist * list = mime->mm_data.mm_multipart.mm_mp_list;
    int count = 0;
    for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) count++;
    jclass bytesClass = env->FindClass("[B");
    jobjectArray out = bytesClass != nullptr ? env->NewObjectArray(count, bytesClass, nullptr) : nullptr;
    if (bytesClass != nullptr) env->DeleteLocalRef(bytesClass);
    if (out == nullptr) {
        mailmime_free(mime);
        return nullptr;
    }
    int index = 0;
    const char * base = in.data();
    const char * end = base + in.size();
    for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur), ++index) {
        auto * part = static_cast<struct mailmime *>(clist_content(cur));
        const char * start = part != nullptr ? part->mm_mime_start : nullptr;
        size_t len = part != nullptr ? part->mm_length : 0;
        if (start == nullptr || start < base || start > end || len > static_cast<size_t>(end - start)) {
            mailmime_free(mime);
            throwMimeDecode(env, "could not parse this message");
            return nullptr;
        }
        jbyteArray row = env->NewByteArray(static_cast<jsize>(len));
        if (row == nullptr) {
            mailmime_free(mime);
            return nullptr;
        }
        if (len > 0) env->SetByteArrayRegion(row, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(start));
        env->SetObjectArrayElement(out, index, row);
        env->DeleteLocalRef(row);
    }
    mailmime_free(mime);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_xferOpen(JNIEnv * env, jclass, jstring encoding) {
    auto encodingUtf = utf8FromJava(env, encoding);
    MimeXfer * xfer = mime_xfer_open(encodingUtf ? encodingUtf->c_str() : "");
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(xfer));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_xferTake(JNIEnv * env, jclass, jlong handle, jbyteArray chunk,
    jboolean finalChunk) {
    std::string in = bytesOf(env, chunk);
    size_t n = 0;
    int status = 1;
    char * out = mime_xfer_take(reinterpret_cast<MimeXfer *>(static_cast<uintptr_t>(handle)),
        in.data(), in.size(), finalChunk == JNI_TRUE ? 1 : 0, &n, &status);
    if (status != 0 || out == nullptr) {
        std::free(out);
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (arr != nullptr && n > 0) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(out));
    }
    std::free(out);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_xferClose(JNIEnv *, jclass, jlong handle) {
    mime_xfer_close(reinterpret_cast<MimeXfer *>(static_cast<uintptr_t>(handle)));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_phrases(JNIEnv * env, jclass, jobjectArray values, jstring fallback) {
    MimeEnvScope scope(env);
    if (values == nullptr) return nullptr;
    auto fallbackUtf = utf8FromJava(env, fallback);
    const char * from = fallbackUtf && !fallbackUtf->empty() ? fallbackUtf->c_str() : "ISO-8859-1";
    const jsize n = env->GetArrayLength(values);
    jobjectArray out = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    if (out == nullptr) return nullptr;
    for (jsize i = 0; i < n; ++i) {
        if (env->PushLocalFrame(16) < 0) {
            env->DeleteLocalRef(out);
            return nullptr;
        }
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        auto utf = utf8FromJava(env, value);
        const std::string empty;
        const std::string & text = utf ? *utf : empty;
        char * decoded = nullptr;
        char * note = nullptr;
        int status = 1;
        mime_decode_phrase(text.data(), text.size(), from, &decoded, &note, &status);
        const char * shown = status == 0 && decoded != nullptr ? decoded : text.c_str();
        jstring row = newString(env, shown != nullptr ? shown : "");
        std::free(decoded);
        std::free(note);
        if (row != nullptr) env->SetObjectArrayElement(out, i, row);
        env->PopLocalFrame(nullptr);
    }
    return out;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_phrase(JNIEnv * env, jclass, jstring value, jstring fallback) {
    MimeEnvScope scope(env);
    auto utf = utf8FromJava(env, value);
    auto fallbackUtf = utf8FromJava(env, fallback);
    const std::string empty;
    const std::string & text = utf ? *utf : empty;
    const char * from = fallbackUtf && !fallbackUtf->empty() ? fallbackUtf->c_str() : "ISO-8859-1";
    char * decoded = nullptr;
    char * note = nullptr;
    int status = 1;
    mime_decode_phrase(text.data(), text.size(), from, &decoded, &note, &status);
    jobjectArray arr = stringTriple(env, status == 0 ? "ok" : "error", decoded != nullptr ? decoded : "",
        note != nullptr ? note : "");
    std::free(decoded);
    std::free(note);
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_parameter2231(JNIEnv * env, jclass, jstring raw, jstring fallback) {
    MimeEnvScope scope(env);
    auto rawUtf = utf8FromJava(env, raw);
    auto fallbackUtf = utf8FromJava(env, fallback);
    const std::string empty;
    const std::string & text = rawUtf ? *rawUtf : empty;
    char * decoded = mime_decode_2231(text.data(), text.size(), fallbackUtf ? fallbackUtf->c_str() : "ISO-8859-1");
    jstring out = newString(env, decoded != nullptr ? decoded : "");
    std::free(decoded);
    return out;
}

static jstring bytesAsLatin1(JNIEnv * env, const char * text) {
    if (text == nullptr) return nullptr;
    std::string utf8;
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(text); *p != 0; ++p) {
        unsigned char b = *p;
        if (b < 0x80) {
            utf8.push_back(static_cast<char>(b));
        } else {
            utf8.push_back(static_cast<char>(0xC0 | (b >> 6)));
            utf8.push_back(static_cast<char>(0x80 | (b & 0x3F)));
        }
    }
    return newString(env, utf8.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_ui_reader_MimeDecode_headers(JNIEnv * env, jclass, jbyteArray bytes, jstring fallback) {
    MimeEnvScope scope(env);
    std::string in = bytesOf(env, bytes);
    auto fallbackUtf = utf8FromJava(env, fallback);
    const char * from = fallbackUtf && !fallbackUtf->empty() ? fallbackUtf->c_str() : "ISO-8859-1";
    MimeHeaders * rows = nullptr;
    int count = 0;
    if (mime_parse_headers(in.data(), in.size(), &rows, &count, from) != 0 || count < 0) {
        mime_headers_free(rows, count);
        jclass ex = env->FindClass("org/dlang/liveimap/ui/compose/MimeDecodeException");
        if (ex != nullptr) {
            env->ThrowNew(ex, "could not parse these headers");
            env->DeleteLocalRef(ex);
        }
        return nullptr;
    }
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray arr = cls != nullptr ? env->NewObjectArray(count * 3, cls, nullptr) : nullptr;
    if (cls != nullptr) env->DeleteLocalRef(cls);
    if (arr == nullptr) {
        mime_headers_free(rows, count);
        return nullptr;
    }
    for (int i = 0; i < count; ++i) {
        jstring name = bytesAsLatin1(env, rows[i].name != nullptr ? rows[i].name : "");
        jstring value = newString(env, rows[i].value != nullptr ? rows[i].value : "");
        jstring raw = bytesAsLatin1(env, rows[i].raw != nullptr ? rows[i].raw : "");
        env->SetObjectArrayElement(arr, i * 3, name);
        env->SetObjectArrayElement(arr, i * 3 + 1, value);
        env->SetObjectArrayElement(arr, i * 3 + 2, raw);
        if (name != nullptr) env->DeleteLocalRef(name);
        if (value != nullptr) env->DeleteLocalRef(value);
        if (raw != nullptr) env->DeleteLocalRef(raw);
    }
    mime_headers_free(rows, count);
    return arr;
}

void dropLocal(JNIEnv * env, jobject ref) {
    if (ref != nullptr) env->DeleteLocalRef(ref);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    gVm = vm;
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    if (!resolveJni(env)) return JNI_ERR;
    mime_decode_install();
    setupSaslClient();
    gKeepUnselect = mailimap_unselect;
    registerExtensions();
    return JNI_VERSION_1_6;
}

jobject newSessionOpen(JNIEnv * env, jlong handle, const std::string & tls, const std::string & error,
    bool authRejected, const std::vector<std::string> & cert);

// Stores the account on a session and does not connect. nativeWatch is the one login.
extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStage(JNIEnv * env, jobject,
    jstring host, jint port, jstring user, jstring password, jstring smtpHost, jint smtpPort, jstring from,
    jboolean pipelineFlag, jstring tlsMode, jstring smtpTlsMode, jstring certPin,
    jboolean allowPlaintext, jboolean acceptInvalidCert, jboolean allowOldTls, jstring authMechanism,
    jobjectArray keyChainChain, jobject keyChainKey) {
    registerExtensions();
    JChars h(env, host);
    JChars u(env, user);
    JChars p(env, password);
    JChars sh(env, smtpHost);
    JChars fr(env, from);
    JChars mode(env, tlsMode);
    JChars smtpMode(env, smtpTlsMode);
    JChars pin(env, certPin);
    if (!h.s || !u.s || !p.s || !sh.s || !fr.s || !mode.s || !smtpMode.s || !pin.s) {
        return newSessionOpen(env, 0, "", "", false, {});
    }
    auto held = std::shared_ptr<LiveSession>(new LiveSession());
    held->selfHold = held;
    LiveSession * session = held.get();
    session->stagedWatch = true;
    session->host = h.c();
    session->imapPort = port;
    session->user = u.c();
    session->password = p.c();
    session->passwordMask = randomMask(session->password.size());
    xorMask(session->password, session->passwordMask);
    session->smtpHost = sh.c();
    session->smtpPort = smtpPort;
    session->from = fr.c()[0] != 0 ? fr.c() : u.c();
    session->pipelineCommands = pipelineFlag == JNI_TRUE;
    session->tlsMode = mode.c();
    session->smtpTlsMode = smtpMode.c();
    session->certPin = pin.c();
    session->allowPlaintextAuth = allowPlaintext == JNI_TRUE;
    session->acceptInvalidCert = acceptInvalidCert == JNI_TRUE;
    session->allowOldTls = allowOldTls == JNI_TRUE;
    {
        JChars mech(env, authMechanism);
        if (!mech.s) {
            held->selfHold.reset();
            return newSessionOpen(env, 0, "", "", false, {});
        }
        session->authMechanism = mech.c();
        for (auto & ch : session->authMechanism) ch = static_cast<char>(toupper(static_cast<unsigned char>(ch)));
    }
    if (keyChainKey != nullptr && keyChainChain != nullptr) {
        keyChainInit(env);
        jsize n = env->GetArrayLength(keyChainChain);
        for (jsize i = 0; i < n; ++i) {
            auto der = static_cast<jbyteArray>(env->GetObjectArrayElement(keyChainChain, i));
            if (der == nullptr) continue;
            std::string bytes(static_cast<size_t>(env->GetArrayLength(der)), '\0');
            if (!bytes.empty()) env->GetByteArrayRegion(der, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte *>(&bytes[0]));
            session->keyChainChain.push_back(std::move(bytes));
            env->DeleteLocalRef(der);
        }
        session->keyChainKey = env->NewGlobalRef(keyChainKey);
    }
    startOwner(held);
    publishSession(session);
    return newSessionOpen(env, reinterpret_cast<jlong>(session), "", "", false, {});
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeOpen(JNIEnv * env, jobject,
    jstring host, jint port, jstring user, jstring password, jstring smtpHost, jint smtpPort, jstring from,
    jboolean pipelineFlag, jstring tlsMode, jstring smtpTlsMode, jstring certPin,
    jboolean allowPlaintext, jboolean acceptInvalidCert, jboolean allowOldTls, jstring authMechanism,
    jobjectArray keyChainChain, jobject keyChainKey) {
    registerExtensions();
    JChars h(env, host);
    JChars u(env, user);
    JChars p(env, password);
    JChars sh(env, smtpHost);
    JChars fr(env, from);
    JChars mode(env, tlsMode);
    JChars smtpMode(env, smtpTlsMode);
    JChars pin(env, certPin);
    if (!h.s || !u.s || !p.s || !sh.s || !fr.s || !mode.s || !smtpMode.s || !pin.s) {
        return newSessionOpen(env, 0, "", "", false, {});
    }
    auto held = std::shared_ptr<LiveSession>(new LiveSession());
    held->selfHold = held;
    LiveSession * session = held.get();
    session->host = h.c();
    session->imapPort = port;
    session->user = u.c();
    session->password = p.c();
    session->passwordMask = randomMask(session->password.size());
    xorMask(session->password, session->passwordMask);
    session->smtpHost = sh.c();
    session->smtpPort = smtpPort;
    session->from = fr.c()[0] != 0 ? fr.c() : u.c();
    session->pipelineCommands = pipelineFlag == JNI_TRUE;
    session->tlsMode = mode.c();
    session->smtpTlsMode = smtpMode.c();
    session->certPin = pin.c();
    session->allowPlaintextAuth = allowPlaintext == JNI_TRUE;
    session->acceptInvalidCert = acceptInvalidCert == JNI_TRUE;
    session->allowOldTls = allowOldTls == JNI_TRUE;
    {
        JChars mech(env, authMechanism);
        if (!mech.s) {
            held->selfHold.reset();
            return newSessionOpen(env, 0, "", "", false, {});
        }
        session->authMechanism = mech.c();
        for (auto & ch : session->authMechanism) ch = static_cast<char>(toupper(static_cast<unsigned char>(ch)));
    }
    if (keyChainKey != nullptr && keyChainChain != nullptr) {
        keyChainInit(env);
        jsize n = env->GetArrayLength(keyChainChain);
        for (jsize i = 0; i < n; ++i) {
            auto der = static_cast<jbyteArray>(env->GetObjectArrayElement(keyChainChain, i));
            if (der == nullptr) continue;
            std::string bytes(static_cast<size_t>(env->GetArrayLength(der)), '\0');
            if (!bytes.empty()) env->GetByteArrayRegion(der, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte *>(&bytes[0]));
            session->keyChainChain.push_back(std::move(bytes));
            env->DeleteLocalRef(der);
        }
        session->keyChainKey = env->NewGlobalRef(keyChainKey);
    }
    startOwner(held);
    publishSession(session);
    return newSessionOpen(env, reinterpret_cast<jlong>(session), "", "", false, {});
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeConnect(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        LiveSession * session = lockSession(env, handle);
        if (session == nullptr) return newSessionOpen(env, 0, "", "not connected", false, {});
        std::string password = session->password;
        xorMask(password, session->passwordMask);
        SecretWipe wipePassword{password};
        bindTraffic(&session->imapTraffic, session->trafficDir, "imap", session->trafficRollBytes);
        std::string error;
        std::string address;
        std::string tls;
        bool authRejected = false;
        std::vector<std::string> certOffer;
        mailimap * imap = nullptr;
        if (session->tlsMode == "None") {
            imap = openPlain(env, session->host.c_str(), session->imapPort, session->user.c_str(), password.c_str(),
                session, &error, &address, nullptr, &authRejected, &tls);
        } else {
            imap = openTls(env, session->tlsMode.c_str(), session->host.c_str(), session->imapPort,
                session->user.c_str(), password.c_str(), session, &error, &address, session->certPin.c_str(), true,
                nullptr, &authRejected, &tls, &certOffer);
        }
        if (imap == nullptr) {
            unlockSession(session);
            return newSessionOpen(env, 0, tls, error, authRejected, certOffer);
        }
        struct mailimap_capability_data * caps = nullptr;
        int r = mailimap_capability(imap, &caps);
        if (!cmdOk(r) || caps == nullptr) {
            std::string why = serviceLabel("IMAP", session->host.c_str(), session->imapPort) + " (" + address
                + "): " + protocolError("capability", r, imapErrorName(r), imap->imap_response);
            mailimap_free(imap);
            unlockSession(session);
            return newSessionOpen(env, 0, tls, why, false, certOffer);
        }
        session->imap = imap;
        session->capabilityLine = joinCapabilities(caps);
        mailimap_capability_data_free(caps);
        unlockSession(session);
        return newSessionOpen(env, handle, tls, "", false, {});
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCapabilityLine(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jstring {


    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    jstring line = newString(env, session->capabilityLine.c_str());
    unlockSession(session);
    return line;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeEnable(JNIEnv * env, jobject, jlong handle, jstring capability) {
    jstring g_capability = capability != nullptr ? static_cast<jstring>(env->NewGlobalRef(capability)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_capability};
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {
        jstring capability = g_capability;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return;
    JChars name(env, capability);
    enableNamed(session, name.c());
    unlockSession(session);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSetSessionFlags(JNIEnv * env, jobject, jlong handle,
    jboolean pipelineFlag) {
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {
    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    session->pipelineCommands = pipelineFlag == JNI_TRUE;
    unlockSession(session);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSetSessionLimits(JNIEnv * env, jobject, jlong handle,
    jint connectSec, jint readSec, jint idleRenewSec, jint watchWakeMs, jint maxResultUids, jstring trafficDir,
    jlong trafficRollBytes) {
    jstring g_dir = trafficDir != nullptr ? static_cast<jstring>(env->NewGlobalRef(trafficDir)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_dir};
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {
        jstring trafficDir = g_dir;
        LiveSession * session = lockSession(env, handle);
        if (session == nullptr) return;
        if (connectSec >= 1) session->connectTimeoutSec = connectSec;
        if (readSec >= 1) session->readTimeoutSec = readSec;
        session->idleRenewSec = idleRenewSec;
        if (watchWakeMs >= 100) session->watchWakeMs = watchWakeMs;
        if (maxResultUids >= 1) {
            session->maxResultUids.store(static_cast<uint64_t>(maxResultUids));
            tlsMaxResultUids = static_cast<uint64_t>(maxResultUids);
        }
        JChars dir(env, trafficDir);
        std::string next = dir.s ? dir.c() : "";
        int64_t roll = trafficRollBytes > 0 ? static_cast<int64_t>(trafficRollBytes) : 0;
        session->trafficDir = next;
        session->trafficRollBytes = roll;
        bindTraffic(&session->imapTraffic, next, "imap", roll);
        bindTraffic(&session->watchTraffic, next, "watch", roll);
        if (session->imap != nullptr) {
            if (!next.empty()) mailimap_set_logger(session->imap, fileTrafficLogger, &session->imapTraffic);
            if (session->imap->imap_stream != nullptr && (!next.empty() || session->watchProgress)) {
                mailstream_set_logger(session->imap->imap_stream, streamTrafficLogger, session);
            }
        }
        unlockSession(session);
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSessionDead(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jboolean {


    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return JNI_TRUE;
    jboolean dead = session->imap == nullptr ? JNI_TRUE : JNI_FALSE;
    unlockSession(session);
    return dead;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeNoop(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jint {


    LiveSession * session = lockImapSession(env, handle);
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
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCompress(JNIEnv * env, jobject, jlong handle) {
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {


    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return;
    compressSession(session);
    unlockSession(session);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeClose(JNIEnv * env, jobject, jlong handle) {
    auto * session = reinterpret_cast<LiveSession *>(handle);
    // Keep the shared_ptr until freeSession returns. The use count is dropped first so this close
    // does not wait on itself. A second close can still enter freeSession on live memory; unlink keeps one winner.
    std::shared_ptr<LiveSession> kept = retainSession(session);
    if (!kept) return;
    releaseUsers(session);
    freeSession(session, env);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeNamespaces(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobjectArray {


    LiveSession * session = lockImapSession(env, handle);
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
    struct NsRow { std::string prefix; jchar delim; jfieldID kind; };
    std::vector<NsRow> rows;
    for (Item item : items) {
        if (item.item == nullptr || item.item->ns_data_list == nullptr) continue;
        for (clistiter * cur = clist_begin(item.item->ns_data_list); cur != nullptr; cur = clist_next(cur)) {
            auto * info = static_cast<struct mailimap_namespace_info *>(clist_content(cur));
            if (info == nullptr) continue;
            const char * prefix = info->ns_prefix != nullptr ? info->ns_prefix : "";
            session->delims[prefix] = info->ns_delimiter;
            rows.push_back(NsRow{prefix, static_cast<jchar>(info->ns_delimiter), item.kind});
        }
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(rows.size()), gJni.ns, nullptr);
    if (arr == nullptr) {
        mailimap_namespace_data_free(data);
        unlockSession(session);
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(rows.size()); ++i) {
        jobject kind = env->GetStaticObjectField(gJni.nsKind, rows[static_cast<size_t>(i)].kind);
        jstring jp = newString(env, rows[static_cast<size_t>(i)].prefix.c_str());
        jobject ns = env->NewObject(gJni.ns, gJni.nsInit, jp, rows[static_cast<size_t>(i)].delim, kind);
        if (ns != nullptr) env->SetObjectArrayElement(arr, i, ns);
        if (jp != nullptr) env->DeleteLocalRef(jp);
        if (kind != nullptr) env->DeleteLocalRef(kind);
        if (ns != nullptr) env->DeleteLocalRef(ns);
        if (env->ExceptionCheck() || ns == nullptr) {
            env->DeleteLocalRef(arr);
            mailimap_namespace_data_free(data);
            unlockSession(session);
            return nullptr;
        }
    }
    mailimap_namespace_data_free(data);
    unlockSession(session);
    return arr;
    });
}

extern "C" JNIEXPORT jchar JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeHierarchyDelimiter(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jchar {


    LiveSession * session = lockImapSession(env, handle);
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
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStatusMessages(JNIEnv * env, jobject, jlong handle,
    jobjectArray mailboxes) {
    jobjectArray g_mailboxes = mailboxes != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(mailboxes)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jobjectArray p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_mailboxes};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jobjectArray mailboxes = g_mailboxes;

    LiveSession * session = lockImapSession(env, handle);
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
                if (!wireOk(chars.c())) { env->DeleteLocalRef(item); continue; }  // F42 native backstop
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
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeListLevel(JNIEnv * env, jobject, jlong handle,
    jstring prefix, jstring parent, jstring kind) {
    jstring g_prefix = prefix != nullptr ? static_cast<jstring>(env->NewGlobalRef(prefix)) : nullptr;
    jstring g_parent = parent != nullptr ? static_cast<jstring>(env->NewGlobalRef(parent)) : nullptr;
    jstring g_kind = kind != nullptr ? static_cast<jstring>(env->NewGlobalRef(kind)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; jstring p2; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); } } ownerRefs{env, g_prefix, g_parent, g_kind};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobjectArray {
        jstring prefix = g_prefix;
        jstring parent = g_parent;
        jstring kind = g_kind;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars pref(env, prefix);
    JChars par(env, parent);
    JChars kindChars(env, kind);
    if (!wireOk(pref.c()) || !wireOk(par.c())) {  // F42 native backstop
        throwFailure(env, "refused: CR or LF in a mailbox name");
        unlockSession(session);
        return nullptr;
    }
    bool extended = strcmp(kindChars.c(), "Plain") != 0;
    bool withMessages = strcmp(kindChars.c(), "ExtendedWithMessages") == 0
        || strcmp(kindChars.c(), "ExtendedWithStatus") == 0;
    bool withUnseen = strcmp(kindChars.c(), "ExtendedWithStatus") == 0;
    std::string reference;
    if (parent != nullptr && par.c()[0] != 0) {
        reference = par.c();
        char delim = learnDelim(session, par.c());
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
    struct Listed {
        std::string mailbox;
        std::string leaf;
        std::string use;
        bool children = false;
        char delim = 0;
        bool hasMessages = false;
        bool hasUnseen = false;
        int messages = 0;
        int unseen = 0;
    };
    std::vector<Listed> rows;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
            auto * mb = static_cast<struct mailimap_mailbox_list *>(clist_content(cur));
            if (mb == nullptr || mb->mb_name == nullptr) continue;
            noteDelim(session, mb->mb_name, mb->mb_delimiter);
            Listed row;
            row.mailbox = mb->mb_name;
            row.leaf = leafOf(mb->mb_name, mb->mb_delimiter, session->utf8);
            row.use = extended ? specialUseOf(mb) : "";
            row.children = hasChildren(mb);
            row.delim = mb->mb_delimiter;
            if (withMessages) {
                auto found = counts.find(mb->mb_name);
                if (found != counts.end()) {
                    row.hasMessages = found->second.hasMessages;
                    row.hasUnseen = found->second.hasUnseen;
                    row.messages = found->second.messages;
                    row.unseen = found->second.unseen;
                }
            }
            rows.push_back(std::move(row));
        }
        mailimap_list_result_free(list);
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(rows.size()), gJni.folderEntry, nullptr);
    if (arr == nullptr) {
        unlockSession(session);
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(rows.size()); ++i) {
        const Listed & row = rows[static_cast<size_t>(i)];
        jstring jmb = newString(env, row.mailbox.c_str());
        jstring jleaf = newString(env, row.leaf.c_str());
        jstring juse = row.use.empty() ? nullptr : newString(env, row.use.c_str());
        jobject jmessages = nullptr;
        jobject junseen = nullptr;
        if (row.hasMessages) {
            jmessages = env->CallStaticObjectMethod(gJni.integerCls, gJni.integerValueOf, row.messages);
        }
        if (row.hasUnseen) {
            junseen = env->CallStaticObjectMethod(gJni.integerCls, gJni.integerValueOf, row.unseen);
        }
        jobject entry = env->NewObject(gJni.folderEntry, gJni.folderInit, jmb, jleaf,
            static_cast<jboolean>(row.children), static_cast<jchar>(row.delim),
            juse, jmessages, junseen);
        if (entry != nullptr) env->SetObjectArrayElement(arr, i, entry);
        if (jmb != nullptr) env->DeleteLocalRef(jmb);
        if (jleaf != nullptr) env->DeleteLocalRef(jleaf);
        if (juse != nullptr) env->DeleteLocalRef(juse);
        if (jmessages != nullptr) env->DeleteLocalRef(jmessages);
        if (junseen != nullptr) env->DeleteLocalRef(junseen);
        if (entry != nullptr) env->DeleteLocalRef(entry);
        if (env->ExceptionCheck() || entry == nullptr) {
            env->DeleteLocalRef(arr);
            unlockSession(session);
            return nullptr;
        }
    }
    unlockSession(session);
    return arr;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeMailboxListed(JNIEnv * env, jobject, jlong handle,
    jstring mailbox) {
    jstring g_mailbox = mailbox != nullptr ? static_cast<jstring>(env->NewGlobalRef(mailbox)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_mailbox};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jboolean {
        jstring mailbox = g_mailbox;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return JNI_FALSE;
    JChars name(env, mailbox);
    if (!wireOk(name.c())) {  // F42 native backstop
        unlockSession(session);
        return JNI_FALSE;
    }
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
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSubscribedMailboxes(JNIEnv * env, jobject, jlong handle,
    jboolean extended) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobjectArray {


    LiveSession * session = lockImapSession(env, handle);
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
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSelect(JNIEnv * env, jobject, jlong handle, jstring mailbox,
    jboolean readWrite) {
    jstring g_mailbox = mailbox != nullptr ? static_cast<jstring>(env->NewGlobalRef(mailbox)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_mailbox};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jstring mailbox = g_mailbox;

    LiveSession * session = lockImapSession(env, handle);
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
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeUnselect(JNIEnv * env, jobject, jlong handle) {
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {


    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return;
    int r = mailimap_unselect(session->imap);
    if (!cmdOk(r)) {
        throwImap(env, session, r, "unselect failed");
    } else {
        session->selectedMailbox.clear();
        session->selectedReadWrite = false;
    }
    unlockSession(session);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCloseMailbox(JNIEnv * env, jobject, jlong handle) {
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {


    LiveSession * session = lockImapSession(env, handle);
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
    });
}


// One row at a time: the Java list gains the row, then that row's native strings are released
// before the next FETCH item is read. The C++ rows and the Java array are not both full.
bool emitIndexRow(JNIEnv * env, LiveSession * session, jobject out, Row & row,
    bool serverPreview, bool bodyPreview, int peekLimit, jboolean preferHtml, jboolean showDeleted,
    int * envelopeRows, const char * unlabeled, jstring accountId, const char * mailbox, jlong uidValidity) {
    // F113: only answers to our FETCH (they carry ENVELOPE).
    if (row.uid != 0 && row.hasEnvelope) {
        *envelopeRows += 1;
    } else {
        releaseRow(row);
        return true;
    }
    std::string preview;
    bool hasPreview = false;
    if (serverPreview && row.hasPreview && !row.preview.empty()) {
        preview = std::move(row.preview);
        hasPreview = true;
    } else if (bodyPreview && row.structure != nullptr) {
        PartWant want;
        findPreferred(row.structure, "", preferHtml == JNI_TRUE, &want);
        if (want.found) {
            std::vector<PreviewAsk> asks;
            asks.push_back(PreviewAsk{row.uid, want.section, want.charset, want.encoding, want.html});
            std::vector<std::string> got;
            std::vector<int> failed;
            int br = previewBurst(session->imap, asks, peekLimit, got, session->pipelineCommands, &failed, unlabeled);
            if (br != MAILIMAP_NO_ERROR) {
                preview.clear();
                preview.shrink_to_fit();
                releaseRow(row);
                throwImap(env, session, br, "fetch failed");
                return false;
            }
            if (!failed.empty() && failed[0] != 0) {
                preview.clear();
                hasPreview = false;
                row.previewFailed = true;
            } else if (!got.empty() && !got[0].empty()) {
                preview = std::move(got[0]);
                hasPreview = true;
            }
        }
    }
    bool keep = !(showDeleted == JNI_FALSE && row.deleted);
    jobject obj = keep ? rowObject(env, row, preview, hasPreview, accountId, mailbox, uidValidity) : nullptr;
    preview.clear();
    preview.shrink_to_fit();
    releaseRow(row);
    if (!keep) return !env->ExceptionCheck();
    if (obj == nullptr || env->ExceptionCheck()) {
        if (obj != nullptr) env->DeleteLocalRef(obj);
        return false;
    }
    env->CallBooleanMethod(out, gJni.listAdd, obj);
    env->DeleteLocalRef(obj);
    return !env->ExceptionCheck();
}

bool consumeIndexList(JNIEnv * env, LiveSession * session, jobject out, clist * list,
    const char * accountEmail, const char * altSpecs, bool serverPreview, bool bodyPreview,
    int peekLimit, jboolean preferHtml, jboolean showDeleted, int * envelopeRows, const char * unlabeled,
    jstring accountId, const char * mailbox, jlong uidValidity) {
    if (list == nullptr) return true;
    for (clistiter * cur = clist_begin(list); cur != nullptr; cur = clist_next(cur)) {
        Row row;
        readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row, accountEmail, altSpecs, unlabeled);
        if (!emitIndexRow(env, session, out, row, serverPreview, bodyPreview, peekLimit, preferHtml,
                showDeleted, envelopeRows, unlabeled, accountId, mailbox, uidValidity)) {
            return false;
        }
    }
    return true;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchIndex(JNIEnv * env, jobject, jlong handle,
    jstring mailbox, jint mode, jint firstSequence, jint lastSequence, jlongArray uids, jint limit,
    jint prefetch, jboolean includePreview, jint previewByteLimit, jboolean preferHtml, jboolean showDeleted,
    jboolean useServerPreview, jstring accountEmail, jstring altAddresses, jstring unlabeledCharset, jstring accountId) {
    jstring g_mailbox = mailbox != nullptr ? static_cast<jstring>(env->NewGlobalRef(mailbox)) : nullptr;
    jlongArray g_uids = uids != nullptr ? static_cast<jlongArray>(env->NewGlobalRef(uids)) : nullptr;
    jstring g_accountEmail = accountEmail != nullptr ? static_cast<jstring>(env->NewGlobalRef(accountEmail)) : nullptr;
    jstring g_altAddresses = altAddresses != nullptr ? static_cast<jstring>(env->NewGlobalRef(altAddresses)) : nullptr;
    jstring g_unlabeled = unlabeledCharset != nullptr ? static_cast<jstring>(env->NewGlobalRef(unlabeledCharset)) : nullptr;
    jstring g_account = accountId != nullptr ? static_cast<jstring>(env->NewGlobalRef(accountId)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jlongArray p1; jstring p2; jstring p3; jstring p4; jstring p5; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); if (p3) e->DeleteGlobalRef(p3); if (p4) e->DeleteGlobalRef(p4); if (p5) e->DeleteGlobalRef(p5); } } ownerRefs{env, g_mailbox, g_uids, g_accountEmail, g_altAddresses, g_unlabeled, g_account};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobjectArray {
        jstring mailbox = g_mailbox;
        jlongArray uids = g_uids;
        jstring accountEmail = g_accountEmail;
        jstring altAddresses = g_altAddresses;
        jstring unlabeledCharset = g_unlabeled;
        jstring accountId = g_account;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars accountEmailChars(env, accountEmail);
    JChars altChars(env, altAddresses);
    JChars unlabeledChars(env, unlabeledCharset);
    const char * unlabeled = unlabeledChars.c();
    if (unlabeled == nullptr || unlabeled[0] == 0 || unlabeled[0] == '\n') unlabeled = "ISO-8859-1";
    JChars mb(env, mailbox);
    if (session->selectedMailbox != mb.c()) {
        if (!selectMailbox(env, session, mb.c(), false)) {
            unlockSession(session);
            return nullptr;
        }
    }
    struct mailimap_selection_info * info = session->imap->imap_selection_info;
    uint32_t exists = info != nullptr ? info->sel_exists : 0;
    jlong uidValidity = info != nullptr ? static_cast<jlong>(info->sel_uidvalidity) : 0;
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
        jobject rowsOut = newList(env);
        if (rowsOut == nullptr) {
            if (list != nullptr) mailimap_fetch_list_free(list);
            unlockSession(session);
            return nullptr;
        }
        int envelopeRows = 0;
        if (!consumeIndexList(env, session, rowsOut, list, accountEmailChars.c(), altChars.c(),
                serverPreview, bodyPreview, peekLimit, preferHtml, showDeleted, &envelopeRows, unlabeled,
                accountId, mb.c(), uidValidity)) {
            if (list != nullptr) mailimap_fetch_list_free(list);
            env->DeleteLocalRef(rowsOut);
            unlockSession(session);
            return nullptr;
        }
        noteParsedRows(session, static_cast<size_t>(envelopeRows));
        if (exists > 0 && first <= last && envelopeRows == 0) {
            env->DeleteLocalRef(rowsOut);
            abortEmptyIndexFetch(env, session, list);
            return nullptr;
        }
        if (list != nullptr) mailimap_fetch_list_free(list);
        jobjectArray arr = indexRowsToArray(env, rowsOut);
        env->DeleteLocalRef(rowsOut);
        unlockSession(session);
        return arr;
    }
    std::vector<uint32_t> all;
    if (uids != nullptr) {
        jsize n = env->GetArrayLength(uids);
        std::vector<jlong> raw(static_cast<size_t>(n));
        if (n > 0) env->GetLongArrayRegion(uids, 0, n, raw.data());
        for (jlong value : raw) {
            if (value <= 0 || value > static_cast<jlong>(UINT32_MAX)) {
                throwFailure(env, "bad uid");
                unlockSession(session);
                return nullptr;
            }
            all.push_back(static_cast<uint32_t>(value));
        }
    }
    jobject rowsOut = newList(env);
    if (rowsOut == nullptr) {
        unlockSession(session);
        return nullptr;
    }
    const size_t pageSize = 60;
    for (size_t off = 0; off < all.size(); off += pageSize) {
        size_t end = std::min(all.size(), off + pageSize);
        std::vector<uint32_t> page(all.begin() + static_cast<std::ptrdiff_t>(off), all.begin() + static_cast<std::ptrdiff_t>(end));
        struct mailimap_set * set = setFromUids(page);
        clist * list = nullptr;
        int r = fetchRows(session->imap, set, true, true, serverPreview, &list);
        if (set != nullptr) mailimap_set_free(set);
        if (!cmdOk(r)) {
            if (list != nullptr) mailimap_fetch_list_free(list);
            env->DeleteLocalRef(rowsOut);
            throwImap(env, session, r, "fetch failed");
            unlockSession(session);
            return nullptr;
        }
        int envelopeRows = 0;
        if (!consumeIndexList(env, session, rowsOut, list, accountEmailChars.c(), altChars.c(),
                serverPreview, bodyPreview, peekLimit, preferHtml, showDeleted, &envelopeRows, unlabeled,
                accountId, mb.c(), uidValidity)) {
            if (list != nullptr) mailimap_fetch_list_free(list);
            env->DeleteLocalRef(rowsOut);
            unlockSession(session);
            return nullptr;
        }
        noteParsedRows(session, static_cast<size_t>(envelopeRows));
        if (exists > 0 && !page.empty() && envelopeRows == 0) {
            env->DeleteLocalRef(rowsOut);
            abortEmptyIndexFetch(env, session, list);
            return nullptr;
        }
        if (list != nullptr) mailimap_fetch_list_free(list);
    }
    jobjectArray arr = indexRowsToArray(env, rowsOut);
    env->DeleteLocalRef(rowsOut);
    unlockSession(session);
    return arr;
    });
}

// Finds the part with IMAP section `target` the same way mimeFromBody numbers sections (multipart child i is
// "<parent>.i", a message/rfc822 part's body is "<part>.1", a single top-level part is "1"). Iterative.
struct mailimap_body * bodyAtSection(struct mailimap_body * body, const std::string & target, std::string * found) {
    std::string section;
    while (body != nullptr) {
        std::string here = section;
        if (body->bd_type != MAILIMAP_BODY_MPART && here.empty()) here = "1";
        if (here == target) {
            *found = here;
            return body;
        }
        auto under = [&](const std::string & s) {
            return target.size() > s.size() && target.compare(0, s.size(), s) == 0 && target[s.size()] == '.';
        };
        struct mailimap_body * next = nullptr;
        std::string nextSection;
        if (body->bd_type == MAILIMAP_BODY_MPART && body->bd_data.bd_body_mpart != nullptr
            && body->bd_data.bd_body_mpart->bd_list != nullptr) {
            int index = 1;
            for (clistiter * cur = clist_begin(body->bd_data.bd_body_mpart->bd_list); cur != nullptr;
                 cur = clist_next(cur), ++index) {
                std::string child = section.empty() ? std::to_string(index) : section + "." + std::to_string(index);
                if (child == target || under(child)) {
                    next = static_cast<struct mailimap_body *>(clist_content(cur));
                    nextSection = child;
                    break;
                }
            }
        } else if (body->bd_type == MAILIMAP_BODY_1PART && body->bd_data.bd_body_1part != nullptr
            && body->bd_data.bd_body_1part->bd_type == MAILIMAP_BODY_TYPE_1PART_MSG
            && body->bd_data.bd_body_1part->bd_data.bd_type_msg != nullptr && under(here)) {
            next = body->bd_data.bd_body_1part->bd_data.bd_type_msg->bd_body;
            nextSection = next != nullptr && next->bd_type == MAILIMAP_BODY_MPART ? here : here + ".1"; // F112
            if (next != nullptr && next->bd_type != MAILIMAP_BODY_MPART && nextSection == target) {
                *found = nextSection;
                return next;
            }
        }
        if (next == nullptr) return nullptr;
        body = next;
        section = nextSection;
    }
    return nullptr;
}

jobject fetchStructureFrom(JNIEnv * env, jlong handle, jlong uid, const std::string & start);

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchStructureAt(JNIEnv * env, jobject, jlong handle,
    jlong uid, jstring jSection) {
    jstring g_jSection = jSection != nullptr ? static_cast<jstring>(env->NewGlobalRef(jSection)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_jSection};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jstring jSection = g_jSection;
        JChars section(env, jSection);
        std::string start = section.c();
        return fetchStructureFrom(env, handle, uid, start);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchStructure(JNIEnv * env, jobject, jlong handle, jlong uid) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        return fetchStructureFrom(env, handle, uid, "");
    });
}

jobject fetchStructureFrom(JNIEnv * env, jlong handle, jlong uid, const std::string & start) {
    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    if (uid <= 0 || uid > static_cast<jlong>(UINT32_MAX)) {
        throwFailure(env, "bad uid");
        unlockSession(session);
        return nullptr;
    }
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
            readAtt(static_cast<struct mailimap_msg_att *>(clist_content(cur)), &row, nullptr, nullptr, "ISO-8859-1");
            body = row.structure;
        }
    }
    jobject obj = nullptr;
    if (start.empty()) {
        obj = mimeFromBody(env, body, "");
    } else {
        std::string found;
        struct mailimap_body * at = bodyAtSection(body, start, &found);
        if (at != nullptr) obj = mimeFromBody(env, at, found);
    }
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
    jstring g_section = section != nullptr ? static_cast<jstring>(env->NewGlobalRef(section)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_section};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jbyteArray {
        jstring section = g_section;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    if (uid <= 0 || uid > static_cast<jlong>(UINT32_MAX)) {
        throwFailure(env, "bad uid");
        unlockSession(session);
        return nullptr;
    }
    if (offset < 0 || length < 0) {
        throwFailure(env, "bad fetch range");
        unlockSession(session);
        return nullptr;
    }
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
    bool matched = false;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr && !matched; cur = clist_next(cur)) {
            auto * msg = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            if (msg == nullptr || msg->att_list == nullptr) continue;
            for (clistiter * ic = clist_begin(msg->att_list); ic != nullptr && !matched; ic = clist_next(ic)) {
                auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(ic));
                if (item == nullptr) continue;
                if (item->att_type == MAILIMAP_MSG_ATT_ITEM_STATIC && item->att_data.att_static != nullptr) {
                    struct mailimap_msg_att_static * st = item->att_data.att_static;
                    if (st->att_type == MAILIMAP_MSG_ATT_BODY_SECTION && st->att_data.att_body_section != nullptr) {
                        // libetpan: a real empty nstring is a non-null pointer of length 0; NIL is a null pointer
                        // of length 0. Both are a body. No BODY section on this FETCH is still "gone".
                        struct mailimap_msg_att_body_section * sec = st->att_data.att_body_section;
                        bytes = sec->sec_body_part;
                        n = bytes != nullptr ? sec->sec_length : 0;
                        matched = true;
                    }
                } else if (item->att_type == MAILIMAP_MSG_ATT_ITEM_EXTENSION && item->att_data.att_extension_data != nullptr) {
                    struct mailimap_extension_data * ext = item->att_data.att_extension_data;
                    if (ext->ext_extension == &liveimap_extra_extension && ext->ext_type == LIVE_BINARY) {
                        auto * blob = static_cast<LiveBlob *>(ext->ext_data);
                        if (blob != nullptr) {
                            bytes = blob->data;
                            n = bytes != nullptr ? blob->len : 0;
                            matched = true;
                        }
                    }
                }
            }
        }
    }
    if (!matched) {
        if (list != nullptr) mailimap_fetch_list_free(list);
        throwFailure(env, "message no longer exists");
        unlockSession(session);
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (n > 0 && bytes != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(bytes));
    }
    if (list != nullptr) mailimap_fetch_list_free(list);
    unlockSession(session);
    return arr;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchRfc822(JNIEnv * env, jobject, jlong handle, jlong uid) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jbyteArray {


    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    if (uid <= 0 || uid > static_cast<jlong>(UINT32_MAX)) {
        throwFailure(env, "bad uid");
        unlockSession(session);
        return nullptr;
    }
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
    bool matched = false;
    if (list != nullptr) {
        for (clistiter * cur = clist_begin(list); cur != nullptr && !matched; cur = clist_next(cur)) {
            auto * msg = static_cast<struct mailimap_msg_att *>(clist_content(cur));
            if (msg == nullptr || msg->att_list == nullptr) continue;
            for (clistiter * ic = clist_begin(msg->att_list); ic != nullptr && !matched; ic = clist_next(ic)) {
                auto * item = static_cast<struct mailimap_msg_att_item *>(clist_content(ic));
                if (item == nullptr || item->att_type != MAILIMAP_MSG_ATT_ITEM_STATIC || item->att_data.att_static == nullptr) continue;
                struct mailimap_msg_att_static * st = item->att_data.att_static;
                if (st->att_type == MAILIMAP_MSG_ATT_RFC822) {
                    // NIL is a null pointer of length 0. The attribute itself is the body; a FETCH that
                    // names the message and has no RFC822 still leaves matched false.
                    bytes = st->att_data.att_rfc822.att_content;
                    n = bytes != nullptr ? st->att_data.att_rfc822.att_length : 0;
                    matched = true;
                }
            }
        }
    }
    if (!matched) {
        if (list != nullptr) mailimap_fetch_list_free(list);
        throwFailure(env, "message no longer exists");
        unlockSession(session);
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
    if (arr == nullptr) {
        if (!env->ExceptionCheck()) throwFailure(env, "out of memory");
        if (list != nullptr) mailimap_fetch_list_free(list);
        unlockSession(session);
        return nullptr;
    }
    if (n > 0 && bytes != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(bytes));
    }
    if (list != nullptr) mailimap_fetch_list_free(list);
    unlockSession(session);
    if (env->ExceptionCheck()) return nullptr;
    return arr;
    });
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
        struct mailimap_store_att_flags * att = mailimap_store_att_flags_new_add_flags_silent(addFlags);
        addFlags = nullptr;
        int r = mailimap_uid_store(session->imap, set, att);
        mailimap_store_att_flags_free(att);
        if (!cmdOk(r)) {
            if (removeFlags != nullptr) mailimap_flag_list_free(removeFlags);
            throwImap(env, session, r, "store failed (add flags did not complete)");
            return false;
        }
    } else if (addFlags != nullptr) {
        mailimap_flag_list_free(addFlags);
    }
    if (remove != nullptr && env->GetArrayLength(remove) > 0) {
        struct mailimap_store_att_flags * att = mailimap_store_att_flags_new_remove_flags_silent(removeFlags);
        removeFlags = nullptr;
        int r = mailimap_uid_store(session->imap, set, att);
        mailimap_store_att_flags_free(att);
        if (!cmdOk(r)) {
            throwImap(env, session, r, "store failed (remove flags did not complete; add flags may have applied)");
            return false;
        }
    } else if (removeFlags != nullptr) {
        mailimap_flag_list_free(removeFlags);
    }
    return true;
}

// UIDs named by a request set. '*' (set_first 0 or set_last 0) counts as the result UID cap.
// A finite total above the cap is returned as that total.
uint64_t resultUidCap() {
    if (tlsMaxResultUids >= 1) return tlsMaxResultUids;
    return 2000000ULL;
}

uint64_t requestedCount(struct mailimap_set * set) {
    uint64_t n = 0;
    if (set == nullptr || set->set_list == nullptr) return 0;
    for (clistiter * cur = clist_begin(set->set_list); cur != nullptr; cur = clist_next(cur)) {
        auto * item = static_cast<struct mailimap_set_item *>(clist_content(cur));
        if (item == nullptr) continue;
        if (item->set_first == 0 || item->set_last == 0) return resultUidCap();
        uint32_t a = item->set_first, b = item->set_last;
        n += (a > b ? a - b : b - a) + 1ULL;
    }
    return n;
}

// COPYUID's destination set, expanded. A range that would pass limit stops; UIDs already pushed stay.
struct CopyMoveOut {
    std::vector<uint32_t> uids;
    uint32_t uidvalidity = 0;
};

bool rememberDestUids(CopyMoveOut * out, struct mailimap_set * dest, uint64_t limit) {
    out->uids.clear();
    if (dest == nullptr || dest->set_list == nullptr) return true;
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
        if (static_cast<uint64_t>(last) - first + 1 + out->uids.size() > limit) return false;
        for (uint32_t uid = first;; ++uid) {
            if (uid != 0) out->uids.push_back(uid);
            if (uid == last) break;
        }
    }
    return true;
}

jobject newCopyMove(JNIEnv * env, const CopyMoveOut & out) {
    std::vector<jlong> raw;
    raw.reserve(out.uids.size());
    for (uint32_t uid : out.uids) raw.push_back(static_cast<jlong>(uid));
    jlongArray arr = env->NewLongArray(static_cast<jsize>(raw.size()));
    if (arr != nullptr && !raw.empty()) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(raw.size()), raw.data());
    }
    jobject obj = env->NewObject(gJni.copyMove, gJni.copyMoveInit, arr, static_cast<jlong>(out.uidvalidity));
    if (arr != nullptr) env->DeleteLocalRef(arr);
    return obj;
}

jobject newSessionOpen(JNIEnv * env, jlong handle, const std::string & tls, const std::string & error,
    bool authRejected, const std::vector<std::string> & cert) {
    auto at = [&](size_t i) -> const char * {
        return i < cert.size() ? cert[i].c_str() : "";
    };
    jstring jtls = newString(env, tls.c_str());
    jstring jerr = newString(env, error.c_str());
    jstring c0 = newString(env, at(0));
    jstring c1 = newString(env, at(1));
    jstring c2 = newString(env, at(2));
    jstring c3 = newString(env, at(3));
    jstring c4 = newString(env, at(4));
    jobject obj = env->NewObject(gJni.sessionOpen, gJni.sessionOpenInit, handle, jtls, jerr,
        authRejected ? JNI_TRUE : JNI_FALSE, c0, c1, c2, c3, c4);
    dropLocal(env, jtls);
    dropLocal(env, jerr);
    dropLocal(env, c0);
    dropLocal(env, c1);
    dropLocal(env, c2);
    dropLocal(env, c3);
    dropLocal(env, c4);
    return obj;
}

void freeUidSets(struct mailimap_set * source, struct mailimap_set * dest) {
    if (source != nullptr) mailimap_set_free(source);
    if (dest != nullptr) mailimap_set_free(dest);
}

bool copyOrMoveSet(JNIEnv * env, LiveSession * session, struct mailimap_set * set,
    const char * destMailbox, const char * kind, CopyMoveOut * out) {
    out->uids.clear();
    out->uidvalidity = 0;
    uint64_t asked = requestedCount(set);
    if (asked > resultUidCap()) {
        throwFailure(env, "maxResultUids");
        return false;
    }
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
    bool kept = rememberDestUids(out, copied, asked);
    freeUidSets(source, copied);
    if (!kept) {
        throwFailure(env, "COPYUID larger than the request");
        return false;
    }
    if (!out->uids.empty()) out->uidvalidity = uidvalidity;
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
    for (jlong value : raw) {
        if (value <= 0 || value > static_cast<jlong>(UINT32_MAX)) {
            throwFailure(env, "bad uid");
            values.clear();
            return values;
        }
        values.push_back(static_cast<uint32_t>(value));
    }
    return values;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStoreFlags(JNIEnv * env, jobject, jlong handle,
    jlongArray uids, jobjectArray add, jobjectArray remove) {
    jlongArray g_uids = uids != nullptr ? static_cast<jlongArray>(env->NewGlobalRef(uids)) : nullptr;
    jobjectArray g_add = add != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(add)) : nullptr;
    jobjectArray g_remove = remove != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(remove)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jlongArray p0; jobjectArray p1; jobjectArray p2; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); } } ownerRefs{env, g_uids, g_add, g_remove};
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {
        jlongArray uids = g_uids;
        jobjectArray add = g_add;
        jobjectArray remove = g_remove;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return;
    std::vector<uint32_t> values = uidsFromArray(env, uids);
    if (env->ExceptionCheck() || values.empty()) {
        unlockSession(session);
        return;
    }
    bool hadZero = false;
    struct mailimap_set * set = setFromUids(values, &hadZero);
    if (set == nullptr) {
        throwFailure(env, hadZero ? "bad uid" : "store failed");
        unlockSession(session);
        return;
    }
    applyFlagChange(env, session, set, add, remove);
    mailimap_set_free(set);
    unlockSession(session);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStoreFlagsAll(JNIEnv * env, jobject, jlong handle,
    jobjectArray add, jobjectArray remove) {
    jobjectArray g_add = add != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(add)) : nullptr;
    jobjectArray g_remove = remove != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(remove)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jobjectArray p0; jobjectArray p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_add, g_remove};
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {
        jobjectArray add = g_add;
        jobjectArray remove = g_remove;

    LiveSession * session = lockImapSession(env, handle);
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
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeUidExpungeDeleted(JNIEnv * env, jobject, jlong handle) {
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {


    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return;
    struct mailimap_set * set = mailimap_set_new_interval(1, 0);
    int r = mailimap_uid_expunge(session->imap, set);
    mailimap_set_free(set);
    if (!cmdOk(r)) throwImap(env, session, r, "expunge failed");
    unlockSession(session);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeUidExpunge(JNIEnv * env, jobject, jlong handle,
    jlongArray uids) {
    jlongArray g_uids = uids != nullptr ? static_cast<jlongArray>(env->NewGlobalRef(uids)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jlongArray p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_uids};
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {
        jlongArray uids = g_uids;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return;
    std::vector<uint32_t> values = uidsFromArray(env, uids);
    if (env->ExceptionCheck() || values.empty()) {
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
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCopyThenDelete(JNIEnv * env, jobject, jlong handle,
    jlongArray uids, jstring target, jstring moveKind) {
    jlongArray g_uids = uids != nullptr ? static_cast<jlongArray>(env->NewGlobalRef(uids)) : nullptr;
    jstring g_target = target != nullptr ? static_cast<jstring>(env->NewGlobalRef(target)) : nullptr;
    jstring g_moveKind = moveKind != nullptr ? static_cast<jstring>(env->NewGlobalRef(moveKind)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jlongArray p0; jstring p1; jstring p2; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); } } ownerRefs{env, g_uids, g_target, g_moveKind};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jlongArray uids = g_uids;
        jstring target = g_target;
        jstring moveKind = g_moveKind;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    std::vector<uint32_t> values = uidsFromArray(env, uids);
    if (env->ExceptionCheck() || values.empty()) {
        unlockSession(session);
        return nullptr;
    }
    JChars dest(env, target);
    JChars kindChars(env, moveKind);
    if (!wireOk(dest.c())) {  // F42 native backstop
        throwFailure(env, "refused: CR or LF in a mailbox name");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_set * set = setFromUids(values);
    if (set == nullptr) {
        throwFailure(env, "copy failed");
        unlockSession(session);
        return nullptr;
    }
    CopyMoveOut moved;
    bool ok = copyOrMoveSet(env, session, set, dest.c(), kindChars.c(), &moved);
    mailimap_set_free(set);
    jobject result = ok ? newCopyMove(env, moved) : nullptr;
    unlockSession(session);
    return result;
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeCopyAllThenDelete(JNIEnv * env, jobject, jlong handle,
    jstring target, jstring moveKind) {
    jstring g_target = target != nullptr ? static_cast<jstring>(env->NewGlobalRef(target)) : nullptr;
    jstring g_moveKind = moveKind != nullptr ? static_cast<jstring>(env->NewGlobalRef(moveKind)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_target, g_moveKind};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jstring target = g_target;
        jstring moveKind = g_moveKind;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    struct mailimap_set * set = mailimap_set_new_interval(1, 0);
    if (set == nullptr) {
        throwFailure(env, "copy failed");
        unlockSession(session);
        return nullptr;
    }
    JChars dest(env, target);
    JChars kindChars(env, moveKind);
    if (!wireOk(dest.c())) {  // F42 native backstop
        throwFailure(env, "refused: CR or LF in a mailbox name");
        mailimap_set_free(set);
        unlockSession(session);
        return nullptr;
    }
    CopyMoveOut moved;
    bool ok = copyOrMoveSet(env, session, set, dest.c(), kindChars.c(), &moved);
    mailimap_set_free(set);
    jobject result = ok ? newCopyMove(env, moved) : nullptr;
    unlockSession(session);
    return result;
    });
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

// Empty path or empty file: out stays empty, the same as an empty byte array. A file that cannot be read fails.
bool loadMessageFile(const std::string & path, std::vector<char> & out) {
    out.clear();
    if (path.empty()) return true;
    FILE * file = fopen(path.c_str(), "rb");
    if (file == nullptr) return false;
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return false;
    }
    long end = ftell(file);
    if (end < 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        return false;
    }
    if (end == 0) {
        fclose(file);
        return true;
    }
    out.resize(static_cast<size_t>(end));
    size_t got = fread(out.data(), 1, out.size(), file);
    int bad = ferror(file);
    fclose(file);
    if (bad != 0 || got != out.size()) {
        out.clear();
        return false;
    }
    return true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeAppend(JNIEnv * env, jobject, jlong handle,
    jstring mailbox, jstring path, jobjectArray flags) {
    jstring g_mailbox = mailbox != nullptr ? static_cast<jstring>(env->NewGlobalRef(mailbox)) : nullptr;
    jstring g_path = path != nullptr ? static_cast<jstring>(env->NewGlobalRef(path)) : nullptr;
    jobjectArray g_flags = flags != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(flags)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; jobjectArray p2; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); } } ownerRefs{env, g_mailbox, g_path, g_flags};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlong {
        jstring mailbox = g_mailbox;
        jstring path = g_path;
        jobjectArray flags = g_flags;

    LiveSession * session = lockImapSession(env, handle);
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
    if (!wireOk(mb.c())) {  // F42 native backstop
        if (flagList != nullptr) mailimap_flag_list_free(flagList);
        throwFailure(env, "refused: CR or LF in a mailbox name");
        unlockSession(session);
        return 0;
    }
    std::vector<char> messageBytes;
    if (path != nullptr) {
        auto got = utf8FromJava(env, path);
        if (!got) {
            if (flagList != nullptr) mailimap_flag_list_free(flagList);
            unlockSession(session);
            return 0;
        }
        if (!loadMessageFile(*got, messageBytes)) {
            if (flagList != nullptr) mailimap_flag_list_free(flagList);
            throwFailure(env, "append failed");
            unlockSession(session);
            return 0;
        }
    }
    int r = appendLiteralPlus(session->imap, mb.c(),
        messageBytes.empty() ? "" : messageBytes.data(), messageBytes.size(), flagList);
    if (flagList != nullptr) mailimap_flag_list_free(flagList);
    jlong uid = 0;
    if (!cmdOk(r)) {
        throwImap(env, session, r, "append failed");
    } else {
        uid = appendUidFromBuffer(session->imap);
    }
    unlockSession(session);
    return uid;
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchText(JNIEnv * env, jobject, jlong handle,
    jstring query, jboolean useEsearch, jboolean withCharset) {
    jstring g_query = query != nullptr ? static_cast<jstring>(env->NewGlobalRef(query)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, g_query};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        jstring query = g_query;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars q(env, query);
    if (!lineSafeArgument(q.c())) { // F118
        unlockSession(session);
        throwFailure(env, "bad search");
        return nullptr;
    }
    struct mailimap_search_key * key = mailimap_search_key_new_text(strdup(q.c()));
    if (useEsearch == JNI_TRUE) {
        struct mailimap_response * response = nullptr;
        int r = sendEsearch(session->imap, true, "ALL", withCharset == JNI_TRUE, false, key, &response);
        mailimap_search_key_free(key);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<uint32_t> result = takeEsearch(session->imap);
        bool ok = taggedOk(response);
        mailimap_response_free(response);
        if (!ok) {
            throwImap(env, session, r, "search failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = uidArray(env, result);
        unlockSession(session);
        return arr;
    }
    clist * result = nullptr;
    int r = mailimap_uid_search(session->imap, withCharset == JNI_TRUE ? "UTF-8" : nullptr, key, &result);
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
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchCriterion(JNIEnv * env, jobject, jlong handle,
    jstring kind, jstring argument, jboolean useEsearch, jboolean withCharset) {
    jstring g_kind = kind != nullptr ? static_cast<jstring>(env->NewGlobalRef(kind)) : nullptr;
    jstring g_argument = argument != nullptr ? static_cast<jstring>(env->NewGlobalRef(argument)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_kind, g_argument};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        jstring kind = g_kind;
        jstring argument = g_argument;

    LiveSession * session = lockImapSession(env, handle);
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
    return completeSearch(env, session, key, useEsearch, withCharset, JNI_FALSE);
    });
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
    // Two Group steps in one advanced query both parse. A group inside a group is refused in groupKeySep.
    for (jsize i = 0; i < n; ++i) {
        jstring kindJs = static_cast<jstring>(env->GetObjectArrayElement(kinds, i));
        jstring argJs = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        struct mailimap_search_key * step = nullptr;
        {
            JChars kindChars(env, kindJs);
            JChars argChars(env, argJs);
            if (kindChars.s && argChars.s) step = criterionKey(env, kindChars.c(), argChars.c());
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
    jboolean useEsearch, jboolean withCharset, jboolean fuzzy) {
    jstring g_combiner = combiner != nullptr ? static_cast<jstring>(env->NewGlobalRef(combiner)) : nullptr;
    jbooleanArray g_negated = negated != nullptr ? static_cast<jbooleanArray>(env->NewGlobalRef(negated)) : nullptr;
    jobjectArray g_kinds = kinds != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(kinds)) : nullptr;
    jobjectArray g_arguments = arguments != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(arguments)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jbooleanArray p1; jobjectArray p2; jobjectArray p3; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); if (p3) e->DeleteGlobalRef(p3); } } ownerRefs{env, g_combiner, g_negated, g_kinds, g_arguments};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        jstring combiner = g_combiner;
        jbooleanArray negated = g_negated;
        jobjectArray kinds = g_kinds;
        jobjectArray arguments = g_arguments;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars combinerChars(env, combiner);
    struct mailimap_search_key * key = advancedKey(env, combinerChars.c(), negated, kinds, arguments);
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    return completeSearch(env, session, key, useEsearch, withCharset, fuzzy);
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchCriterionCount(JNIEnv * env, jobject, jlong handle,
    jstring kind, jstring argument, jboolean withCharset) {
    jstring g_kind = kind != nullptr ? static_cast<jstring>(env->NewGlobalRef(kind)) : nullptr;
    jstring g_argument = argument != nullptr ? static_cast<jstring>(env->NewGlobalRef(argument)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_kind, g_argument};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlong {
        jstring kind = g_kind;
        jstring argument = g_argument;

    LiveSession * session = lockImapSession(env, handle);
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
    return completeCount(env, session, key, withCharset, JNI_FALSE);
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchAdvancedCount(JNIEnv * env, jobject, jlong handle,
    jstring combiner, jbooleanArray negated, jobjectArray kinds, jobjectArray arguments, jboolean withCharset,
    jboolean fuzzy) {
    jstring g_combiner = combiner != nullptr ? static_cast<jstring>(env->NewGlobalRef(combiner)) : nullptr;
    jbooleanArray g_negated = negated != nullptr ? static_cast<jbooleanArray>(env->NewGlobalRef(negated)) : nullptr;
    jobjectArray g_kinds = kinds != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(kinds)) : nullptr;
    jobjectArray g_arguments = arguments != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(arguments)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jbooleanArray p1; jobjectArray p2; jobjectArray p3; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); if (p3) e->DeleteGlobalRef(p3); } } ownerRefs{env, g_combiner, g_negated, g_kinds, g_arguments};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlong {
        jstring combiner = g_combiner;
        jbooleanArray negated = g_negated;
        jobjectArray kinds = g_kinds;
        jobjectArray arguments = g_arguments;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return -1;
    JChars combinerChars(env, combiner);
    struct mailimap_search_key * key = advancedKey(env, combinerChars.c(), negated, kinds, arguments);
    if (env->ExceptionCheck() || key == nullptr) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return -1;
    }
    return completeCount(env, session, key, withCharset, fuzzy);
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchScope(JNIEnv * env, jobject, jlong handle,
    jstring scope, jstring home, jstring combiner, jbooleanArray negated, jobjectArray kinds, jobjectArray arguments,
    jboolean withCharset, jboolean fuzzy, jstring accountId) {
    jstring g_scope = scope != nullptr ? static_cast<jstring>(env->NewGlobalRef(scope)) : nullptr;
    jstring g_home = home != nullptr ? static_cast<jstring>(env->NewGlobalRef(home)) : nullptr;
    jstring g_combiner = combiner != nullptr ? static_cast<jstring>(env->NewGlobalRef(combiner)) : nullptr;
    jbooleanArray g_negated = negated != nullptr ? static_cast<jbooleanArray>(env->NewGlobalRef(negated)) : nullptr;
    jobjectArray g_kinds = kinds != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(kinds)) : nullptr;
    jobjectArray g_arguments = arguments != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(arguments)) : nullptr;
    jstring g_account = accountId != nullptr ? static_cast<jstring>(env->NewGlobalRef(accountId)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; jstring p2; jbooleanArray p3; jobjectArray p4; jobjectArray p5; jstring p6; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); if (p3) e->DeleteGlobalRef(p3); if (p4) e->DeleteGlobalRef(p4); if (p5) e->DeleteGlobalRef(p5); if (p6) e->DeleteGlobalRef(p6); } } ownerRefs{env, g_scope, g_home, g_combiner, g_negated, g_kinds, g_arguments, g_account};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobjectArray {
        jstring scope = g_scope;
        jstring home = g_home;
        jstring combiner = g_combiner;
        jbooleanArray negated = g_negated;
        jobjectArray kinds = g_kinds;
        jobjectArray arguments = g_arguments;
        jstring accountId = g_account;

    LiveSession * session = lockImapSession(env, handle);
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
    return runScopeSearch(env, session, scopeChars.c(), homeChars.c(), key, withCharset, fuzzy, accountId);
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchScopeCount(JNIEnv * env, jobject, jlong handle,
    jstring scope, jstring home, jstring combiner, jbooleanArray negated, jobjectArray kinds, jobjectArray arguments,
    jboolean withCharset, jboolean fuzzy) {
    jstring g_scope = scope != nullptr ? static_cast<jstring>(env->NewGlobalRef(scope)) : nullptr;
    jstring g_home = home != nullptr ? static_cast<jstring>(env->NewGlobalRef(home)) : nullptr;
    jstring g_combiner = combiner != nullptr ? static_cast<jstring>(env->NewGlobalRef(combiner)) : nullptr;
    jbooleanArray g_negated = negated != nullptr ? static_cast<jbooleanArray>(env->NewGlobalRef(negated)) : nullptr;
    jobjectArray g_kinds = kinds != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(kinds)) : nullptr;
    jobjectArray g_arguments = arguments != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(arguments)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; jstring p2; jbooleanArray p3; jobjectArray p4; jobjectArray p5; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); if (p3) e->DeleteGlobalRef(p3); if (p4) e->DeleteGlobalRef(p4); if (p5) e->DeleteGlobalRef(p5); } } ownerRefs{env, g_scope, g_home, g_combiner, g_negated, g_kinds, g_arguments};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlong {
        jstring scope = g_scope;
        jstring home = g_home;
        jstring combiner = g_combiner;
        jbooleanArray negated = g_negated;
        jobjectArray kinds = g_kinds;
        jobjectArray arguments = g_arguments;

    LiveSession * session = lockImapSession(env, handle);
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
    return runScopeCount(env, session, scopeChars.c(), homeChars.c(), key, withCharset, fuzzy);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchStart(JNIEnv * env, jobject, jlong handle,
    jstring rule, jboolean byUid, jstring edge, jboolean useEsearch) {
    jstring g_rule = rule != nullptr ? static_cast<jstring>(env->NewGlobalRef(rule)) : nullptr;
    jstring g_edge = edge != nullptr ? static_cast<jstring>(env->NewGlobalRef(edge)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_rule, g_edge};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        jstring rule = g_rule;
        jstring edge = g_edge;

    LiveSession * session = lockImapSession(env, handle);
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
    });
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
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {


    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    if (uid <= 0 || uid > 0xffffffffll) {
        throwFailure(env, "bad search");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_search_key * key = uidOnlyKey(static_cast<uint32_t>(uid));
    const char * edge = useEsearch == JNI_TRUE ? "Min" : "All";
    return runEdgeSearch(env, session, key, false, edge, useEsearch == JNI_TRUE);
    });
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

// FETCH by message sequence numbers [first, last]; the fetch type should include UID.
clist * seqFetchRange(JNIEnv * env, LiveSession * session, struct mailimap_fetch_type * fetch, uint32_t first,
    uint32_t last) {
    if (fetch == nullptr) {
        throwFailure(env, "fetch failed");
        return nullptr;
    }
    struct mailimap_set * set = mailimap_set_new_interval(first, last);
    if (set == nullptr) {
        mailimap_fetch_type_free(fetch);
        throwFailure(env, "fetch failed");
        return nullptr;
    }
    clist * list = nullptr;
    int r = mailimap_fetch(session->imap, set, fetch, &list);
    mailimap_set_free(set);
    mailimap_fetch_type_free(fetch);
    if (!cmdOk(r)) {
        if (list != nullptr) mailimap_fetch_list_free(list);
        throwImap(env, session, r, "fetch failed");
        return nullptr;
    }
    return list;
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

struct CoFetch {
    std::vector<int64_t> uids;
    std::vector<int64_t> numbers;
    std::vector<uint8_t> blank;
    std::vector<std::string> text;
    int err = 0;
};

struct CoHeaders {
    std::vector<int64_t> uids;
    std::vector<std::string> messageId, references, inReplyTo, subject, date;
    int err = 0;
};

struct CoFlat {
    std::vector<int64_t> uids;
    std::vector<int16_t> depths;
};

std::vector<uint32_t> co_search_all(mailimap * imap, int * err);
CoFetch co_fetch_sort(mailimap * imap, const uint32_t * uids, size_t n, int field);
CoHeaders co_fetch_thread(mailimap * imap, const uint32_t * uids, size_t n);
int co_order(const int64_t * uids, const int64_t * number, const uint8_t * blank, const uint8_t * bytes,
    const int32_t * off, const int32_t * len, int n, int numeric, int newestFirst, int32_t * out);
int co_insert_at(const int64_t * uids, const int64_t * number, const uint8_t * blank, const uint8_t * bytes,
    const int32_t * off, const int32_t * len, int n, int numeric, int newestFirst, int64_t newUid, int64_t newNumber,
    int newBlank, const uint8_t * newBytes, int newLen);
CoFlat co_thread_subject(const int64_t * uids, const char * const * subjects, const int64_t * epochs,
    const uint8_t * blankDate, int n);
CoFlat co_thread_references(const int64_t * uidIn, const char * const * messageId, const char * const * references,
    const char * const * inReplyTo, int n);

namespace {

jlongArray longsOf(JNIEnv * env, const int64_t * data, size_t n) {
    jlongArray arr = env->NewLongArray(static_cast<jsize>(n));
    if (arr == nullptr || n == 0) return arr;
    std::vector<jlong> tmp(n);
    for (size_t i = 0; i < n; ++i) tmp[i] = static_cast<jlong>(data[i]);
    env->SetLongArrayRegion(arr, 0, static_cast<jsize>(n), tmp.data());
    return arr;
}

jlongArray longsOf(JNIEnv * env, const std::vector<int64_t> & data) {
    return longsOf(env, data.data(), data.size());
}

jobjectArray stringsOf(JNIEnv * env, const std::vector<std::string> & rows) {
    jclass cls = env->FindClass("java/lang/String");
    if (cls == nullptr) return nullptr;
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(rows.size()), cls, nullptr);
    env->DeleteLocalRef(cls);
    if (arr == nullptr) return nullptr;
    for (size_t i = 0; i < rows.size(); ++i) {
        if (env->PushLocalFrame(4) < 0) return nullptr;
        jstring text = newString(env, rows[i].c_str());
        if (text == nullptr) {
            env->PopLocalFrame(nullptr);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), text);
        env->PopLocalFrame(nullptr);
    }
    return arr;
}

bool takeLongs(JNIEnv * env, jlongArray arr, std::vector<int64_t> * out) {
    out->clear();
    if (arr == nullptr) return true;
    jsize n = env->GetArrayLength(arr);
    out->resize(static_cast<size_t>(n));
    if (n == 0) return true;
    jlong * ptr = env->GetLongArrayElements(arr, nullptr);
    if (ptr == nullptr) return false;
    for (jsize i = 0; i < n; ++i) (*out)[static_cast<size_t>(i)] = static_cast<int64_t>(ptr[i]);
    env->ReleaseLongArrayElements(arr, ptr, JNI_ABORT);
    return true;
}

bool takeInts(JNIEnv * env, jintArray arr, std::vector<int32_t> * out) {
    out->clear();
    if (arr == nullptr) return true;
    jsize n = env->GetArrayLength(arr);
    out->resize(static_cast<size_t>(n));
    if (n == 0) return true;
    jint * ptr = env->GetIntArrayElements(arr, nullptr);
    if (ptr == nullptr) return false;
    for (jsize i = 0; i < n; ++i) (*out)[static_cast<size_t>(i)] = static_cast<int32_t>(ptr[i]);
    env->ReleaseIntArrayElements(arr, ptr, JNI_ABORT);
    return true;
}

bool takeBytes(JNIEnv * env, jbyteArray arr, std::vector<uint8_t> * out) {
    out->clear();
    if (arr == nullptr) return true;
    jsize n = env->GetArrayLength(arr);
    out->resize(static_cast<size_t>(n));
    if (n == 0) return true;
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(out->data()));
    return !env->ExceptionCheck();
}

bool takeBools(JNIEnv * env, jbooleanArray arr, std::vector<uint8_t> * out) {
    out->clear();
    if (arr == nullptr) return true;
    jsize n = env->GetArrayLength(arr);
    out->resize(static_cast<size_t>(n));
    if (n == 0) return true;
    jboolean * ptr = env->GetBooleanArrayElements(arr, nullptr);
    if (ptr == nullptr) return false;
    for (jsize i = 0; i < n; ++i) (*out)[static_cast<size_t>(i)] = ptr[i] ? 1 : 0;
    env->ReleaseBooleanArrayElements(arr, ptr, JNI_ABORT);
    return true;
}

bool takeStrings(JNIEnv * env, jobjectArray arr, int n, std::vector<std::string> * out) {
    out->assign(static_cast<size_t>(n), std::string());
    if (arr == nullptr) return true;
    for (int i = 0; i < n; ++i) {
        if (env->PushLocalFrame(4) < 0) return false;
        jstring text = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (text != nullptr) {
            auto got = utf8FromJava(env, text);
            if (!got) {
                env->PopLocalFrame(nullptr);
                return false;
            }
            (*out)[static_cast<size_t>(i)] = std::move(*got);
        }
        env->PopLocalFrame(nullptr);
    }
    return true;
}

jobject newClientFetch(JNIEnv * env, const CoFetch & rows) {
    jclass cls = env->FindClass("org/dlang/liveimap/session/ClientFetch");
    if (cls == nullptr) return nullptr;
    jmethodID init = env->GetMethodID(cls, "<init>", "([J[J[Z[Ljava/lang/String;)V");
    if (init == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jlongArray uids = longsOf(env, rows.uids);
    jlongArray numbers = longsOf(env, rows.numbers);
    jbooleanArray blanks = env->NewBooleanArray(static_cast<jsize>(rows.blank.size()));
    jobjectArray texts = stringsOf(env, rows.text);
    if (blanks != nullptr) {
        std::vector<jboolean> flags(rows.blank.size());
        for (size_t i = 0; i < rows.blank.size(); ++i) flags[i] = rows.blank[i] ? JNI_TRUE : JNI_FALSE;
        if (!flags.empty()) env->SetBooleanArrayRegion(blanks, 0, static_cast<jsize>(flags.size()), flags.data());
    }
    if (uids == nullptr || numbers == nullptr || blanks == nullptr || texts == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jobject obj = env->NewObject(cls, init, uids, numbers, blanks, texts);
    env->DeleteLocalRef(cls);
    return obj;
}

jobject newClientHeaders(JNIEnv * env, const CoHeaders & rows) {
    jclass cls = env->FindClass("org/dlang/liveimap/session/ClientHeaders");
    if (cls == nullptr) return nullptr;
    jmethodID init = env->GetMethodID(cls, "<init>",
        "([J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V");
    if (init == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jlongArray uids = longsOf(env, rows.uids);
    jobjectArray messageId = stringsOf(env, rows.messageId);
    jobjectArray references = stringsOf(env, rows.references);
    jobjectArray inReplyTo = stringsOf(env, rows.inReplyTo);
    jobjectArray subject = stringsOf(env, rows.subject);
    jobjectArray date = stringsOf(env, rows.date);
    if (uids == nullptr || messageId == nullptr || references == nullptr || inReplyTo == nullptr || subject == nullptr
        || date == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jobject obj = env->NewObject(cls, init, uids, messageId, references, inReplyTo, subject, date);
    env->DeleteLocalRef(cls);
    return obj;
}

jobject newThreadFlat(JNIEnv * env, const CoFlat & flat) {
    jclass cls = env->FindClass("org/dlang/liveimap/session/ThreadFlat");
    if (cls == nullptr) return nullptr;
    jmethodID init = env->GetMethodID(cls, "<init>", "([J[S)V");
    if (init == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jlongArray uids = longsOf(env, flat.uids);
    jshortArray depths = env->NewShortArray(static_cast<jsize>(flat.depths.size()));
    if (uids == nullptr || depths == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    if (!flat.depths.empty()) {
        std::vector<jshort> tmp(flat.depths.size());
        for (size_t i = 0; i < flat.depths.size(); ++i) tmp[i] = static_cast<jshort>(flat.depths[i]);
        env->SetShortArrayRegion(depths, 0, static_cast<jsize>(tmp.size()), tmp.data());
    }
    jobject obj = env->NewObject(cls, init, uids, depths);
    env->DeleteLocalRef(cls);
    return obj;
}

std::vector<uint32_t> asUids(const std::vector<int64_t> & uids) {
    std::vector<uint32_t> out;
    out.reserve(uids.size());
    for (int64_t uid : uids) {
        if (uid <= 0 || uid > 0xffffffffLL) continue;
        out.push_back(static_cast<uint32_t>(uid));
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchUids(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        LiveSession * session = lockImapSession(env, handle);
        if (session == nullptr) return nullptr;
        int err = MAILIMAP_NO_ERROR;
        std::vector<uint32_t> uids = co_search_all(session->imap, &err);
        if (err != MAILIMAP_NO_ERROR) {
            throwImap(env, session, err, "sort failed");
            unlockSession(session);
            return nullptr;
        }
        std::vector<int64_t> wide(uids.size());
        for (size_t i = 0; i < uids.size(); ++i) wide[i] = static_cast<int64_t>(uids[i]);
        jlongArray arr = longsOf(env, wide);
        unlockSession(session);
        return arr;
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchSortChunk(JNIEnv * env, jobject, jlong handle,
    jlongArray uids, jint field) {
    jlongArray gUids = uids != nullptr ? static_cast<jlongArray>(env->NewGlobalRef(uids)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jlongArray p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, gUids};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jlongArray uids = gUids;
        LiveSession * session = lockImapSession(env, handle);
        if (session == nullptr) return nullptr;
        std::vector<int64_t> wide;
        if (!takeLongs(env, uids, &wide)) {
            unlockSession(session);
            return nullptr;
        }
        std::vector<uint32_t> set = asUids(wide);
        CoFetch rows = co_fetch_sort(session->imap, set.data(), set.size(), static_cast<int>(field));
        if (rows.err != MAILIMAP_NO_ERROR) {
            throwImap(env, session, rows.err, "sort failed");
            unlockSession(session);
            return nullptr;
        }
        jobject obj = newClientFetch(env, rows);
        unlockSession(session);
        return obj;
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeFetchThreadChunk(JNIEnv * env, jobject, jlong handle,
    jlongArray uids) {
    jlongArray gUids = uids != nullptr ? static_cast<jlongArray>(env->NewGlobalRef(uids)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jlongArray p0; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); } } ownerRefs{env, gUids};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jlongArray uids = gUids;
        LiveSession * session = lockImapSession(env, handle);
        if (session == nullptr) return nullptr;
        std::vector<int64_t> wide;
        if (!takeLongs(env, uids, &wide)) {
            unlockSession(session);
            return nullptr;
        }
        std::vector<uint32_t> set = asUids(wide);
        CoHeaders rows = co_fetch_thread(session->imap, set.data(), set.size());
        if (rows.err != MAILIMAP_NO_ERROR) {
            throwImap(env, session, rows.err, "thread failed");
            unlockSession(session);
            return nullptr;
        }
        jobject obj = newClientHeaders(env, rows);
        unlockSession(session);
        return obj;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSortClient(JNIEnv * env, jobject, jlongArray uids,
    jlongArray numbers, jbooleanArray blanks, jbyteArray keyBytes, jintArray keyOff, jintArray keyLen, jboolean numeric,
    jboolean newestFirst) {
    std::vector<int64_t> uid;
    std::vector<int64_t> number;
    std::vector<uint8_t> blank;
    std::vector<uint8_t> bytes;
    std::vector<int32_t> off;
    std::vector<int32_t> len;
    if (!takeLongs(env, uids, &uid) || !takeLongs(env, numbers, &number) || !takeBools(env, blanks, &blank)
        || !takeBytes(env, keyBytes, &bytes) || !takeInts(env, keyOff, &off) || !takeInts(env, keyLen, &len)) {
        return nullptr;
    }
    int n = static_cast<int>(uid.size());
    if (static_cast<int>(blank.size()) < n || static_cast<int>(off.size()) < n || static_cast<int>(len.size()) < n
        || (numeric && static_cast<int>(number.size()) < n)) {
        throwFailure(env, "sort failed");
        return nullptr;
    }
    std::vector<int32_t> out(static_cast<size_t>(n));
    int wrote = n == 0 ? 0 : co_order(uid.data(), numeric ? number.data() : nullptr, blank.data(),
        bytes.empty() ? nullptr : bytes.data(), off.data(), len.data(), n, numeric ? 1 : 0, newestFirst ? 1 : 0,
        out.data());
    jintArray arr = env->NewIntArray(wrote);
    if (arr != nullptr && wrote > 0) {
        env->SetIntArrayRegion(arr, 0, wrote, out.data());
    }
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeInsertAt(JNIEnv * env, jobject, jlongArray uids,
    jlongArray numbers, jbooleanArray blanks, jbyteArray keyBytes, jintArray keyOff, jintArray keyLen, jboolean numeric,
    jboolean newestFirst, jlong newUid, jlong newNumber, jboolean newBlank, jbyteArray newBytes) {
    std::vector<int64_t> uid;
    std::vector<int64_t> number;
    std::vector<uint8_t> blank;
    std::vector<uint8_t> bytes;
    std::vector<int32_t> off;
    std::vector<int32_t> len;
    std::vector<uint8_t> fresh;
    if (!takeLongs(env, uids, &uid) || !takeLongs(env, numbers, &number) || !takeBools(env, blanks, &blank)
        || !takeBytes(env, keyBytes, &bytes) || !takeInts(env, keyOff, &off) || !takeInts(env, keyLen, &len)
        || !takeBytes(env, newBytes, &fresh)) {
        return 0;
    }
    int n = static_cast<int>(uid.size());
    if (static_cast<int>(blank.size()) < n || static_cast<int>(off.size()) < n || static_cast<int>(len.size()) < n
        || (numeric && static_cast<int>(number.size()) < n)) {
        throwFailure(env, "sort failed");
        return 0;
    }
    if (n == 0) {
        return co_insert_at(nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, 0, numeric ? 1 : 0,
            newestFirst ? 1 : 0, static_cast<int64_t>(newUid), static_cast<int64_t>(newNumber), newBlank ? 1 : 0,
            fresh.empty() ? nullptr : fresh.data(), static_cast<int>(fresh.size()));
    }
    return co_insert_at(uid.data(), numeric ? number.data() : nullptr, blank.data(),
        bytes.empty() ? nullptr : bytes.data(), off.data(), len.data(), n, numeric ? 1 : 0, newestFirst ? 1 : 0,
        static_cast<int64_t>(newUid), static_cast<int64_t>(newNumber), newBlank ? 1 : 0,
        fresh.empty() ? nullptr : fresh.data(), static_cast<int>(fresh.size()));
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeThreadSubject(JNIEnv * env, jobject, jlongArray uids,
    jobjectArray subjects, jlongArray epochs) {
    std::vector<int64_t> uid;
    if (!takeLongs(env, uids, &uid)) return nullptr;
    std::vector<int64_t> epoch;
    if (!takeLongs(env, epochs, &epoch)) return nullptr;
    int n = static_cast<int>(uid.size());
    std::vector<std::string> subject;
    if (!takeStrings(env, subjects, n, &subject)) return nullptr;
    if (static_cast<int>(epoch.size()) < n) {
        throwFailure(env, "thread failed");
        return nullptr;
    }
    std::vector<const char *> keys(static_cast<size_t>(n));
    std::vector<uint8_t> blank(static_cast<size_t>(n));
    const int64_t minEpoch = static_cast<int64_t>(0x8000000000000000ULL);
    for (int i = 0; i < n; ++i) {
        keys[static_cast<size_t>(i)] = subject[static_cast<size_t>(i)].c_str();
        blank[static_cast<size_t>(i)] = epoch[static_cast<size_t>(i)] == minEpoch ? 1 : 0;
    }
    CoFlat flat = n == 0 ? CoFlat() : co_thread_subject(uid.data(), keys.data(), epoch.data(), blank.data(), n);
    return newThreadFlat(env, flat);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeThreadReferences(JNIEnv * env, jobject, jlongArray uids,
    jobjectArray messageId, jobjectArray references, jobjectArray inReplyTo) {
    std::vector<int64_t> uid;
    if (!takeLongs(env, uids, &uid)) return nullptr;
    int n = static_cast<int>(uid.size());
    std::vector<std::string> mid;
    std::vector<std::string> refs;
    std::vector<std::string> reply;
    if (!takeStrings(env, messageId, n, &mid) || !takeStrings(env, references, n, &refs)
        || !takeStrings(env, inReplyTo, n, &reply)) {
        return nullptr;
    }
    std::vector<const char *> a(static_cast<size_t>(n));
    std::vector<const char *> b(static_cast<size_t>(n));
    std::vector<const char *> c(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) {
        a[static_cast<size_t>(i)] = mid[static_cast<size_t>(i)].c_str();
        b[static_cast<size_t>(i)] = refs[static_cast<size_t>(i)].c_str();
        c[static_cast<size_t>(i)] = reply[static_cast<size_t>(i)].c_str();
    }
    CoFlat flat = n == 0 ? CoFlat() : co_thread_references(uid.data(), a.data(), b.data(), c.data(), n);
    return newThreadFlat(env, flat);
}

namespace {
// Filter-chain criteria for SORT/THREAD: a group argument (IndexModel.groupArgument) or null for ALL. Sets *bad and
// throws when malformed.
struct mailimap_search_key * chainKey(JNIEnv * env, jstring criteria, bool * utf8, bool * bad) {
    *utf8 = false;
    *bad = false;
    if (criteria == nullptr) return nullptr;
    JChars text(env, criteria);
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(text.c()); p != nullptr && *p != 0; ++p) {
        if (*p >= 0x80) { *utf8 = true; break; }
    }
    // filterChainGroup text: \\n between steps, \\t between fields, parsed at depth 0. Two top-level Group steps both parse.
    struct mailimap_search_key * key = groupKeySep(env, text.c(), 0, '\n', '\t');
    if (key == nullptr || env->ExceptionCheck()) {
        if (key != nullptr) mailimap_search_key_free(key);
        *bad = true;
        return nullptr;
    }
    return key;
}
}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSort(JNIEnv * env, jobject, jlong handle,
    jstring keyName, jboolean newestFirst, jboolean useEsort, jstring criteria) {
    jstring g_keyName = keyName != nullptr ? static_cast<jstring>(env->NewGlobalRef(keyName)) : nullptr;
    jstring g_criteria = criteria != nullptr ? static_cast<jstring>(env->NewGlobalRef(criteria)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_keyName, g_criteria};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        jstring keyName = g_keyName;
        jstring criteria = g_criteria;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    bool utf8 = false, bad = false;
    struct mailimap_search_key * crit = chainKey(env, criteria, &utf8, &bad);
    if (bad) {
        if (crit != nullptr) mailimap_search_key_free(crit);
        unlockSession(session);
        return nullptr;
    }
    // SORT/THREAD always name a charset; after ENABLE UTF8=ACCEPT only UTF-8 is valid (RFC 6855 3, courier).
    utf8 = utf8 || session->utf8;
    struct KeyHolder { struct mailimap_search_key * k; ~KeyHolder() { if (k != nullptr) mailimap_search_key_free(k); } } held{crit};
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
        int r = sendUidSortChoice(session->imap, name.c(), reverse, esort, &response, "ALL", crit, utf8);
        if (r != MAILIMAP_NO_ERROR) {
            throwImap(env, session, r, "sort failed");
            unlockSession(session);
            return nullptr;
        }
        jlongArray arr = nullptr;
        bool ok = false;
        if (esort) {
            std::vector<uint32_t> result = takeEsearch(session->imap);
            ok = taggedOk(response);
            mailimap_response_free(response);
            if (!ok) {
                throwImap(env, session, r, "sort failed");
                unlockSession(session);
                return nullptr;
            }
            arr = uidArray(env, result);
        } else {
            clist * result = takeSort(session->imap);
            ok = taggedOk(response);
            mailimap_response_free(response);
            if (!ok) {
                if (result != nullptr) mailimap_sort_result_free(result);
                throwImap(env, session, r, "sort failed");
                unlockSession(session);
                return nullptr;
            }
            arr = uidArray(env, result);
            if (result != nullptr) mailimap_sort_result_free(result);
        }
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
    struct mailimap_search_key * all = crit != nullptr ? nullptr : mailimap_search_key_new_all();
    clist * result = nullptr;
    int r = mailimap_uid_sort(session->imap, utf8 ? "UTF-8" : "US-ASCII", key, crit != nullptr ? crit : all, &result);
    mailimap_sort_key_free(key);
    if (all != nullptr) mailimap_search_key_free(all);
    if (!cmdOk(r)) {
        throwImap(env, session, r, "sort failed");
        unlockSession(session);
        return nullptr;
    }
    jlongArray arr = uidArray(env, result);
    if (result != nullptr) mailimap_sort_result_free(result);
    unlockSession(session);
    return arr;
    });
}

namespace {
// [count, uid...] for a PARTIAL window; count -1 if the server sent none.
jlongArray windowArray(JNIEnv * env, jlong count, const std::vector<uint32_t> & list) {
    std::vector<jlong> values;
    values.reserve(list.size() + 1);
    values.push_back(count);
    for (uint32_t uid : list) values.push_back(static_cast<jlong>(uid));
    jlongArray arr = env->NewLongArray(static_cast<jsize>(values.size()));
    if (arr != nullptr && !values.empty()) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(values.size()), values.data());
    }
    return arr;
}

std::string partialReturn(jint first, jint last) {
    return "PARTIAL " + std::to_string(first) + ":" + std::to_string(last) + " COUNT";
}
}  // namespace

// UID SORT RETURN (PARTIAL first:last COUNT): one window of the sorted order plus the total (ESORT + PARTIAL).
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSortWindow(JNIEnv * env, jobject, jlong handle,
    jstring keyName, jboolean newestFirst, jint first, jint last, jstring criteria) {
    jstring g_keyName = keyName != nullptr ? static_cast<jstring>(env->NewGlobalRef(keyName)) : nullptr;
    jstring g_criteria = criteria != nullptr ? static_cast<jstring>(env->NewGlobalRef(criteria)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_keyName, g_criteria};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        jstring keyName = g_keyName;
        jstring criteria = g_criteria;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    bool utf8 = false, bad = false;
    struct mailimap_search_key * crit = chainKey(env, criteria, &utf8, &bad);
    if (bad) {
        if (crit != nullptr) mailimap_search_key_free(crit);
        unlockSession(session);
        return nullptr;
    }
    // SORT/THREAD always name a charset; after ENABLE UTF8=ACCEPT only UTF-8 is valid (RFC 6855 3, courier).
    utf8 = utf8 || session->utf8;
    struct KeyHolder { struct mailimap_search_key * k; ~KeyHolder() { if (k != nullptr) mailimap_search_key_free(k); } } held{crit};
    JChars name(env, keyName);
    std::string ret = partialReturn(first, last);
    if (!validPartialReturn(ret.c_str())) {
        throwFailure(env, "bad window");
        unlockSession(session);
        return nullptr;
    }
    session->esearchCount = -1;
    tlsEsearchExtCount = -1;
    tlsLive = session;
    struct mailimap_response * response = nullptr;
    int r = sendUidSortChoice(session->imap, name.c(), newestFirst == JNI_TRUE, true, &response, ret.c_str(), crit, utf8);
    tlsLive = nullptr;
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session, r, "sort failed");
        unlockSession(session);
        return nullptr;
    }
    std::vector<uint32_t> result = takeEsearch(session->imap);
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) {
        throwImap(env, session, r, "sort failed");
        unlockSession(session);
        return nullptr;
    }
    jlongArray arr = windowArray(env, session->esearchCount >= 0 ? session->esearchCount : tlsEsearchExtCount, result);
    unlockSession(session);
    return arr;
    });
}

// UID SEARCH RETURN (PARTIAL first:last COUNT) for one criterion; negative positions count from the newest.
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSearchCriterionWindow(JNIEnv * env, jobject, jlong handle,
    jstring kind, jstring argument, jboolean withCharset, jint first, jint last) {
    jstring g_kind = kind != nullptr ? static_cast<jstring>(env->NewGlobalRef(kind)) : nullptr;
    jstring g_argument = argument != nullptr ? static_cast<jstring>(env->NewGlobalRef(argument)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_kind, g_argument};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlongArray {
        jstring kind = g_kind;
        jstring argument = g_argument;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    JChars kindChars(env, kind);
    JChars argChars(env, argument);
    std::string ret = partialReturn(first, last);
    struct mailimap_search_key * key = criterionKey(env, kindChars.c(), argChars.c());
    if (env->ExceptionCheck() || key == nullptr || !validPartialReturn(ret.c_str())) {
        if (key != nullptr) mailimap_search_key_free(key);
        if (!env->ExceptionCheck()) throwFailure(env, "search failed");
        unlockSession(session);
        return nullptr;
    }
    session->esearchCount = -1;
    tlsEsearchExtCount = -1;
    tlsLive = session;
    struct mailimap_response * response = nullptr;
    int r = sendEsearch(session->imap, true, ret.c_str(), withCharset == JNI_TRUE, false, key, &response);
    tlsLive = nullptr;
    mailimap_search_key_free(key);
    if (r != MAILIMAP_NO_ERROR) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return nullptr;
    }
    std::vector<uint32_t> result = takeEsearch(session->imap);
    bool ok = taggedOk(response);
    mailimap_response_free(response);
    if (!ok) {
        throwImap(env, session, r, "search failed");
        unlockSession(session);
        return nullptr;
    }
    jlongArray arr = windowArray(env, session->esearchCount >= 0 ? session->esearchCount : tlsEsearchExtCount, result);
    unlockSession(session);
    return arr;
    });
}

// Iterative preorder of the children. A node without a UID is -1. The synthetic root is not stored.
CoFlat flatFromLive(LiveThread * root) {
    CoFlat flat;
    if (root == nullptr) return flat;
    struct Frame { LiveThread * n; int depth; };
    std::vector<Frame> stack;
    auto pushKids = [&](LiveThread * n, int depth) {
        if (n == nullptr || n->children == nullptr) return;
        std::vector<LiveThread *> kids;
        for (clistiter * cur = clist_begin(n->children); cur != nullptr; cur = clist_next(cur)) {
            auto * child = static_cast<LiveThread *>(clist_content(cur));
            if (child != nullptr) kids.push_back(child);
        }
        for (int i = static_cast<int>(kids.size()) - 1; i >= 0; --i) {
            stack.push_back(Frame{kids[static_cast<size_t>(i)], depth});
        }
    };
    pushKids(root, 0);
    while (!stack.empty()) {
        Frame top = stack.back();
        stack.pop_back();
        flat.uids.push_back(top.n->has_uid ? static_cast<int64_t>(top.n->uid) : static_cast<int64_t>(-1));
        flat.depths.push_back(static_cast<int16_t>(top.depth));
        pushKids(top.n, top.depth + 1);
    }
    return flat;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeThread(JNIEnv * env, jobject, jlong handle, jstring algorithm,
    jstring criteria) {
    jstring g_algorithm = algorithm != nullptr ? static_cast<jstring>(env->NewGlobalRef(algorithm)) : nullptr;
    jstring g_criteria = criteria != nullptr ? static_cast<jstring>(env->NewGlobalRef(criteria)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jstring p1; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); } } ownerRefs{env, g_algorithm, g_criteria};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jobject {
        jstring algorithm = g_algorithm;
        jstring criteria = g_criteria;

    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return nullptr;
    bool utf8 = false, bad = false;
    struct mailimap_search_key * crit = chainKey(env, criteria, &utf8, &bad);
    if (bad) {
        if (crit != nullptr) mailimap_search_key_free(crit);
        unlockSession(session);
        return nullptr;
    }
    // SORT/THREAD always name a charset; after ENABLE UTF8=ACCEPT only UTF-8 is valid (RFC 6855 3, courier).
    utf8 = utf8 || session->utf8;
    struct KeyHolder { struct mailimap_search_key * k; ~KeyHolder() { if (k != nullptr) mailimap_search_key_free(k); } } held{crit};
    JChars alg(env, algorithm);
    if (strcasecmp(alg.c(), "REFERENCES") != 0 && strcasecmp(alg.c(), "ORDEREDSUBJECT") != 0) {
        throwFailure(env, "thread algorithm must be REFERENCES or ORDEREDSUBJECT");
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_response * response = nullptr;
    int r = sendUidThread(session->imap, alg.c(), &response, crit, utf8);
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
    jobject obj = newThreadFlat(env, flatFromLive(tree));
    live_thread_free(tree);
    unlockSession(session);
    return obj;
    });
}

// ENABLE CONDSTORE on a watch connection. Does not touch the session or enable QRESYNC.
void enableCondstoreOn(mailimap * imap) {
    if (imap == nullptr) return;
    clist * list = clist_new();
    if (list == nullptr) return;
    char * owned = strdup("CONDSTORE");
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
    mailimap_enable(imap, data, &result);
    mailimap_capability_data_free(data);
    if (result != nullptr) mailimap_capability_data_free(result);
}

// ENABLE UTF8=ACCEPT on a watch connection. Does not touch the session: the main imap and its utf8 flag stay put.
void enableUtf8On(mailimap * imap) {
    if (imap == nullptr) return;
    clist * list = clist_new();
    if (list == nullptr) return;
    char * owned = strdup("UTF8=ACCEPT");
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
    mailimap_enable(imap, data, &result);
    mailimap_capability_data_free(data);
    if (result != nullptr) mailimap_capability_data_free(result);
}

// Watch-only login: connect and CAPABILITY, no EXAMINE and no IDLE. The Kotlin gate decides.
extern "C" JNIEXPORT jstring JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatchLogin(JNIEnv * env, jobject, jlong handle) {
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jstring {


    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return nullptr;
    if (!session->stagedWatch) {
        unlockSession(session);
        throwFailure(env, "not connected");
        return nullptr;
    }
    const std::string host = session->host;
    const std::string user = session->user;
    std::string password = session->password;
    xorMask(password, session->passwordMask);
    const std::string tlsMode = session->tlsMode;
    const std::string certPin = session->certPin;
    const int port = session->imapPort;
    ImapOpenMaterial owned;
    owned.env = env;
    owned.allowPlaintextAuth = session->allowPlaintextAuth;
    owned.authMechanism = session->authMechanism;
    owned.allowOldTls = session->allowOldTls;
    owned.acceptInvalidCert = session->acceptInvalidCert;
    owned.keyChainChain = session->keyChainChain;
    if (session->keyChainKey != nullptr) owned.keyChainKey = env->NewGlobalRef(session->keyChainKey);
    bindTraffic(&session->watchTraffic, session->trafficDir, "watch", session->trafficRollBytes);
    owned.traffic = &session->watchTraffic;
    owned.connectTimeoutSec = session->connectTimeoutSec;
    owned.readTimeoutSec = session->readTimeoutSec;
    std::string error;
    mailimap * watch = nullptr;
    if (tlsMode == "None") {
        watch = openPlain(env, host.c_str(), port, user.c_str(), password.c_str(), nullptr, &error, nullptr, &owned);
    } else {
        watch = openTls(env, tlsMode.c_str(), host.c_str(), port, user.c_str(), password.c_str(), nullptr, &error, nullptr, certPin.c_str(), false, &owned);
    }
    wipeSecret(password);
    if (watch == nullptr) {
        if (!session->closed.load()) throwFailure(env, error.empty() ? "connection failed" : error);
        unlockSession(session);
        return nullptr;
    }
    struct mailimap_capability_data * caps = nullptr;
    int r = mailimap_capability(watch, &caps);
    if (!cmdOk(r) || caps == nullptr) {
        if (caps != nullptr) mailimap_capability_data_free(caps);
        std::string why = "capability failed: " + imapText(watch, "capability failed");
        mailimap_free(watch);
        if (!session->closed.load()) throwFailure(env, why);
        unlockSession(session);
        return nullptr;
    }
    std::string line = joinCapabilities(caps);
    mailimap_capability_data_free(caps);
    if (session->closed.load()) {
        mailimap_free(watch);
        unlockSession(session);
        return nullptr;
    }
    if (session->stagedReady != nullptr) mailimap_free(session->stagedReady);
    session->stagedReady = watch;
    session->capabilityLine = line;
    jstring out = newString(env, line.c_str());
    unlockSession(session);
    return out;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeUseStagedImap(JNIEnv * env, jobject, jlong handle) {
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {


    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return;
    if (session->imap == nullptr && session->stagedReady != nullptr) {
        session->imap = session->stagedReady;
        session->stagedReady = nullptr;
    }
    unlockSession(session);
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeWatch(JNIEnv * env, jobject thiz, jlong handle, jstring mailbox,
    jobject callback) {
    jobject g_thiz = thiz != nullptr ? static_cast<jobject>(env->NewGlobalRef(thiz)) : nullptr;
    jstring g_mailbox = mailbox != nullptr ? static_cast<jstring>(env->NewGlobalRef(mailbox)) : nullptr;
    jobject g_callback = callback != nullptr ? static_cast<jobject>(env->NewGlobalRef(callback)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jobject p0; jstring p1; jobject p2; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); } } ownerRefs{env, g_thiz, g_mailbox, g_callback};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jlong {
        jobject thiz = g_thiz;
        jstring mailbox = g_mailbox;
        jobject callback = g_callback;

    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return 0;
    if (session->imap == nullptr && !session->stagedWatch) {
        unlockSession(session);
        throwConnectionLost(env, "IMAP connection lost");
        return 0;
    }
    if (tlsWatch == session) {
        signalWatchStop(session);
        unlockSession(session);
        return 0;
    }
    if (session->watch != nullptr || session->watchPhase.load() != 0) finishIdleStop(session);
    JChars mb(env, mailbox);
    if (!wireOk(mb.c())) {  // F42 native backstop
        throwFailure(env, "refused: CR or LF in a mailbox name");
        unlockSession(session);
        return 0;
    }
    std::string error;
    mailimap * watch = nullptr;
    // F13/F136/F261: connect, TLS, login, EXAMINE and the IDLE-start wait run without the session lock.
    // The use count taken by lockSession stays held, so the session cannot be deleted meanwhile.
    // Copied before the unlock: the examine and the watch thread do not read session->imap or the password.
    // F267: auth, certificate, and log fields are owning copies too. openPlain/openTls must not read them
    // from the live session, and must not keep pointers into it.
    const std::string host = session->host;
    const std::string user = session->user;
    std::string password = session->password;
    xorMask(password, session->passwordMask);  // F21: clear only for this login; wiped below
    const std::string tlsMode = session->tlsMode;
    const std::string certPin = session->certPin;
    const int port = session->imapPort;
    const bool utf8 = session->utf8;
    const std::string watchCapabilityLine = session->capabilityLine;
    const std::string mailboxName = mb.c();
    ImapOpenMaterial owned;
    owned.env = env;
    owned.allowPlaintextAuth = session->allowPlaintextAuth;
    owned.authMechanism = session->authMechanism;
    owned.allowOldTls = session->allowOldTls;
    owned.acceptInvalidCert = session->acceptInvalidCert;
    owned.keyChainChain = session->keyChainChain;
    if (session->keyChainKey != nullptr) owned.keyChainKey = env->NewGlobalRef(session->keyChainKey);
    bindTraffic(&session->watchTraffic, session->trafficDir, "watch", session->trafficRollBytes);
    owned.traffic = &session->watchTraffic;
    owned.connectTimeoutSec = session->connectTimeoutSec;
    owned.readTimeoutSec = session->readTimeoutSec;
    const uint64_t generation = bumpWatchGeneration(session);
    // A 0 return must not leave this generation's callback published. Older publishes are cleared too,
    // unless a newer generation has already replaced them.
    auto clearThisWatch = [&]() {
        if (gJni.clearPublishedWatch == nullptr || env->ExceptionCheck()) return;
        env->CallVoidMethod(thiz, gJni.clearPublishedWatch, static_cast<jlong>(generation));
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    mailimap * prepared = nullptr;
    if (session->stagedWatch && session->stagedReady != nullptr) {
        prepared = session->stagedReady;
        session->stagedReady = nullptr;
    }
    if (prepared != nullptr) {
        watch = prepared;
    } else if (tlsMode == "None") {
        watch = openPlain(env, host.c_str(), port, user.c_str(), password.c_str(), nullptr, &error, nullptr, &owned);
    } else {
        watch = openTls(env, tlsMode.c_str(), host.c_str(), port, user.c_str(), password.c_str(), nullptr, &error, nullptr, certPin.c_str(), false, &owned);
    }
    wipeSecret(password);
    if (watchSetupSuperseded(session->closed.load(), session->watchGeneration.load(), generation, session->watch != nullptr)) {
        // closed, or a newer watch/stop ran while this one connected: drop this connection quietly.
        if (watch != nullptr) mailimap_free(watch);
        clearThisWatch();
        unlockSession(session);
        return 0;
    }
    if (watch == nullptr) {
        clearThisWatch();
        throwFailure(env, error);
        unlockSession(session);
        return 0;
    }
    std::string capsForCondstore = watchCapabilityLine;
    if (prepared == nullptr) {
        struct mailimap_capability_data * caps = nullptr;
        int capR = mailimap_capability(watch, &caps);
        if (cmdOk(capR) && caps != nullptr) {
            capsForCondstore = joinCapabilities(caps);
            mailimap_capability_data_free(caps);
        }
    }
    if (utf8) enableUtf8On(watch);
    const bool condstore = capabilityLineOffersCondstore(capsForCondstore);
    uint64_t watchMod = 0;
    int r = condstore
        ? selectFullCapture(watch, mailboxName.c_str(), &watchMod, "EXAMINE", true)
        : mailimap_examine(watch, mailboxName.c_str());
    if (watchSetupSuperseded(session->closed.load(), session->watchGeneration.load(), generation, session->watch != nullptr)) {
        if (watch != nullptr) mailimap_free(watch);
        clearThisWatch();
        unlockSession(session);
        return 0;
    }
    if (!cmdOk(r)) {
        clearThisWatch();
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
        return 0;
    }
    session->watch = watch;
    session->watchStop.store(false);
    session->startState = 0;
    session->startError.clear();
    session->watchGate = 0;
    session->watchQueue.clear();
    session->watchLostBeforePublish = false;
    session->watchEnded = false;
    if (watch->imap_selection_info != nullptr) {
        session->watchUidValidity = watch->imap_selection_info->sel_uidvalidity;
        session->watchExists = static_cast<int>(watch->imap_selection_info->sel_exists);
    }
    if (session->selfGlobal != nullptr) env->DeleteGlobalRef(session->selfGlobal);
    session->selfGlobal = env->NewGlobalRef(thiz);
    if (session->watchWakeFd < 0) session->watchWakeFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    int started = beginWatchIdle(session) ? 1 : -1;
    if (session->watchStop.load() || session->closed.load() || session->watchGeneration.load() != generation) started = 0;
    const bool superseded = watchSetupSuperseded(session->closed.load(), session->watchGeneration.load(), generation, false);
    bool lostEarly = session->watchLostBeforePublish || session->watchEnded;
    // Publish only after IDLE has started. A dead IDLE is not published. A newer publish wins in Kotlin.
    if (started > 0 && !superseded && !lostEarly) {
        if (gJni.publishWatch != nullptr && callback != nullptr) {
            env->CallVoidMethod(thiz, gJni.publishWatch, static_cast<jlong>(generation), callback);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    bool deliver = false;
    bool lost = session->watchLostBeforePublish || session->watchEnded;
    if (superseded || started <= 0 || lost) {
        session->watchGate = 2;
        session->watchQueue.clear();
    } else {
        session->watchGate = 1;
        deliver = true;
    }
    if (deliver && session->watchWakeFd >= 0) {
        uint64_t one = 1;
        ssize_t ignored = write(session->watchWakeFd, &one, sizeof(one));
        (void)ignored;
    }
    // This generation is not a live IDLE. Clear it. A stop bumps the generation, so a newer publish stays.
    // An early WatchLost or a thread that already ended is joined and freed like a failed start, without throwFailure.
    if (!deliver) {
        if (!superseded && (started < 0 || lost)) {
            std::string why = session->startError;
            finishIdleStop(session);
            clearThisWatch();
            if (started < 0) throwFailure(env, why);
            unlockSession(session);
            return 0;
        }
        clearThisWatch();
        unlockSession(session);
        return 0;
    }
    unlockSession(session);
    return static_cast<jlong>(generation);
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeStopWatch(JNIEnv *, jobject, jlong handle) {
    if (tlsOwner != nullptr && reinterpret_cast<jlong>(tlsOwner) == handle) {
        tlsOwner->watchPhase.store(3);
        signalWatchStop(tlsOwner);
        kickOwner(tlsOwner);
        return 0;
    }
    auto * session = reinterpret_cast<LiveSession *>(handle);
    std::shared_ptr<LiveSession> kept = retainSession(session);
    if (!kept) return 0;
    const uint64_t generation = bumpWatchGeneration(session);
    session->watchPhase.store(3);
    signalWatchStop(session);
    kickOwner(session);
    for (;;) {
        if (session->closed.load() || session->ownerStop.load()) break;
        int phase = session->watchPhase.load();
        if (phase == 0) break;
        session->watchPhase.wait(phase);
    }
    releaseUsers(session);
    return static_cast<jlong>(generation);
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

// EHLO keyword (whole word after the 250- / 250 code libetpan strips), e.g. SMTPUTF8.
bool smtpOffers(const char * response, const char * keyword) {
    if (response == nullptr) return false;
    size_t k = strlen(keyword);
    const char * p = response;
    while (*p != '\0') {
        const char * line = p;
        while (*p != '\0' && *p != '\n' && *p != '\r') p++;
        size_t n = static_cast<size_t>(p - line);
        while (*p == '\n' || *p == '\r') p++;
        if (n >= k && strncasecmp(line, keyword, k) == 0 && (n == k || line[k] == ' ')) return true;
    }
    return false;
}

bool has8bit(const char * s, size_t n) {
    for (size_t i = 0; i < n; ++i) if (static_cast<unsigned char>(s[i]) >= 0x80) return true;
    return false;
}

// Bytes after the header blank line. A Content-Transfer-Encoding line in the body is not a body byte.
bool bodyHas8bit(const char * raw, size_t n) {
    for (size_t i = 0; i + 1 < n; ++i) {
        if (raw[i] != '\n') continue;
        if (raw[i + 1] == '\n') return has8bit(raw + i + 2, n - (i + 2));
        if (raw[i + 1] == '\r' && i + 2 < n && raw[i + 2] == '\n') return has8bit(raw + i + 3, n - (i + 3));
    }
    return false;
}

// Header section only (up to the first empty line).
bool headerHas8bit(const char * raw, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        unsigned char c = static_cast<unsigned char>(raw[i]);
        if (c >= 0x80) return true;
        if (c == '\n' && (i + 1 < n) && (raw[i + 1] == '\n' || (raw[i + 1] == '\r' && i + 2 < n && raw[i + 2] == '\n'))) return false;
    }
    return false;
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

// Not in libetpan's public headers, but exported: raw SMTP command and multi-line reply (returns the code).
extern "C" int mailsmtp_send_command(mailsmtp * f, char * command);
extern "C" int mailsmtp_read_response(mailsmtp * session);

// AUTH EXTERNAL = (RFC 4954 initial response "=": empty authorization identity) on a TLS connection that
// presented a client certificate; the server takes the identity from the certificate. Sent directly because
// Cyrus SASL's client EXTERNAL needs an external SSF/authid set up we cannot pass through mailesmtp_auth_sasl.
int smtpAuthExternal(mailsmtp * smtp) {
    char command[] = "AUTH EXTERNAL =\r\n";
    int r = mailsmtp_send_command(smtp, command);
    if (r == -1) return MAILSMTP_ERROR_STREAM;
    int code = mailsmtp_read_response(smtp);
    if (code == 235) return MAILSMTP_NO_ERROR;
    if (code == 0) return MAILSMTP_ERROR_STREAM;
    return MAILSMTP_ERROR_AUTH_LOGIN;
}

bool chooseSmtpAuth(JNIEnv * env, const std::string & offered, bool tls, bool plaintextOk,
    bool channelBinding, bool clientCert, std::string * mechanism) {
    mechanism->clear();
    jclass cls = env->FindClass("org/dlang/liveimap/session/ImapAuthKt");
    if (cls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (cls != nullptr) env->DeleteLocalRef(cls);
        return false;
    }
    jmethodID method = env->GetStaticMethodID(cls, "chooseImapAuth",
        "(Ljava/lang/String;ZZZLjava/lang/String;Z)Ljava/lang/String;");
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
    jstring jclient = newString(env, saslClientMechs().c_str());
    jvalue args[6];
    args[0].l = jline;
    args[1].z = tls ? JNI_TRUE : JNI_FALSE;
    args[2].z = plaintextOk ? JNI_TRUE : JNI_FALSE;
    args[3].z = channelBinding ? JNI_TRUE : JNI_FALSE;
    args[4].l = jclient;  // was missing: an uninitialized jobject aborted the VM on the first SMTP AUTH
    // A client certificate went out on this TLS connection: EXTERNAL first (sent directly, smtpAuthExternal).
    args[5].z = tls && clientCert ? JNI_TRUE : JNI_FALSE;
    jobject result = env->CallStaticObjectMethodA(cls, method, args);
    env->DeleteLocalRef(jline);
    if (jclient != nullptr) env->DeleteLocalRef(jclient);
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (result != nullptr) env->DeleteLocalRef(result);
        return false;
    }
    if (result != nullptr) {
        auto got = utf8FromJava(env, static_cast<jstring>(result));
        env->DeleteLocalRef(result);
        if (!got) return false;
        *mechanism = std::move(*got);
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

// SMTP envelope addresses go raw into MAIL FROM / RCPT TO: refuse controls (CR/LF would inject commands),
// DEL, angle brackets, and spaces outside a quoted local part. Empty is allowed only for MAIL FROM (<>).
static bool envelopeAddressOk(const char * a) {
    if (a == nullptr) return false;
    bool quoted = false;
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(a); *p != 0; ++p) {
        unsigned char c = *p;
        if (c < 0x20 || c == 0x7f || c == '<' || c == '>') return false;
        if (c == '"') quoted = !quoted;
        else if (c == ' ' && !quoted) return false;
        else if (c == '\\' && quoted && p[1] != 0) ++p;
    }
    return !quoted;
}

std::string smtpTlsModeFor(const std::string & imapMode, int smtpPort, const std::string & smtpMode) {
    if (smtpMode == "None" || smtpMode == "StartTls" || smtpMode == "Implicit") return smtpMode;
    if (imapMode == "None") return imapMode;
    if (imapMode != "StartTls" && imapMode != "Implicit") return imapMode;
    return smtpPort == 465 ? "Implicit" : "StartTls";
}

// False when the session already closed: the fd is shut so the rest of the conversation cannot proceed.
bool rememberSmtpSocket(LiveSession * session, int fd) {
    if (session == nullptr) return false;
    if (fd >= 0) session->smtpFd.store(fd);
    if (session->closed.load()) {
        if (fd >= 0) shutdown(fd, SHUT_RDWR);
        session->smtpFd.store(-1);
        return false;
    }
    return true;
}

// Releases the use count when the SMTP conversation returns.
struct SmtpHold {
    LiveSession * session = nullptr;
    SmtpHold() = default;
    SmtpHold(const SmtpHold &) = delete;
    SmtpHold & operator=(const SmtpHold &) = delete;
    void release() {
        if (session == nullptr) return;
        LiveSession * held = session;
        session = nullptr;
        unlockSession(held);
    }
    ~SmtpHold() { release(); }
};

// Drops the published dup out of smtpFd. Does not close it and does not shut it down.
int takeSmtpFd(LiveSession * session) {
    int owned = -1;
    if (session == nullptr) return owned;
    owned = session->smtpFd.exchange(-1);
    return owned;
}

void freeSmtp(LiveSession * session, mailsmtp * smtp) {
    int owned = -1;
    if (session != nullptr) owned = session->smtpFd.exchange(-1);
    if (smtp != nullptr) mailsmtp_free(smtp);
    if (owned >= 0) close(owned);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeSmtp(JNIEnv * env, jobject, jlong handle,
    jstring message, jobjectArray recipients, jstring smtpUsername, jstring smtpPassword, jint dsnNotify, jstring envelopeFrom) {
    jstring g_message = message != nullptr ? static_cast<jstring>(env->NewGlobalRef(message)) : nullptr;
    jobjectArray g_recipients = recipients != nullptr ? static_cast<jobjectArray>(env->NewGlobalRef(recipients)) : nullptr;
    jstring g_smtpUsername = smtpUsername != nullptr ? static_cast<jstring>(env->NewGlobalRef(smtpUsername)) : nullptr;
    jstring g_smtpPassword = smtpPassword != nullptr ? static_cast<jstring>(env->NewGlobalRef(smtpPassword)) : nullptr;
    jstring g_envelopeFrom = envelopeFrom != nullptr ? static_cast<jstring>(env->NewGlobalRef(envelopeFrom)) : nullptr;
    struct OwnerRefs { JNIEnv * e; jstring p0; jobjectArray p1; jstring p2; jstring p3; jstring p4; ~OwnerRefs() { if (p0) e->DeleteGlobalRef(p0); if (p1) e->DeleteGlobalRef(p1); if (p2) e->DeleteGlobalRef(p2); if (p3) e->DeleteGlobalRef(p3); if (p4) e->DeleteGlobalRef(p4); } } ownerRefs{env, g_message, g_recipients, g_smtpUsername, g_smtpPassword, g_envelopeFrom};
    return withOwnerCall(env, handle, [&](JNIEnv * env) -> jint {
        jstring message = g_message;
        jobjectArray recipients = g_recipients;
        jstring smtpUsername = g_smtpUsername;
        jstring smtpPassword = g_smtpPassword;
        jstring envelopeFrom = g_envelopeFrom;

    LiveSession * session = lockSession(env, handle);
    if (session == nullptr) return 0;
    SmtpHold hold;
    hold.session = session;
    // F52: no silent fallback to the IMAP login here. Kotlin passes the IMAP login explicitly only when the SMTP
    // host is the same host or registrable domain (Public Suffix List, smtpMayReuseImapLogin).
    auto authUserGot = utf8FromJava(env, smtpUsername);
    auto authPassGot = utf8FromJava(env, smtpPassword);
    if (!authUserGot || !authPassGot) {
        if (authPassGot) wipeSecret(*authPassGot);
        return 0;
    }
    std::string authUser = std::move(*authUserGot);
    std::string authPass = std::move(*authPassGot);
    SecretWipe authPassWipe{authPass};
    if (authPass.empty()) authUser.clear();
    // MAIL FROM: the message's From when compose chose it (a plus alias or a configured From), else the account.
    auto mailFromGot = utf8FromJava(env, envelopeFrom);
    if (!mailFromGot) return 0;
    std::string mailFrom = std::move(*mailFromGot);
    if (mailFrom.empty()) mailFrom = session->from;
    {
        bool ok = envelopeAddressOk(mailFrom.c_str());
        jsize nr = recipients != nullptr ? env->GetArrayLength(recipients) : 0;
        for (jsize i = 0; i < nr && ok; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(recipients, i));
            {
                JChars rc(env, js);
                ok = rc.c() != nullptr && rc.c()[0] != 0 && envelopeAddressOk(rc.c());
            }
            if (js != nullptr) env->DeleteLocalRef(js);
        }
        if (!ok) {
            throwFailure(env, "bad envelope address");
            return 0;
        }
    }
    // A stored SMTP mode wins. Otherwise the IMAP mode, adjusted to the SMTP port.
    const std::string mode = smtpTlsModeFor(session->tlsMode, session->smtpPort, session->smtpTlsMode);
    if (mode != "None" && mode != "StartTls" && mode != "Implicit") {
        throwFailure(env, "unknown tls mode");
        return 0;
    }
    // Owning copies. The conversation below must not read these from the live session or keep pointers into it.
    const std::string smtpHost = session->smtpHost;
    const int port = session->smtpPort;
    TrafficFile smtpLog;
    smtpLog.dir = session->trafficDir;
    smtpLog.conn = "smtp";
    smtpLog.rollBytes = session->trafficRollBytes;
    const int connectSec = session->connectTimeoutSec > 0 ? session->connectTimeoutSec : 30;
    const int readSec = session->readTimeoutSec > 0 ? session->readTimeoutSec : 60;
    const bool allowOldTls = session->allowOldTls;
    const bool allowPlaintextAuth = session->allowPlaintextAuth;
    const bool acceptInvalidCert = session->acceptInvalidCert;
    const std::string certPin = session->certPin;
    const std::string imapHost = session->host;
    std::vector<std::string> keyChainChain = session->keyChainChain;
    jobject keyChainKey = session->keyChainKey != nullptr ? env->NewGlobalRef(session->keyChainKey) : nullptr;
    struct KeyRef {
        JNIEnv * env;
        jobject ref;
        ~KeyRef() { if (ref != nullptr && env != nullptr) env->DeleteGlobalRef(ref); }
    } keyRef{env, keyChainKey};
    mailsmtp * smtp = mailsmtp_new(0, nullptr);
    if (smtp == nullptr) {
        throwFailure(env, smtpDetail("SMTP", MAILSMTP_ERROR_MEMORY, nullptr));
        return 0;
    }
    // Traffic file for this SMTP conversation and recording as IMAP, connection id "smtp"; AUTH goes out as private data.
    if (!smtpLog.dir.empty()) mailsmtp_set_logger(smtp, smtpFileLogger, &smtpLog);
    mailsmtp_set_timeout(smtp, readSec);
    const char * host = smtpHost.c_str();
    std::string address;
    std::string where;
    TlsCapture capture;
    capture.allowOldTls = allowOldTls;
    capture.keyChainChain = &keyChainChain;
    capture.keyChainKey = keyChainKey;
    bool secured = false;
    // smtpFd is a dup this code owns. The library may close its own number.
    int smtpOwned = -1;
    // Network waits run on the owner. Close shuts smtpFd down without waiting behind this call's read.
    if (mode == "Implicit") {
        address = smtpHost;
        if (port < 1 || port > 65535) {
            freeSmtp(session, smtp);
            throwFailure(env, connectFailure("SMTP", host, port, address, "connection failed"));
            return 0;
        }
        std::string dialError;
        int fd = tcpConnect("SMTP", host, port, &address, &dialError, connectSec);
        if (fd < 0) {
            freeSmtp(session, smtp);
            throwFailure(env, dialError);
            return 0;
        }
        // Publish a dup before the handshake so close can shut this socket down during TLS.
        smtpOwned = dup(fd);
        if (smtpOwned < 0) {
            close(fd);
            freeSmtp(session, smtp);
            throwFailure(env, connectFailure("SMTP", host, port, address, "connection failed"));
            return 0;
        }
        if (!rememberSmtpSocket(session, smtpOwned)) {
            close(smtpOwned);
            smtpOwned = -1;
            freeSmtp(session, smtp);
            close(fd);
            throwFailure(env, "not connected");
            return 0;
        }
        tlsCapture = &capture;
        mailstream * stream = mailstream_ssl_open_with_server_name_callback_timeout(
            fd, readSec, sniName(host), prepareTls, nullptr);
        tlsCapture = nullptr;
        if (stream == nullptr) {
            freeSmtp(session, smtp);
            close(fd);
            throwFailure(env, connectFailure("SMTP", host, port, address, "connection failed"));
            return 0;
        }
        where = serviceLabel("SMTP", host, port) + " (" + address + "): ";
        int r = mailsmtp_connect(smtp, stream);
        if (r != MAILSMTP_NO_ERROR) {
            // The library already closed its fd. Copy the response, then drop the dup. Do not shut that number down.
            std::string response = asciiSafe(smtp->response, "connection failed");
            int owned = takeSmtpFd(session);
            if (owned >= 0) close(owned);
            freeSmtp(session, smtp);
            throwFailure(env, connectFailure("SMTP", host, port, address, response));
            return 0;
        }
        if (capture.ders.empty()) {
            freeSmtp(session, smtp);
            throwFailure(env, "certificate rejected");
            return 0;
        }
        secured = true;
    } else {
        std::string dialError;
        int fd = tcpConnect("SMTP", host, port, &address, &dialError, connectSec);
        if (fd < 0) {
            freeSmtp(session, smtp);
            throwFailure(env, dialError);
            return 0;
        }
        // Publish a dup before the greeting so close can shut this socket down while it is read.
        smtpOwned = dup(fd);
        if (smtpOwned < 0) {
            close(fd);
            freeSmtp(session, smtp);
            throwFailure(env, connectFailure("SMTP", host, port, address, "connection failed"));
            return 0;
        }
        if (!rememberSmtpSocket(session, smtpOwned)) {
            close(smtpOwned);
            smtpOwned = -1;
            freeSmtp(session, smtp);
            close(fd);
            throwFailure(env, "not connected");
            return 0;
        }
        mailstream * stream = mailstream_socket_open_timeout(fd, readSec);
        if (stream == nullptr) {
            freeSmtp(session, smtp);
            close(fd);
            throwFailure(env, connectFailure("SMTP", host, port, address, "connection failed"));
            return 0;
        }
        where = serviceLabel("SMTP", host, port) + " (" + address + "): ";
        int r = mailsmtp_connect(smtp, stream);
        if (r != MAILSMTP_NO_ERROR) {
            // The library already closed its fd. Copy the response, then drop the dup. Do not shut that number down.
            std::string response = asciiSafe(smtp->response, "connection failed");
            int owned = takeSmtpFd(session);
            if (owned >= 0) close(owned);
            freeSmtp(session, smtp);
            throwFailure(env, where + response);
            return 0;
        }
        if (mode == "StartTls") {
            r = mailesmtp_ehlo(smtp);
            if (r == MAILSMTP_NO_ERROR) {
                if ((smtp->esmtp & MAILSMTP_ESMTP_STARTTLS) == 0) {
                    freeSmtp(session, smtp);
                    throwFailure(env, "server does not offer STARTTLS");
                    return 0;
                }
            } else if (r == MAILSMTP_ERROR_NOT_IMPLEMENTED ||
                r == MAILSMTP_ERROR_UNEXPECTED_CODE ||
                r == MAILSMTP_ERROR_ACTION_NOT_TAKEN) {
                freeSmtp(session, smtp);
                throwFailure(env, "server does not offer STARTTLS");
                return 0;
            } else {
                std::string why = where + smtpDetail("EHLO", r, smtp->response);
                freeSmtp(session, smtp);
                throwFailure(env, why);
                return 0;
            }
            tlsCapture = &capture;
            r = mailsmtp_socket_starttls_with_server_name_callback(smtp, sniName(host), prepareTls, nullptr);
            tlsCapture = nullptr;
            if (r == MAILSMTP_ERROR_STARTTLS_NOT_SUPPORTED) {
                freeSmtp(session, smtp);
                throwFailure(env, "server does not offer STARTTLS");
                return 0;
            }
            if (r != MAILSMTP_NO_ERROR) {
                std::string response = asciiSafe(smtp->response, "");
                std::string why = response.empty() ? std::string("STARTTLS failed")
                    : std::string("STARTTLS failed: ") + response;
                freeSmtp(session, smtp);
                throwFailure(env, why);
                return 0;
            }
            if (!rememberSmtpSocket(session, smtpOwned)) {
                freeSmtp(session, smtp);
                throwFailure(env, "not connected");
                return 0;
            }
            if (capture.ders.empty()) {
                freeSmtp(session, smtp);
                throwFailure(env, "certificate rejected");
                return 0;
            }
            secured = true;
        }
    }
    if (secured) {
        const char * pin = "";
        // F52/F139: the IMAP pin and the IMAP "accept invalid certificate" belong to the IMAP host; a separately
        // configured SMTP host is verified on its own.
        const bool imapHostToo = !imapHost.empty() && !smtpHost.empty() &&
            strcasecmp(imapHost.c_str(), host) == 0;
        if (imapHostToo) pin = certPin.c_str();
        std::string trust = peerTrustCheck(env, host, capture.ders, pin, false);
        if (!trust.empty() && imapHostToo && acceptInvalidCert && certificateVerdict(trust) && trust != "certificate changed") {
            trust.clear();
        }
        if (!trust.empty()) {
            freeSmtp(session, smtp);
            throwFailure(env, trust);
            return 0;
        }
    }
    bool allow8bit = false;
    bool smtpUtf8 = false;
    bool dsnOffered = false;
    std::string offeredAuth;
    int r = mailesmtp_ehlo(smtp);
    if (r == MAILSMTP_NO_ERROR) {
        allow8bit = (smtp->esmtp & MAILSMTP_ESMTP_8BITMIME) != 0;
        smtpUtf8 = smtpOffers(smtp->response, "SMTPUTF8");
        dsnOffered = (smtp->esmtp & MAILSMTP_ESMTP_DSN) != 0;
        offeredAuth = smtpAuthOffered(smtp->response);
    } else if (r == MAILSMTP_ERROR_NOT_IMPLEMENTED ||
        r == MAILSMTP_ERROR_UNEXPECTED_CODE ||
        r == MAILSMTP_ERROR_ACTION_NOT_TAKEN) {
        r = mailsmtp_helo(smtp);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = where + smtpDetail("HELO", r, smtp->response);
            freeSmtp(session, smtp);
            throwFailure(env, why);
            return 0;
        }
    } else {
        std::string why = where + smtpDetail("EHLO", r, smtp->response);
        freeSmtp(session, smtp);
        throwFailure(env, why);
        return 0;
    }
    const bool smtpTls = mode == "StartTls" || mode == "Implicit";
    unsigned char binding[64];
    size_t bindingLen = 0;
    const char * bindingName = nullptr;
    if (smtpTls) {
        fillTlsChannelBinding(smtp->stream, binding, &bindingLen, &bindingName);
    }
    std::string mechanism;
    if (!chooseSmtpAuth(env, offeredAuth, smtpTls, allowPlaintextAuth,
            bindingLen > 0, gClientCertLoaded, &mechanism)) {
        freeSmtp(session, smtp);
        throwFailure(env, "auth choice failed");
        return 0;
    }
    // F52 (David): no SMTP password configured = send without AUTH (existing accounts work this way). If the
    // server insists on AUTH, MAIL FROM fails and the message says how to set the password.
    const bool noCredentials = authPass.empty() && mechanism != "EXTERNAL";
    if (noCredentials) mechanism.clear();
    bool plus = mechanism.size() >= 5
        && mechanism.compare(mechanism.size() - 5, 5, "-PLUS") == 0;
    if (plus && bindingLen > 0) {
        int br = mailsmtp_set_channel_binding(smtp, bindingName, binding, bindingLen);
        if (br != 0) {
            freeSmtp(session, smtp);
            throwFailure(env, "channel binding failed");
            return 0;
        }
    } else {
        mailsmtp_set_channel_binding(smtp, nullptr, nullptr, 0);
    }
    bool authOk = false;
    if (mechanism == "EXTERNAL") {
        r = smtpAuthExternal(smtp);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = smtpDetail("SMTP AUTH EXTERNAL", r, smtp->response);
            if (!gClientCertError.empty()) why += " (" + gClientCertError + ")";
            freeSmtp(session, smtp);
            throwFailure(env, why);
            return 0;
        }
        authOk = true;
    } else if (!mechanism.empty()) {
        if (authUser.empty()) {
            freeSmtp(session, smtp);
            throwFailure(env, "SMTP server needs a password (the IMAP password is only reused for the same provider)");
            return 0;
        }
        r = mailesmtp_auth_sasl(smtp, mechanism.c_str(), smtpHost.c_str(), nullptr,
            nullptr, "",  // authzid, as for IMAP
            authUser.c_str(), authPass.c_str(),
            nullptr);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = smtpDetail("AUTH", r, smtp->response);
            freeSmtp(session, smtp);
            throwFailure(env, why);
            return 0;
        }
        authOk = true;
    } else if (!offeredAuth.empty() && !noCredentials) {
        if (!authUser.empty() && !smtpTls && !allowPlaintextAuth &&
            offeredPlainOrLogin(offeredAuth)) {
            freeSmtp(session, smtp);
            throwFailure(env, "The password was not sent.");
            return 0;
        }
        freeSmtp(session, smtp);
        throwFailure(env, "no usable SMTP authentication");
        return 0;
    }
    if (message == nullptr) {
        // Test account (David, 2026-10-12): connect, TLS, EHLO and AUTH only; nothing is sent. QUIT.
        int owned = takeSmtpFd(session);
        mailsmtp_quit(smtp);
        if (owned >= 0) close(owned);
        freeSmtp(session, smtp);
        if (authOk) return 2;
        if (!authUser.empty() && offeredAuth.empty()) return 3;
        return offeredAuth.empty() ? 3 : 4;
    }
    auto path = utf8FromJava(env, message);
    if (!path) {
        freeSmtp(session, smtp);
        return 0;
    }
    std::vector<char> messageBytes;
    if (!loadMessageFile(*path, messageBytes)) {
        freeSmtp(session, smtp);
        throwFailure(env, "could not read the message file");
        return 0;
    }
    const char * raw = messageBytes.empty() ? nullptr : messageBytes.data();
    size_t nmsg = messageBytes.size();
    // SMTPUTF8 before 8BITMIME: a body rewrite cannot fix raw UTF-8 headers or addresses.
    // RFC 6531: non-ASCII addresses or raw UTF-8 headers need SMTPUTF8; without it, refuse before MAIL FROM
    // ("smtputf8") so the user is told instead of the server mangling or bouncing the message.
    bool needUtf8 = has8bit(mailFrom.data(), mailFrom.size()) ||
        (raw != nullptr && headerHas8bit(reinterpret_cast<const char *>(raw), static_cast<size_t>(nmsg)));
    {
        jsize nr = recipients != nullptr ? env->GetArrayLength(recipients) : 0;
        for (jsize i = 0; i < nr && !needUtf8; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(recipients, i));
            {
                JChars rc(env, js);
                if (rc.c() != nullptr && has8bit(rc.c(), strlen(rc.c()))) needUtf8 = true;
            }
            if (js != nullptr) env->DeleteLocalRef(js);
        }
    }
    if (needUtf8 && !smtpUtf8) {
        freeSmtp(session, smtp);
        throwFailure(env, "smtputf8");
        return 0;
    }
    if (!allow8bit && raw != nullptr &&
        messageHas8bitCte(reinterpret_cast<const char *>(raw), static_cast<size_t>(nmsg))) {
        freeSmtp(session, smtp);
        throwFailure(env, "8bitmime");
        return 0;
    }
    bool declare8bit = false;
    if (raw != nullptr) {
        const char * bytes = reinterpret_cast<const char *>(raw);
        size_t len = static_cast<size_t>(nmsg);
        declare8bit = messageHas8bitCte(bytes, len) || bodyHas8bit(bytes, len);
    }
    const char * body8 = (declare8bit && allow8bit) ? " BODY=8BITMIME" : "";
    if (needUtf8) {
        std::string cmd = "MAIL FROM:<" + mailFrom + "> SMTPUTF8" + body8 +
            (dsnNotify != 0 && dsnOffered ? " RET=HDRS" : "") + "\r\n";
        r = mailsmtp_send_command(smtp, const_cast<char *>(cmd.c_str()));
        if (r == MAILSMTP_NO_ERROR) {
            r = mailsmtp_read_response(smtp) == 250 ? MAILSMTP_NO_ERROR : MAILSMTP_ERROR_MAILBOX_UNAVAILABLE;
        }
    } else if (declare8bit && allow8bit) {
        std::string cmd = std::string("MAIL FROM:<") + mailFrom + ">" + body8 +
            (dsnNotify != 0 && dsnOffered ? " RET=HDRS" : "") + "\r\n";
        r = mailsmtp_send_command(smtp, const_cast<char *>(cmd.c_str()));
        if (r == MAILSMTP_NO_ERROR) {
            r = mailsmtp_read_response(smtp) == 250 ? MAILSMTP_NO_ERROR : MAILSMTP_ERROR_MAILBOX_UNAVAILABLE;
        }
    } else {
        // RFC 3461: RET=HDRS only when DSN is asked for (libetpan adds it when the server offers DSN).
        r = dsnNotify != 0 ? mailesmtp_mail(smtp, mailFrom.c_str(), 0, nullptr)
                           : mailsmtp_mail(smtp, mailFrom.c_str());
    }
    if (r != MAILSMTP_NO_ERROR) {
        std::string why = where + smtpDetail("MAIL FROM", r, smtp->response);
        if (noCredentials && !offeredAuth.empty()) {
            why += " (no SMTP password is set: set it in Settings, or tick \"Same as IMAP password\")";
        }
        freeSmtp(session, smtp);
        throwFailure(env, why);
        return 0;
    }
    jsize nrcpt = recipients != nullptr ? env->GetArrayLength(recipients) : 0;
    for (jsize i = 0; i < nrcpt; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(recipients, i));
        {
            JChars rcpt(env, js);
            r = dsnNotify != 0 && dsnOffered ? mailesmtp_rcpt(smtp, rcpt.c(), dsnNotify, nullptr)
                                             : mailsmtp_rcpt(smtp, rcpt.c());
        }
        if (js != nullptr) env->DeleteLocalRef(js);
        if (r != MAILSMTP_NO_ERROR) {
            std::string why = where + smtpDetail("RCPT", r, smtp->response);
            freeSmtp(session, smtp);
            throwFailure(env, why);
            return 0;
        }
    }
    r = mailsmtp_data(smtp);
    bool bodySent = false;
    if (r == MAILSMTP_NO_ERROR) {
        bodySent = true;
        r = mailsmtp_data_message(smtp, raw != nullptr ? const_cast<char *>(raw) : "", nmsg);
    }
    if (r != MAILSMTP_NO_ERROR) {
        // F50: a lost connection or timeout once the message was handed over leaves the result unknown (the server
        // may already have accepted it); say so instead of "not sent", so a retry does not send it twice.
        bool uncertain = bodySent && (r == MAILSMTP_ERROR_STREAM || r == MAILSMTP_ERROR_CONNECTION_REFUSED);
        std::string why = std::string(uncertain ? "smtp-uncertain: " : "") + where + smtpDetail("DATA", r, smtp->response);
        freeSmtp(session, smtp);
        throwFailure(env, why);
        return 0;
    }
    int owned = takeSmtpFd(session);
    mailsmtp_quit(smtp);
    if (owned >= 0) close(owned);
    freeSmtp(session, smtp);
    // 0 = no DSN asked, 1 = DSN requested, -1 = asked but the server doesn't offer DSN.
    return dsnNotify == 0 ? 0 : (dsnOffered ? 1 : -1);
    });
}


// Capability probe for the settings check: connect in the chosen TLS mode, read CAPABILITY before and after
// TLS, and judge the certificate. Never logs in, never selects. Returns key=value lines:
// error=, pre=, post=, starttls=absent, tls=<failure>, trust=<verdict>, accepted=<verdict>.
namespace {
std::string probeCaps(mailimap * imap) {
    struct mailimap_capability_data * caps = nullptr;
    int r = mailimap_capability(imap, &caps);
    std::string line = cmdOk(r) && caps != nullptr ? joinCapabilities(caps) : std::string();
    if (caps != nullptr) mailimap_capability_data_free(caps);
    return line;
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeProbe(JNIEnv * env, jclass,
    jstring host, jint port, jstring tlsMode, jstring certPin, jboolean acceptInvalidCert, jboolean allowOldTls) {
    registerExtensions();
    JChars h(env, host);
    JChars mode(env, tlsMode);
    JChars pin(env, certPin);
    if (!h.s || !mode.s || !pin.s) return nullptr;
    LiveSession probe;
    probe.acceptInvalidCert = acceptInvalidCert == JNI_TRUE;
    probe.allowOldTls = allowOldTls == JNI_TRUE;
    std::vector<std::string> lines;
    std::string error;
    std::string address;
    TlsCapture capture;
    capture.allowOldTls = probe.allowOldTls;
    mailimap * imap = nullptr;
    bool tried = false;
    bool tlsOk = false;
    if (strcmp(mode.c(), "Implicit") == 0) {
        imap = mailimap_new(0, nullptr);
        if (imap != nullptr) {
            mailimap_set_timeout(imap, probe.readTimeoutSec);
            std::string dialError;
            int fd = tcpConnect("IMAP", h.c(), port, &address, &dialError, probe.connectTimeoutSec);
            if (fd < 0) {
                lines.push_back("error=" + dialError);
            } else {
                tlsCapture = &capture;
                mailstream * stream = mailstream_ssl_open_with_server_name_callback_timeout(
                    fd, probe.readTimeoutSec, sniName(h.c()), prepareTls, nullptr);
                tlsCapture = nullptr;
                if (stream == nullptr) {
                    close(fd);
                    lines.push_back("error=" + connectFailure("IMAP", h.c(), port, address, "connection failed"));
                } else {
                    tried = true;
                    int r = mailimap_connect(imap, stream);
                    tlsOk = connectOk(r);
                    if (!tlsOk) {
                        std::string reason = tlsFailureReason();
                        lines.push_back("tls=" + (reason.empty() ? std::string("TLS handshake failed") : reason));
                        if (capture.ders.empty() && reason.empty()) lines.push_back("error=connection failed");
                    }
                }
            }
        } else {
            lines.push_back("error=" + protocolError("IMAP", MAILIMAP_ERROR_MEMORY, "MAILIMAP_ERROR_MEMORY", nullptr));
        }
    } else {
        imap = openTcp(h.c(), port, &error, &address, nullptr, probe.connectTimeoutSec, probe.readTimeoutSec);
        if (imap == nullptr) {
            lines.push_back("error=" + error);
        } else {
            std::string pre = probeCaps(imap);
            lines.push_back("pre=" + pre);
            if (strcmp(mode.c(), "StartTls") == 0) {
                bool offered = (" " + pre + " ").find(" STARTTLS ") != std::string::npos;
                if (!offered) {
                    // Never send STARTTLS unless CAPABILITY lists it.
                    lines.push_back("starttls=absent");
                } else {
                    tlsCapture = &capture;
                    int r = mailimap_socket_starttls_with_server_name_callback(imap, sniName(h.c()), prepareTls, nullptr);
                    tlsCapture = nullptr;
                    tried = true;
                    tlsOk = cmdOk(r);
                    if (!tlsOk) {
                        std::string reason = tlsFailureReason();
                        lines.push_back("tls=" + (reason.empty() ? std::string("TLS handshake failed") : reason));
                    }
                }
            }
        }
    }
    if (tried && tlsOk) {
        std::string trust = capture.ders.empty() ? std::string("certificate rejected")
            : peerTrustCheck(env, h.c(), capture.ders, pin.c(), false);
        if (!trust.empty()) {
            if (probe.acceptInvalidCert && certificateVerdict(trust) && trust != "certificate changed") {
                lines.push_back("accepted=" + trust);
            }
            else lines.push_back("trust=" + trust);
        }
        lines.push_back("post=" + probeCaps(imap));
    }
    if (imap != nullptr) rejectImap(imap);
    jclass stringCls = env->FindClass("java/lang/String");
    if (stringCls == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(lines.size()), stringCls, nullptr);
    env->DeleteLocalRef(stringCls);
    if (arr == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    for (size_t i = 0; i < lines.size(); ++i) {
        jstring js = newString(env, lines[i].c_str());
        if (js == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(arr);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LabTestHooks_nativeSetMaxTls12(JNIEnv *, jobject, jboolean on) {
    gLabMaxTls12.store(on == JNI_TRUE);
}

// Side connection for one long search: watch INPROGRESS, give reads [timeoutSec] (each line received restarts it),
// and remember the socket so nativeAbort can drop the connection mid-command.
extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativePrepareLongSearch(JNIEnv * env, jobject, jlong handle,
    jint timeoutSec) {
    withOwnerCall(env, handle, [&](JNIEnv * env) -> void {


    LiveSession * session = lockImapSession(env, handle);
    if (session == nullptr) return;
    session->progressDone.store(-1);
    session->progressTotal.store(-1);
    session->progressLines.store(0);
    session->watchProgress = true;
    if (session->imap != nullptr && session->imap->imap_stream != nullptr) {
        mailstream * stream = session->imap->imap_stream;
        mailstream_set_logger(stream, streamTrafficLogger, session);
        mailstream_low * low = mailstream_get_low(stream);
        if (low != nullptr) {
            mailstream_low_set_timeout(low, timeoutSec);
            mailimap_set_timeout(session->imap, timeoutSec);
            session->abortFd.store(mailstream_low_get_fd(low));
        }
    }
    unlockSession(session);
    });
}

// [done, total, progress lines seen]; -1 for unknown. No session lock: the search holds it.
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeProgress(JNIEnv * env, jobject, jlong handle) {
    jlong values[3] = {-1, -1, 0};
    auto * session = reinterpret_cast<LiveSession *>(handle);
    std::shared_ptr<LiveSession> kept = retainSession(session);
    if (kept) {
        values[0] = session->progressDone.load();
        values[1] = session->progressTotal.load();
        values[2] = session->progressLines.load();
        releaseUsers(session);
    }
    jlongArray arr = env->NewLongArray(3);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, 3, values);
    return arr;
}

// Cancel: shut the socket down so the blocked read fails now. The fd is closed later by nativeClose.
extern "C" JNIEXPORT void JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeAbort(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<LiveSession *>(handle);
    std::shared_ptr<LiveSession> kept = retainSession(session);
    if (!kept) return;
    int fd = session->abortFd.load();
    if (fd >= 0) shutdown(fd, SHUT_RDWR);
    shutdownFd(session->smtpFd.load());
    releaseUsers(session);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_dlang_liveimap_engine_LibetpanMailSession_nativeIdleRenews(JNIEnv *, jclass) {
    return gIdleRenews.load();
}

extern "C" int mime_build_file(const char * path,
    const char ** header_names, const char ** header_values, int header_count,
    const char * body, const char * html_body, int flowed, int eight_bit,
    const char ** part_paths, const char ** part_types, const char ** part_dispositions,
    const char ** part_names, const char ** part_encodings, int part_count,
    char * damaged, size_t damaged_cap, char * error, size_t error_cap);

static bool jniStringArray(JNIEnv * env, jobjectArray arr, std::vector<std::string> * out) {
    if (arr == nullptr) return true;
    jsize n = env->GetArrayLength(arr);
    if (env->ExceptionCheck()) return false;
    out->reserve(out->size() + static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (env->ExceptionCheck()) return false;
        auto text = utf8FromJava(env, js);
        if (js != nullptr) env->DeleteLocalRef(js);
        if (!text) return false;
        out->push_back(std::move(*text));
    }
    return true;
}

static const char ** cStrings(const std::vector<std::string> & values, std::vector<const char *> * ptrs) {
    ptrs->clear();
    ptrs->reserve(values.size());
    for (const std::string & value : values) ptrs->push_back(value.c_str());
    return ptrs->empty() ? nullptr : ptrs->data();
}

// Compose passes headers, body text, the 8BITMIME choice, and one path per part. No message jbyteArray.
extern "C" JNIEXPORT jstring JNICALL
Java_org_dlang_liveimap_ui_compose_Rfc822Kt_writeOutgoingMessage(JNIEnv * env, jclass,
    jstring path, jobjectArray headerNames, jobjectArray headerValues, jstring body, jstring htmlBody,
    jboolean flowed, jboolean eightBit, jobjectArray partPaths, jobjectArray partTypes,
    jobjectArray partDispositions, jobjectArray partNames, jobjectArray partEncodings) {
    auto pathText = utf8FromJava(env, path);
    auto bodyText = utf8FromJava(env, body);
    auto htmlText = utf8FromJava(env, htmlBody);
    if (!pathText || !bodyText || !htmlText) return nullptr;
    std::vector<std::string> names;
    std::vector<std::string> values;
    std::vector<std::string> paths;
    std::vector<std::string> types;
    std::vector<std::string> dispositions;
    std::vector<std::string> partNameText;
    std::vector<std::string> encodings;
    if (!jniStringArray(env, headerNames, &names) || !jniStringArray(env, headerValues, &values) ||
        !jniStringArray(env, partPaths, &paths) || !jniStringArray(env, partTypes, &types) ||
        !jniStringArray(env, partDispositions, &dispositions) || !jniStringArray(env, partNames, &partNameText) ||
        !jniStringArray(env, partEncodings, &encodings)) {
        return nullptr;
    }
    if (names.size() != values.size() || paths.size() != types.size() || paths.size() != dispositions.size() ||
        paths.size() != partNameText.size() || paths.size() != encodings.size()) {
        throwFailure(env, "could not write the message");
        return nullptr;
    }
    std::vector<const char *> namePtr;
    std::vector<const char *> valuePtr;
    std::vector<const char *> pathPtr;
    std::vector<const char *> typePtr;
    std::vector<const char *> dispositionPtr;
    std::vector<const char *> partNamePtr;
    std::vector<const char *> encodingPtr;
    char damaged[256];
    char error[256];
    int result = mime_build_file(pathText->c_str(),
        cStrings(names, &namePtr), cStrings(values, &valuePtr), static_cast<int>(names.size()),
        bodyText->c_str(), htmlText->c_str(), flowed == JNI_TRUE, eightBit == JNI_TRUE,
        cStrings(paths, &pathPtr), cStrings(types, &typePtr), cStrings(dispositions, &dispositionPtr),
        cStrings(partNameText, &partNamePtr), cStrings(encodings, &encodingPtr), static_cast<int>(paths.size()),
        damaged, sizeof(damaged), error, sizeof(error));
    if (result == 1) return env->NewStringUTF(damaged);
    if (result != 0) {
        throwFailure(env, error[0] != 0 ? error : "could not write the message");
        return nullptr;
    }
    return env->NewStringUTF("");
}
