package transport

import (
	"bytes"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"

	"streaming/chat/internal/archive"
	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
)

// Rutas P2 de moderación y replay (SPEC-05 P2). Solo se montan con el archivo habilitado.
func (s *Server) mountP2(mux *http.ServeMux) {
	mux.HandleFunc("DELETE /api/chat/sessions/{sessionId}/messages/{messageId}", s.handleRemoveMessage)
	mux.HandleFunc("PUT /api/chat/channels/{channelId}/blocks/{userId}", s.handlePutBlock)
	mux.HandleFunc("DELETE /api/chat/channels/{channelId}/blocks/{userId}", s.handleLiftBlock)
	mux.HandleFunc("GET /api/chat/channels/{channelId}/blocks/{userId}", s.handleGetBlock)
	mux.HandleFunc("POST /api/chat/channels/{channelId}/message-removals", s.handleRemoveUserMessages)
	mux.HandleFunc("GET /api/chat/channels/{channelId}/moderation-operations/{operationId}", s.handleOperation)
	mux.HandleFunc("GET /api/chat/vods/{vodId}/messages", s.handleReplay)
}

const bodyLimit = 16 << 10

// moderator valida transporte y origen de una operación de moderación y pide a Core la autorización
// de propietario. Las mutaciones de navegador exigen el doble envío CSRF emitido por Core.
func (s *Server) moderator(w http.ResponseWriter, r *http.Request, channelID string, mutation bool) (string, bool) {
	if !chat.ValidOpaqueID(channelID) {
		s.writeError(w, r, http.StatusNotFound, chat.Fail(chat.CodeChannelNotFound))
		return "", false
	}
	if origin := r.Header.Get("Origin"); mutation && origin != "" && !s.originAllowed(origin) {
		s.writeError(w, r, http.StatusForbidden, chat.Fail(chat.CodeOriginNotAllowed))
		return "", false
	}
	var credential string
	if ck, err := r.Cookie(s.cfg.SessionCookie); err == nil {
		credential = ck.Value
	}
	if credential == "" {
		s.writeError(w, r, http.StatusUnauthorized, chat.Fail(chat.CodeAuthRequired))
		return "", false
	}
	if mutation && !csrfValid(r) {
		s.writeError(w, r, http.StatusForbidden, chat.Fail(chat.CodeCSRFInvalid))
		return "", false
	}
	userID, cerr := s.svc.AuthorizeModerator(core.WithRequestID(r.Context(), requestID(r)), credential, channelID)
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return "", false
	}
	return userID, true
}

func csrfValid(r *http.Request) bool {
	header := r.Header.Get("X-XSRF-TOKEN")
	cookie, err := r.Cookie("XSRF-TOKEN")
	return err == nil && header != "" && subtle.ConstantTimeCompare([]byte(header), []byte(cookie.Value)) == 1
}

// idempotent lee el cuerpo, exige Idempotency-Key UUID y registra la respuesta de la acción.
func (s *Server) idempotent(w http.ResponseWriter, r *http.Request, userID string, body []byte, action func() (int, any, *chat.Error)) {
	key := r.Header.Get("Idempotency-Key")
	if _, err := uuid.Parse(key); err != nil || len(key) != 36 {
		s.writeError(w, r, http.StatusBadRequest, chat.Fail(chat.CodeIdempotencyRequired))
		return
	}
	sum := sha256.Sum256(append([]byte(r.Method+" "+r.URL.Path+" "), body...))
	res, cerr := s.svc.Idempotent(r.Context(), userID, key, hex.EncodeToString(sum[:]), func() (archive.Result, *chat.Error) {
		status, value, cerr := action()
		if cerr != nil {
			return archive.Result{}, cerr
		}
		raw, err := json.Marshal(value)
		if err != nil {
			return archive.Result{}, chat.Fail(chat.CodeChatUnavailable)
		}
		return archive.Result{Status: status, Body: raw}, nil
	})
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(res.Status)
	_, _ = w.Write(append(res.Body, '\n'))
}

func (s *Server) readBody(w http.ResponseWriter, r *http.Request, dst any) ([]byte, bool) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, bodyLimit))
	if err != nil {
		s.writeError(w, r, http.StatusRequestEntityTooLarge, &chat.Error{Code: chat.CodeValidation, Message: "El cuerpo supera 16 KiB."})
		return nil, false
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil || dec.Decode(new(any)) != io.EOF {
		s.writeError(w, r, http.StatusBadRequest, &chat.Error{Code: chat.CodeValidation, Message: "Cuerpo JSON inválido."})
		return nil, false
	}
	return raw, true
}

type messageRemoval struct {
	ChannelID string `json:"channelId"`
	chat.Removal
}

