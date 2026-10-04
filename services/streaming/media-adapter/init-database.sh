#!/bin/sh
set -eu
# Separate role/database: the technical adapter has no domain-table grants.
psql -v ON_ERROR_STOP=1 -v media_password="$MEDIA_DATABASE_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE media_adapter LOGIN PASSWORD %L', :'media_password')
WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='media_adapter')\gexec
SELECT 'CREATE DATABASE streaming_media OWNER media_adapter'
WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='streaming_media')\gexec
SQL
