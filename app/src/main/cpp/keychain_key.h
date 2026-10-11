#pragma once
#include <jni.h>
#include <string>
#include <vector>

struct ssl_ctx_st;

// Caches KeyChainSigner and builds the OpenSSL key methods; call on a Java thread before any TLS setup.
void keyChainInit(JNIEnv * env);
// Loads the certificate chain (DER, leaf first) and a keystore-backed signing key into [ctx]. [key] is a JNI
// global reference that must outlive the TLS connection.
bool useKeyChainKey(ssl_ctx_st * ctx, const std::vector<std::string> & chain, jobject key, std::string * why);
