FROM debian:bookworm
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential autoconf automake libtool pkg-config bison flex gperf \
    libssl-dev libjansson-dev libxml2-dev libsqlite3-dev libical-dev \
    libsasl2-dev libsasl2-modules sasl2-bin libpcre2-dev uuid-dev \
    libicu-dev libxapian-dev zlib1g-dev libnghttp2-dev \
    && rm -rf /var/lib/apt/lists/*
COPY xxd /usr/local/bin/xxd
COPY rsync /usr/local/bin/rsync
RUN chmod 755 /usr/local/bin/xxd /usr/local/bin/rsync
COPY cyrus-imapd-3.12.4.tar.gz /tmp/cyrus-imapd-3.12.4.tar.gz
RUN tar -xzf /tmp/cyrus-imapd-3.12.4.tar.gz -C /tmp \
    && cd /tmp/cyrus-imapd-3.12.4 \
    && ./configure --prefix=/usr/local/cyrus --enable-http --enable-jmap --enable-xapian \
    && make -j4 \
    && make install \
    && rm -rf /tmp/cyrus-imapd-3.12.4 /tmp/cyrus-imapd-3.12.4.tar.gz
COPY imapd.conf /etc/imapd.conf
COPY cyrus.conf /etc/cyrus.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
