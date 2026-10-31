// Package archive es el repositorio PostgreSQL de Chat (ADR-024): copia durable de mensajes para
// replay, bloqueos por canal, retiros, operaciones de moderación, idempotencia y vínculos VOD.
// Redis sigue siendo el almacén del vivo; aquí solo escribe Chat.
package archive

import (
	"context"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"streaming/chat/internal/chat"
)

//go:embed migrations/*.sql
var migrations embed.FS

// DB encapsula el pool; ningún otro paquete escribe SQL.
type DB struct{ pool *pgxpool.Pool }

// Open conecta y verifica la base. El pool necesita al menos dos conexiones: la idempotencia retiene
// una transacción mientras la acción usa otra.
func Open(ctx context.Context, url string) (*DB, error) {
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, errors.New("CHAT_DATABASE_URL inválida")
	}
	if cfg.MaxConns < 4 {
		cfg.MaxConns = 4
	}
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return &DB{pool: pool}, nil
}

func (d *DB) Close()                         { d.pool.Close() }
func (d *DB) Ping(ctx context.Context) error { return d.pool.Ping(ctx) }

// Migrate aplica en orden las migraciones embebidas, una sola réplica a la vez.
func (d *DB) Migrate(ctx context.Context) error {
	conn, err := d.pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, `SELECT pg_advisory_lock(hashtextextended('chat.migrations', 0))`); err != nil {
		return err
	}
	defer conn.Exec(context.Background(), `SELECT pg_advisory_unlock(hashtextextended('chat.migrations', 0))`)
	if _, err := conn.Exec(ctx, `CREATE TABLE IF NOT EXISTS chat_schema_migrations (
		version TEXT PRIMARY KEY, applied_at_utc TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	names, err := fs.Glob(migrations, "migrations/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, name := range names {
		version := strings.TrimSuffix(strings.TrimPrefix(name, "migrations/"), ".sql")
		var applied bool
		if err := conn.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM chat_schema_migrations WHERE version=$1)`, version).Scan(&applied); err != nil {
			return err
		}
		if applied {
			continue
		}
		sql, err := migrations.ReadFile(name)
		if err != nil {
			return err
		}
		tx, err := conn.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(sql)); err != nil {
			tx.Rollback(ctx)
			return fmt.Errorf("migración %s: %w", version, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO chat_schema_migrations (version) VALUES ($1)`, version); err != nil {
			tx.Rollback(ctx)
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
	}
	return nil
}

// ---- Archivo ----

// Row es un mensaje confirmado en Redis con el canal de su sala.
type Row struct {
	ChannelID string
	Message   chat.Message
}

// InsertMessages copia mensajes confirmados. Es idempotente: un mensaje ya archivado, o retirado
// antes de llegar al archivo, no se reemplaza.
func (d *DB) InsertMessages(ctx context.Context, rows []Row) error {
	if len(rows) == 0 {
		return nil
	}
	tx, err := d.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	batch := &pgx.Batch{}
	for _, r := range rows {
		m := r.Message
		batch.Queue(`INSERT INTO chat_sessions (session_id, channel_id, first_message_at_utc, last_message_at_utc)
			VALUES ($1, $2, $3, $3)
			ON CONFLICT (session_id) DO UPDATE SET
			  first_message_at_utc = LEAST(COALESCE(chat_sessions.first_message_at_utc, EXCLUDED.first_message_at_utc), EXCLUDED.first_message_at_utc),
			  last_message_at_utc = GREATEST(COALESCE(chat_sessions.last_message_at_utc, EXCLUDED.last_message_at_utc), EXCLUDED.last_message_at_utc)`,
			m.SessionID, r.ChannelID, m.ServerCreatedAtUTC)
		batch.Queue(`INSERT INTO chat_messages (session_id, sequence, message_id, author_user_id, author_handle,
			author_display_name, author_avatar_uri, text, server_created_at_utc, stream_offset_ms, stream_generation)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
			ON CONFLICT DO NOTHING`,
			m.SessionID, m.Sequence, m.MessageID, m.Author.UserID, m.Author.Handle, m.Author.DisplayName,
			m.Author.AvatarURI, m.Text, m.ServerCreatedAtUTC, m.StreamOffsetMs, m.StreamGeneration)
	}
	if err := tx.SendBatch(ctx, batch).Close(); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// SessionChannel devuelve el canal de una sesión archivada.
func (d *DB) SessionChannel(ctx context.Context, sid string) (string, bool, error) {
	var channel string
	err := d.pool.QueryRow(ctx, `SELECT channel_id FROM chat_sessions WHERE session_id=$1`, sid).Scan(&channel)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	return channel, err == nil, err
}

const messageColumns = `session_id, sequence, message_id, author_user_id, author_handle, author_display_name,
	author_avatar_uri, text, server_created_at_utc, stream_offset_ms, stream_generation, removed_at_utc, removal_version`

type archived struct {
	msg     chat.Message
	removal *chat.Removal
}

func scanMessage(row pgx.Row) (archived, error) {
	var a archived
	var text *string
	var removedAt *time.Time
	var removalVersion *int64
	m := &a.msg
	if err := row.Scan(&m.SessionID, &m.Sequence, &m.MessageID, &m.Author.UserID, &m.Author.Handle,
		&m.Author.DisplayName, &m.Author.AvatarURI, &text, &m.ServerCreatedAtUTC, &m.StreamOffsetMs,
		&m.StreamGeneration, &removedAt, &removalVersion); err != nil {
		return a, err
	}
	m.ServerCreatedAtUTC = m.ServerCreatedAtUTC.UTC()
	if text != nil {
		m.Text = *text
	}
	if removedAt != nil && removalVersion != nil {
		a.removal = &chat.Removal{MessageID: m.MessageID, SessionID: m.SessionID, Sequence: m.Sequence,
			ModerationVersion: *removalVersion, RemovedAtUTC: removedAt.UTC()}
	}
	return a, nil
}

// ArchivedMessage busca un mensaje archivado; removal no es nil si ya fue retirado.
func (d *DB) ArchivedMessage(ctx context.Context, sid, messageID string) (chat.Message, *chat.Removal, bool, error) {
	a, err := scanMessage(d.pool.QueryRow(ctx, `SELECT `+messageColumns+` FROM chat_messages
		WHERE session_id=$1 AND message_id=$2`, sid, messageID))
	if errors.Is(err, pgx.ErrNoRows) {
		return chat.Message{}, nil, false, nil
	}
	if err != nil {
		return chat.Message{}, nil, false, err
	}
	return a.msg, a.removal, true, nil
}

// ArchivedByAuthor devuelve los mensajes vigentes de un autor en la sesión hasta upTo.
func (d *DB) ArchivedByAuthor(ctx context.Context, sid, userID string, upTo int64) ([]chat.Message, error) {
	rows, err := d.pool.Query(ctx, `SELECT `+messageColumns+` FROM chat_messages
		WHERE session_id=$1 AND author_user_id=$2 AND sequence<=$3 AND removed_at_utc IS NULL ORDER BY sequence`,
		sid, userID, upTo)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []chat.Message
	for rows.Next() {
		a, err := scanMessage(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a.msg)
	}
	return out, rows.Err()
}

// LastArchivedSequence es el mayor sequence archivado de la sesión.
func (d *DB) LastArchivedSequence(ctx context.Context, sid string) (int64, error) {
	var seq *int64
	err := d.pool.QueryRow(ctx, `SELECT max(sequence) FROM chat_messages WHERE session_id=$1`, sid).Scan(&seq)
	if err != nil || seq == nil {
		return 0, err
	}
	return *seq, nil
}

// ---- Retiros ----

// RemoveResult es el efecto durable de un retiro.
type RemoveResult struct {
	ModerationVersion int64
	Removals          []chat.Removal // nuevos y previos, para reflejarlos de forma idempotente en Redis
	Newly             int
}

// RemoveMessages retira mensajes conocidos (de Redis o del archivo). Un mensaje aún no archivado se
// inserta ya retirado, sin texto; el archivador posterior no lo repone. Un mensaje ya retirado
// conserva su marca original. La moderationVersion de la sesión crece una vez por operación.
func (d *DB) RemoveMessages(ctx context.Context, channelID, sid, removedBy string, msgs []chat.Message, now time.Time) (RemoveResult, error) {
	tx, err := d.pool.Begin(ctx)
	if err != nil {
		return RemoveResult{}, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `INSERT INTO chat_sessions (session_id, channel_id) VALUES ($1, $2)
		ON CONFLICT (session_id) DO NOTHING`, sid, channelID); err != nil {
		return RemoveResult{}, err
	}
	// El bloqueo de fila serializa los retiros de una sesión y su moderationVersion.
	var current int64
	if err := tx.QueryRow(ctx, `SELECT moderation_version FROM chat_sessions WHERE session_id=$1 FOR UPDATE`, sid).Scan(&current); err != nil {
		return RemoveResult{}, err
	}
	next := current + 1
	res := RemoveResult{ModerationVersion: current}
	now = now.UTC().Truncate(time.Millisecond)
	for _, m := range msgs {
		var removedAt time.Time
		var version int64
		err := tx.QueryRow(ctx, `INSERT INTO chat_messages (session_id, sequence, message_id, author_user_id,
			author_handle, author_display_name, author_avatar_uri, text, server_created_at_utc, stream_offset_ms,
			stream_generation, removed_at_utc, removed_by_user_id, removal_version)
			VALUES ($1, $2, $3, $4, $5, $6, $7, NULL, $8, $9, $10, $11, $12, $13)
			ON CONFLICT (session_id, sequence) DO UPDATE SET text = NULL, removed_at_utc = EXCLUDED.removed_at_utc,
			  removed_by_user_id = EXCLUDED.removed_by_user_id, removal_version = EXCLUDED.removal_version
			WHERE chat_messages.removed_at_utc IS NULL AND chat_messages.message_id = EXCLUDED.message_id
			RETURNING removed_at_utc, removal_version`,
			sid, m.Sequence, m.MessageID, m.Author.UserID, m.Author.Handle, m.Author.DisplayName, m.Author.AvatarURI,
			m.ServerCreatedAtUTC, m.StreamOffsetMs, m.StreamGeneration, now, removedBy, next).Scan(&removedAt, &version)
		if errors.Is(err, pgx.ErrNoRows) {
			// Ya retirado: se devuelve su marca vigente.
			if err := tx.QueryRow(ctx, `SELECT removed_at_utc, removal_version FROM chat_messages
				WHERE session_id=$1 AND message_id=$2 AND removed_at_utc IS NOT NULL`, sid, m.MessageID).Scan(&removedAt, &version); err != nil {
				return RemoveResult{}, err
			}
		} else if err != nil {
			return RemoveResult{}, err
		} else {
			res.Newly++
		}
		res.Removals = append(res.Removals, chat.Removal{MessageID: m.MessageID, SessionID: sid, Sequence: m.Sequence,
			ModerationVersion: version, RemovedAtUTC: removedAt.UTC()})
	}
	if res.Newly > 0 {
		if _, err := tx.Exec(ctx, `UPDATE chat_sessions SET moderation_version=$2 WHERE session_id=$1`, sid, next); err != nil {
			return RemoveResult{}, err
		}
		res.ModerationVersion = next
	}
	return res, tx.Commit(ctx)
}

// ---- Bloqueos ----

func scanBlock(row pgx.Row, channelID, userID string) (chat.Block, error) {
	b := chat.Block{ChannelID: channelID, UserID: userID}
	var mode, duration *string
	err := row.Scan(&mode, &duration, &b.ExpiresAtUTC, &b.BlockedAtUTC, &b.BlockedByUserID, &b.Version)
	if errors.Is(err, pgx.ErrNoRows) {
		return b, nil
	}
	if err != nil {
		return b, err
	}
	if mode != nil {
		m := chat.BlockMode(*mode)
		b.Mode = &m
	}
	b.Duration = duration
	if b.ExpiresAtUTC != nil {
		t := b.ExpiresAtUTC.UTC()
		b.ExpiresAtUTC = &t
	}
	if b.BlockedAtUTC != nil {
		t := b.BlockedAtUTC.UTC()
		b.BlockedAtUTC = &t
	}
	return b, nil
}

const blockColumns = `mode, duration, expires_at_utc, blocked_at_utc, blocked_by_user_id, version`

// GetBlock devuelve el bloqueo registrado; Version 0 si nunca existió.
func (d *DB) GetBlock(ctx context.Context, channelID, userID string) (chat.Block, error) {
	return scanBlock(d.pool.QueryRow(ctx, `SELECT `+blockColumns+` FROM chat_blocks WHERE channel_id=$1 AND user_id=$2`,
		channelID, userID), channelID, userID)
}

// PutBlock registra un bloqueo; gana la última escritura y la versión crece. expires es nil si es permanente.
func (d *DB) PutBlock(ctx context.Context, channelID, userID, by string, mode chat.BlockMode, duration *string, expires *time.Time, now time.Time) (chat.Block, error) {
	now = now.UTC().Truncate(time.Millisecond)
	return scanBlock(d.pool.QueryRow(ctx, `INSERT INTO chat_blocks (channel_id, user_id, mode, duration, expires_at_utc,
		blocked_at_utc, blocked_by_user_id, version, updated_at_utc) VALUES ($1, $2, $3, $4, $5, $6, $7, 1, $6)
		ON CONFLICT (channel_id, user_id) DO UPDATE SET mode = EXCLUDED.mode, duration = EXCLUDED.duration,
		  expires_at_utc = EXCLUDED.expires_at_utc, blocked_at_utc = EXCLUDED.blocked_at_utc,
		  blocked_by_user_id = EXCLUDED.blocked_by_user_id, version = chat_blocks.version + 1,
		  updated_at_utc = EXCLUDED.updated_at_utc
		RETURNING `+blockColumns, channelID, userID, string(mode), duration, expires, now, by), channelID, userID)
}

// LiftBlock retira un bloqueo sin borrar mensajes. Sin registro previo devuelve el estado vacío.
func (d *DB) LiftBlock(ctx context.Context, channelID, userID string, now time.Time) (chat.Block, error) {
	b, err := scanBlock(d.pool.QueryRow(ctx, `UPDATE chat_blocks SET mode = NULL, duration = NULL, expires_at_utc = NULL,
		blocked_at_utc = NULL, blocked_by_user_id = NULL, version = version + 1, updated_at_utc = $3
		WHERE channel_id=$1 AND user_id=$2 RETURNING `+blockColumns, channelID, userID, now.UTC()), channelID, userID)
	return b, err
}

// ActiveBlocks lista los bloqueos vigentes para reconstruir la copia de Redis.
func (d *DB) ActiveBlocks(ctx context.Context, now time.Time) ([]chat.Block, error) {
	rows, err := d.pool.Query(ctx, `SELECT channel_id, user_id, `+blockColumns+` FROM chat_blocks
		WHERE mode = 'PERMANENT' OR (mode = 'TEMPORARY' AND expires_at_utc > $1)`, now.UTC())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []chat.Block
	for rows.Next() {
		var channel, user string
		var mode, duration *string
		b := chat.Block{}
		if err := rows.Scan(&channel, &user, &mode, &duration, &b.ExpiresAtUTC, &b.BlockedAtUTC, &b.BlockedByUserID, &b.Version); err != nil {
			return nil, err
		}
		b.ChannelID, b.UserID, b.Duration = channel, user, duration
		if mode != nil {
			m := chat.BlockMode(*mode)
			b.Mode = &m
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// ---- Operaciones de moderación ----

// Operation es el resultado de un retiro de mensajes por usuario.
type Operation struct {
	OperationID       string
	ChannelID         string
	SessionID         string
	TargetUserID      string
	RequestedBy       string
	UpToSequence      int64
	RemovedCount      int
	ModerationVersion int64
	Status            string
	CreatedAtUTC      time.Time
	CompletedAtUTC    time.Time
}

func (d *DB) SaveOperation(ctx context.Context, op Operation) error {
	_, err := d.pool.Exec(ctx, `INSERT INTO chat_moderation_operations (operation_id, channel_id, session_id,
		target_user_id, requested_by_user_id, up_to_sequence, removed_count, moderation_version, status,
		created_at_utc, completed_at_utc) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
		op.OperationID, op.ChannelID, op.SessionID, op.TargetUserID, op.RequestedBy, op.UpToSequence,
		op.RemovedCount, op.ModerationVersion, op.Status, op.CreatedAtUTC, op.CompletedAtUTC)
	return err
}

