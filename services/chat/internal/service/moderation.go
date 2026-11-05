package service

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"

	"streaming/chat/internal/archive"
	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
	"streaming/chat/internal/store"
	"streaming/chat/internal/vod"
)

// ModeratorAuthority es el contrato Core que confirma sesión vigente y propiedad del canal.
type ModeratorAuthority interface {
	ModerationContext(ctx context.Context, credential, channelID string) (core.ModerationContext, *chat.Error)
}

// ReplayAuthority es el contrato VOD que autoriza la lectura del VOD y entrega su timeline.
type ReplayAuthority interface {
	ReplayContext(ctx context.Context, credential, vodID, requestID string) (vod.Timeline, *chat.Error)
}

// P2Options habilita el archivo PostgreSQL, la moderación y el replay (SPEC-05 P2, ADR-024).
type P2Options struct {
	Archive              *archive.DB
	Moderators           ModeratorAuthority
	Vods                 ReplayAuthority // sin VOD configurado el replay responde no disponible
	UnlinkedRetention    time.Duration   // historial de sesiones que nunca recibieron un VOD
	IdempotencyRetention time.Duration
}

// EnableP2 activa las capacidades P2. Sin llamarla, Chat conserva el comportamiento P1.
func (s *Service) EnableP2(o P2Options) { s.p2 = &o }

// P2Enabled indica si el archivo y la moderación están disponibles.
func (s *Service) P2Enabled() bool { return s.p2 != nil }

func (s *Service) unavailable(msg string, args ...any) *chat.Error {
	s.log.Error(msg, args...)
	return chat.Fail(chat.CodeChatUnavailable)
}

// ChannelOf resuelve el canal de una sesión: primero la sala viva, luego el archivo.
func (s *Service) ChannelOf(ctx context.Context, sid string) (string, *chat.Error) {
	room, err := s.store.Room(ctx, sid)
	if err != nil {
		return "", s.unavailable("leer sala", "sessionId", sid, "err", err)
	}
	if room.ChannelID != "" {
		return room.ChannelID, nil
	}
	channel, found, err := s.p2.Archive.SessionChannel(ctx, sid)
	if err != nil {
		return "", s.unavailable("leer sesión archivada", "sessionId", sid, "err", err)
	}
	if !found {
		return "", chat.Fail(chat.CodeSessionNotFound)
	}
	return channel, nil
}

// AuthorizeModerator pide a Core una autorización nueva de propietario para el canal.
func (s *Service) AuthorizeModerator(ctx context.Context, credential, channelID string) (string, *chat.Error) {
	mc, cerr := s.p2.Moderators.ModerationContext(ctx, credential, channelID)
	if cerr != nil {
		return "", cerr
	}
	return mc.UserID, nil
}

// Idempotent ejecuta una mutación una vez por usuario e Idempotency-Key.
func (s *Service) Idempotent(ctx context.Context, userID, key, fingerprint string, action func() (archive.Result, *chat.Error)) (archive.Result, *chat.Error) {
	var actionErr *chat.Error
	res, err := s.p2.Archive.Idempotent(ctx, userID, key, fingerprint, s.p2.IdempotencyRetention, func() (archive.Result, error) {
		r, cerr := action()
		if cerr != nil {
			actionErr = cerr
			return archive.Result{}, cerr
		}
		return r, nil
	})
	if actionErr != nil {
		return archive.Result{}, actionErr
	}
	if errors.Is(err, archive.ErrIdempotencyConflict) {
		return archive.Result{}, chat.Fail(chat.CodeIdempotencyConflict)
	}
	if errors.Is(err, archive.ErrIdempotencyBusy) {
		// La primera petición con esta clave sigue en curso; el cliente reintenta con la misma clave.
		return archive.Result{}, chat.Fail(chat.CodeChatUnavailable)
	}
	if err != nil {
		return archive.Result{}, s.unavailable("idempotencia", "err", err)
	}
	return res, nil
}

