// Host harness around the app's IMAP response handling: libetpan's parser plus the app's own extension parsers
// (ESEARCH/PARTIAL, THREAD, PREVIEW, BINARY, scope hits, ...) and the post-processing that turns their results into
// UID lists and thread trees. Built by tools/test-servers/fuzz/build.sh for boundary.cpp and the libFuzzer target.
#include "liveimap_jni.cpp"
#include <sys/socket.h>

extern "C" int liveimap_parse(const uint8_t * data, size_t size, int greeting, size_t maxLine) {
    registerExtensions();
    // The socket driver uses recv(), so feed the bytes through a socketpair from a writer thread, then EOF.
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return -1;
    int fd = sv[0];
    std::thread writer([=] {
        size_t off = 0;
        while (off < size) {
            ssize_t w = send(sv[1], data + off, size - off, MSG_NOSIGNAL);
            if (w <= 0) break;
            off += static_cast<size_t>(w);
        }
        shutdown(sv[1], SHUT_WR);
    });
    mailstream * st = mailstream_socket_open(fd);
    if (st == nullptr) { close(fd); shutdown(sv[1], SHUT_RDWR); writer.join(); close(sv[1]); return -1; }
    if (maxLine != 0) mailstream_set_max_line_length(st, maxLine);
    mailimap * s = mailimap_new(0, nullptr);
    int last = MAILIMAP_NO_ERROR;
    if (greeting) {
        // Greeting with capabilities, as on connect.
        int r = mailimap_connect(s, st);
        if (r != MAILIMAP_NO_ERROR_AUTHENTICATED && r != MAILIMAP_NO_ERROR_NON_AUTHENTICATED) {
            mailimap_free(s);
            shutdown(sv[1], SHUT_RDWR); writer.join(); close(sv[1]);
            return r;
        }
    } else {
        s->imap_stream = st;
    }
    s->imap_state = MAILIMAP_STATE_SELECTED;
    s->imap_tag = 1;  // inputs end with the tagged reply "1 ..." (libetpan tags are numbers)
    if (s->imap_selection_info == nullptr) s->imap_selection_info = mailimap_selection_info_new();
    for (int i = 0; i < 64; ++i) {
        // EOF after a complete response is the normal end; EOF before any response is a stream error.
        if (mailimap_read_line(s) == nullptr || s->imap_stream_buffer->len == 0) {
            if (i == 0) last = MAILIMAP_ERROR_STREAM;
            break;
        }
        struct mailimap_response * resp = nullptr;
        last = mailimap_parse_response(s, &resp);
        if (resp != nullptr) mailimap_response_free(resp);
        // The app's consumers of what the extensions left behind.
        clist * es = takeEsearch(s);
        if (es != nullptr) mailimap_search_result_free(es);
        live_thread_free(detachThread(s));
        freeScopeHits(takeScopeHits(s));
        if (last != MAILIMAP_NO_ERROR) break;
    }
    mailimap_free(s);  // closes the stream and fd
    shutdown(sv[1], SHUT_RDWR);  // unblock the writer if the parser stopped early
    writer.join();
    close(sv[1]);
    return last;
}

// COPYUID destination expansion with a server-chosen dest interval; returns how many UIDs the app kept.
extern "C" size_t liveimap_copyuid_expand(uint32_t first, uint32_t last, uint64_t requested) {
    auto * session = new LiveSession();
    struct mailimap_set * dest = mailimap_set_new_interval(first, last);
    rememberDestUids(session, dest, requested);
    size_t n = session->copiedUids.size();
    mailimap_set_free(dest);
    delete session;
    return n;
}
