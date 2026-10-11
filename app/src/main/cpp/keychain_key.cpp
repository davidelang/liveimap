// TLS client certificates whose private key stays in the Android keystore (KeyChain, or the app's own
// AndroidKeyStore in lab runs). OpenSSL gets an EVP_PKEY built from the certificate's public key with an
// RSA_METHOD / EC_KEY_METHOD whose private operation calls KeyChainSigner.sign through JNI:
//   RSA PKCS#1 v1.5 (TLS 1.2 rsa_pkcs1_*): OpenSSL hands over the DigestInfo; Java "NONEwithRSA".
//   RSA-PSS (TLS 1.3, rsa_pss_rsae_*): OpenSSL applies PSS padding and asks for the raw private operation
//     (RSA_NO_PADDING); Java "RSA/ECB/NoPadding" decrypt with the private key.
//   ECDSA: OpenSSL hands over the digest; Java "NONEwithECDSA" returns the DER signature.
// OpenSSL 1.1.1w; the NDK include path has no openssl headers, so the few symbols are declared here.
#include "keychain_key.h"

#include <android/log.h>
#include <cstring>

struct rsa_st;
struct rsa_meth_st;
struct ec_key_st;
struct ec_key_method_st;
struct evp_pkey_st;
struct x509_st;
struct ssl_ctx_st;
struct bignum_st;
struct bignum_ctx;
struct ecdsa_sig_st;

extern "C" {
x509_st * d2i_X509(x509_st ** a, const unsigned char ** in, long len);
void X509_free(x509_st * x);
evp_pkey_st * X509_get_pubkey(x509_st * x);
evp_pkey_st * EVP_PKEY_new(void);
void EVP_PKEY_free(evp_pkey_st * pkey);
rsa_st * EVP_PKEY_get1_RSA(evp_pkey_st * pkey);
ec_key_st * EVP_PKEY_get1_EC_KEY(evp_pkey_st * pkey);
int EVP_PKEY_set1_RSA(evp_pkey_st * pkey, rsa_st * key);
int EVP_PKEY_set1_EC_KEY(evp_pkey_st * pkey, ec_key_st * key);
rsa_st * RSAPublicKey_dup(rsa_st * rsa);
void RSA_free(rsa_st * r);
int RSA_size(const rsa_st * rsa);
const rsa_meth_st * RSA_PKCS1_OpenSSL(void);
rsa_meth_st * RSA_meth_dup(const rsa_meth_st * meth);
int RSA_meth_set1_name(rsa_meth_st * meth, const char * name);
int RSA_meth_set_priv_enc(rsa_meth_st * rsa, int (* priv_enc)(int flen, const unsigned char * from,
    unsigned char * to, rsa_st * rsa, int padding));
int RSA_set_method(rsa_st * rsa, const rsa_meth_st * meth);
int RSA_set_ex_data(rsa_st * r, int idx, void * arg);
void * RSA_get_ex_data(const rsa_st * r, int idx);
ec_key_st * EC_KEY_dup(const ec_key_st * src);
void EC_KEY_free(ec_key_st * key);
const ec_key_method_st * EC_KEY_OpenSSL(void);
ec_key_method_st * EC_KEY_METHOD_new(const ec_key_method_st * meth);
void EC_KEY_METHOD_get_sign(const ec_key_method_st * meth,
    int (** psign)(int, const unsigned char *, int, unsigned char *, unsigned int *, const bignum_st *,
        const bignum_st *, ec_key_st *),
    int (** psign_setup)(ec_key_st *, bignum_ctx *, bignum_st **, bignum_st **),
    ecdsa_sig_st * (** psign_sig)(const unsigned char *, int, const bignum_st *, const bignum_st *, ec_key_st *));
void EC_KEY_METHOD_set_sign(ec_key_method_st * meth,
    int (* sign)(int, const unsigned char *, int, unsigned char *, unsigned int *, const bignum_st *,
        const bignum_st *, ec_key_st *),
    int (* sign_setup)(ec_key_st *, bignum_ctx *, bignum_st **, bignum_st **),
    ecdsa_sig_st * (* sign_sig)(const unsigned char *, int, const bignum_st *, const bignum_st *, ec_key_st *));
int EC_KEY_set_method(ec_key_st * key, const ec_key_method_st * meth);
int EC_KEY_set_ex_data(ec_key_st * key, int idx, void * arg);
void * EC_KEY_get_ex_data(const ec_key_st * key, int idx);
int ECDSA_size(const ec_key_st * eckey);
ecdsa_sig_st * d2i_ECDSA_SIG(ecdsa_sig_st ** sig, const unsigned char ** pp, long len);
int CRYPTO_get_ex_new_index(int class_index, long argl, void * argp, void * new_func, void * dup_func, void * free_func);
int SSL_CTX_use_certificate(ssl_ctx_st * ctx, x509_st * x);
int SSL_CTX_use_PrivateKey(ssl_ctx_st * ctx, evp_pkey_st * pkey);
long SSL_CTX_ctrl(ssl_ctx_st * ctx, int cmd, long larg, void * parg);
}