// RemoveMessage retira un mensaje de la sesión: primero de forma durable y luego en la sala viva.
func (s *Service) RemoveMessage(ctx context.Context, moderator, channelID, sid, messageID string) (chat.Removal, *chat.Error) {
	msg, found, err := s.store.MessageByID(ctx, sid, messageID)
	if err != nil {
		return chat.Removal{}, s.unavailable("leer mensaje", "sessionId", sid, "err", err)
	}
	if !found {
		archived, removal, ok, err := s.p2.Archive.ArchivedMessage(ctx, sid, messageID)
		if err != nil {
			return chat.Removal{}, s.unavailable("leer mensaje archivado", "sessionId", sid, "err", err)
		}
		if !ok {
			return chat.Removal{}, chat.Fail(chat.CodeMessageNotFound)
		}
		if removal != nil {
			return *removal, nil
		}
		msg = archived
	}
	res, cerr := s.remove(ctx, moderator, channelID, sid, []chat.Message{msg})
	if cerr != nil {
		return chat.Removal{}, cerr
	}
	return res.Removals[0], nil
}

func (s *Service) remove(ctx context.Context, moderator, channelID, sid string, msgs []chat.Message) (archive.RemoveResult, *chat.Error) {
	res, err := s.p2.Archive.RemoveMessages(ctx, channelID, sid, moderator, msgs, s.now())
	if err != nil {
		return archive.RemoveResult{}, s.unavailable("retirar mensajes", "sessionId", sid, "err", err)
	}
	// El retiro durable ya rige para el replay; la sala viva se actualiza a continuación. Si Redis
	// falla, el reintento del cliente vuelve a aplicar las mismas marcas sin otra versión.
	if err := s.store.ApplyRemovals(ctx, sid, res.ModerationVersion, res.Removals); err != nil {
		return archive.RemoveResult{}, s.unavailable("difundir retiros", "sessionId", sid, "err", err)
	}
	s.log.Info("mensajes retirados", "event", "chat_moderation", "sessionId", sid, "channelId", channelID,
		"removed", res.Newly, "moderationVersion", res.ModerationVersion)
	return res, nil
}

// RemoveUserMessages retira los mensajes de target en la emisión indicada enviados hasta este momento.
func (s *Service) RemoveUserMessages(ctx context.Context, moderator, channelID, sid, target string) (archive.Operation, *chat.Error) {
	live, err := s.store.LastSequence(ctx, sid)
	if err != nil {
		return archive.Operation{}, s.unavailable("leer secuencia", "sessionId", sid, "err", err)
	}
	archived, err := s.p2.Archive.LastArchivedSequence(ctx, sid)
	if err != nil {
		return archive.Operation{}, s.unavailable("leer secuencia archivada", "sessionId", sid, "err", err)
	}
	upTo := max(live, archived)
	started := s.now().UTC().Truncate(time.Millisecond)
	fromRedis, err := s.store.MessagesByAuthor(ctx, sid, target, upTo)
	if err != nil {
		return archive.Operation{}, s.unavailable("leer mensajes del autor", "sessionId", sid, "err", err)
	}
	fromArchive, err := s.p2.Archive.ArchivedByAuthor(ctx, sid, target, upTo)
	if err != nil {
		return archive.Operation{}, s.unavailable("leer mensajes archivados del autor", "sessionId", sid, "err", err)
	}
	bySeq := map[int64]chat.Message{}
	for _, m := range append(fromArchive, fromRedis...) {
		bySeq[m.Sequence] = m
	}
	msgs := make([]chat.Message, 0, len(bySeq))
	for _, m := range bySeq {
		msgs = append(msgs, m)
	}
	op := archive.Operation{
		OperationID: "mop_" + strings.ReplaceAll(uuid.Must(uuid.NewV7()).String(), "-", ""),
		ChannelID:   channelID, SessionID: sid, TargetUserID: target, RequestedBy: moderator,
		UpToSequence: upTo, Status: "COMPLETED", CreatedAtUTC: started,
	}
	if len(msgs) > 0 {
		res, cerr := s.remove(ctx, moderator, channelID, sid, msgs)
		if cerr != nil {
			return archive.Operation{}, cerr
		}
		op.RemovedCount, op.ModerationVersion = res.Newly, res.ModerationVersion
	}
	op.CompletedAtUTC = s.now().UTC().Truncate(time.Millisecond)
	if err := s.p2.Archive.SaveOperation(ctx, op); err != nil {
		return archive.Operation{}, s.unavailable("guardar operación", "sessionId", sid, "err", err)
	}
	return op, nil
}

