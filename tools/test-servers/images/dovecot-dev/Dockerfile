FROM debian:13-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git ca-certificates autoconf automake libtool gettext \
    pkg-config bison flex libssl-dev zlib1g-dev liblz4-dev libzstd-dev \
    libcap-dev libsodium-dev libpam0g-dev libsqlite3-dev libicu-dev \
    python3 wget xz-utils \
    && rm -rf /var/lib/apt/lists/*
COPY . /src
WORKDIR /src
RUN ./autogen.sh
RUN ./configure --prefix=/usr/local --with-sqlite --with-icu --with-ssl=openssl > /tmp/configure.out 2>&1; \
    code=$?; \
    cat /tmp/configure.out; \
    if [ "$code" -ne 0 ]; then exit "$code"; fi; \
    if grep -E '^                 :' /tmp/configure.out | grep -Ei 'sqlite|icu|openssl|sodium|lz4|zstd|libcap|pam'; then \
      echo "configure named a missing dependency" >&2; \
      exit 1; \
    fi
RUN make -j4 && make install
RUN groupadd -g 1000 vmail \
    && useradd -u 1000 -g 1000 -d /srv/vmail -M -s /usr/sbin/nologin vmail \
    && groupadd --system dovecot \
    && useradd --system -g dovecot -d /nonexistent -M -s /usr/sbin/nologin dovecot \
    && mkdir -p /usr/local/etc/dovecot /srv/vmail /run/dovecot \
    && chown vmail:vmail /srv/vmail
COPY dovecot-lab.conf /usr/local/etc/dovecot/dovecot.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
