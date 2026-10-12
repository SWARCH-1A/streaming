#!/bin/sh
set -eu
# Supervise the standard PostgreSQL entrypoint and provision Media on every boot,
# including existing volumes. TCP waits for the final server, not initdb's socket.
ready_file=/tmp/streaming-media-ready
rm -f "$ready_file"
docker-entrypoint.sh "$@" &
postgres_pid=$!
cleanup() {
    rm -f "$ready_file"
    if kill -0 "$postgres_pid" 2>/dev/null; then
        kill -TERM "$postgres_pid" 2>/dev/null || true
        wait "$postgres_pid" || true
    fi
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
until pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do
    if ! kill -0 "$postgres_pid" 2>/dev/null; then
        wait "$postgres_pid"
        exit 1
    fi
    sleep 1
done
export PGHOST=127.0.0.1 PGUSER="$POSTGRES_USER" PGDATABASE="$POSTGRES_DB"
export PGPASSWORD="$POSTGRES_PASSWORD"
# Separate role/database: the technical adapter has no domain-table grants.
psql -v ON_ERROR_STOP=1 -v media_password="$MEDIA_DATABASE_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE media_adapter LOGIN PASSWORD %L', :'media_password')
WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='media_adapter')\gexec
SELECT format('ALTER ROLE media_adapter LOGIN PASSWORD %L', :'media_password')\gexec
SELECT 'CREATE DATABASE streaming_media OWNER media_adapter'
WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='streaming_media')\gexec
SQL
touch "$ready_file"
wait "$postgres_pid"