func (s *Server) handleRemoveMessage(w http.ResponseWriter, r *http.Request) {
	sid, messageID := r.PathValue("sessionId"), r.PathValue("messageId")
	if !chat.ValidSessionID(sid) || !chat.ValidOpaqueID(messageID) {
		s.writeError(w, r, http.StatusNotFound, chat.Fail(chat.CodeMessageNotFound))
		return
	}
	channelID, cerr := s.svc.ChannelOf(r.Context(), sid)
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	userID, ok := s.moderator(w, r, channelID, true)
	if !ok {
		return
	}
	s.idempotent(w, r, userID, nil, func() (int, any, *chat.Error) {
		removal, cerr := s.svc.RemoveMessage(r.Context(), userID, channelID, sid, messageID)
		return http.StatusOK, messageRemoval{ChannelID: channelID, Removal: removal}, cerr
	})
}

type blockCommand struct {
	Mode     chat.BlockMode `json:"mode"`
	Duration *string        `json:"duration"`
}

func (s *Server) blockTarget(w http.ResponseWriter, r *http.Request, mutation bool) (string, string, string, bool) {
	channelID, target := r.PathValue("channelId"), r.PathValue("userId")
	userID, ok := s.moderator(w, r, channelID, mutation)
	if !ok {
		return "", "", "", false
	}
	if !chat.ValidOpaqueID(target) {
		s.writeError(w, r, http.StatusBadRequest, &chat.Error{Code: chat.CodeValidation, Message: "userId inválido."})
		return "", "", "", false
	}
	return userID, channelID, target, true
}

func (s *Server) handlePutBlock(w http.ResponseWriter, r *http.Request) {
	userID, channelID, target, ok := s.blockTarget(w, r, true)
	if !ok {
		return
	}
	var cmd blockCommand
	raw, ok := s.readBody(w, r, &cmd)
	if !ok {
		return
	}
	s.idempotent(w, r, userID, raw, func() (int, any, *chat.Error) {
		b, cerr := s.svc.PutBlock(r.Context(), userID, channelID, target, cmd.Mode, cmd.Duration)
		return http.StatusOK, b, cerr
	})
}

func (s *Server) handleLiftBlock(w http.ResponseWriter, r *http.Request) {
	userID, channelID, target, ok := s.blockTarget(w, r, true)
	if !ok {
		return
	}
	s.idempotent(w, r, userID, nil, func() (int, any, *chat.Error) {
		b, cerr := s.svc.LiftBlock(r.Context(), channelID, target)
		return http.StatusOK, b, cerr
	})
}

func (s *Server) handleGetBlock(w http.ResponseWriter, r *http.Request) {
	_, channelID, target, ok := s.blockTarget(w, r, false)
	if !ok {
		return
	}
	b, cerr := s.svc.GetBlock(r.Context(), channelID, target)
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, b)
}

type removalCommand struct {
	SessionID string `json:"sessionId"`
	UserID    string `json:"userId"`
}

type operationView struct {
	OperationID       string    `json:"operationId"`
	ChannelID         string    `json:"channelId"`
	SessionID         string    `json:"sessionId"`
	TargetUserID      string    `json:"targetUserId"`
	Scope             string    `json:"scope"`
	UpToSequence      int64     `json:"upToSequence"`
	RemovedCount      int       `json:"removedCount"`
	ModerationVersion int64     `json:"moderationVersion"`
	Status            string    `json:"status"`
	CreatedAtUTC      time.Time `json:"createdAtUtc"`
	CompletedAtUTC    time.Time `json:"completedAtUtc"`
	StatusURI         string    `json:"statusUri"`
}

func viewOperation(op archive.Operation) operationView {
	return operationView{OperationID: op.OperationID, ChannelID: op.ChannelID, SessionID: op.SessionID,
		TargetUserID: op.TargetUserID, Scope: "SESSION", UpToSequence: op.UpToSequence, RemovedCount: op.RemovedCount,
		ModerationVersion: op.ModerationVersion, Status: op.Status, CreatedAtUTC: op.CreatedAtUTC,
		CompletedAtUTC: op.CompletedAtUTC,
		StatusURI:      "/api/chat/channels/" + op.ChannelID + "/moderation-operations/" + op.OperationID}
}

