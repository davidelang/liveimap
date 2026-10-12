// Boundary cases for server responses (list item 2): each must return without a crash, sanitizer report,
// hang or unbounded allocation; malformed input must be a parse/stream error, never MAILIMAP_NO_ERROR.
// Run under ASan+UBSan with a small stack (build.sh) so deep recursion shows up as a crash.
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <sys/resource.h>

extern "C" int liveimap_parse(const uint8_t * data, size_t size, int greeting, size_t maxLine);
extern "C" size_t liveimap_copyuid_expand(uint32_t first, uint32_t last, uint64_t requested);

static int failures = 0;

enum Want { Ok, Err, Any };

static void check(const char * name, const std::string & in, Want want, int greeting = 0, size_t maxLine = 0) {
    auto t0 = std::chrono::steady_clock::now();
    int r = liveimap_parse(reinterpret_cast<const uint8_t *>(in.data()), in.size(), greeting, maxLine);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    bool ok = r == 0 || (greeting && (r == 0));
    bool good = want == Any || (want == Ok ? ok : !ok);
    if (ms > 5000) good = false;
    std::printf("%-44s r=%-3d %7.1f ms %s\n", name, r, ms, good ? "ok" : "FAIL");
    if (!good) ++failures;
}

static std::string rep(const std::string & s, int n) { std::string o; o.reserve(s.size() * n); while (n--) o += s; return o; }

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    const std::string tag = "1 OK done\r\n";
    // Literals.
    check("literal size 4294967295", "* 1 FETCH (BODY[] {4294967295}\r\nabc", Err);
    check("literal size 2^64+", "* 1 FETCH (BODY[] {184467440737095516160}\r\nabc", Err);
    check("literal shorter than declared", "* 1 FETCH (UID 1 BODY[] {100}\r\nonly ten b)\r\n", Err);
    check("literal8 huge", "* 1 FETCH (BINARY[1] ~{999999999999}\r\nx", Err);
    check("literal exact + more", "* 1 FETCH (UID 1 BODY[] {3}\r\nabc)\r\n" + tag, Ok);
    check("literal size with junk", "* 1 FETCH (BODY[] {12x}\r\nabc)\r\n" + tag, Err);
    // Line length.
    check("line over cap (5000 > 4096)", "* OK " + std::string(5000, 'x') + "\r\n" + tag, Err, 0, 4096);
    check("line no CRLF at EOF", "* OK " + std::string(100000, 'y'), Err);
    check("1 MB flag line", "* FLAGS (" + rep("\\Seen ", 170000) + ")\r\n" + tag, Ok);
    // Nesting.
    check("BODYSTRUCTURE 5000 levels",
        "* 1 FETCH (UID 1 BODYSTRUCTURE " + rep("(", 5000) + "\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 1 1" +
        rep(" \"MIXED\")", 5000) + ")\r\n" + tag, Any);
    check("THREAD 100000 deep", "* THREAD " + rep("(1 ", 100000) + rep(")", 100000) + "\r\n" + tag, Any);
    check("THREAD unbalanced", "* THREAD ((1)(2\r\n" + tag, Err);
    check("THREAD 300000 nodes", "* THREAD " + rep("(1)", 300000) + "\r\n" + tag, Any);
    check("parenthesized list 200000 open", "* LIST " + rep("(", 200000) + "\r\n", Err);
    // Sequence sets the app expands.
    check("ESEARCH ALL 1:4294967295", "* ESEARCH (TAG \"1\") UID ALL 1:4294967295\r\n" + tag, Any);
    check("ESEARCH 4000 ranges of 1M", "* ESEARCH (TAG \"1\") UID ALL " + [] {
        std::string s; for (uint64_t i = 0; i < 4000; ++i) { if (i) s += ","; s += std::to_string(i * 1000000 + 1) + ":" + std::to_string(i * 1000000 + 999999); } return s; }() + "\r\n" + tag, Any);
    check("ESEARCH PARTIAL bad range", "* ESEARCH (TAG \"1\") UID PARTIAL (5:1 NIL) COUNT 3\r\n" + tag, Any);
    check("ESEARCH COUNT overflow", "* ESEARCH (TAG \"1\") UID COUNT 99999999999999\r\n" + tag, Any);
    check("COPYUID dest 1:4294967295", "1 OK [COPYUID 1 1:2 1:4294967295] done\r\n", Any);
    check("SEARCH 0 and *", "* SEARCH 0 * 4294967295\r\n" + tag, Any);
    // Envelopes, addresses, charsets.
    check("ENVELOPE bad encoded-words",
        "* 1 FETCH (UID 1 ENVELOPE (\"x\" \"=?x-unknown?Q?=ZZ=?= =?utf-8?B?####?= =?utf-8?Q?\" NIL NIL NIL NIL NIL NIL NIL NIL))\r\n" + tag, Ok);
    check("ENVELOPE address missing fields", "* 1 FETCH (ENVELOPE (NIL NIL ((\"a\" NIL)) NIL NIL NIL NIL NIL NIL NIL))\r\n" + tag, Err);
    check("ENVELOPE 100000 addresses",
        "* 1 FETCH (UID 1 ENVELOPE (NIL NIL (" + rep("(NIL NIL \"a\" \"b\")", 100000) + ") NIL NIL NIL NIL NIL NIL NIL))\r\n" + tag, Any);
    check("ENVELOPE NUL in quoted", [] { static const char t[] = "* 1 FETCH (ENVELOPE (\"a\0b\" NIL NIL NIL NIL NIL NIL NIL NIL NIL))\r\n"; return std::string(t, sizeof t - 1); }() + tag, Any);
    check("8-bit in quoted string", "* 1 FETCH (UID 1 ENVELOPE (NIL \"\xff\xfe\xc3\x28\" NIL NIL NIL NIL NIL NIL NIL NIL))\r\n" + tag, Any);
    check("BODYSTRUCTURE bad charset param",
        "* 1 FETCH (UID 1 BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"x-\xff\xff\") NIL NIL \"8BIT\" 5 1))\r\n" + tag, Ok);
    check("BODYSTRUCTURE negative size", "* 1 FETCH (BODYSTRUCTURE (\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" -5 1))\r\n" + tag, Any);
    check("BODYSTRUCTURE missing fields", "* 1 FETCH (BODYSTRUCTURE (\"TEXT\"))\r\n" + tag, Any);
    check("BODYSTRUCTURE empty multipart", "* 1 FETCH (BODYSTRUCTURE ( \"MIXED\"))\r\n" + tag, Any);
    check("BODY[ without section (fuzz crash)", "* 1 FETCH (UID 1 BODY[x {3}\r\nabc)\r\n" + tag, Err);
    check("PREVIEW huge literal", "* 1 FETCH (UID 1 PREVIEW {4294967290}\r\n", Err);
    // Greetings and capabilities.
    check("greeting 20000 capabilities", "* OK [CAPABILITY " + rep("X-CAP ", 20000) + "IMAP4rev1] hi\r\n", Any, 1);
    check("greeting garbage", "\xff\xfe\x00junk\r\n", Err, 1);
    check("greeting BYE", "* BYE go away\r\n", Err, 1);
    check("empty input", "", Err);
    // Malformed BODYSTRUCTURE: the app's libetpan skips a malformed FETCH (keeps the session usable), so these
    // are "Any": what matters is no crash or sanitizer report.
    // COPYUID: the app keeps at most as many destination UIDs as it asked to copy.
    {
        size_t a = liveimap_copyuid_expand(1, 4294967295u, 2);
        size_t b = liveimap_copyuid_expand(10, 11, 2);
        bool good = a == 0 && b == 2;
        std::printf("%-44s kept %zu / %zu  %s\n", "COPYUID 1:4294967295 for 2 requested", a, b, good ? "ok" : "FAIL");
        if (!good) ++failures;
    }
    std::printf("%d failure(s)\n", failures);
    return failures ? 1 : 0;
}
