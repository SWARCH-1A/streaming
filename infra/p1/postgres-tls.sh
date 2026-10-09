#!/bin/sh
set -eu
mkdir -p /tmp/p1-tls
cp /run/secrets/server.crt /tmp/p1-tls/server.crt
cp /run/secrets/server.key /tmp/p1-tls/server.key
chown -R postgres:postgres /tmp/p1-tls
chmod 700 /tmp/p1-tls
chmod 600 /tmp/p1-tls/server.key
if [ -f /init-database.sh ]; then
  exec /bin/sh /init-database.sh "$@"
fi
exec docker-entrypoint.sh "$@"