func (s *Server) handleRemoveUserMessages(w http.ResponseWriter, r *http.Request) {
	channelID := r.PathValue("channelId")
	userID, ok := s.moderator(w, r, channelID, true)
	if !ok {
		return
	}
	var cmd removalCommand
	raw, ok := s.readBody(w, r, &cmd)
	if !ok {
		return
	}
	if !chat.ValidSessionID(cmd.SessionID) || !chat.ValidOpaqueID(cmd.UserID) {
		s.writeError(w, r, http.StatusBadRequest, &chat.Error{Code: chat.CodeValidation, Message: "sessionId y userId son obligatorios."})
		return
	}
	// Solo se retiran mensajes de una emisión del mismo canal.
	sessionChannel, cerr := s.svc.ChannelOf(r.Context(), cmd.SessionID)
	if cerr != nil || sessionChannel != channelID {
		if cerr == nil || cerr.Code == chat.CodeSessionNotFound {
			cerr = chat.Fail(chat.CodeSessionNotFound)
		}
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	s.idempotent(w, r, userID, raw, func() (int, any, *chat.Error) {
		op, cerr := s.svc.RemoveUserMessages(r.Context(), userID, channelID, cmd.SessionID, cmd.UserID)
		return http.StatusAccepted, viewOperation(op), cerr
	})
}

func (s *Server) handleOperation(w http.ResponseWriter, r *http.Request) {
	channelID, operationID := r.PathValue("channelId"), r.PathValue("operationId")
	if _, ok := s.moderator(w, r, channelID, false); !ok {
		return
	}
	if !chat.ValidOpaqueID(operationID) {
		s.writeError(w, r, http.StatusNotFound, chat.Fail(chat.CodeOperationNotFound))
		return
	}
	op, cerr := s.svc.Operation(r.Context(), channelID, operationID)
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, viewOperation(op))
}

// replayCursor liga la página siguiente al VOD, la ventana y la versión del vínculo (mapping).
type replayCursor struct {
	VodID      string `json:"v"`
	FromMs     int64  `json:"f"`
	ToMs       int64  `json:"t"`
	VodVersion int64  `json:"g"`
	OffsetMs   int64  `json:"o"`
	Sequence   int64  `json:"s"`
}

type replayPage struct {
	VodID      string            `json:"vodId"`
	FromMs     int64             `json:"fromMs"`
	ToMs       int64             `json:"toMs"`
	Items      []chat.ReplayItem `json:"items"`
	NextCursor *string           `json:"nextCursor"`
}

func (s *Server) handleReplay(w http.ResponseWriter, r *http.Request) {
	vodID := r.PathValue("vodId")
	if !chat.ValidOpaqueID(vodID) {
		s.writeError(w, r, http.StatusNotFound, chat.Fail(chat.CodeVodNotFound))
		return
	}
	q := r.URL.Query()
	invalid := func(msg string) {
		s.writeError(w, r, http.StatusBadRequest, &chat.Error{Code: chat.CodeValidation, Message: msg})
	}
	for key := range q {
		if key != "fromMs" && key != "toMs" && key != "limit" && key != "cursor" {
			invalid("Parámetro no admitido: " + key)
			return
		}
	}
	fromMs, err1 := strconv.ParseInt(q.Get("fromMs"), 10, 64)
	toMs, err2 := strconv.ParseInt(q.Get("toMs"), 10, 64)
	if err1 != nil || err2 != nil || fromMs < 0 || toMs <= fromMs || toMs-fromMs > chat.ReplayWindowMaxMs {
		invalid("fromMs y toMs deben cumplir 0 <= fromMs < toMs y una ventana de hasta 300000 ms.")
		return
	}
	limit := chat.HistoryMaxLimit
	if raw := q.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > chat.HistoryMaxLimit {
			invalid("limit debe ser un entero entre 1 y 50.")
			return
		}
		limit = n
	}
	var after *archive.ReplayPosition
	var expectVersion *int64
	if raw := q.Get("cursor"); raw != "" {
		var c replayCursor
		decoded, err := base64.RawURLEncoding.DecodeString(raw)
		if err != nil || json.Unmarshal(decoded, &c) != nil || c.VodID != vodID || c.FromMs != fromMs || c.ToMs != toMs {
			invalid("cursor no corresponde a esta consulta.")
			return
		}
		after = &archive.ReplayPosition{OffsetMs: c.OffsetMs, Sequence: c.Sequence}
		expectVersion = &c.VodVersion
	}
	page, cerr := s.svc.Replay(r.Context(), vodID, fromMs, toMs, after, expectVersion, limit)
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	out := replayPage{VodID: vodID, FromMs: fromMs, ToMs: toMs, Items: page.Items}
	if page.More && len(page.Items) > 0 {
		last := page.Items[len(page.Items)-1]
		raw, _ := json.Marshal(replayCursor{VodID: vodID, FromMs: fromMs, ToMs: toMs, VodVersion: page.VodVersion,
			OffsetMs: last.VodOffsetMs, Sequence: last.Sequence})
		cursor := base64.RawURLEncoding.EncodeToString(raw)
		out.NextCursor = &cursor
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, out)
}
