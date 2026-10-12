package core

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"streaming/chat/internal/chat"
)

func newClient(t *testing.T, h http.HandlerFunc) *Client {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	c, err := New(Config{DevelopmentHTTP: true, BaseURL: srv.URL, ServiceToken: "svc-token", ConnectTimeout: 100 * time.Millisecond, RequestTimeout: 200 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestMessageContextSendsServiceAndSessionHeaders(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/internal/core/chat/message-context" {
			t.Errorf("ruta %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("X-Service-Name") != "chat" || r.Header.Get("X-Service-Token") != "svc-token" ||
			r.Header.Get("X-Session-Credential") != "cookie-value" {
			t.Errorf("headers %v", r.Header)
		}
		var body map[string]string
		json.NewDecoder(r.Body).Decode(&body)
		if body["sessionId"] != "ses_1" || body["clientMessageId"] != "cm-1" || len(body) != 2 {
			t.Errorf("body %v", body)
		}
		pos := int64(42)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(MessageContext{UserID: "usr_1", Handle: "ana", SessionID: "ses_1", WriteAllowed: true, TimelinePositionMs: &pos})
	})
	mc, err := c.MessageContext(context.Background(), "cookie-value", "ses_1", "cm-1")
	if err != nil || mc.UserID != "usr_1" || *mc.TimelinePositionMs != 42 {
		t.Fatalf("%+v %v", mc, err)
	}
}

func TestErrorsMapToStableCodes(t *testing.T) {
	cases := []struct {
		status int
		body   string
		want   string
	}{
		{401, `{"code":"UNAUTHENTICATED"}`, chat.CodeAuthRequired},
		{401, `{"code":"SERVICE_UNAUTHORIZED"}`, chat.CodeCoreUnavailable},
		{404, `{"code":"NOT_FOUND"}`, chat.CodeSessionNotFound},
		{503, `{"code":"STREAMING_UNAVAILABLE"}`, chat.CodeStreamingUnavailable},
		{409, `{"code":"TIMELINE_UNAVAILABLE"}`, chat.CodeTimelineUnavailable},
		{500, `oops`, chat.CodeCoreUnavailable},
		{400, `{}`, chat.CodeCoreUnavailable},
	}
	for _, tc := range cases {
		c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
			w.Write([]byte(tc.body))
		})
		_, err := c.MessageContext(context.Background(), "x", "ses_1", "cm-1")
		if err == nil || err.Code != tc.want {
			t.Fatalf("%d %s -> %v, want %s", tc.status, tc.body, err, tc.want)
		}
	}
}

func TestRedirectDoesNotForwardPrivateHeaders(t *testing.T) {
	calls := 0
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		http.Redirect(w, r, "/leak", http.StatusTemporaryRedirect)
	})
	if _, err := c.MessageContext(context.Background(), "credential", "ses_1", "cm-1"); err == nil || err.Code != chat.CodeCoreUnavailable || calls != 1 {
		t.Fatalf("redirect: calls=%d err=%v", calls, err)
	}
}

func TestHTTPRequiresExplicitIsolatedDevelopmentOptIn(t *testing.T) {
	if _, err := New(Config{BaseURL: "http://core:8082"}); err == nil {
		t.Fatal("HTTP accepted without explicit development opt-in")
	}
	for _, url := range []string{"https://user:password@core", "https://core/path", "https://core?secret=value"} {
		if _, err := New(Config{BaseURL: url}); err == nil {
			t.Fatal("unsafe base URL accepted")
		}
	}
}

func TestOversizedSuccessfulJsonCannotBeAcceptedFromItsTruncatedPrefix(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"userId":"usr_1","sessionId":"ses_1"}` + strings.Repeat(" ", 70<<10)))
	})
	if _, err := c.MessageContext(context.Background(), "x", "ses_1", "cm-1"); err == nil || err.Code != chat.CodeCoreUnavailable {
		t.Fatalf("oversized JSON accepted: %v", err)
	}
}

func TestTimeoutAndMismatchedSessionAreCoreUnavailable(t *testing.T) {
	slow := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(400 * time.Millisecond)
	})
	if _, err := slow.MessageContext(context.Background(), "x", "ses_1", "cm-1"); err == nil || err.Code != chat.CodeCoreUnavailable {
		t.Fatalf("timeout -> %v", err)
	}
	wrong := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(MessageContext{UserID: "usr_1", SessionID: "ses_otra"})
	})
	if _, err := wrong.MessageContext(context.Background(), "x", "ses_1", "cm-1"); err == nil || err.Code != chat.CodeCoreUnavailable {
		t.Fatalf("sesión distinta -> %v", err)
	}
	down, _ := New(Config{DevelopmentHTTP: true, BaseURL: "http://127.0.0.1:1", ServiceToken: "t", ConnectTimeout: 100 * time.Millisecond, RequestTimeout: 200 * time.Millisecond})
	if _, err := down.Snapshot(context.Background(), "ses_1"); err == nil || err.Code != chat.CodeCoreUnavailable {
		t.Fatalf("Core caído -> %v", err)
	}
}

func TestSnapshotWithoutSessionCredential(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/internal/core/chat/sessions/ses_1" || r.Header.Get("X-Session-Credential") != "" {
			t.Errorf("solicitud %s %s %v", r.Method, r.URL.Path, r.Header)
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(SessionSnapshot{SessionID: "ses_1", Status: "LIVE", StreamGeneration: 1, SessionVersion: 3})
	})
	snap, err := c.Snapshot(context.Background(), "ses_1")
	if err != nil || snap.Status != "LIVE" || snap.SessionVersion != 3 {
		t.Fatalf("%+v %v", snap, err)
	}
}
