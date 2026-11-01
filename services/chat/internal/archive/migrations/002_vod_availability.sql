-- Chat P2 (ADR-024): el vínculo VOD sigue el payload de vod.availability.changed.v1 definido por VOD
-- (ADR-022). El mapping temporal ya no se copia: se consulta en p2.vod.replay-context. Ningún evento
-- con el payload anterior pudo aplicarse, así que no hay vínculos que conservar.
DROP TABLE chat_vod_links;
DELETE FROM chat_broker_inbox WHERE consumer = 'chat.vod';

CREATE TABLE chat_vod_links (
    vod_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    mapping_version BIGINT NOT NULL CHECK (mapping_version >= 0),
    available BOOLEAN NOT NULL,
    restriction TEXT NOT NULL CHECK (restriction IN ('NONE', 'RETIRED', 'DELETED')),
    publication_state TEXT NOT NULL CHECK (publication_state IN ('DRAFT', 'PUBLISHED')),
    updated_at_utc TIMESTAMPTZ NOT NULL
);
CREATE INDEX chat_vod_links_session ON chat_vod_links (session_id);
