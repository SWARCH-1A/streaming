// Package transport expone Chat por HTTP/WS: historial público, WebSocket de sala y la ruta privada
// de eventos de sesión. Las rutas /internal/* solo se montan en el listener interno.
package transport

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"time"

	"github.com/google/uuid"

	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
	"streaming/chat/internal/service"
	"streaming/chat/internal/store"
)

type Config struct {
	AllowedOrigins []string // orígenes web exactos (esquema://host[:puerto])
	SessionCookie  string   // nombre de la cookie de sesión Core
	EventsProducer string   // X-Service-Name autorizado para session-events
	EventsToken    string   // X-Service-Token de ese productor
}

type Server struct {
	cfg  Config
	svc  *service.Service
	hub  *Hub
	ping func(context.Context) error
	log  *slog.Logger
}

func NewServer(cfg Config, svc *service.Service, hub *Hub, ping func(context.Context) error, log *slog.Logger) *Server {
	return &Server{cfg: cfg, svc: svc, hub: hub, ping: ping, log: log}
}

// PublicHandler sirve lo que el reverse proxy encamina a Chat.
func (s *Server) PublicHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/chat/sessions/{sessionId}/messages", s.handleHistory)
	mux.HandleFunc("GET /realtime/chat/sessions/{sessionId}", s.handleRealtime)
	mux.HandleFunc("GET /healthz", s.handleLive)
	mux.HandleFunc("GET /readyz", s.handleReady)
	return s.withRequestID(mux)
}

// InternalHandler sirve contratos entre procesos; nunca se expone en la entrada pública.
func (s *Server) InternalHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /internal/chat/session-events", s.handleSessionEvent)
	mux.HandleFunc("GET /healthz", s.handleLive)
	mux.HandleFunc("GET /readyz", s.handleReady)
	return s.withRequestID(mux)
}

func (s *Server) handleHistory(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sessionId")
	if !chat.ValidSessionID(sid) {
		s.writeError(w, r, http.StatusNotFound, chat.Fail(chat.CodeSessionNotFound))
		return
	}
	limit := chat.HistoryMaxLimit
	if raw := r.URL.Query().Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > chat.HistoryMaxLimit {
			s.writeError(w, r, http.StatusBadRequest, &chat.Error{Code: chat.CodeValidation, Message: "limit debe ser un entero entre 1 y 50."})
			return
		}
		limit = n
	}
	ctx := core.WithRequestID(r.Context(), requestID(r))
	status, snapshot, msgs, cerr := s.svc.History(ctx, sid, limit)
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, historyResponse{SessionID: sid, RoomStatus: status, SnapshotSequence: snapshot, Items: msgs})
}

func (s *Server) handleSessionEvent(w http.ResponseWriter, r *http.Request) {
	if !s.serviceAuthorized(r) {
		s.writeError(w, r, http.StatusUnauthorized, &chat.Error{Code: "SERVICE_UNAUTHORIZED", Message: "Credencial de servicio inválida."})
		return
	}
	var ev sessionEvent
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&ev); err != nil {
		s.writeError(w, r, http.StatusBadRequest, &chat.Error{Code: chat.CodeValidation, Message: "Cuerpo JSON inválido."})
		return
	}
	state, msg := s.validateEvent(ev)
	if msg != "" {
		s.writeError(w, r, http.StatusBadRequest, &chat.Error{Code: chat.CodeValidation, Message: msg})
		return
	}
	res, err := s.svc.ApplySessionEvent(r.Context(), ev.EventID, eventFingerprint(ev), state)
	if err != nil {
		s.log.Error("aplicar evento de sesión", "eventId", ev.EventID, "err", err)
		s.writeError(w, r, http.StatusServiceUnavailable, chat.Fail(chat.CodeChatUnavailable))
		return
	}
	switch res {
	case store.EventConflict:
		s.writeError(w, r, http.StatusConflict, &chat.Error{Code: "EVENT_ID_CONFLICT", Message: "eventId reutilizado con otro contenido."})
	case store.EventDuplicate:
		writeJSON(w, http.StatusOK, map[string]any{"accepted": true, "duplicate": true, "eventId": ev.EventID})
	case store.Stale:
		writeJSON(w, http.StatusAccepted, map[string]any{"accepted": true, "duplicate": false, "eventId": ev.EventID, "ignored": true, "reason": "STALE_VERSION"})
	default:
		writeJSON(w, http.StatusAccepted, map[string]any{"accepted": true, "duplicate": false, "eventId": ev.EventID})
	}
}

