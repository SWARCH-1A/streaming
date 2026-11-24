package audit

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func example(t *testing.T) Event {
	t.Helper()
	actor := "usr_fixture"
	return Event{EventID: "a38a5088-2178-444f-9ebd-a3a1dfbe7d01", EventType: "security.audit.vod.v1", SchemaVersion: 1, Producer: "vod", AggregateID: "audit:a38a5088-2178-444f-9ebd-a3a1dfbe7d01", Sequence: 1, OccurredAtUTC: "2026-11-20T12:00:00Z", CorrelationID: "req_fixture", Payload: Payload{ActorID: &actor, Action: "VOD_PATCH", TargetType: "VOD", TargetID: "vod_fixture", Outcome: "DENIED", ReasonCode: "VOD_FORBIDDEN"}}
}
func TestEventTrustBoundary(t *testing.T) {
	e := example(t)
	raw, _ := json.Marshal(e)
	if _, err := Parse(raw, "vod"); err != nil {
		t.Fatal(err)
	}
	for name, change := range map[string]func(string) string{
		"case-aliased envelope": func(s string) string { return strings.Replace(s, `"eventType":`, `"EventType":`, 1) },
		"case-aliased actor":    func(s string) string { return strings.Replace(s, `"actorId":`, `"ActorID":`, 1) },
		"null plus alias": func(s string) string {
			return strings.Replace(s, `"actorId":"usr_fixture"`, `"actorId":null,"ActorID":"usr_forged"`, 1)
		},
		"wrong producer": func(s string) string { return strings.Replace(s, `"producer":"vod"`, `"producer":"core"`, 1) },
		"duplicate nested key": func(s string) string {
			return strings.Replace(s, `"actorId":"usr_fixture"`, `"actorId":"usr_fixture","actorId":"usr_forged"`, 1)
		},
		"duplicate root key": func(s string) string { return strings.Replace(s, `"sequence":1`, `"sequence":1,"sequence":1`, 1) },
		"secret field": func(s string) string {
			return strings.Replace(s, `"payload":{`, `"payload":{"password":"fixture-secret",`, 1)
		},
		"unknown timezone":     func(s string) string { return strings.Replace(s, "12:00:00Z", "12:00:00-00:00", 1) },
		"unsupported SQL year": func(s string) string { return strings.Replace(s, "2026-11-20", "0000-11-20", 1) },
		"trailing body":        func(s string) string { return s + `{}` },
		"missing actor":        func(s string) string { return strings.Replace(s, `"actorId":"usr_fixture",`, "", 1) },
		"log injection":        func(s string) string { return strings.Replace(s, "req_fixture", `req_fixture\nforged`, 1) },
		"wrong aggregate":      func(s string) string { return strings.Replace(s, "audit:", "vod:", 1) },
		"unknown action":       func(s string) string { return strings.Replace(s, "VOD_PATCH", "ACCOUNT_STATUS", 1) },
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := Parse([]byte(change(string(raw))), "vod"); err == nil {
				t.Fatal("untrusted event accepted")
			}
		})
	}
	e.Payload.ActorID = nil
	raw, _ = json.Marshal(e)
	if _, err := Parse(raw, "vod"); err != nil {
		t.Fatal("unknown denied actor rejected", err)
	}
	e.Payload.Outcome = "SUCCESS"
	raw, _ = json.Marshal(e)
	if _, err := Parse(raw, "vod"); err == nil {
		t.Fatal("unattributed success accepted")
	}
}
func TestWafEventIsAnUnattributedBlock(t *testing.T) {
	e := Event{EventID: "a38a5088-2178-544f-9ebd-a3a1dfbe7d03", EventType: "security.audit.waf.v1", SchemaVersion: 1, Producer: "waf", AggregateID: "audit:a38a5088-2178-544f-9ebd-a3a1dfbe7d03", Sequence: 1, OccurredAtUTC: "2026-11-20T12:00:00Z", CorrelationID: "2ef5522f57c4fe9648f2d11c094825dc", Payload: Payload{Action: "WAF_BLOCK", TargetType: "ROUTE", TargetID: "api_taxonomy", Outcome: "DENIED", ReasonCode: "SQL_INJECTION"}}
	raw, _ := json.Marshal(e)
	if _, err := Parse(raw, "waf"); err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(raw, "vod"); err == nil {
		t.Fatal("WAF event accepted from the VOD subscription")
	}
	for name, change := range map[string]func(string) string{
		"attributed actor": func(s string) string { return strings.Replace(s, `"actorId":null`, `"actorId":"usr_forged"`, 1) },
		"success":          func(s string) string { return strings.Replace(s, `"DENIED"`, `"SUCCESS"`, 1) },
		"domain action":    func(s string) string { return strings.Replace(s, "WAF_BLOCK", "VOD_DELETE", 1) },
		"domain target":    func(s string) string { return strings.Replace(s, `"ROUTE"`, `"VOD"`, 1) },
		"path in target":   func(s string) string { return strings.Replace(s, "api_taxonomy", "/api/taxonomy", 1) },
		"other type":       func(s string) string { return strings.Replace(s, "security.audit.waf.v1", "security.audit.vod.v1", 1) },
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := Parse([]byte(change(string(raw))), "waf"); err == nil {
				t.Fatal("untrusted WAF event accepted")
			}
		})
	}
	for name, producer := range map[string]string{"core": "core", "vod": "vod"} {
		c := example(t)
		c.Producer, c.EventType = producer, "security.audit."+producer+".v1"
		c.Payload.Action, c.Payload.TargetType = "WAF_BLOCK", "ROUTE"
		raw, _ := json.Marshal(c)
		if _, err := Parse(raw, producer); err == nil {
			t.Fatal(name + " may not report WAF blocks")
		}
	}
}
func TestQueryValidationAndUnauthenticatedBoundary(t *testing.T) {
	for _, query := range []string{"limit=101", "limit=0", "cursor=-1", "cursor=%2B1", "limit=2&limit=3", "token=secret", "actorId=x%0Aforged", "from=2026-11-21T00:00:00Z&to=2026-11-20T00:00:00Z"} {
		r := httptest.NewRequest("GET", "/internal/audit/events?"+query, nil)
		if _, err := ParseQuery(r.URL.Query()); err == nil {
			t.Fatalf("accepted %s", query)
		}
	}
	r := httptest.NewRequest("GET", "/internal/audit/events", nil)
	w := httptest.NewRecorder()
	Handler(nil, strings.Repeat("s", 32)).ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
}

func TestMalformedQueryIsNotSilentlyDropped(t *testing.T) {
	token := strings.Repeat("s", 32)
	for _, query := range []string{"limit=1&token=%zz", "limit=1;outcome=DENIED", "requestId=%xx"} {
		r := httptest.NewRequest("GET", "/internal/audit/events?"+query, nil)
		r.Header.Set("X-Service-Token", token)
		r.Header.Set("X-Service-Name", "core")
		r.Header.Set("X-Audit-Actor", "usr_admin")
		w := httptest.NewRecorder()
		Handler(nil, token).ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatal(query, w.Code)
		}
	}
}
