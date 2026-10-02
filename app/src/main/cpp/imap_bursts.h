#pragma once

#include <libetpan/libetpan.h>

#include <map>
#include <string>
#include <vector>

struct PreviewAsk {
    uint32_t uid = 0;
    std::string section;
    std::string charset;
};

struct mailimap_section * sectionFromSpec(const std::string & spec);
std::string previewSectionText(clist * list, const std::string & charsetName);
bool taggedOk(struct mailimap_response * response);
void dropStatus(mailimap * imap);
void dropFetchList(mailimap * imap);
int statusMessagesPipeline(mailimap * imap, const std::vector<std::string> & mailboxes,
    std::map<std::string, int> * out, bool pipeline);
int previewBurst(mailimap * imap, const std::vector<PreviewAsk> & asks, int byteLimit,
    std::vector<std::string> & previews, bool pipeline);
