# Sourced by ../build. LiveIMAP compile flags for this pin (libpin "Options-only" role: the build script owns
# flags; fetch-deps never invents them; patches/ holds code diffs only). See ../SOURCE.md.
#
# USE_SASL     host configure cannot see the Android Cyrus SASL headers; without it mailimap_authenticate and
#              mailsmtp_auth_sasl are stubs.
# HAVE_SHA512  Cyrus SASL compiles SCRAM-SHA-256/-512 (and -PLUS) only with it; OpenSSL 3 provides SHA-2.
LIBETPAN_APP_CFLAGS="-DUSE_SASL=1 -DHAVE_SHA512=1"
