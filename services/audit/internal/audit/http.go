package audit

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"time"
)

var cursorID = regexp.MustCompile(`^[0-9]{1,19}$`)

func ParseQuery(v url.Values) (Query, error) {
	q := Query{Limit: 50}
	for key, values := range v {
		if len(values) != 1 {
			return q, ErrInvalid
		}
		value := values[0]
		switch key {
		case "actorId", "targetId":
			if !opaque.MatchString(value) {
				return q, ErrInvalid
			}
			if key == "actorId" {
				q.ActorID = value
			} else {
				q.TargetID = value
			}
		case "action":
			if !code.MatchString(value) {
				return q, ErrInvalid
			}
			q.Action = value
		case "outcome":
			if value != "SUCCESS" && value != "DENIED" {
				return q, ErrInvalid
			}
			q.Outcome = value
		case "producer":
			if value != "core" && value != "vod" && value != "waf" {
				return q, ErrInvalid
			}
			q.Producer = value
		case "requestId":
			if !requestID.MatchString(value) {
				return q, ErrInvalid
			}
			q.RequestID = value
		case "cursor":
			n, e := strconv.ParseInt(value, 10, 64)
			if e != nil || n < 0 || !cursorID.MatchString(value) {
				return q, ErrInvalid
			}
			q.Cursor = n
		case "limit":
			n, e := strconv.Atoi(value)
			if e != nil || n < 1 || n > 100 {
				return q, ErrInvalid
			}
			q.Limit = n
		case "from", "to":
			t, e := time.Parse(time.RFC3339Nano, value)
			if e != nil {
				return q, ErrInvalid
			}
			if key == "from" {
				q.From = &t
			} else {
				q.To = &t
			}
		default:
			return q, ErrInvalid
		}
	}
	if q.From != nil && q.To != nil && !q.From.Before(*q.To) {
		return q, ErrInvalid
	}
	return q, nil
}
func failure(w http.ResponseWriter, r *http.Request, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	id := r.Header.Get("X-Request-Id")
	if !requestID.MatchString(id) {
		id = "req_audit"
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": code, "message": "No fue posible consultar la auditoría.", "requestId": id})
}
func Handler(s *Store, token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if s.Writer.PingContext(ctx) != nil || s.Reader.PingContext(ctx) != nil {
			failure(w, r, 503, "AUDIT_UNAVAILABLE")
			return
		}
		w.WriteHeader(200)
	})
	mux.HandleFunc("GET /internal/audit/events", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if len(r.Header.Values("X-Service-Token")) != 1 || subtle.ConstantTimeCompare([]byte(token), []byte(r.Header.Get("X-Service-Token"))) != 1 || token == "" || r.Header.Get("X-Service-Name") != "core" {
			failure(w, r, 401, "SERVICE_UNAUTHORIZED")
			return
		}
		if len(r.URL.RawQuery) > 2048 {
			failure(w, r, 400, "VALIDATION_ERROR")
			return
		}
		values, err := url.ParseQuery(r.URL.RawQuery)
		if err != nil {
			failure(w, r, 400, "VALIDATION_ERROR")
			return
		}
		q, err := ParseQuery(values)
		if err != nil {
			failure(w, r, 400, "VALIDATION_ERROR")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		// No evidence is disclosed if the access itself cannot be audited.
		if err = s.RecordRead(ctx, r.Header.Get("X-Audit-Actor")); err != nil {
			failure(w, r, 503, "AUDIT_UNAVAILABLE")
			return
		}
		page, err := s.Query(ctx, q)
		if err != nil {
			failure(w, r, 503, "AUDIT_UNAVAILABLE")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(page)
	})
	return mux
}
