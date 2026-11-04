#!/bin/sh
set -eu
# Sentinel rewrites its config; the working file must be writable, not bind-mounted.
cat > /tmp/sentinel.conf <<EOF
port 26379
dir /tmp
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
sentinel announce-ip ${SENTINEL_HOST}
sentinel monitor flights redis-master 6379 2
sentinel down-after-milliseconds flights 3000
sentinel failover-timeout flights 10000
sentinel parallel-syncs flights 1
EOF
exec redis-server /tmp/sentinel.conf --sentinel
