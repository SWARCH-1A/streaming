// Gateway composes screen reads; domain APIs and media retain their owners behind Caddy.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
)

// screenBudget bounds a complete screen, including dependent reads (ADR-023).
const screenBudget = 3 * time.Second

// maxUpstreamBody bounds one internal JSON read; the gateway never buffers media.
const maxUpstreamBody = 2 << 20

type gateway struct {
	core, vod, live string
	client          *http.Client
	proxies         trustedProxies
	queriesFile     string
	budget          time.Duration // zero means screenBudget
}

type section struct {
	Status string `json:"status"`
	Data   any    `json:"data,omitempty"`
	Error  any    `json:"error,omitempty"`
}

type requestIDKey struct{}

var (
	requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
	resourceID       = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
	channelHandle    = regexp.MustCompile(`^[A-Za-z0-9_]{4,25}$`)
)

func failure(code, requestID string) section {
	return section{Status: "UNAVAILABLE", Error: map[string]any{"code": code, "retryable": true, "requestId": requestID}}
}

// withRequestID fixes the correlation id once, before any concurrent internal read starts.
func withRequestID(r *http.Request) *http.Request {
	value := r.Header.Get("X-Request-ID")
	if !requestIDPattern.MatchString(value) {
		raw := make([]byte, 16)
		_, _ = rand.Read(raw)
		value = "req_" + hex.EncodeToString(raw)
	}
	return r.WithContext(context.WithValue(r.Context(), requestIDKey{}, value))
}

func correlation(r *http.Request) string {
	if value, ok := r.Context().Value(requestIDKey{}).(string); ok {
		return value
	}
	if value := r.Header.Get("X-Request-ID"); requestIDPattern.MatchString(value) {
		return value
	}
	return "gateway"
}

// sessionCookie keeps only the session credential; other public cookies never reach the domains.
func sessionCookie(r *http.Request) string {
	if cookie, e := r.Cookie("stream_session"); e == nil && cookie.Value != "" {
		return (&http.Cookie{Name: cookie.Name, Value: cookie.Value}).String()
	}
	return ""
}

func presentsIdentity(r *http.Request) bool {
	return sessionCookie(r) != "" || r.Header.Get("Authorization") != ""
}

// read performs one internal GET. Only an explicit allow-list of public headers is forwarded:
// identity (when requested), correlation and the observed client address. X-Service-* and any
// other client-supplied private header are never copied.
func (g gateway) read(ctx context.Context, r *http.Request, base, path string, identity bool) (json.RawMessage, int) {
	request, e := http.NewRequestWithContext(ctx, http.MethodGet, base+path, nil)
	if e != nil {
		return nil, http.StatusServiceUnavailable
	}
	if identity {
		if cookie := sessionCookie(r); cookie != "" {
			request.Header.Set("Cookie", cookie)
		}
		if bearer := r.Header.Get("Authorization"); bearer != "" {
			request.Header.Set("Authorization", bearer)
		}
	}
	g.forwarding(r, request)
	request.Header.Set("Accept", "application/json")
	res, e := g.client.Do(request)
	if e != nil {
		return nil, transportStatus(ctx, e)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 64<<10))
		return nil, res.StatusCode
	}
	raw, e := io.ReadAll(io.LimitReader(res.Body, maxUpstreamBody+1))
	if e != nil {
		return nil, transportStatus(ctx, e)
	}
	if len(raw) > maxUpstreamBody || !json.Valid(raw) {
		return nil, http.StatusServiceUnavailable
	}
	return raw, http.StatusOK
}

// forwarding propagates correlation and the client address reconstructed by trusted proxies.
func (g gateway) forwarding(r *http.Request, request *http.Request) {
	request.Header.Set("X-Request-ID", correlation(r))
	if client := g.proxies.forwardedFor(r); client != "" {
		request.Header.Set("X-Forwarded-For", client)
	}
}

