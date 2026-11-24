\getenv writer_password AUDIT_WRITER_DB_PASSWORD
\getenv reader_password AUDIT_READER_DB_PASSWORD
CREATE SCHEMA audit;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE TABLE audit.events (
 position bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 event_id uuid PRIMARY KEY,
 producer text NOT NULL CONSTRAINT events_producer_check CHECK (producer IN ('core','vod','waf')),
 actor_id text,
 action text NOT NULL,
 target_type text NOT NULL,
 target_id text NOT NULL,
 outcome text NOT NULL CHECK (outcome IN ('SUCCESS','DENIED')),
 reason_code text NOT NULL,
 occurred_at_utc timestamptz NOT NULL,
 received_at_utc timestamptz NOT NULL DEFAULT clock_timestamp(),
 request_id text NOT NULL,
 event jsonb NOT NULL
);
CREATE INDEX ON audit.events(actor_id,position);
CREATE INDEX ON audit.events(target_id,position);
CREATE INDEX ON audit.events(request_id,position);
CREATE INDEX ON audit.events(occurred_at_utc);
CREATE TABLE audit.reads (
 position bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 actor_id text NOT NULL,
 occurred_at_utc timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE ROLE audit_writer LOGIN PASSWORD :'writer_password';
CREATE ROLE audit_reader LOGIN PASSWORD :'reader_password';
REVOKE ALL ON DATABASE audit FROM PUBLIC;
GRANT CONNECT ON DATABASE audit TO audit_writer,audit_reader;
GRANT USAGE ON SCHEMA audit TO audit_writer,audit_reader;
GRANT INSERT,SELECT ON audit.events TO audit_writer;
GRANT INSERT ON audit.reads TO audit_writer;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA audit TO audit_writer;
GRANT SELECT ON audit.events,audit.reads TO audit_reader;
-- Runtime roles cannot UPDATE, DELETE, TRUNCATE or create replacement tables.
ALTER ROLE audit_reader SET default_transaction_read_only=on;
ALTER ROLE audit_writer SET statement_timeout='3s';
ALTER ROLE audit_reader SET statement_timeout='2s';