namespace {
constexpr int kExIndexEcKey = 8;  // CRYPTO_EX_INDEX_EC_KEY
constexpr int kExIndexRsa = 9;    // CRYPTO_EX_INDEX_RSA
constexpr int kRsaPkcs1Padding = 1;
constexpr int kRsaNoPadding = 3;
constexpr int kOpRsaPkcs1 = 0;
constexpr int kOpRsaRaw = 1;
constexpr int kOpEcdsa = 2;

JavaVM * gKeyVm = nullptr;
jclass gSignerClass = nullptr;
jmethodID gSignerSign = nullptr;
int gRsaIndex = -1;
int gEcIndex = -1;
rsa_meth_st * gRsaMethod = nullptr;
ec_key_method_st * gEcMethod = nullptr;
int (* gEcSignSetup)(ec_key_st *, bignum_ctx *, bignum_st **, bignum_st **) = nullptr;

// Calls KeyChainSigner.sign(key, op, data) on this thread (attaching it if OpenSSL runs on a native thread).
// Writes at most [cap] bytes to [out]; returns the length, or -1.
int javaSign(jobject key, int op, const unsigned char * data, int len, unsigned char * out, int cap) {
    if (gKeyVm == nullptr || gSignerClass == nullptr || key == nullptr) return -1;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (gKeyVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (gKeyVm->AttachCurrentThread(&env, nullptr) != 0) return -1;
        attached = true;
    }
    int got = -1;
    jbyteArray in = env->NewByteArray(len);
    if (in != nullptr) {
        env->SetByteArrayRegion(in, 0, len, reinterpret_cast<const jbyte *>(data));
        auto sig = static_cast<jbyteArray>(env->CallStaticObjectMethod(gSignerClass, gSignerSign, key, op, in));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else if (sig != nullptr) {
            jsize n = env->GetArrayLength(sig);
            if (n <= cap) {
                env->GetByteArrayRegion(sig, 0, n, reinterpret_cast<jbyte *>(out));
                got = n;
            }
            env->DeleteLocalRef(sig);
        }
        env->DeleteLocalRef(in);
    }
    if (attached) gKeyVm->DetachCurrentThread();
    if (got < 0) __android_log_print(ANDROID_LOG_WARN, "LiveIMAP", "keystore client-cert signing failed (op %d)", op);
    return got;
}

int rsaPrivEnc(int flen, const unsigned char * from, unsigned char * to, rsa_st * rsa, int padding) {
    auto key = static_cast<jobject>(RSA_get_ex_data(rsa, gRsaIndex));
    int size = RSA_size(rsa);
    if (padding == kRsaPkcs1Padding) return javaSign(key, kOpRsaPkcs1, from, flen, to, size);
    if (padding == kRsaNoPadding && flen == size) return javaSign(key, kOpRsaRaw, from, flen, to, size);
    return -1;
}

int ecSign(int, const unsigned char * dgst, int dlen, unsigned char * sig, unsigned int * siglen,
    const bignum_st *, const bignum_st *, ec_key_st * ec) {
    auto key = static_cast<jobject>(EC_KEY_get_ex_data(ec, gEcIndex));
    int n = javaSign(key, kOpEcdsa, dgst, dlen, sig, ECDSA_size(ec));
    if (n < 0) return 0;
    *siglen = static_cast<unsigned int>(n);
    return 1;
}

ecdsa_sig_st * ecSignSig(const unsigned char * dgst, int dlen, const bignum_st *, const bignum_st *, ec_key_st * ec) {
    unsigned char der[256];
    unsigned int len = 0;
    if (ECDSA_size(ec) > static_cast<int>(sizeof(der))) return nullptr;
    if (ecSign(0, dgst, dlen, der, &len, nullptr, nullptr, ec) != 1) return nullptr;
    const unsigned char * p = der;
    return d2i_ECDSA_SIG(nullptr, &p, static_cast<long>(len));
}
}  // namespace