func transportStatus(ctx context.Context, e error) int {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) || errors.Is(e, context.DeadlineExceeded) {
		return http.StatusGatewayTimeout
	}
	return http.StatusServiceUnavailable
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
	case 400, 401, 403, 404, 410, 422, 429, 504:
		return status
	default:
		return http.StatusServiceUnavailable
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store, private")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func screenError(w http.ResponseWriter, r *http.Request, status int) {
	status = normalizedStatus(status)
	code := "SCREEN_UNAVAILABLE"
	message := "La pantalla no está disponible."
	switch status {
	case 400, 422:
		code, message = "VALIDATION_ERROR", "La consulta no es válida."
	case 401:
		code, message = "SESSION_INVALID", "La sesión no es válida."
	case 403:
		code, message = "FORBIDDEN", "No tienes acceso a esta pantalla."
	case 404:
		code, message = "NOT_FOUND", "El recurso no existe o no está disponible."
	case 410:
		code, message = "CURSOR_INVALID", "El cursor caducó; vuelve a consultar sin cursor."
	case 429:
		code, message = "RATE_LIMITED", "Demasiadas solicitudes."
	case 504:
		code = "SCREEN_TIMEOUT"
	}
	writeJSON(w, status, map[string]any{"code": code, "message": message, "requestId": correlation(r)})
}

func writeScreen(w http.ResponseWriter, r *http.Request, result map[string]any, partial bool) {
	result["meta"] = map[string]any{"partial": partial, "requestId": correlation(r)}
	writeJSON(w, http.StatusOK, result)
}

// screen applies the request id, the total budget and the private cache policy to a handler.
func (g gateway) screen(handler func(context.Context, http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		r = withRequestID(r)
		budget := g.budget
		if budget <= 0 {
			budget = screenBudget
		}
		ctx, cancel := context.WithTimeout(r.Context(), budget)
		defer cancel()
		handler(ctx, w, r)
	}
}

func env(key, value string) string {
	if v := os.Getenv(key); v != "" {
		return strings.TrimRight(v, "/")
	}
	return value
}

func (g gateway) routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/screens/channels/{handle}", g.screen(g.channelScreen))
	mux.HandleFunc("GET /api/screens/watch/{streamId}", g.screen(g.watchScreen))
	mux.HandleFunc("GET /api/screens/vods/{vodId}", g.screen(g.vodScreen))
	mux.HandleFunc("GET /api/screens/explore", g.screen(g.exploreScreen))
	mux.HandleFunc("POST /api/admin/content-removals", func(w http.ResponseWriter, r *http.Request) { g.removal(w, withRequestID(r)) })
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) })
	// Published paths with another method answer 405 instead of falling through to 404.
	for _, path := range []string{"/api/screens/channels/{handle}", "/api/screens/watch/{streamId}", "/api/screens/vods/{vodId}", "/api/screens/explore", "/api/admin/content-removals"} {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			r = withRequestID(r)
			writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"code": "METHOD_NOT_ALLOWED", "message": "Método no permitido.", "requestId": correlation(r)})
		})
	}
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		r = withRequestID(r)
		writeJSON(w, http.StatusNotFound, map[string]any{"code": "NOT_FOUND", "message": "Ruta no encontrada.", "requestId": correlation(r)})
	})
	return mux
}

func main() {
	proxies, e := parseTrustedProxies(os.Getenv("GATEWAY_TRUSTED_PROXIES"))
	if e != nil {
		os.Stderr.WriteString("invalid GATEWAY_TRUSTED_PROXIES\n")
		os.Exit(2)
	}
	g := gateway{
		core:        env("GATEWAY_CORE_URL", "https://core:8081"),
		vod:         env("GATEWAY_VOD_URL", "https://vod:8098"),
		live:        env("GATEWAY_STREAMING_URL", "https://live:8080"),
		client:      &http.Client{Timeout: screenBudget, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		proxies:     proxies,
		queriesFile: env("GATEWAY_QUERIES_FILE", "/app/graphql-queries.json"),
	}
	s := &http.Server{Addr: ":8097", Handler: g.routes(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32 << 10}
	if os.Getenv("GATEWAY_DEVELOPMENT_HTTP") == "true" {
		e = s.ListenAndServe()
	} else {
		e = s.ListenAndServeTLS(os.Getenv("GATEWAY_TLS_CERT_FILE"), os.Getenv("GATEWAY_TLS_KEY_FILE"))
	}
	if e != nil {
		os.Exit(1)
	}
}
