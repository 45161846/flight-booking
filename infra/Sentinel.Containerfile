FROM docker.io/library/redis:7.4
# Sentinel rewrites its configuration. Keep it in the writable container layer.
COPY --chown=redis:redis sentinel.conf /tmp/sentinel.conf
USER redis
ENTRYPOINT ["redis-server"]
CMD ["/tmp/sentinel.conf", "--sentinel"]
