FROM debian:bookworm
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential autoconf automake libtool pkg-config bison flex gperf \
    cmake libmoo-perl libapp-cmd-perl libtype-tiny-perl \
    libssl-dev libjansson-dev libxml2-dev libsqlite3-dev libical-dev \
    libsasl2-dev libsasl2-modules sasl2-bin libpcre2-dev uuid-dev \
    libicu-dev libxapian-dev zlib1g-dev libnghttp2-dev \
    && rm -rf /var/lib/apt/lists/*
COPY xxd /usr/local/bin/xxd
COPY rsync /usr/local/bin/rsync
RUN chmod 755 /usr/local/bin/xxd /usr/local/bin/rsync
# Bookworm libical is 3.0. Cyrus 3.13 http requires libical >= 3.99.99.
COPY libical-4.0.5.tar.gz /tmp/libical-4.0.5.tar.gz
RUN tar -xzf /tmp/libical-4.0.5.tar.gz -C /tmp \
    && cmake -S /tmp/libical-4.0.5 -B /tmp/libical-build \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=/usr/local \
      -DLIBICAL_GLIB=OFF \
      -DLIBICAL_GOBJECT_INTROSPECTION=OFF \
      -DLIBICAL_JAVA_BINDINGS=OFF \
      -DLIBICAL_CXX_BINDINGS=OFF \
      -DLIBICAL_BUILD_DOCS=OFF \
      -DLIBICAL_BUILD_EXAMPLES=OFF \
    && cmake --build /tmp/libical-build -j4 \
    && cmake --install /tmp/libical-build \
    && printf '%s\n' /usr/local/lib > /etc/ld.so.conf.d/usr-local.conf \
    && ldconfig \
    && rm -rf /tmp/libical-4.0.5 /tmp/libical-build /tmp/libical-4.0.5.tar.gz
ENV PKG_CONFIG_PATH=/usr/local/lib/pkgconfig:/usr/local/lib/x86_64-linux-gnu/pkgconfig
COPY . /src
RUN cd /src \
    && autoreconf -i \
    && ./configure --prefix=/usr/local/cyrus --enable-http --enable-jmap --enable-xapian \
    && make -j4 \
    && make install \
    && rm -rf /src
COPY imapd.conf /etc/imapd.conf
COPY cyrus.conf /etc/cyrus.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
