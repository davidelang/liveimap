#include <libetpan/mailimf.h>
#include <libetpan/mailmime.h>
#include <libetpan/mailmime_content.h>
#include <libetpan/mailmime_types.h>
#include <libetpan/mailmime_types_helper.h>
#include <libetpan/mmapstring.h>

#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#include <string>
#include <vector>

// 0 written, 1 a part did not decode (name in damaged), 2 any other failure (text in error).
extern "C" int mime_build_file(const char * path,
    const char ** header_names, const char ** header_values, int header_count,
    const char * body, const char * html_body, int flowed, int eight_bit,
    const char ** part_paths, const char ** part_types, const char ** part_dispositions,
    const char ** part_names, const char ** part_encodings, int part_count,
    char * damaged, size_t damaged_cap, char * error, size_t error_cap);

static void set_text(char * buf, size_t cap, const char * text) {
    if (buf == nullptr || cap == 0) return;
    if (text == nullptr) text = "";
    snprintf(buf, cap, "%s", text);
}

static void set_error(char * error, size_t cap, const char * text) {
    set_text(error, cap, text);
}

static std::string crlf_text(const char * text) {
    std::string out;
    if (text == nullptr) text = "";
    for (const char * p = text; *p != 0; ++p) {
        if (*p == '\r') {
            out.append("\r\n");
            if (p[1] == '\n') ++p;
        } else if (*p == '\n') {
            out.append("\r\n");
        } else {
            out.push_back(*p);
        }
    }
    if (out.size() < 2 || out[out.size() - 2] != '\r' || out[out.size() - 1] != '\n') out.append("\r\n");
    return out;
}

static bool line_exceeds(const std::string & bytes, int limit) {
    int count = 0;
    for (unsigned char b : bytes) {
        if (b == '\n') {
            if (count > limit) return true;
            count = 0;
        } else if (b != '\r') {
            count++;
            if (count > limit) return true;
        }
    }
    return count > limit;
}

static int text_mechanism(const std::string & bytes, int eight_bit) {
    if (eight_bit && !line_exceeds(bytes, 998)) {
        for (unsigned char b : bytes) {
            if (b > 127) return MAILMIME_MECHANISM_8BIT;
        }
        return MAILMIME_MECHANISM_7BIT;
    }
    return MAILMIME_MECHANISM_QUOTED_PRINTABLE;
}

static std::string lower_token(const char * encoding) {
    std::string token;
    if (encoding == nullptr) return token;
    for (const char * p = encoding; *p != 0 && *p != ';'; ++p) {
        unsigned char c = static_cast<unsigned char>(*p);
        if (c == ' ' || c == '\t') continue;
        if (c >= 'A' && c <= 'Z') c = static_cast<unsigned char>(c - 'A' + 'a');
        token.push_back(static_cast<char>(c));
    }
    return token;
}

static int mechanism_of(const std::string & token, const char ** literal) {
    *literal = nullptr;
    if (token == "7bit") return MAILMIME_MECHANISM_7BIT;
    if (token == "8bit") return MAILMIME_MECHANISM_8BIT;
    if (token == "binary") return MAILMIME_MECHANISM_BINARY;
    if (token == "quoted-printable") return MAILMIME_MECHANISM_QUOTED_PRINTABLE;
    if (token == "base64") return MAILMIME_MECHANISM_BASE64;
    *literal = token.c_str();
    return MAILMIME_MECHANISM_TOKEN;
}

static int read_file(const char * path, std::string * out) {
    FILE * file = fopen(path, "rb");
    if (file == nullptr) return -1;
    char buf[8192];
    size_t n = 0;
    while ((n = fread(buf, 1, sizeof(buf), file)) > 0) out->append(buf, n);
    int bad = ferror(file) != 0;
    fclose(file);
    return bad ? -1 : 0;
}

