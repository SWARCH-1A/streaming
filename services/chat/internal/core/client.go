// Package core consume los dos contratos privados que Chat necesita de Core: el contexto autorizado
// por mensaje y el snapshot de sesión. Core compone usuario/autor locales con el estado/timeline de
// Streaming; Chat no conoce a Streaming por red. No hay caché ni reintento automático.
package core

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"time"

	"streaming/chat/internal/chat"
)

// MessageContext es la respuesta de POST /internal/core/chat/message-context.
type MessageContext struct {
	UserID                string    `json:"userId"`
	Handle                string    `json:"handle"`
	DisplayName           string    `json:"displayName"`
	AvatarURI             *string   `json:"avatarUri"`
	ProfileVersion        int64     `json:"profileVersion"`
	SessionID             string    `json:"sessionId"`
	StreamGeneration      int64     `json:"streamGeneration"`
	SessionVersion        int64     `json:"sessionVersion"`
	Availability          string    `json:"availability"`
	AuthorizedAtUTC       time.Time `json:"authorizedAtUtc"`
	TimelinePositionMs    *int64    `json:"timelinePositionMs"`
	TimelineSampleVersion *int64    `json:"timelineSampleVersion"`
	WriteAllowed          bool      `json:"writeAllowed"`
	DenialCode            *string   `json:"denialCode"`
}

// SessionSnapshot es la respuesta de GET /internal/core/chat/sessions/{sessionId}; sin identidad privada.
type SessionSnapshot struct {
	SessionID          string `json:"sessionId"`
	StreamID           string `json:"streamId"`
	StreamGeneration   int64  `json:"streamGeneration"`
	SessionVersion     int64  `json:"sessionVersion"`
	Status             string `json:"status"`
	Availability       string `json:"availability"`
	TimelinePositionMs *int64 `json:"timelinePositionMs"`
}

// Config de la conexión privada Chat→Core.
type Config struct {
	BaseURL        string
	ServiceToken   string
	CAFile         string // opcional: CA privada para TLS interno
	ConnectTimeout time.Duration
	RequestTimeout time.Duration
}

type Client struct {
	base    *url.URL
	token   string
	timeout time.Duration
	http    *http.Client
}

func New(cfg Config) (*Client, error) {
	base, err := url.Parse(cfg.BaseURL)
	if err != nil || base.Scheme == "" || base.Host == "" {
		return nil, fmt.Errorf("URL de Core inválida")
	}
	transport := &http.Transport{
		DialContext:         (&net.Dialer{Timeout: cfg.ConnectTimeout}).DialContext,
		TLSHandshakeTimeout: cfg.ConnectTimeout,
		MaxIdleConnsPerHost: 32,
		IdleConnTimeout:     90 * time.Second,
	}
	if cfg.CAFile != "" {
		pem, err := os.ReadFile(cfg.CAFile)
		if err != nil {
			return nil, fmt.Errorf("leer CA de Core: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("CA de Core sin certificados")
		}
		transport.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
	}
	return &Client{base: base, token: cfg.ServiceToken, timeout: cfg.RequestTimeout, http: &http.Client{Transport: transport}}, nil
}

// MessageContext pide una autorización nueva para un mensaje lógico. La credencial de sesión solo
// viaja en X-Session-Credential; nunca se registra ni persiste.
func (c *Client) MessageContext(ctx context.Context, credential, sessionID, clientMessageID string) (MessageContext, *chat.Error) {
	body, _ := json.Marshal(map[string]string{"sessionId": sessionID, "clientMessageId": clientMessageID})
	var out MessageContext
	err := c.do(ctx, http.MethodPost, "/internal/core/chat/message-context", body, credential, &out)
	if err != nil {
		return MessageContext{}, err
	}
	if out.UserID == "" || out.SessionID != sessionID {
		return MessageContext{}, chat.Fail(chat.CodeCoreUnavailable)
	}
	return out, nil
}

// Snapshot obtiene el estado autoritativo de la sesión para abrir o reconciliar una sala.
func (c *Client) Snapshot(ctx context.Context, sessionID string) (SessionSnapshot, *chat.Error) {
	var out SessionSnapshot
	if err := c.do(ctx, http.MethodGet, "/internal/core/chat/sessions/"+url.PathEscape(sessionID), nil, "", &out); err != nil {
		return SessionSnapshot{}, err
	}
	if out.SessionID != sessionID {
		return SessionSnapshot{}, chat.Fail(chat.CodeCoreUnavailable)
	}
	return out, nil
}

func (c *Client) do(ctx context.Context, method, path string, body []byte, credential string, out any) *chat.Error {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, c.base.JoinPath(path).String(), bytes.NewReader(body))
	if err != nil {
		return chat.Fail(chat.CodeCoreUnavailable)
	}
	req.Header.Set("X-Service-Name", "chat")
	req.Header.Set("X-Service-Token", c.token)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if credential != "" {
		req.Header.Set("X-Session-Credential", credential)
	}
	if rid, ok := ctx.Value(requestIDKey{}).(string); ok {
		req.Header.Set("X-Request-Id", rid)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return chat.Fail(chat.CodeCoreUnavailable)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return chat.Fail(chat.CodeCoreUnavailable)
	}
	if resp.StatusCode == http.StatusOK {
		if err := json.Unmarshal(raw, out); err != nil {
			return chat.Fail(chat.CodeCoreUnavailable)
		}
		return nil
	}
	return mapError(resp.StatusCode, raw)
}

// mapError conserva los códigos que Core/Streaming definen y nunca convierte un fallo en anonimato.
func mapError(status int, raw []byte) *chat.Error {
	var env struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(raw, &env)
	switch env.Code {
	case chat.CodeStreamingUnavailable, chat.CodeTimelineUnavailable, chat.CodeAuthRequired,
		chat.CodeChatNotOpen, chat.CodeChatReadOnly:
		return chat.Fail(env.Code)
	}
	switch status {
	case http.StatusUnauthorized:
		return chat.Fail(chat.CodeAuthRequired)
	case http.StatusNotFound:
		return chat.Fail(chat.CodeSessionNotFound)
	}
	return chat.Fail(chat.CodeCoreUnavailable)
}

type requestIDKey struct{}

// WithRequestID propaga la correlación hacia Core.
func WithRequestID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, requestIDKey{}, id)
}