// Operation devuelve una operación de moderación del canal.
func (s *Service) Operation(ctx context.Context, channelID, operationID string) (archive.Operation, *chat.Error) {
	op, found, err := s.p2.Archive.GetOperation(ctx, channelID, operationID)
	if err != nil {
		return archive.Operation{}, s.unavailable("leer operación", "err", err)
	}
	if !found {
		return archive.Operation{}, chat.Fail(chat.CodeOperationNotFound)
	}
	return op, nil
}

// ---- Bloqueos ----

func (s *Service) withActive(b chat.Block) chat.Block {
	b.Active = b.ActiveAt(s.now())
	return b
}

// PutBlock bloquea a target en el canal. TEMPORARY exige una duración fija; PERMANENT no admite
// duración. El vencimiento se calcula con el reloj del servidor y gana la última escritura.
func (s *Service) PutBlock(ctx context.Context, moderator, channelID, target string, mode chat.BlockMode, duration *string) (chat.Block, *chat.Error) {
	if target == moderator {
		return chat.Block{}, chat.Fail(chat.CodeCannotBlockOwner)
	}
	now := s.now().UTC().Truncate(time.Millisecond)
	var expires *time.Time
	switch mode {
	case chat.BlockTemporary:
		if duration == nil {
			return chat.Block{}, &chat.Error{Code: chat.CodeValidation, Message: "duration es obligatorio para un bloqueo temporal."}
		}
		d, ok := chat.BlockDurations[*duration]
		if !ok {
			return chat.Block{}, &chat.Error{Code: chat.CodeValidation, Message: "duration debe ser PT10M, PT1H, PT24H o P7D."}
		}
		until := now.Add(d)
		expires = &until
	case chat.BlockPermanent:
		if duration != nil {
			return chat.Block{}, &chat.Error{Code: chat.CodeValidation, Message: "Un bloqueo permanente no admite duration."}
		}
	default:
		return chat.Block{}, &chat.Error{Code: chat.CodeValidation, Message: "mode debe ser TEMPORARY o PERMANENT."}
	}
	b, err := s.p2.Archive.PutBlock(ctx, channelID, target, moderator, mode, duration, expires, now)
	if err != nil {
		return chat.Block{}, s.unavailable("guardar bloqueo", "channelId", channelID, "err", err)
	}
	if err := s.store.SetBlock(ctx, channelID, target, expires, b.Version); err != nil {
		// PostgreSQL ya registró el bloqueo; el mantenimiento periódico repone la copia de Redis.
		return chat.Block{}, s.unavailable("aplicar bloqueo", "channelId", channelID, "err", err)
	}
	s.log.Info("bloqueo aplicado", "event", "chat_block", "channelId", channelID, "mode", mode, "version", b.Version)
	return s.withActive(b), nil
}

// LiftBlock retira el bloqueo sin borrar mensajes anteriores.
func (s *Service) LiftBlock(ctx context.Context, channelID, target string) (chat.Block, *chat.Error) {
	b, err := s.p2.Archive.LiftBlock(ctx, channelID, target, s.now())
	if err != nil {
		return chat.Block{}, s.unavailable("retirar bloqueo", "channelId", channelID, "err", err)
	}
	if err := s.store.LiftBlock(ctx, channelID, target, b.Version); err != nil {
		return chat.Block{}, s.unavailable("retirar bloqueo en Redis", "channelId", channelID, "err", err)
	}
	return s.withActive(b), nil
}

// GetBlock devuelve el estado de bloqueo vigente según el reloj del servidor.
func (s *Service) GetBlock(ctx context.Context, channelID, target string) (chat.Block, *chat.Error) {
	b, err := s.p2.Archive.GetBlock(ctx, channelID, target)
	if err != nil {
		return chat.Block{}, s.unavailable("leer bloqueo", "channelId", channelID, "err", err)
	}
	return s.withActive(b), nil
}

