package vod

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestAuditCorrelationBoundary(t *testing.T) {
	for _, supplied := range []string{"", strings.Repeat("x", 81), "forged\nentry", "<script>"} {
		r := httptest.NewRequest("PATCH", "/api/vods/vod_fixture", nil)
		r.Header.Set("X-Request-Id", supplied)
		got := auditRequestID(r)
		if !auditCorrelation.MatchString(got) || got == supplied {
			t.Fatalf("unsafe correlation accepted: %q", got)
		}
	}
	r := httptest.NewRequest("PATCH", "/api/vods/vod_fixture", nil)
	r.Header.Set("X-Request-Id", "req.valid-123_abc")
	if got := auditRequestID(r); got != "req.valid-123_abc" {
		t.Fatal("valid correlation lost", got)
	}
}

func TestAuditErrorCorrelationMatchesEvidence(t *testing.T) {
	for _, supplied := range []string{"", strings.Repeat("x", 81), "req.valid-123_abc"} {
		t.Run(supplied, func(t *testing.T) {
			service := &Service{Store: &Store{AuditEnabled: true}}
			r := httptest.NewRequest("PATCH", "/api/vods/vod_fixture", nil)
			r.Header.Set("X-Request-Id", supplied)
			w := httptest.NewRecorder()
			var evidenceID string
			handler := service.audited("VOD_PATCH", func(w http.ResponseWriter, r *http.Request) error {
				evidenceID = r.Context().Value(auditKey{}).(*auditAttempt).RequestID
				return Fail(400, "VALIDATION_ERROR")
			})
			respondError(w, r, handler(w, r))
			var body struct {
				RequestID string `json:"requestId"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if body.RequestID != evidenceID || w.Header().Get("X-Request-Id") != evidenceID {
				t.Fatalf("correlation differs: body=%s header=%s evidence=%s", body.RequestID, w.Header().Get("X-Request-Id"), evidenceID)
			}
		})
	}
}
