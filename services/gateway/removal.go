package main

import (
	"bytes"
	"crypto/hmac"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"strings"
)

func gatewayError(w http.ResponseWriter, r *http.Request, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store, private")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"code": code, "message": "No se pudo completar la operación.", "requestId": correlation(r)})
}

// The Gateway forwards identity; VOD and Core enforce role, version, reason and domain ownership.
func (g gateway) removal(w http.ResponseWriter, r *http.Request) {
	credential := ""
	if cookie, e := r.Cookie("stream_session"); e == nil {
		credential = cookie.Value
	}
	bearer := r.Header.Get("Authorization")
	if bearer != "" {
		if !strings.HasPrefix(bearer, "Bearer ") || strings.TrimPrefix(bearer, "Bearer ") == "" || (credential != "" && credential != strings.TrimPrefix(bearer, "Bearer ")) {
			gatewayError(w, r, 401, "CREDENTIAL_CONFLICT")
			return
		}
		credential = strings.TrimPrefix(bearer, "Bearer ")
	}
	if credential == "" {
		gatewayError(w, r, 401, "SESSION_REQUIRED")
		return
	}
	if bearer == "" {
		cookie, e := r.Cookie("XSRF-TOKEN")
		if e != nil || r.Header.Get("Origin") != os.Getenv("GATEWAY_PUBLIC_ORIGIN") || len(cookie.Value) < 20 || !hmac.Equal([]byte(cookie.Value), []byte(r.Header.Get("X-XSRF-TOKEN"))) {
			gatewayError(w, r, 403, "CSRF_REQUIRED")
			return
		}
	}
	var body struct {
		ResourceType    string `json:"resourceType"`
		ResourceID      string `json:"resourceId"`
		ExpectedVersion int64  `json:"expectedVersion"`
		Reason          string `json:"reason"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384))
	decoder.DisallowUnknownFields()
	var tail any
	if decoder.Decode(&body) != nil || decoder.Decode(&tail) != io.EOF || body.ResourceType != "VOD" {
		gatewayError(w, r, 422, "VALIDATION_ERROR")
		return
	}
	raw, _ := json.Marshal(map[string]any{"vodId": body.ResourceID, "expectedVersion": body.ExpectedVersion, "reason": body.Reason})
	request, e := http.NewRequestWithContext(r.Context(), "POST", env("GATEWAY_VOD_INTERNAL_URL", "https://vod:8099")+"/internal/vod/content-removals", bytes.NewReader(raw))
	if e != nil {
		gatewayError(w, r, 503, "VOD_UNAVAILABLE")
		return
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Service-Name", "gateway")
	request.Header.Set("X-Service-Token", os.Getenv("GATEWAY_VOD_TOKEN"))
	request.Header.Set("X-Session-Credential", credential)
	if key := r.Header.Get("Idempotency-Key"); key != "" {
		request.Header.Set("Idempotency-Key", key)
	}
	g.forwarding(r, request)
	// Commands are forwarded once: a lost response is recovered by the caller with the same key (CA-07).
	response, e := g.client.Do(request)
	if e != nil {
		gatewayError(w, r, 503, "VOD_UNAVAILABLE")
		return
	}
	defer response.Body.Close()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store, private")
	w.WriteHeader(response.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(response.Body, 16384))
}
