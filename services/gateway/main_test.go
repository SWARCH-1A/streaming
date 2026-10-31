package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestChannelPartialFailureKeepsIdentityAndRequestID(t *testing.T) {
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Request-ID") != "req_channel" {
			t.Error("missing correlation")
		}
		if r.URL.Path == "/api/channels/by-handle/fixture" {
			_, _ = w.Write([]byte(`{"channel":{"channelId":"chn_fixture"},"stream":null}`))
			return
		}
		if r.Header.Get("Cookie") != "stream_session=alice" {
			t.Error("identity changed")
		}
		_, _ = w.Write([]byte(`{"followed":true}`))
	}))
	defer core.Close()
	vod := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(503) }))
	defer vod.Close()
	g := gateway{core: core.URL, vod: vod.URL, client: &http.Client{Timeout: time.Second}}
	r := httptest.NewRequest("GET", "/api/screens/channels/fixture", nil)
	r.SetPathValue("handle", "fixture")
	r.Header.Set("Cookie", "stream_session=alice")
	r.Header.Set("X-Request-ID", "req_channel")
	w := httptest.NewRecorder()
	g.serve(w, r)
	var result struct {
		Meta struct {
			Partial   bool
			RequestID string `json:"requestId"`
		}
		Vods section
		Live section
	}
	if json.Unmarshal(w.Body.Bytes(), &result) != nil || w.Code != 200 || !result.Meta.Partial || result.Meta.RequestID != "req_channel" || result.Vods.Status != "UNAVAILABLE" || result.Live.Status != "EMPTY" {
		t.Fatal(w.Code, w.Body.String())
	}
}
func TestParallelSSRRequestsDoNotShareCredentials(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(5 * time.Millisecond)
		_ = json.NewEncoder(w).Encode(map[string]string{"identity": r.Header.Get("Cookie")})
	}))
	defer upstream.Close()
	g := gateway{client: upstream.Client()}
	var wg sync.WaitGroup
	for _, identity := range []string{"stream_session=alice", "stream_session=bob"} {
		wg.Add(1)
		go func(identity string) {
			defer wg.Done()
			r := httptest.NewRequest("GET", "/", nil)
			r.Header.Set("Cookie", identity)
			raw, code := g.read(r.Context(), r, upstream.URL, "/api/me")
			var got map[string]string
			_ = json.Unmarshal(raw, &got)
			if code != 200 || got["identity"] != identity {
				t.Errorf("shared identity: %s", raw)
			}
		}(identity)
	}
	wg.Wait()
}
func TestInvalidPresentedIdentityIsNotAnonymous(t *testing.T) {
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/channels/by-handle/fixture" {
			_, _ = w.Write([]byte(`{"channel":{"channelId":"chn_fixture"},"stream":null}`))
			return
		}
		w.WriteHeader(401)
	}))
	defer core.Close()
	vod := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`{"items":[],"nextCursor":null}`)) }))
	defer vod.Close()
	g := gateway{core: core.URL, vod: vod.URL, client: core.Client()}
	r := httptest.NewRequest("GET", "/api/screens/channels/fixture", nil)
	r.SetPathValue("handle", "fixture")
	r.Header.Set("Cookie", "stream_session=revoked")
	w := httptest.NewRecorder()
	g.serve(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code, w.Body.String())
	}
}