func (s *Server) validateEvent(ev sessionEvent) (chat.SessionState, string) {
	p := ev.Payload
	switch {
	case ev.EventID == "" || len(ev.EventID) > 128:
		return chat.SessionState{}, "eventId requerido."
	case ev.EventType == "" || ev.SchemaVersion < 1 || ev.OccurredAtUTC.IsZero():
		return chat.SessionState{}, "eventType, schemaVersion y occurredAtUtc requeridos."
	case ev.Producer != s.cfg.EventsProducer:
		return chat.SessionState{}, "producer no corresponde al servicio autenticado."
	case !chat.ValidSessionID(p.SessionID) || ev.AggregateID != "session:"+p.SessionID:
		return chat.SessionState{}, "aggregateId debe ser session:{sessionId}."
	case p.StreamGeneration == nil || p.SessionVersion == nil || *p.SessionVersion != ev.Sequence:
		return chat.SessionState{}, "streamGeneration y sessionVersion requeridos; sequence=sessionVersion."
	}
	status, ok := chat.RoomStatusFromSession(p.Status)
	if !ok {
		return chat.SessionState{}, "status desconocido."
	}
	return chat.SessionState{
		SessionID: p.SessionID, StreamGeneration: *p.StreamGeneration, SessionVersion: *p.SessionVersion,
		Status: status, Availability: p.Availability,
	}, ""
}

// eventFingerprint permite distinguir un reintento idéntico de un eventId reutilizado.
func eventFingerprint(ev sessionEvent) string {
	p := ev.Payload
	canon := fmt.Sprintf("%s|%s|%d|%s|%d|%s|%s|%s|%s|%d|%d|%s|%s",
		ev.EventID, ev.EventType, ev.SchemaVersion, ev.AggregateID, ev.Sequence,
		ev.OccurredAtUTC.UTC().Format(time.RFC3339Nano), ev.Producer,
		p.StreamID, p.SessionID, *p.StreamGeneration, *p.SessionVersion, p.Status, p.Availability)
	sum := sha256.Sum256([]byte(canon))
	return hex.EncodeToString(sum[:])
}

func (s *Server) serviceAuthorized(r *http.Request) bool {
	name := r.Header.Get("X-Service-Name")
	token := r.Header.Get("X-Service-Token")
	okName := subtle.ConstantTimeCompare([]byte(name), []byte(s.cfg.EventsProducer)) == 1
	okToken := subtle.ConstantTimeCompare([]byte(token), []byte(s.cfg.EventsToken)) == 1
	return okName && okToken && s.cfg.EventsToken != ""
}

func (s *Server) originAllowed(origin string) bool {
	for _, o := range s.cfg.AllowedOrigins {
		if origin == o {
			return true
		}
	}
	return false
}

func (s *Server) handleLive(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *Server) handleReady(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), time.Second)
	defer cancel()
	if err := s.ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

// httpStatus traduce códigos de contrato para respuestas previas al Upgrade y REST.
func httpStatus(code string) int {
	switch code {
	case chat.CodeSessionNotFound:
		return http.StatusNotFound
	case chat.CodeChatNotOpen:
		return http.StatusConflict
	case chat.CodeAuthRequired:
		return http.StatusUnauthorized
	case chat.CodeValidation:
		return http.StatusBadRequest
	default:
		return http.StatusServiceUnavailable
	}
}

func (s *Server) writeError(w http.ResponseWriter, r *http.Request, status int, e *chat.Error) {
	writeJSON(w, status, errorEnvelope{Code: e.Code, Message: e.Message, RequestID: requestID(r)})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

type ridKey struct{}

var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

func (s *Server) withRequestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rid := r.Header.Get("X-Request-Id")
		if !requestIDPattern.MatchString(rid) {
			rid = newRequestID()
		}
		w.Header().Set("X-Request-Id", rid)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ridKey{}, rid)))
	})
}

func requestID(r *http.Request) string {
	rid, _ := r.Context().Value(ridKey{}).(string)
	return rid
}

func newRequestID() string { return uuid.NewString() }