// ---- Replay ----

// ReplayPage es una página de la ventana [fromMs, toMs) del video.
type ReplayPage struct {
	VodID             string
	MappingVersion    int64
	ModerationVersion int64
	Items             []chat.ReplayItem
	More              bool
}

// Replay lee una página del chat de un VOD. VOD decide en cada consulta si la credencial puede leer
// el VOD (publicado, o borrador para su propietario) y entrega el timeline; Chat traduce la ventana
// del video a un tramo de la sesión y omite los mensajes retirados. Si el cursor se emitió con otra
// mappingVersion, responde CURSOR_EXPIRED.
func (s *Service) Replay(ctx context.Context, credential, requestID, vodID string, fromMs, toMs int64, afterSequence int64, expectMapping *int64, limit int) (ReplayPage, *chat.Error) {
	if s.p2.Vods == nil {
		return ReplayPage{}, chat.Fail(vod.CodeReplayUnavailable)
	}
	timeline, cerr := s.p2.Vods.ReplayContext(ctx, credential, vodID, requestID)
	if cerr != nil {
		return ReplayPage{}, cerr
	}
	if expectMapping != nil && *expectMapping != timeline.MappingVersion {
		return ReplayPage{}, chat.Fail(chat.CodeCursorExpired)
	}
	page := ReplayPage{VodID: vodID, MappingVersion: timeline.MappingVersion, ModerationVersion: 1, Items: []chat.ReplayItem{}}
	lo, ok := timeline.SessionThreshold(fromMs)
	if !ok {
		return page, nil
	}
	r := archive.ReplayRange{SessionID: timeline.SessionID, FromOffsetMs: lo}
	if hi, ok := timeline.SessionThreshold(toMs); ok {
		r.ToOffsetMs = &hi
	}
	messages, moderation, err := s.p2.Archive.Replay(ctx, r, afterSequence, limit+1)
	if err != nil {
		return ReplayPage{}, s.unavailable("leer replay", "vodId", vodID, "err", err)
	}
	// moderation_version cuenta los retiros de la sesión desde 0; el contrato publica versiones >= 1.
	page.ModerationVersion = moderation + 1
	if len(messages) > limit {
		messages, page.More = messages[:limit], true
	}
	for _, m := range messages {
		page.Items = append(page.Items, chat.ReplayItem{MessageID: m.MessageID, SessionID: m.SessionID,
			Sequence: m.Sequence, Author: m.Author, Text: m.Text, ServerCreatedAtUTC: m.ServerCreatedAtUTC,
			StreamOffsetMs: m.StreamOffsetMs, StreamGeneration: m.StreamGeneration,
			MediaOffsetMs: timeline.MediaOffset(m.StreamOffsetMs), ModerationVersion: page.ModerationVersion})
	}
	return page, nil
}

// ApplyVodEvent registra un cambio de disponibilidad VOD recibido por el broker.
func (s *Service) ApplyVodEvent(ctx context.Context, eventID, fingerprint string, link archive.VodLink) (archive.VodOutcome, error) {
	return s.p2.Archive.ApplyVod(ctx, eventID, fingerprint, link, s.now())
}

// ---- Archivo y mantenimiento ----

// RunArchiver copia a PostgreSQL los mensajes confirmados en Redis. Cada réplica es un consumidor
// del mismo grupo; una entrada se confirma y borra solo después del commit en PostgreSQL, y las
// que quedaron pendientes en una réplica caída se reclaman tras claimIdle.
func (s *Service) RunArchiver(ctx context.Context, consumer string, claimIdle time.Duration) {
	backoff := 100 * time.Millisecond
	wait := func() bool {
		select {
		case <-ctx.Done():
			return false
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, 5*time.Second)
		return true
	}
	for ctx.Err() == nil {
		if err := s.store.EnsureArchiveGroup(ctx); err == nil {
			break
		}
		if !wait() {
			return
		}
	}
	lastClaim := time.Time{}
	for ctx.Err() == nil {
		var entries []store.ArchiveEntry
		var err error
		if time.Since(lastClaim) >= claimIdle {
			entries, err = s.store.ClaimArchive(ctx, consumer, claimIdle, 100)
			lastClaim = time.Now()
		}
		if err == nil && len(entries) == 0 {
			entries, err = s.store.ReadArchive(ctx, consumer, 100, 2*time.Second)
		}
		if err == nil && len(entries) > 0 {
			err = s.archive(ctx, entries)
		}
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			s.log.Warn("archivo de chat", "err", err)
			if !wait() {
				return
			}
			continue
		}
		backoff = 100 * time.Millisecond
	}
}

