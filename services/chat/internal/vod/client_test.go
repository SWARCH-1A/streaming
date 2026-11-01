package vod

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"streaming/chat/internal/chat"
)

func TestReplayContextSendsServiceIdentityAndMapsErrors(t *testing.T) {
	var got http.Header
	status, body := http.StatusOK, any(fixture)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/internal/vod/vods/vod_1/replay-context" {
			http.NotFound(w, r)
			return
		}
		got = r.Header.Clone()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		json.NewEncoder(w).Encode(body)
	}))
	defer srv.Close()
	c, err := New(Config{BaseURL: srv.URL, ServiceToken: "vod-token", DevelopmentHTTP: true, ConnectTimeout: time.Second, RequestTimeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	tl, cerr := c.ReplayContext(context.Background(), "cookie-ana", "vod_1", "req_1")
	if cerr != nil || tl.MappingVersion != 3 || tl.SessionID != "ses_1" {
		t.Fatalf("%+v %v", tl, cerr)
	}
	if got.Get("X-Service-Name") != "chat" || got.Get("X-Service-Token") != "vod-token" ||
		got.Get("X-Session-Credential") != "cookie-ana" || got.Get("X-Request-Id") != "req_1" {
		t.Fatalf("cabeceras: %v", got)
	}
	if _, cerr := c.ReplayContext(context.Background(), "", "vod_1", ""); cerr != nil || got.Get("X-Session-Credential") != "" {
		t.Fatalf("un espectador anónimo no envía credencial: %v %v", cerr, got)
	}
	for name, tc := range map[string]struct {
		status int
		body   any
		code   string
	}{
		"no legible":        {404, map[string]string{"code": "VOD_NOT_FOUND"}, chat.CodeVodNotFound},
		"cuenta suspendida": {404, map[string]string{"code": "VOD_UNAVAILABLE"}, chat.CodeVodNotFound},
		"sesión inválida":   {401, map[string]string{"code": "SESSION_REQUIRED"}, chat.CodeAuthRequired},
		"token de Chat":     {401, map[string]string{"code": "SERVICE_UNAUTHORIZED"}, CodeReplayUnavailable},
		"Core caído":        {503, map[string]string{"code": "CORE_UNAVAILABLE"}, CodeReplayUnavailable},
		"timeline inválido": {200, map[string]any{"vodId": "vod_1"}, CodeReplayUnavailable},
	} {
		status, body = tc.status, tc.body
		if _, cerr := c.ReplayContext(context.Background(), "cookie-ana", "vod_1", ""); cerr == nil || cerr.Code != tc.code {
			t.Errorf("%s: %v", name, cerr)
		}
	}
}

func TestNewRequiresHTTPSAndToken(t *testing.T) {
	for _, cfg := range []Config{
		{BaseURL: "http://vod:8099", ServiceToken: "x"},
		{BaseURL: "https://vod:8099/base", ServiceToken: "x"},
		{BaseURL: "https://vod:8099"},
	} {
		if _, err := New(cfg); err == nil {
			t.Errorf("%+v debe rechazarse", cfg)
		}
	}
}