void keyChainInit(JNIEnv * env) {
    if (gSignerClass != nullptr) return;
    env->GetJavaVM(&gKeyVm);
    // FindClass here, on a Java thread with the app class loader; native threads would not find app classes.
    jclass cls = env->FindClass("org/dlang/liveimap/engine/KeyChainSigner");
    if (cls == nullptr) {
        env->ExceptionClear();
        return;
    }
    gSignerSign = env->GetStaticMethodID(cls, "sign", "(Ljava/lang/Object;I[B)[B");
    if (gSignerSign == nullptr) {
        env->ExceptionClear();
        return;
    }
    gRsaIndex = CRYPTO_get_ex_new_index(kExIndexRsa, 0, nullptr, nullptr, nullptr, nullptr);
    gEcIndex = CRYPTO_get_ex_new_index(kExIndexEcKey, 0, nullptr, nullptr, nullptr, nullptr);
    gRsaMethod = RSA_meth_dup(RSA_PKCS1_OpenSSL());
    RSA_meth_set1_name(gRsaMethod, "LiveIMAP keystore RSA");
    RSA_meth_set_priv_enc(gRsaMethod, rsaPrivEnc);
    gEcMethod = EC_KEY_METHOD_new(EC_KEY_OpenSSL());
    EC_KEY_METHOD_get_sign(gEcMethod, nullptr, &gEcSignSetup, nullptr);
    EC_KEY_METHOD_set_sign(gEcMethod, ecSign, gEcSignSetup, ecSignSig);
    gSignerClass = static_cast<jclass>(env->NewGlobalRef(cls));
    env->DeleteLocalRef(cls);
}

bool useKeyChainKey(ssl_ctx_st * ctx, const std::vector<std::string> & chain, jobject key, std::string * why) {
    if (gSignerClass == nullptr || chain.empty()) {
        *why = "client certificate: keystore signing unavailable";
        return false;
    }
    const auto * in = reinterpret_cast<const unsigned char *>(chain[0].data());
    x509_st * cert = d2i_X509(nullptr, &in, static_cast<long>(chain[0].size()));
    if (cert == nullptr) {
        *why = "client certificate: unreadable certificate from the keystore";
        return false;
    }
    evp_pkey_st * pub = X509_get_pubkey(cert);
    evp_pkey_st * pkey = EVP_PKEY_new();
    bool good = false;
    if (pub != nullptr && pkey != nullptr) {
        // A copy of the public key with our method, so the certificate's own key object stays untouched.
        if (rsa_st * rsa = EVP_PKEY_get1_RSA(pub)) {
            rsa_st * dup = RSAPublicKey_dup(rsa);
            RSA_free(rsa);
            if (dup != nullptr) {
                good = RSA_set_method(dup, gRsaMethod) == 1 && RSA_set_ex_data(dup, gRsaIndex, key) == 1
                    && EVP_PKEY_set1_RSA(pkey, dup) == 1;
                RSA_free(dup);
            }
        } else if (ec_key_st * ec = EVP_PKEY_get1_EC_KEY(pub)) {
            ec_key_st * dup = EC_KEY_dup(ec);
            EC_KEY_free(ec);
            if (dup != nullptr) {
                good = EC_KEY_set_method(dup, gEcMethod) == 1 && EC_KEY_set_ex_data(dup, gEcIndex, key) == 1
                    && EVP_PKEY_set1_EC_KEY(pkey, dup) == 1;
                EC_KEY_free(dup);
            }
        } else {
            *why = "client certificate: only RSA and EC keys are supported";
        }
    }
    if (good) {
        good = SSL_CTX_use_certificate(ctx, cert) == 1 && SSL_CTX_use_PrivateKey(ctx, pkey) == 1;
        if (!good) *why = "client certificate: OpenSSL refused the keystore key";
    } else if (why->empty()) {
        *why = "client certificate: could not wrap the keystore key";
    }
    if (good) {
        for (size_t i = 1; i < chain.size(); ++i) {
            const auto * p = reinterpret_cast<const unsigned char *>(chain[i].data());
            x509_st * extra = d2i_X509(nullptr, &p, static_cast<long>(chain[i].size()));
            if (extra == nullptr) continue;
            SSL_CTX_ctrl(ctx, 89 /* SSL_CTRL_CHAIN_CERT */, 0 /* take ownership */, extra);
        }
    }
    if (pub != nullptr) EVP_PKEY_free(pub);
    if (pkey != nullptr) EVP_PKEY_free(pkey);
    X509_free(cert);
    return good;
}
