// libFuzzer target: one server byte stream through libetpan + the app's extension parsers (imap_harness.cpp).
// Byte 0: bit 0 set = starts with a greeting (connect path); the rest is what the server sends.
#include <cstddef>
#include <cstdint>
extern "C" int liveimap_parse(const uint8_t * data, size_t size, int greeting, size_t maxLine);
extern "C" int LLVMFuzzerTestOneInput(const uint8_t * data, size_t size) {
    if (size < 1) return 0;
    liveimap_parse(data + 1, size - 1, data[0] & 1, 1 << 20);
    return 0;
}
