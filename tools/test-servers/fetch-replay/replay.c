#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include "clist.h"
#include "imap_test_utils.h"
#include "mailimap.h"
#include "mailimap_types_helper.h"
int main(int argc, char ** argv) {
  FILE * f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  char * in = malloc(n + 1); fread(in, 1, n, f); in[n] = 0; fclose(f);
  MMAPString * output;
  mailimap * s = mailimap_new(0, NULL);
  s->imap_stream = imap_test_stream_from_string_with_output(in, &output);
  s->imap_state = MAILIMAP_STATE_SELECTED;
  s->imap_selection_info = mailimap_selection_info_new();
  clist * reqs = clist_new();
  for (int i = 1; i <= 32; i++) {
    struct mailimap_fetch_type * ft = mailimap_fetch_type_new_fetch_att_list_empty();
    clist * part = clist_new(); uint32_t * one = malloc(4); *one = 1; clist_append(part, one);
    struct mailimap_section * sec = mailimap_section_new_part(mailimap_section_part_new(part));
    mailimap_fetch_type_new_fetch_att_list_add(ft, mailimap_fetch_att_new_body_peek_section_partial(sec, 0, 2048));
    mailimap_fetch_type_new_fetch_att_list_add(ft, mailimap_fetch_att_new_uid());
    clist_append(reqs, mailimap_uid_fetch_request_new(mailimap_set_new_single(i), ft));
  }
  int cmd[32]; clist * result = NULL;
  int r = mailimap_uid_fetch_multiple(s, reqs, &result, cmd);
  int ok = 0; for (int i = 0; i < 32; i++) ok += cmd[i] == MAILIMAP_NO_ERROR;
  printf("%s: r=%d results=%d cmdOk=%d\n", argv[1], r, result ? clist_count(result) : -1, ok);
  return 0;
}
