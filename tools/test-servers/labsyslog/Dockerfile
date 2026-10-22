FROM debian:bookworm@sha256:2c037a04925515fdd6ea85ea14a682d0e79931f5e9f5d07b6dbfc6ba12f9e858
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY labsyslog.c /src/labsyslog.c
RUN gcc -O2 -static -o /labsyslog /src/labsyslog.c
