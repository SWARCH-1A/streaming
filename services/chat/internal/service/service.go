// Package service contiene los casos de uso de Chat: resolver la sala, enviar un mensaje, leer
// historial y aplicar eventos de sesión. Coordina el contrato Core y el repositorio Redis.
package service

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"

	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
	"streaming/chat/internal/store"
)

// CoreAPI es el contrato privado Core que usa Chat.
type CoreAPI interface {
	MessageContext(ctx context.Context, credential, sessionID, clientMessageID string) (core.MessageContext, *chat.Error)
	Snapshot(ctx context.Context, sessionID string) (core.SessionSnapshot, *chat.Error)
}

type Service struct {
	core   CoreAPI
	store  *store.Store
	log    *slog.Logger
	budget time.Duration // máximo entre pedir contexto e intentar persistir
	now    func() time.Time
}

func New(c CoreAPI, s *store.Store, log *slog.Logger, authBudget time.Duration) *Service {
	return &Service{core: c, store: s, log: log, budget: authBudget, now: time.Now}
}

// ResolveRoom devuelve el estado de la sala. Con reconcile=true consulta el snapshot y cae al estado
// local si Core no responde; una sala desconocida sin snapshot falla (nunca “sala vacía”).
func (s *Service) ResolveRoom(ctx context.Context, sid string, reconcile bool) (chat.RoomStatus, *chat.Error) {
	local, err := s.store.Room(ctx, sid)
	if err != nil {
		s.log.Error("leer sala", "sessionId", sid, "err", err)
		return "", chat.Fail(chat.CodeChatUnavailable)
	}
	if local.Known && !reconcile {
		return local.Status, nil
	}
	snap, cerr := s.core.Snapshot(ctx, sid)
	if cerr != nil {
		if local.Known && cerr.Code != chat.CodeSessionNotFound {
			return local.Status, nil
		}
		return "", cerr
	}
	status, ok := chat.RoomStatusFromSession(snap.Status)
	if !ok {
		s.log.Error("estado de sesión desconocido en snapshot", "sessionId", sid, "status", snap.Status)
		return "", chat.Fail(chat.CodeCoreUnavailable)
	}
	if _, err := s.store.ApplyState(ctx, chat.SessionState{
		SessionID: sid, StreamGeneration: snap.StreamGeneration, SessionVersion: snap.SessionVersion,
		Status: status, Availability: snap.Availability,
	}); err != nil {
		s.log.Error("aplicar snapshot", "sessionId", sid, "err", err)
		return "", chat.Fail(chat.CodeChatUnavailable)
	}
	// El estado local puede ser más nuevo que el snapshot (p. ej. READ_ONLY por evento); prevalece.
	if after, err := s.store.Room(ctx, sid); err == nil && after.Known {
		return after.Status, nil
	}
	return status, nil
}

// History lee hasta limit mensajes recientes de una sala abierta o de solo lectura.
func (s *Service) History(ctx context.Context, sid string, limit int) (chat.RoomStatus, int64, []chat.Message, *chat.Error) {
	status, cerr := s.ResolveRoom(ctx, sid, false)
	if cerr != nil {
		return "", 0, nil, cerr
	}
	if status == chat.RoomNotOpen {
		return "", 0, nil, chat.Fail(chat.CodeChatNotOpen)
	}
	snapshot, msgs, err := s.store.History(ctx, sid, limit)
	if err != nil {
		s.log.Error("leer historial", "sessionId", sid, "err", err)
		return "", 0, nil, chat.Fail(chat.CodeChatUnavailable)
	}
	return status, snapshot, msgs, nil
}

// Send procesa un message.send. Un contexto Core por intento; dedupe antes de permiso; cuota y
// persistencia atómicas antes del ACK. Devuelve fresh=true solo cuando creó un mensaje nuevo.
func (s *Service) Send(ctx context.Context, sid, credential, clientMessageID, rawText string) (chat.Ack, bool, *chat.Error) {
	if !chat.ValidClientMessageID(clientMessageID) {
		return chat.Ack{}, false, &chat.Error{Code: chat.CodeValidation, Message: "clientMessageId debe ser un UUID."}
	}
	text, verr := chat.NormalizeText(rawText)
	if verr != nil {
		return chat.Ack{}, false, verr
	}
	if credential == "" {
		return chat.Ack{}, false, chat.Fail(chat.CodeAuthRequired)
	}

	started := time.Now()
	mc, cerr := s.core.MessageContext(ctx, credential, sid, clientMessageID)
	if cerr != nil {
		return chat.Ack{}, false, cerr
	}
	if time.Since(started) > s.budget {
		return chat.Ack{}, false, chat.Fail(chat.CodeTimelineUnavailable)
	}

	writeAllowed, denial := mc.WriteAllowed, ""
	if mc.DenialCode != nil {
		denial = *mc.DenialCode
	}
	if writeAllowed && mc.TimelinePositionMs == nil {
		writeAllowed, denial = false, chat.CodeTimelineUnavailable
	}
	if !writeAllowed && denial == "" {
		denial = chat.CodeChatReadOnly
	}
	var offset int64
	if mc.TimelinePositionMs != nil {
		offset = *mc.TimelinePositionMs
	}
	msg := chat.Message{
		MessageID: "msg_" + uuid.Must(uuid.NewV7()).String(),
		SessionID: sid,
		Author: chat.Author{
			UserID: mc.UserID, Handle: mc.Handle, DisplayName: displayName(mc), AvatarURI: mc.AvatarURI,
		},
		Text:               text,
		ServerCreatedAtUTC: s.now().UTC().Truncate(time.Millisecond),
		StreamOffsetMs:     offset,
		StreamGeneration:   mc.StreamGeneration,
	}
	res, err := s.store.Accept(ctx, store.AcceptInput{
		SessionID: sid, UserID: mc.UserID, ClientMessageID: clientMessageID,
		TextHash: chat.TextFingerprint(text), WriteAllowed: writeAllowed, DenialCode: denial, Message: msg,
	})
	if err != nil {
		s.log.Error("persistir mensaje", "sessionId", sid, "err", err)
		return chat.Ack{}, false, chat.Fail(chat.CodeChatUnavailable)
	}
	switch res.Outcome {
	case store.Accepted:
		return res.Ack, true, nil
	case store.Duplicate:
		return res.Ack, false, nil
	case store.Conflict:
		return chat.Ack{}, false, chat.Fail(chat.CodeMessageIDConflict)
	case store.RateLimited:
		e := chat.Fail(chat.CodeRateLimited)
		e.RetryAfter = res.RetryAfter
		return chat.Ack{}, false, e
	default:
		return chat.Ack{}, false, chat.Fail(res.DenialCode)
	}
}

func displayName(mc core.MessageContext) string {
	if mc.DisplayName != "" {
		return mc.DisplayName
	}
	return mc.Handle
}

// ApplySessionEvent registra una notificación durable de estado; no autoriza escrituras.
func (s *Service) ApplySessionEvent(ctx context.Context, eventID, fingerprint string, st chat.SessionState) (store.ApplyResult, error) {
	return s.store.ApplyEvent(ctx, eventID, fingerprint, st)
}
