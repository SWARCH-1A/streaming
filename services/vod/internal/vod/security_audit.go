package vod

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"time"
)

var auditCorrelation = regexp.MustCompile(`^[A-Za-z0-9._-]{1,80}$`)

func auditRequestID(r *http.Request) string {
	id := r.Header.Get("X-Request-Id")
	if !auditCorrelation.MatchString(id) {
		return ID("req_")
	}
	return id
}

type auditKey struct{}
type auditAttempt struct {
	ActorID                     *string
	Action, TargetID, RequestID string
}

func (s *Store) auditEvent(ctx context.Context, tx interface {
	ExecContext(context.Context, string, ...any) (sql.Result, error)
}, actor *string, action, target, outcome, reason, request string) error {
	if !s.AuditEnabled {
		return nil
	}
	if !ValidID(target) {
		target = "unknown"
	}
	id := ID("evt_")
	body, err := json.Marshal(Envelope{EventID: id, EventType: "security.audit.vod.v1", SchemaVersion: 1, Producer: "vod", AggregateID: "audit:" + id, Sequence: 1, OccurredAt: time.Now().UTC(), CorrelationID: request,
		Payload: mustAuditPayload(actor, action, target, outcome, reason)})
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO vod_outbox(event_id,body) VALUES($1,$2)", id, body)
	return err
}
func mustAuditPayload(actor *string, action, target, outcome, reason string) json.RawMessage {
	body, _ := json.Marshal(map[string]any{"actorId": actor, "action": action, "targetType": "VOD", "targetId": target, "outcome": outcome, "reasonCode": reason})
	return body
}
func (s *Service) audited(action string, f func(http.ResponseWriter, *http.Request) error) func(http.ResponseWriter, *http.Request) error {
	return func(w http.ResponseWriter, r *http.Request) (err error) {
		if !s.Store.AuditEnabled {
			return f(w, r)
		}
		attempt := &auditAttempt{Action: action, TargetID: r.PathValue("vodId"), RequestID: auditRequestID(r)}
		// Normalize once; response, producer and authority calls share this opaque identifier.
		r = r.Clone(context.WithValue(r.Context(), auditKey{}, attempt))
		r.Header.Set("X-Request-Id", attempt.RequestID)
		w.Header().Set("X-Request-Id", attempt.RequestID)
		defer func() {
			var failure *Fault
			if errors.As(err, &failure) && (failure.Status == 401 || failure.Status == 403) {
				capture, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), 2*time.Second)
				defer cancel()
				if e := s.Store.auditEvent(capture, s.Store.DB, attempt.ActorID, attempt.Action, attempt.TargetID, "DENIED", failure.Code, attempt.RequestID); e != nil {
					err = Fail(503, "AUDIT_UNAVAILABLE")
				}
			}
		}()
		return f(w, r)
	}
}
func (s *Service) auditActor(r *http.Request, credential string) error {
	attempt, _ := r.Context().Value(auditKey{}).(*auditAttempt)
	if attempt == nil {
		return nil
	}
	actor, err := s.Authority.Check(r.Context(), "", credential, "SESSION")
	if err != nil {
		return err
	}
	if actor.UserID != "" {
		attempt.ActorID = &actor.UserID
	}
	return nil
}