static bool file_has_high(const char * path, int * readable) {
    FILE * file = fopen(path, "rb");
    if (file == nullptr) {
        *readable = 0;
        return false;
    }
    unsigned char buf[8192];
    size_t n = 0;
    bool high = false;
    while ((n = fread(buf, 1, sizeof(buf), file)) > 0) {
        for (size_t i = 0; i < n; ++i) {
            if (buf[i] > 127) high = true;
        }
    }
    *readable = ferror(file) == 0;
    fclose(file);
    return high;
}

static bool ws_byte(unsigned char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

static bool b64_char(unsigned char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
}

static bool hex_char(unsigned char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

// mailmime_part_parse skips illegal base64 and ignores a broken quoted-printable tail.
// A finished body that the reader would reject is damaged.
static int finished_base64(const std::string & in) {
    int alpha = 0;
    int pad = 0;
    bool padding = false;
    int in_group = 0;
    for (unsigned char c : in) {
        if (ws_byte(c)) continue;
        if (c == '=') {
            if (pad >= 2) return 1;
            padding = true;
            ++pad;
            continue;
        }
        if (padding || !b64_char(c)) return 1;
        ++in_group;
        ++alpha;
        if (in_group == 4) in_group = 0;
    }
    if (padding) {
        int mod = alpha % 4;
        if (pad == 1 && mod != 3) return 1;
        if (pad == 2 && mod != 2) return 1;
        if (pad == 0) return 1;
        return 0;
    }
    return in_group == 0 ? 0 : 1;
}

static int finished_qp(const std::string & in) {
    size_t i = 0;
    while (i < in.size()) {
        if (in[i] != '=') {
            ++i;
            continue;
        }
        if (i + 1 >= in.size()) return 1;
        unsigned char n = static_cast<unsigned char>(in[i + 1]);
        if (n == '\n') {
            i += 2;
            continue;
        }
        if (n == '\r') {
            if (i + 2 < in.size() && in[i + 2] == '\n') i += 3;
            else i += 2;
            continue;
        }
        if (i + 2 >= in.size()) return 1;
        if (!hex_char(static_cast<unsigned char>(in[i + 1])) || !hex_char(static_cast<unsigned char>(in[i + 2]))) {
            return 1;
        }
        i += 3;
    }
    return 0;
}

static int decode_part(const std::string & in, int mech, std::string * out) {
    if (mech == MAILMIME_MECHANISM_BASE64) {
        if (finished_base64(in) != 0) return 1;
    } else if (mech == MAILMIME_MECHANISM_QUOTED_PRINTABLE) {
        if (finished_qp(in) != 0) return 1;
    }
    if (in.empty()) return 0;
    size_t indx = 0;
    char * result = nullptr;
    size_t result_len = 0;
    int r = mailmime_part_parse(in.data(), in.size(), &indx, mech, &result, &result_len);
    if (r != MAILIMF_NO_ERROR || result == nullptr) {
        if (result != nullptr) mmap_string_unref(result);
        return 1;
    }
    out->append(result, result_len);
    mmap_string_unref(result);
    return 0;
}

static char * dup_text(const char * text) {
    if (text == nullptr) text = "";
    size_t n = strlen(text);
    char * copy = static_cast<char *>(malloc(n + 1u));
    if (copy == nullptr) return nullptr;
    memcpy(copy, text, n + 1u);
    return copy;
}

static struct mailmime_disposition * disposition_of(const char * text) {
    if (text == nullptr || text[0] == 0) return nullptr;
    const char * parm = strchr(text, ';');
    if (parm != nullptr) {
        ++parm;
        while (*parm == ' ' || *parm == '\t') ++parm;
    }
    struct mailmime_disposition_type * type =
        mailmime_disposition_type_new(MAILMIME_DISPOSITION_TYPE_ATTACHMENT, nullptr);
    if (type == nullptr) return nullptr;
    clist * list = clist_new();
    if (list == nullptr) {
        mailmime_disposition_type_free(type);
        return nullptr;
    }
    if (parm != nullptr && strncasecmp(parm, "filename*=", 10) == 0) {
        char * name = dup_text("filename*");
        char * value = dup_text(parm + 10);
        struct mailmime_parameter * param = name != nullptr && value != nullptr
            ? mailmime_parameter_new(name, value) : nullptr;
        if (param == nullptr) {
            free(name);
            free(value);
        } else {
            struct mailmime_disposition_parm * item = mailmime_disposition_parm_new(
                MAILMIME_DISPOSITION_PARM_PARAMETER, nullptr, nullptr, nullptr, nullptr, 0, param);
            if (item == nullptr || clist_append(list, item) != 0) {
                if (item != nullptr) mailmime_disposition_parm_free(item);
                else mailmime_parameter_free(param);
            }
        }
    } else if (parm != nullptr && strncasecmp(parm, "filename=", 9) == 0) {
        const char * value = parm + 9;
        std::string inside;
        if (*value == '"') {
            ++value;
            while (*value != 0 && *value != '"') {
                if (*value == '\\' && value[1] != 0) {
                    inside.push_back(value[1]);
                    value += 2;
                } else {
                    inside.push_back(*value);
                    ++value;
                }
            }
        } else {
            inside.assign(value);
        }
        char * owned = dup_text(inside.c_str());
        struct mailmime_disposition_parm * item = owned == nullptr ? nullptr
            : mailmime_disposition_parm_new(
                MAILMIME_DISPOSITION_PARM_FILENAME, owned, nullptr, nullptr, nullptr, 0, nullptr);
        if (item == nullptr || clist_append(list, item) != 0) {
            if (item != nullptr) mailmime_disposition_parm_free(item);
            else free(owned);
        }
    }
    struct mailmime_disposition * dsp = mailmime_disposition_new(type, list);
    if (dsp == nullptr) {
        clist_foreach(list, reinterpret_cast<clist_func>(mailmime_disposition_parm_free), nullptr);
        clist_free(list);
        mailmime_disposition_type_free(type);
    }
    return dsp;
}

static struct mailmime_fields * fields_for(int mech, const char * token, struct mailmime_disposition * dsp) {
    char * owned = token != nullptr ? dup_text(token) : nullptr;
    if (token != nullptr && owned == nullptr) {
        if (dsp != nullptr) mailmime_disposition_free(dsp);
        return nullptr;
    }
    struct mailmime_mechanism * enc = mailmime_mechanism_new(mech, owned);
    if (enc == nullptr) {
        free(owned);
        if (dsp != nullptr) mailmime_disposition_free(dsp);
        return nullptr;
    }
    struct mailmime_fields * fields = mailmime_fields_new_with_data(enc, nullptr, nullptr, dsp, nullptr);
    if (fields == nullptr) {
        mailmime_mechanism_free(enc);
        if (dsp != nullptr) mailmime_disposition_free(dsp);
    }
    return fields;
}

static struct mailmime * single_part(const char * content_type, struct mailmime_fields * fields) {
    const char * type = content_type != nullptr && content_type[0] != 0
        ? content_type : "application/octet-stream";
    struct mailmime_content * content = mailmime_content_new_with_str(type);
    if (content == nullptr) content = mailmime_content_new_with_str("application/octet-stream");
    if (content == nullptr) {
        mailmime_fields_free(fields);
        return nullptr;
    }
    // Attachments stay a single part. message/rfc822 must not become a nested message node.
    struct mailmime * part = mailmime_new(MAILMIME_SINGLE, nullptr, 0, fields, content,
        nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
    if (part == nullptr) {
        mailmime_content_free(content);
        mailmime_fields_free(fields);
    }
    return part;
}

static int attach_text(struct mailmime * part, const std::string & text, int mech) {
    struct mailmime_data * data = mailmime_data_new(MAILMIME_DATA_TEXT, mech, 0,
        text.data(), text.size(), nullptr);
    if (data == nullptr) return -1;
    part->mm_body = data;
    part->mm_data.mm_single = data;
    return 0;
}

static int attach_file(struct mailmime * part, const char * path, int mech, int encoded) {
    char * name = dup_text(path);
    if (name == nullptr) return -1;
    struct mailmime_data * data = mailmime_data_new(MAILMIME_DATA_FILE, mech, encoded, nullptr, 0, name);
    if (data == nullptr) {
        free(name);
        return -1;
    }
    part->mm_body = data;
    part->mm_data.mm_single = data;
    return 0;
}

static struct mailmime * text_part(const char * content_type, const std::string & text, int eight_bit,
    std::vector<std::string> * held) {
    held->push_back(text);
    const std::string & stored = held->back();
    int mech = text_mechanism(stored, eight_bit);
    struct mailmime_fields * fields = fields_for(mech, nullptr, nullptr);
    if (fields == nullptr) return nullptr;
    struct mailmime * part = single_part(content_type, fields);
    if (part == nullptr) return nullptr;
    if (attach_text(part, stored, mech) != 0) {
        mailmime_free(part);
        return nullptr;
    }
    return part;
}

// Content-Type is written before a missing boundary would be invented for the delimiters.
// The parameter has to already be on the multipart, or the body is not a parseable part.
static struct mailmime * multiple_part(const char * type) {
    char * boundary = mailmime_generate_boundary();
    if (boundary == nullptr) return nullptr;
    std::string spec;
    spec.append(type != nullptr ? type : "multipart/mixed");
    spec.append("; boundary=");
    spec.append(boundary);
    free(boundary);
    struct mailmime_fields * fields = mailmime_fields_new_empty();
    if (fields == nullptr) return nullptr;
    struct mailmime_content * content = mailmime_content_new_with_str(spec.c_str());
    char boundary_key[] = "boundary";
    char * stored = content != nullptr && content->ct_parameters != nullptr
        ? mailmime_content_param_get(content, boundary_key) : nullptr;
    if (stored == nullptr || stored[0] == 0) {
        if (content != nullptr) mailmime_content_free(content);
        mailmime_fields_free(fields);
        return nullptr;
    }
    clist * list = clist_new();
    if (list == nullptr) {
        mailmime_content_free(content);
        mailmime_fields_free(fields);
        return nullptr;
    }
    struct mailmime * part = mailmime_new(MAILMIME_MULTIPLE, nullptr, 0, fields, content,
        nullptr, nullptr, nullptr, list, nullptr, nullptr);
    if (part == nullptr) {
        clist_free(list);
        mailmime_content_free(content);
        mailmime_fields_free(fields);
    }
    return part;
}

static const char * part_name_at(const char ** part_names, int index) {
    if (part_names == nullptr || part_names[index] == nullptr || part_names[index][0] == 0) return "attachment";
    return part_names[index];
}

static void remember_damaged(char * damaged, size_t cap, const char * name) {
    set_text(damaged, cap, name);
}

static struct mailimf_fields * header_fields(const char ** names, const char ** values, int count, char * error, size_t error_cap) {
    struct mailimf_fields * fields = mailimf_fields_new_empty();
    if (fields == nullptr) {
        set_error(error, error_cap, "could not write the message");
        return nullptr;
    }
    for (int i = 0; i < count; ++i) {
        const char * name = names != nullptr && names[i] != nullptr ? names[i] : "";
        const char * value = values != nullptr && values[i] != nullptr ? values[i] : "";
        if (name[0] == 0) continue;
        char * owned_name = dup_text(name);
        char * owned_value = dup_text(value);
        struct mailimf_field * field = nullptr;
        if (owned_name != nullptr && owned_value != nullptr) {
            field = mailimf_field_new_custom(owned_name, owned_value);
        }
        if (field == nullptr || mailimf_fields_add(fields, field) != MAILIMF_NO_ERROR) {
            if (field != nullptr) {
                mailimf_field_free(field);
            } else if (owned_name == nullptr || owned_value == nullptr) {
                free(owned_name);
                free(owned_value);
            }
            mailimf_fields_free(fields);
            set_error(error, error_cap, "could not write the message");
            return nullptr;
        }
    }
    return fields;
}

extern "C" int mime_build_file(const char * path,
    const char ** header_names, const char ** header_values, int header_count,
    const char * body, const char * html_body, int flowed, int eight_bit,
    const char ** part_paths, const char ** part_types, const char ** part_dispositions,
    const char ** part_names, const char ** part_encodings, int part_count,
    char * damaged, size_t damaged_cap, char * error, size_t error_cap) {
    if (damaged != nullptr && damaged_cap > 0) damaged[0] = 0;
    if (error != nullptr && error_cap > 0) error[0] = 0;
    if (path == nullptr || path[0] == 0) {
        set_error(error, error_cap, "could not write the message");
        return 2;
    }
    std::vector<std::string> held;
    held.reserve(static_cast<size_t>(part_count) + 4u);
    const bool html = html_body != nullptr && html_body[0] != 0;
    const char * plain_type = (!html && flowed)
        ? "text/plain; charset=utf-8; format=flowed; delsp=yes"
        : "text/plain; charset=utf-8";
    struct mailmime * plain = text_part(plain_type, crlf_text(body), eight_bit, &held);
    if (plain == nullptr) {
        set_error(error, error_cap, "could not write the message");
        return 2;
    }
    struct mailmime * root = plain;
    if (html) {
        struct mailmime * html_part = text_part("text/html; charset=utf-8", crlf_text(html_body), eight_bit, &held);
        struct mailmime * alternative = html_part != nullptr ? multiple_part("multipart/alternative") : nullptr;
        int plain_added = 0;
        int html_added = 0;
        if (alternative != nullptr && html_part != nullptr &&
            mailmime_add_part(alternative, plain) == MAILIMF_NO_ERROR) {
            plain_added = 1;
            if (mailmime_add_part(alternative, html_part) == MAILIMF_NO_ERROR) html_added = 1;
        }
        if (!plain_added || !html_added) {
            if (!plain_added) mailmime_free(plain);
            if (html_part != nullptr && !html_added) mailmime_free(html_part);
            if (alternative != nullptr) mailmime_free(alternative);
            set_error(error, error_cap, "could not write the message");
            return 2;
        }
        root = alternative;
    }
    if (part_count > 0) {
        struct mailmime * mixed = multiple_part("multipart/mixed");
        int root_added = mixed != nullptr && mailmime_add_part(mixed, root) == MAILIMF_NO_ERROR;
        if (!root_added) {
            mailmime_free(root);
            if (mixed != nullptr) mailmime_free(mixed);
            set_error(error, error_cap, "could not write the message");
            return 2;
        }
        root = mixed;
        for (int i = 0; i < part_count; ++i) {
            const char * part_path = part_paths != nullptr ? part_paths[i] : nullptr;
            const char * name = part_name_at(part_names, i);
            if (part_path == nullptr || part_path[0] == 0) {
                mailmime_free(root);
                remember_damaged(damaged, damaged_cap, name);
                return 1;
            }
            std::string token = lower_token(part_encodings != nullptr ? part_encodings[i] : "");
            int encoded = 1;
            int mech = MAILMIME_MECHANISM_BASE64;
            const char * literal = nullptr;
            const std::string * decoded_text = nullptr;
            if (token.empty() || token == "raw") {
                encoded = 0;
                mech = MAILMIME_MECHANISM_BASE64;
            } else if (token == "base64" || token == "quoted-printable") {
                std::string wire;
                if (read_file(part_path, &wire) != 0) {
                    mailmime_free(root);
                    remember_damaged(damaged, damaged_cap, name);
                    return 1;
                }
                mech = token == "base64" ? MAILMIME_MECHANISM_BASE64 : MAILMIME_MECHANISM_QUOTED_PRINTABLE;
                std::string decoded;
                if (decode_part(wire, mech, &decoded) != 0) {
                    mailmime_free(root);
                    remember_damaged(damaged, damaged_cap, name);
                    return 1;
                }
                held.push_back(std::move(decoded));
                decoded_text = &held.back();
                encoded = 0;
            } else if (token == "auto") {
                int readable = 0;
                bool high = file_has_high(part_path, &readable);
                if (!readable) {
                    mailmime_free(root);
                    remember_damaged(damaged, damaged_cap, name);
                    return 1;
                }
                mech = high ? MAILMIME_MECHANISM_8BIT : MAILMIME_MECHANISM_7BIT;
                encoded = 1;
            } else {
                mech = mechanism_of(token, &literal);
                encoded = 1;
            }
            struct mailmime_disposition * dsp = disposition_of(
                part_dispositions != nullptr ? part_dispositions[i] : nullptr);
            struct mailmime_fields * fields = fields_for(mech, literal, dsp);
            struct mailmime * part = fields != nullptr
                ? single_part(part_types != nullptr ? part_types[i] : nullptr, fields) : nullptr;
            if (part == nullptr) {
                mailmime_free(root);
                set_error(error, error_cap, "could not write the message");
                return 2;
            }
            int attached = decoded_text != nullptr
                ? attach_text(part, *decoded_text, mech)
                : attach_file(part, part_path, mech, encoded);
            if (attached != 0 || mailmime_add_part(root, part) != MAILIMF_NO_ERROR) {
                mailmime_free(part);
                mailmime_free(root);
                set_error(error, error_cap, "could not write the message");
                return 2;
            }
        }
    }
    struct mailimf_fields * fields = header_fields(header_names, header_values, header_count, error, error_cap);
    struct mailmime * message = fields != nullptr ? mailmime_new_message_data(root) : nullptr;
    if (message == nullptr) {
        if (fields != nullptr) mailimf_fields_free(fields);
        mailmime_free(root);
        set_error(error, error_cap, "could not write the message");
        return 2;
    }
    mailmime_set_imf_fields(message, fields);
    FILE * out = fopen(path, "wb");
    if (out == nullptr) {
        mailmime_free(message);
        set_error(error, error_cap, "could not write the message");
        return 2;
    }
    int col = 0;
    int wrote = mailmime_write_file(out, &col, message);
    if (fclose(out) != 0) wrote = MAILIMF_ERROR_FILE;
    mailmime_free(message);
    if (wrote != MAILIMF_NO_ERROR) {
        remove(path);
        set_error(error, error_cap, "could not write the message");
        return 2;
    }
    return 0;
}

#ifdef LIVEIMAP_FUZZ_MIME_ROUNDTRIP
extern "C" int LLVMFuzzerTestOneInput(const uint8_t * data, size_t size) {
    if (data == nullptr || size < 2) return 0;
    char dir[] = "/tmp/liveimap-mime-XXXXXX";
    if (mkdtemp(dir) == nullptr) return 0;
    std::string part = std::string(dir) + "/part";
    std::string dest = std::string(dir) + "/out.rfc822";
    FILE * file = fopen(part.c_str(), "wb");
    if (file != nullptr) {
        fwrite(data + 1, 1, size - 1, file);
        fclose(file);
    }
    unsigned flags = data[0];
    const char * names[] = {"Date", "From", "Message-ID"};
    const char * values[] = {
        "Tue, 30 Sep 2026 00:00:00 +0000",
        "Me <me@example.com>",
        "<fuzz@example.com>",
    };
    std::string body(reinterpret_cast<const char *>(data + 1), size - 1);
    for (char & ch : body) {
        if (ch == 0) ch = ' ';
    }
    const char * paths[] = {part.c_str()};
    const char * types[] = {"application/octet-stream; name=\"a.bin\""};
    const char * dispositions[] = {"attachment; filename=\"a.bin\""};
    const char * part_names[] = {"a.bin"};
    const char * encoding = (flags & 1u) != 0 ? "base64" : "raw";
    const char * encodings[] = {encoding};
    char damaged[128];
    char error[128];
    const char * html = (flags & 2u) != 0 ? "<p>x</p>" : "";
    mime_build_file(dest.c_str(), names, values, 3, body.c_str(), html,
        (flags & 4u) != 0, (flags & 8u) != 0,
        paths, types, dispositions, part_names, encodings, 1,
        damaged, sizeof(damaged), error, sizeof(error));
    remove(part.c_str());
    remove(dest.c_str());
    rmdir(dir);
    return 0;
}
#endif
