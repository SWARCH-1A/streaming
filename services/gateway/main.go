// Gateway composes screen reads; domain APIs and media retain their owners behind Caddy.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

type gateway struct {
	core, vod, live string
	client          *http.Client
}
type section struct {
	Status string `json:"status"`
	Data   any    `json:"data,omitempty"`
	Error  any    `json:"error,omitempty"`
}

func failure(code, requestID string) section {
	return section{Status: "UNAVAILABLE", Error: map[string]any{"code": code, "retryable": true, "requestId": requestID}}
}
func (g gateway) read(ctx context.Context, r *http.Request, base, path string) (json.RawMessage, int) {
	request, e := http.NewRequestWithContext(ctx, "GET", base+path, nil)
	if e != nil {
		return nil, 503
	}
	for _, name := range []string{"Cookie", "Authorization", "X-Request-ID"} {
		if v := r.Header.Get(name); v != "" {
			request.Header.Set(name, v)
		}
	}
	request.Header.Set("Accept", "application/json")
	res, e := g.client.Do(request)
	if e != nil {
		return nil, 503
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, res.StatusCode
	}
	raw, e := io.ReadAll(io.LimitReader(res.Body, 2<<20))
	if e != nil || !json.Valid(raw) {
		return nil, 503
	}
	return raw, 200
}
func available(raw json.RawMessage) section {
	var page struct {
		Items []any `json:"items"`
	}
	if json.Unmarshal(raw, &page) == nil && page.Items != nil && len(page.Items) == 0 {
		return section{Status: "EMPTY", Data: raw}
	}
	return section{Status: "AVAILABLE", Data: raw}
}
func normalizedStatus(status int) int {
	switch status {
	case 400, 401, 403, 404, 410, 422, 429:
		return status
	case 504:
		return 504
	default:
		return 503
	}
}
func correlation(r *http.Request) string {
	value := r.Header.Get("X-Request-ID")
	if regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`).MatchString(value) {
		return value
	}
	raw := make([]byte, 16)
	_, _ = rand.Read(raw)
	value = "req_" + hex.EncodeToString(raw)
	r.Header.Set("X-Request-ID", value)
	return value
}
func (g gateway) serve(w http.ResponseWriter, r *http.Request) {
	correlation(r)
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	w.Header().Set("Cache-Control", "no-store, private")
	w.Header().Set("Content-Type", "application/json")
	result := map[string]any{}
	status := 200
	partial := false
	switch {
	case strings.HasPrefix(r.URL.Path, "/api/screens/vods/"):
		id := r.PathValue("vodId")
		if id == "" || strings.ContainsAny(id, "/%?#") {
			status = 404
			break
		}
		var raw, timeline json.RawMessage
		var code, timelineCode int
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); raw, code = g.read(ctx, r, g.vod, "/api/vods/"+id) }()
		go func() { defer wg.Done(); timeline, timelineCode = g.read(ctx, r, g.vod, "/api/vods/"+id+"/timeline") }()
		wg.Wait()
		if code != 200 {
			status = code
			break
		}
		if timelineCode != 200 {
			status = timelineCode
			break
		}
		var v struct {
			ChannelID string `json:"channelId"`
		}
		if json.Unmarshal(raw, &v) != nil || v.ChannelID == "" {
			status = 503
			break
		}
		channel, c := g.read(ctx, r, g.core, "/api/channels/"+url.PathEscape(v.ChannelID))
		result["vod"] = raw
		result["mapping"] = timeline
		if c == 200 {
			result["channel"] = available(channel)
		} else {
			result["channel"] = failure("CHANNEL_UNAVAILABLE", correlation(r))
			partial = true
		}
	case strings.HasPrefix(r.URL.Path, "/api/screens/channels/"):
		channel, code := g.read(ctx, r, g.core, "/api/channels/by-handle/"+url.PathEscape(r.PathValue("handle")))
		if code != 200 {
			status = code
			break
		}
		var c struct {
			Channel struct {
				ID string `json:"channelId"`
			} `json:"channel"`
			Stream json.RawMessage `json:"stream"`
		}
		if json.Unmarshal(channel, &c) != nil || c.Channel.ID == "" {
			status = 503
			break
		}
		result["channel"] = channel
		var vods, follow json.RawMessage
		var vc, fc int
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			vods, vc = g.read(ctx, r, g.vod, "/api/vods?channelId="+url.QueryEscape(c.Channel.ID))
		}()
		go func() {
			defer wg.Done()
			follow, fc = g.read(ctx, r, g.core, "/api/channels/"+url.PathEscape(c.Channel.ID)+"/followers/me")
		}()
		wg.Wait()
		if vc == 200 {
			result["vods"] = available(vods)
		} else {
			result["vods"] = failure("VOD_UNAVAILABLE", correlation(r))
			partial = true
		}
		if fc == 200 {
			result["follow"] = available(follow)
		} else if fc == 401 {
			if r.Header.Get("Cookie") != "" || r.Header.Get("Authorization") != "" {
				status = 401
				break
			}
			result["follow"] = section{Status: "DENIED", Error: map[string]any{"code": "SESSION_REQUIRED", "retryable": false, "requestId": correlation(r)}}
		} else {
			result["follow"] = failure("FOLLOW_UNAVAILABLE", correlation(r))
			partial = true
		}
		if len(c.Stream) > 0 && string(c.Stream) != "null" {
			result["live"] = available(c.Stream)
		} else {
			result["live"] = section{Status: "EMPTY"}
		}
	case r.URL.Path == "/api/screens/explore":
		// Discovery and VOD have distinct cursors. This aggregate carries the VOD page plus public live metadata.
		q := r.URL.Query()
		vquery := url.Values{}
		for _, key := range []string{"title", "channelId", "categoryId", "tagId", "limit", "cursor"} {
			if q.Has(key) {
				vquery[key] = q[key]
			}
		}
		var raw, live json.RawMessage
		var code, liveCode int
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); raw, code = g.read(ctx, r, g.vod, "/api/vods?"+vquery.Encode()) }()
		go func() { defer wg.Done(); live, liveCode = g.discovery(ctx, r) }()
		wg.Wait()
		if code == 401 {
			status = 401
			break
		}
		if code == 200 {
			result["vods"] = available(raw)
		} else {
			result["vods"] = failure("VOD_UNAVAILABLE", correlation(r))
			partial = true
		}

		if liveCode == 200 {
			result["discovery"] = available(live)
		} else {
			result["discovery"] = failure("DISCOVERY_UNAVAILABLE", correlation(r))
			partial = true
		}
	default:
		status = 404
	}
	if status != 200 {
		w.WriteHeader(normalizedStatus(status))
		_ = json.NewEncoder(w).Encode(map[string]any{"code": "SCREEN_UNAVAILABLE", "message": "La pantalla no está disponible.", "requestId": correlation(r)})
		return
	}
	result["meta"] = map[string]any{"partial": partial, "requestId": correlation(r)}
	_ = json.NewEncoder(w).Encode(result)
}
func (g gateway) discovery(ctx context.Context, r *http.Request) (json.RawMessage, int) {
	rawQueries, e := os.ReadFile(env("GATEWAY_QUERIES_FILE", "/app/graphql-queries.json"))
	if e != nil {
		return nil, 503
	}
	var queries []struct {
		Name  string `json:"name"`
		Query string `json:"query"`
	}
	if json.Unmarshal(rawQueries, &queries) != nil {
		return nil, 503
	}
	query := ""
	for _, q := range queries {
		if q.Name == "LiveStreams" {
			query = q.Query
		}
	}
	if query == "" {
		return nil, 503
	}
	variables := map[string]any{"limit": 12}
	for _, field := range []string{"categoryId", "tagId"} {
		if value := r.URL.Query().Get(field); value != "" {
			variables[field] = value
		}
	}
	if value := r.URL.Query().Get("title"); value != "" {
		variables["q"] = value
	}
	payload, _ := json.Marshal(map[string]any{"query": query, "variables": variables})
	body := string(payload)
	request, e := http.NewRequestWithContext(ctx, "POST", g.core+"/api/discovery/graphql", strings.NewReader(body))
	if e != nil {
		return nil, 503
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Request-ID", correlation(r))
	res, e := g.client.Do(request)
	if e != nil {
		return nil, 503
	}
	defer res.Body.Close()
	raw, e := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if e != nil || res.StatusCode != 200 {
		return nil, 503
	}
	var data struct {
		Data   json.RawMessage `json:"data"`
		Errors []any           `json:"errors"`
	}
	if json.Unmarshal(raw, &data) != nil || len(data.Errors) > 0 || len(data.Data) == 0 {
		return nil, 503
	}
	return data.Data, 200
}
func env(key, value string) string {
	if v := os.Getenv(key); v != "" {
		return strings.TrimRight(v, "/")
	}
	return value
}
func main() {
	g := gateway{env("GATEWAY_CORE_URL", "https://core:8081"), env("GATEWAY_VOD_URL", "https://vod:8098"), env("GATEWAY_STREAMING_URL", "https://live:8080"), &http.Client{Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	mux := http.NewServeMux()
	for _, p := range []string{"GET /api/screens/vods/{vodId}", "GET /api/screens/channels/{handle}", "GET /api/screens/explore"} {
		mux.HandleFunc(p, g.serve)
	}
	mux.HandleFunc("POST /api/admin/content-removals", g.removal)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })
	s := &http.Server{Addr: ":8097", Handler: mux, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second}
	var e error
	if os.Getenv("GATEWAY_DEVELOPMENT_HTTP") == "true" {
		e = s.ListenAndServe()
	} else {
		e = s.ListenAndServeTLS(os.Getenv("GATEWAY_TLS_CERT_FILE"), os.Getenv("GATEWAY_TLS_KEY_FILE"))
	}
	if e != nil {
		os.Exit(1)
	}
}
