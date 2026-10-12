/* Host stubs: the fuzz/boundary builds parse server responses only; SASL is never initialized. */
int sasl_client_init(const void * c) { (void) c; return -1; }
int sasl_client_add_plugin(const char * n, void * f) { (void) n; (void) f; return -1; }
const char ** sasl_global_listmech(void) { return 0; }
int scram_client_plug_init(void) { return -1; }
int crammd5_client_plug_init(void) { return -1; }
int digestmd5_client_plug_init(void) { return -1; }
int plain_client_plug_init(void) { return -1; }
int login_client_plug_init(void) { return -1; }
