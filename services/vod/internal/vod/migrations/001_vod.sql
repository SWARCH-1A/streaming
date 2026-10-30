CREATE TABLE IF NOT EXISTS vod_recordings (
 id text PRIMARY KEY, session_id text NOT NULL UNIQUE, owner_id text NOT NULL, channel_id text NOT NULL,
 data jsonb NOT NULL, index_ref jsonb, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS vod_channel ON vod_recordings(channel_id,id);
CREATE INDEX IF NOT EXISTS vod_owner ON vod_recordings(owner_id,id);
CREATE TABLE IF NOT EXISTS vod_objects (
 object_id text PRIMARY KEY, vod_id text NOT NULL REFERENCES vod_recordings(id), identity text NOT NULL UNIQUE,
 ref jsonb NOT NULL, confirmed boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS vod_assets (
 vod_id text NOT NULL REFERENCES vod_recordings(id), path text NOT NULL, ref jsonb NOT NULL,
 mapping_version bigint NOT NULL, PRIMARY KEY(vod_id,path));
CREATE TABLE IF NOT EXISTS vod_timelines (vod_id text PRIMARY KEY REFERENCES vod_recordings(id), data jsonb NOT NULL);
CREATE TABLE IF NOT EXISTS vod_inbox (consumer text NOT NULL,event_id text NOT NULL,fingerprint text NOT NULL, PRIMARY KEY(consumer,event_id));
CREATE TABLE IF NOT EXISTS vod_outbox (event_id text PRIMARY KEY, body jsonb NOT NULL, sent boolean NOT NULL DEFAULT false);
CREATE TABLE IF NOT EXISTS vod_accounts (user_id text PRIMARY KEY, version bigint NOT NULL, status text NOT NULL);
CREATE TABLE IF NOT EXISTS vod_commands (actor text NOT NULL,key text NOT NULL,fingerprint text NOT NULL,response jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(actor,key));
CREATE TABLE IF NOT EXISTS vod_jobs (
 id text PRIMARY KEY,vod_id text NOT NULL REFERENCES vod_recordings(id),kind text NOT NULL,state text NOT NULL DEFAULT 'PENDING',
 attempts integer NOT NULL DEFAULT 0,error_code text NOT NULL DEFAULT '',next_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(vod_id,kind));
CREATE TABLE IF NOT EXISTS vod_tracks (vod_id text NOT NULL REFERENCES vod_recordings(id),track_id text PRIMARY KEY,data jsonb NOT NULL,removed boolean NOT NULL DEFAULT false);
CREATE TABLE IF NOT EXISTS vod_audit (id bigserial PRIMARY KEY,actor text NOT NULL,vod_id text NOT NULL,operation text NOT NULL,occurred_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE vod_audit ADD COLUMN IF NOT EXISTS reason text NOT NULL DEFAULT '';
ALTER TABLE vod_jobs ADD COLUMN IF NOT EXISTS actor_id text NOT NULL DEFAULT '';
