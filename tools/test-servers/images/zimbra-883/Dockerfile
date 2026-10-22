# syntax=docker/dockerfile:1
FROM liveimap-zimbra-883:lan AS upstream
FROM scratch
COPY --from=upstream / /
COPY zimbra.env /etc/lab/zimbra.env
COPY entrypoint.sh /entrypoint.sh
RUN chmod 600 /etc/lab/zimbra.env && chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