// GetOperation devuelve una operación del canal; found=false si no existe.
func (d *DB) GetOperation(ctx context.Context, channelID, operationID string) (Operation, bool, error) {
	op := Operation{}
	err := d.pool.QueryRow(ctx, `SELECT operation_id, channel_id, session_id, target_user_id, requested_by_user_id,
		up_to_sequence, removed_count, moderation_version, status, created_at_utc, completed_at_utc
		FROM chat_moderation_operations WHERE channel_id=$1 AND operation_id=$2`, channelID, operationID).Scan(
		&op.OperationID, &op.ChannelID, &op.SessionID, &op.TargetUserID, &op.RequestedBy, &op.UpToSequence,
		&op.RemovedCount, &op.ModerationVersion, &op.Status, &op.CreatedAtUTC, &op.CompletedAtUTC)
	if errors.Is(err, pgx.ErrNoRows) {
		return op, false, nil
	}
	op.CreatedAtUTC, op.CompletedAtUTC = op.CreatedAtUTC.UTC(), op.CompletedAtUTC.UTC()
	return op, err == nil, err
}

// ---- Idempotencia ----

// ErrIdempotencyConflict indica una Idempotency-Key reutilizada con otra solicitud.
var ErrIdempotencyConflict = errors.New("idempotency conflict")

