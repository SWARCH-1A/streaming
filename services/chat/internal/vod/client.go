package vod

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"time"

	"streaming/chat/internal/chat"
)

// CodeReplayUnavailable indica que VOD no pudo autorizar ni entregar el timeline; el video sigue.
const CodeReplayUnavailable = "REPLAY_UNAVAILABLE"

// Config de la conexión privada Chat→VOD (listener interno de VOD).
type Config struct {
	BaseURL         string
	ServiceToken    string
	CAFile          string // opcional: CA privada para TLS interno
	DevelopmentHTTP bool   // exclusivamente fixtures/red local aislada
	ConnectTimeout  time.Duration
	RequestTimeout  time.Duration
}

type Client struct {
	base    *url.URL
	token   string
	timeout time.Duration
	http    *http.Client
}

func New(cfg Config) (*Client, error) {
	base, err := url.Parse(cfg.BaseURL)
	if err != nil || base.Hostname() == "" || base.User != nil || base.RawQuery != "" || base.Fragment != "" || (base.Path != "" && base.Path != "/") ||
		(base.Scheme != "https" && !(cfg.DevelopmentHTTP && base.Scheme == "http")) {
		return nil, fmt.Errorf("URL de VOD inválida")
	}
	if cfg.ServiceToken == "" {
		return nil, fmt.Errorf("token de servicio VOD requerido")
	}
	transport := &http.Transport{
		DialContext:         (&net.Dialer{Timeout: cfg.ConnectTimeout}).DialContext,
		TLSHandshakeTimeout: cfg.ConnectTimeout,
		MaxIdleConnsPerHost: 16,
		IdleConnTimeout:     90 * time.Second,
	}
	if cfg.CAFile != "" {
		pem, err := os.ReadFile(cfg.CAFile)
		if err != nil {
			return nil, fmt.Errorf("leer CA de VOD: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("CA de VOD sin certificados")
		}
		transport.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
	}
	return &Client{base: base, token: cfg.ServiceToken, timeout: cfg.RequestTimeout, http: &http.Client{Transport: transport,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

// ReplayContext pide a VOD que autorice la lectura del VOD con la credencial del espectador (vacía
// para anónimos) y devuelve su timeline vigente. No hay caché: retiro, borrado o suspensión rigen
// en la siguiente página.
func (c *Client) ReplayContext(ctx context.Context, credential, vodID, requestID string) (Timeline, *chat.Error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		c.base.JoinPath("/internal/vod/vods", url.PathEscape(vodID), "replay-context").String(), nil)
	if err != nil {
		return Timeline{}, chat.Fail(CodeReplayUnavailable)
	}
	req.Header.Set("X-Service-Name", "chat")
	req.Header.Set("X-Service-Token", c.token)
	req.Header.Set("Accept", "application/json")
	if credential != "" {
		req.Header.Set("X-Session-Credential", credential)
	}
	if requestID != "" {
		req.Header.Set("X-Request-Id", requestID)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return Timeline{}, chat.Fail(CodeReplayUnavailable)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, (64<<10)+1))
	if err != nil || len(raw) > 64<<10 {
		return Timeline{}, chat.Fail(CodeReplayUnavailable)
	}
	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusNotFound, http.StatusForbidden, http.StatusGone:
		// VOD responde 404 a un VOD ajeno, retirado, eliminado o de cuenta suspendida.
		return Timeline{}, chat.Fail(chat.CodeVodNotFound)
	case http.StatusUnauthorized:
		var env struct {
			Code string `json:"code"`
		}
		if json.Unmarshal(raw, &env) == nil && env.Code == "SERVICE_UNAUTHORIZED" {
			return Timeline{}, chat.Fail(CodeReplayUnavailable)
		}
		return Timeline{}, chat.Fail(chat.CodeAuthRequired)
	default:
		return Timeline{}, chat.Fail(CodeReplayUnavailable)
	}
	mediaType, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		return Timeline{}, chat.Fail(CodeReplayUnavailable)
	}
	var t Timeline
	if err := json.Unmarshal(raw, &t); err != nil || !t.valid(vodID) {
		return Timeline{}, chat.Fail(CodeReplayUnavailable)
	}
	return t, nil
}
