#include "imap_bursts.h"

#include <libetpan/mailstream_types.h>

#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

extern "C" {
#include "imap_test_utils.h"
}

namespace {

int gFail = 0;

int note(const char * name, const char * why) {
    std::fprintf(stderr, "%s: %s\n", name, why);
    return 1;
}

mailimap * openScript(const char * input, MMAPString ** output) {
    mailimap * imap = mailimap_new(0, nullptr);
    if (imap == nullptr) return nullptr;
    imap->imap_stream = imap_test_stream_from_string_with_output(input, output);
    imap->imap_state = MAILIMAP_STATE_AUTHENTICATED;
    imap->imap_tag = 0;
    return imap;
}

void closeScript(mailimap * imap, MMAPString * output) {
    if (imap != nullptr) {
        if (imap->imap_stream != nullptr) {
            mailstream_close(imap->imap_stream);
            imap->imap_stream = nullptr;
        }
        mailimap_free(imap);
    }
    if (output != nullptr) mmap_string_free(output);
}

int partialStatus() {
    MMAPString * output = nullptr;
    mailimap * imap = openScript("* STATUS A (MESSAGES 1)\r\n1 OK done\r\n", &output);
    if (imap == nullptr || imap->imap_stream == nullptr) return note("partial", "no session");
    std::map<std::string, int> counts;
    int r = statusMessagesPipeline(imap, {"A", "B", "C"}, &counts, true);
    int tag = imap->imap_tag;
    closeScript(imap, output);
    if (r != MAILIMAP_ERROR_STREAM) return note("partial", "expected STREAM");
    if (tag != 3) return note("partial", "expected tag 3");
    return 0;
}

int garbageStatus() {
    MMAPString * output = nullptr;
    mailimap * imap = openScript("1 OK\r\n* GARBAGE (\r\n2 OK\r\n3 OK\r\n", &output);
    if (imap == nullptr || imap->imap_stream == nullptr) return note("garbage", "no session");
    std::map<std::string, int> counts;
    int r = statusMessagesPipeline(imap, {"A", "B", "C"}, &counts, true);
    closeScript(imap, output);
    if (r == MAILIMAP_NO_ERROR) return note("garbage", "returned NO_ERROR");
    return 0;
}

int reorderedStatus() {
    MMAPString * output = nullptr;
    mailimap * imap = openScript(
        "* STATUS B (MESSAGES 2)\r\n2 OK\r\n* STATUS A (MESSAGES 1)\r\n1 OK\r\n3 OK\r\n", &output);
    if (imap == nullptr || imap->imap_stream == nullptr) return note("reorder", "no session");
    std::map<std::string, int> counts;
    int r = statusMessagesPipeline(imap, {"A", "B", "C"}, &counts, true);
    closeScript(imap, output);
    if (r == MAILIMAP_NO_ERROR) {
        if (counts.count("A") == 0 || counts["A"] != 1) return note("reorder", "missing A");
        if (counts.count("B") == 0 || counts["B"] != 2) return note("reorder", "missing B");
        return 0;
    }
    if (r != MAILIMAP_ERROR_STREAM) return note("reorder", "expected STREAM or both counts");
    return 0;
}

struct FailSecond {
    ssize_t (* real)(mailstream_low *, const void *, size_t);
    int sawNewline;
    mailstream_low_driver driver;
};

FailSecond gSecond;

extern "C" ssize_t failSecondWrite(mailstream_low * s, const void * buf, size_t count) {
    if (gSecond.sawNewline) return -1;
    ssize_t n = gSecond.real(s, buf, count);
    if (n > 0) {
        const char * bytes = static_cast<const char *>(buf);
        for (ssize_t i = 0; i < n; ++i) {
            if (bytes[i] == '\n') gSecond.sawNewline = 1;
        }
    }
    return n;
}

int previewWriteFails() {
    MMAPString * output = nullptr;
    mailimap * imap = openScript("*\r\n", &output);
    if (imap == nullptr || imap->imap_stream == nullptr) return note("preview", "no session");
    mailstream * stream = imap->imap_stream;
    mailstream_low_driver * original = stream->low->driver;
    gSecond.real = original->mailstream_write;
    gSecond.sawNewline = 0;
    gSecond.driver = *original;
    gSecond.driver.mailstream_write = failSecondWrite;
    stream->low->driver = &gSecond.driver;
    stream->buffer_max_size = 1;
    std::vector<PreviewAsk> asks = {
        PreviewAsk{1, "1", "UTF-8"},
        PreviewAsk{2, "1", "UTF-8"},
    };
    std::vector<std::string> previews;
    int r = previewBurst(imap, asks, 32, previews, true);
    int tag = imap->imap_tag;
    stream->low->driver = original;
    closeScript(imap, output);
    if (r != MAILIMAP_ERROR_STREAM) return note("preview", "expected STREAM");
    if (tag != 2) return note("preview", "expected the second tag");
    return 0;
}

}  // namespace

int main() {
    gFail = partialStatus();
    if (gFail != 0) return gFail;
    gFail = garbageStatus();
    if (gFail != 0) return gFail;
    gFail = reorderedStatus();
    if (gFail != 0) return gFail;
    gFail = previewWriteFails();
    return gFail;
}