// Result es la respuesta registrada de una mutación.
type Result struct {
	Status int
	Body   []byte
}

// Idempotent ejecuta action una vez por (usuario, clave). Repetir con el mismo fingerprint devuelve
// el resultado registrado durante retention; otro fingerprint es ErrIdempotencyConflict. Solo se
// registran resultados exitosos; un error permite reintentar con la misma clave.
func (d *DB) Idempotent(ctx context.Context, userID, key, fingerprint string, retention time.Duration, action func() (Result, error)) (Result, error) {
	keyHash := sha(strings.ToLower(key))
	tx, err := d.pool.Begin(ctx)
	if err != nil {
		return Result{}, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, "chat.idem|"+userID+"|"+keyHash); err != nil {
		return Result{}, err
	}
	var prev Result
	var prevFingerprint, body string
	err = tx.QueryRow(ctx, `SELECT fingerprint, status, response FROM chat_idempotency
		WHERE user_id=$1 AND key_hash=$2 AND retain_until_utc > now()`, userID, keyHash).Scan(&prevFingerprint, &prev.Status, &body)
	if err == nil {
		if prevFingerprint != fingerprint {
			return Result{}, ErrIdempotencyConflict
		}
		prev.Body = []byte(body)
		return prev, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Result{}, err
	}
	res, err := action()
	if err != nil {
		return Result{}, err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO chat_idempotency (user_id, key_hash, fingerprint, status, response, retain_until_utc)
		VALUES ($1, $2, $3, $4, $5, now() + make_interval(secs => $6))
		ON CONFLICT (user_id, key_hash) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, status = EXCLUDED.status,
		  response = EXCLUDED.response, retain_until_utc = EXCLUDED.retain_until_utc`,
		userID, keyHash, fingerprint, res.Status, string(res.Body), retention.Seconds()); err != nil {
		return Result{}, err
	}
	return res, tx.Commit(ctx)
}

func sha(v string) string {
	sum := sha256.Sum256([]byte(v))
	return hex.EncodeToString(sum[:])
}

// ---- VOD y replay ----

// VodLink es el último vod.availability.changed.v1 aplicado a un VOD. El mapping temporal no se
// copia: Chat lo consulta a VOD (p2.vod.replay-context) en cada lectura de replay.
type VodLink struct {
	VodID            string
	SessionID        string
	ChannelID        string
	Version          int64
	MappingVersion   int64
	Available        bool
	Restriction      string
	PublicationState string
}

// Deleted indica la eliminación terminal del VOD; su replay deja de existir.
func (l VodLink) Deleted() bool { return l.Restriction == "DELETED" }

// VodOutcome describe el efecto de un evento VOD.
type VodOutcome string

const (
	VodApplied   VodOutcome = "APPLIED"
	VodStale     VodOutcome = "STALE"
	VodDuplicate VodOutcome = "DUPLICATE"
	VodConflict  VodOutcome = "CONFLICT"
)

// ApplyVod registra el evento en la inbox y aplica el vínculo en la misma transacción. Versiones
// viejas no reemplazan una nueva y DELETED es terminal. Al quedar una sesión sin VOD vigente tras
// un DELETED, su historial se borra en la misma transacción (VOD delega esa limpieza en Chat).
func (d *DB) ApplyVod(ctx context.Context, eventID, fingerprint string, link VodLink, now time.Time) (VodOutcome, error) {
	tx, err := d.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)
	var prev string
	err = tx.QueryRow(ctx, `SELECT fingerprint FROM chat_broker_inbox WHERE consumer='chat.vod' AND event_id=$1`, eventID).Scan(&prev)
	if err == nil {
		if prev == fingerprint {
			return VodDuplicate, nil
		}
		return VodConflict, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return "", err
	}
	var curVersion int64
	var curRestriction, curSession string
	err = tx.QueryRow(ctx, `SELECT version, restriction, session_id FROM chat_vod_links WHERE vod_id=$1 FOR UPDATE`, link.VodID).
		Scan(&curVersion, &curRestriction, &curSession)
	outcome := VodApplied
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		_, err = tx.Exec(ctx, `INSERT INTO chat_vod_links (vod_id, session_id, channel_id, version, mapping_version,
			available, restriction, publication_state, updated_at_utc) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
			link.VodID, link.SessionID, link.ChannelID, link.Version, link.MappingVersion, link.Available,
			link.Restriction, link.PublicationState, now.UTC())
	case err != nil:
		return "", err
	case curSession != link.SessionID:
		outcome = VodConflict
	case curRestriction == "DELETED" || link.Version <= curVersion:
		outcome = VodStale
	default:
		_, err = tx.Exec(ctx, `UPDATE chat_vod_links SET version=$2, mapping_version=$3, available=$4, restriction=$5,
			publication_state=$6, updated_at_utc=$7 WHERE vod_id=$1`,
			link.VodID, link.Version, link.MappingVersion, link.Available, link.Restriction, link.PublicationState, now.UTC())
	}
	if err != nil {
		return "", err
	}
	if outcome == VodConflict {
		return outcome, nil
	}
	if outcome == VodApplied && link.Deleted() {
		if _, err := tx.Exec(ctx, `DELETE FROM chat_sessions s WHERE s.session_id=$1 AND NOT EXISTS (
			SELECT 1 FROM chat_vod_links l WHERE l.session_id=s.session_id AND l.restriction <> 'DELETED')`, link.SessionID); err != nil {
			return "", err
		}
	}
	if _, err := tx.Exec(ctx, `INSERT INTO chat_broker_inbox (consumer, event_id, fingerprint, outcome)
		VALUES ('chat.vod', $1, $2, $3)`, eventID, fingerprint, string(outcome)); err != nil {
		return "", err
	}
	return outcome, tx.Commit(ctx)
}

