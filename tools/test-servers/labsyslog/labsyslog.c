/* Lab seed images: minimal syslog sink. Binds /dev/log (datagram) and copies every message to stderr, so
 * servers that only log to syslog (Cyrus, Courier, old Dovecot, UW imapd) show up in `docker logs`.
 * Built static in a build stage and copied into each seed image. Usage: labsyslog & (before exec server). */
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(void) {
    struct sockaddr_un a;
    char buf[8192];
    int s = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (s < 0) { perror("labsyslog socket"); return 1; }
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    strcpy(a.sun_path, "/dev/log");
    unlink(a.sun_path);
    if (bind(s, (struct sockaddr *)&a, sizeof a) < 0) { perror("labsyslog bind /dev/log"); return 1; }
    for (;;) {
        ssize_t n = recv(s, buf, sizeof buf - 1, 0);
        if (n <= 0) continue;
        while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\0')) n--;
        buf[n] = '\n';
        if (write(2, buf, n + 1) < 0) return 1;
    }
}
