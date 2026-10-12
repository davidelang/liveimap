// Replays fuzz inputs (crash-*/leak-* artifacts or a corpus) through the host ASan build, one file per argument,
// in the same format as fuzz_imap (byte 0 = mode).
#include <cstdint>
#include <cstdio>
#include <vector>
extern "C" int liveimap_parse(const uint8_t * data, size_t size, int greeting, size_t maxLine);
int main(int argc, char ** argv) {
    for (int i = 1; i < argc; ++i) {
        FILE * f = std::fopen(argv[i], "rb");
        if (f == nullptr) continue;
        std::vector<uint8_t> b;
        int c;
        while ((c = std::fgetc(f)) != EOF) b.push_back(static_cast<uint8_t>(c));
        std::fclose(f);
        if (b.empty()) continue;
        int r = liveimap_parse(b.data() + 1, b.size() - 1, b[0] & 1, 1 << 20);
        std::printf("%s r=%d\n", argv[i], r);
    }
    return 0;
}