// GetVod devuelve el vínculo registrado de un VOD.
func (d *DB) GetVod(ctx context.Context, vodID string) (VodLink, bool, error) {
	l := VodLink{VodID: vodID}
	err := d.pool.QueryRow(ctx, `SELECT session_id, channel_id, version, mapping_version, available, restriction,
		publication_state FROM chat_vod_links WHERE vod_id=$1`, vodID).Scan(&l.SessionID, &l.ChannelID, &l.Version,
		&l.MappingVersion, &l.Available, &l.Restriction, &l.PublicationState)
	if errors.Is(err, pgx.ErrNoRows) {
		return l, false, nil
	}
	return l, err == nil, err
}

// ReplayRange es el tramo [FromOffsetMs, ToOffsetMs) del timeline de la sesión que cae en una ventana
// del video; ToOffsetMs nil llega hasta el final de la sesión.
type ReplayRange struct {
	SessionID    string
	FromOffsetMs int64
	ToOffsetMs   *int64
}

// Replay lee hasta limit mensajes vigentes del tramo, en orden de secuencia y después de afterSequence.
// Los retirados no se entregan. Devuelve también la versión de moderación de la sesión.
func (d *DB) Replay(ctx context.Context, r ReplayRange, afterSequence int64, limit int) ([]chat.Message, int64, error) {
	var moderation int64
	err := d.pool.QueryRow(ctx, `SELECT moderation_version FROM chat_sessions WHERE session_id=$1`, r.SessionID).Scan(&moderation)
	if errors.Is(err, pgx.ErrNoRows) {
		return []chat.Message{}, 0, nil
	}
	if err != nil {
		return nil, 0, err
	}
	rows, err := d.pool.Query(ctx, `SELECT `+messageColumns+` FROM chat_messages
		WHERE session_id=$1 AND stream_offset_ms >= $2 AND ($3::BIGINT IS NULL OR stream_offset_ms < $3)
		  AND sequence > $4 AND removed_at_utc IS NULL
		ORDER BY sequence LIMIT $5`, r.SessionID, r.FromOffsetMs, r.ToOffsetMs, afterSequence, limit)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []chat.Message{}
	for rows.Next() {
		a, err := scanMessage(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, a.msg)
	}
	return out, moderation, rows.Err()
}

// ---- Limpieza ----

// PurgeUnlinked borra sesiones cuyo último mensaje es anterior a before y que nunca tuvieron un VOD.
func (d *DB) PurgeUnlinked(ctx context.Context, before time.Time) (int64, error) {
	tag, err := d.pool.Exec(ctx, `DELETE FROM chat_sessions s WHERE COALESCE(s.last_message_at_utc, s.created_at_utc) < $1
		AND NOT EXISTS (SELECT 1 FROM chat_vod_links l WHERE l.session_id = s.session_id)`, before.UTC())
	return rowsAffected(tag, err)
}

// PurgeIdempotency borra resultados idempotentes vencidos.
func (d *DB) PurgeIdempotency(ctx context.Context) (int64, error) {
	tag, err := d.pool.Exec(ctx, `DELETE FROM chat_idempotency WHERE retain_until_utc <= now()`)
	return rowsAffected(tag, err)
}

func rowsAffected(tag pgconn.CommandTag, err error) (int64, error) {
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}