func (s *Service) archive(ctx context.Context, entries []store.ArchiveEntry) error {
	rows := make([]archive.Row, 0, len(entries))
	ids := make([]string, 0, len(entries))
	for _, e := range entries {
		ids = append(ids, e.ID)
		if e.Invalid {
			s.log.Error("entrada de archivo inválida descartada", "entryId", e.ID)
			continue
		}
		rows = append(rows, archive.Row{ChannelID: e.ChannelID, Message: e.Message})
	}
	if err := s.p2.Archive.InsertMessages(ctx, rows); err != nil {
		return err
	}
	return s.store.AckArchive(ctx, ids)
}

// MaintainP2 repone bloqueos en Redis desde PostgreSQL y borra historial sin VOD vencido.
func (s *Service) MaintainP2(ctx context.Context) {
	now := s.now()
	if err := s.reconcileBlocks(ctx, now); err != nil {
		s.log.Warn("reconciliar bloqueos", "err", err)
	}
	if n, err := s.p2.Archive.PurgeUnlinked(ctx, now.Add(-s.p2.UnlinkedRetention)); err != nil {
		s.log.Warn("borrar historial sin VOD", "err", err)
	} else if n > 0 {
		s.log.Info("historial sin VOD borrado", "sessions", n)
	}
	if _, err := s.p2.Archive.PurgeIdempotency(ctx); err != nil {
		s.log.Warn("borrar idempotencia vencida", "err", err)
	}
}

// reconcileBlocks copia a Redis los bloqueos vigentes y retira las copias que ya no rigen. Todas las
// escrituras llevan la versión de PostgreSQL, de modo que un bloqueo o retiro confirmado mientras
// corre la pasada (en esta u otra réplica) no queda deshecho por la foto anterior.
func (s *Service) reconcileBlocks(ctx context.Context, now time.Time) error {
	blocks, err := s.p2.Archive.ActiveBlocks(ctx, now)
	if err != nil {
		return err
	}
	active := map[string]bool{}
	for _, b := range blocks {
		active[b.ChannelID+":"+b.UserID] = true
		if err := s.store.SetBlock(ctx, b.ChannelID, b.UserID, enforcedUntil(b), b.Version); err != nil {
			s.log.Warn("reponer bloqueo", "channelId", b.ChannelID, "err", err)
		}
	}
	ids, err := s.store.BlockIDs(ctx)
	if err != nil {
		return err
	}
	for _, id := range ids {
		channelID, userID, ok := strings.Cut(id, ":")
		if !ok || active[id] {
			continue
		}
		// Relee la fila: el bloqueo pudo confirmarse después de la foto de ActiveBlocks.
		b, err := s.p2.Archive.GetBlock(ctx, channelID, userID)
		if err != nil {
			return err
		}
		if b.ActiveAt(s.now()) {
			err = s.store.SetBlock(ctx, channelID, userID, enforcedUntil(b), b.Version)
		} else {
			err = s.store.DropStaleBlock(ctx, channelID, userID, b.Version)
		}
		if err != nil {
			s.log.Warn("reconciliar bloqueo", "channelId", channelID, "err", err)
		}
	}
	return nil
}

// enforcedUntil es el vencimiento que aplica Redis: nil para un bloqueo permanente.
func enforcedUntil(b chat.Block) *time.Time {
	if b.Mode != nil && *b.Mode == chat.BlockTemporary {
		return b.ExpiresAtUTC
	}
	return nil
}
