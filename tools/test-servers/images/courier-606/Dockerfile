FROM debian:bookworm
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*
COPY courier-imap-6.0.6.tar.bz2 /src/courier-imap-6.0.6.tar.bz2
COPY courier-unicode-2.6.0.tar.bz2 /src/courier-unicode-2.6.0.tar.bz2
COPY build-inside.sh /src/build-inside.sh
RUN chmod 755 /src/build-inside.sh && /src/build-inside.sh
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
