package vod

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

type Core struct {
	BaseURL, Token string
	Client         *http.Client
}

func NewCore(base, token string) *Core {
	return &Core{strings.TrimRight(base, "/"), token, &http.Client{Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}
func (c *Core) Check(ctx context.Context, channel, credential, operation string) (Access, error) {
	var a Access
	raw, _ := json.Marshal(map[string]string{"channelId": channel, "operation": operation})
	req, e := http.NewRequestWithContext(ctx, "POST", c.BaseURL+"/internal/core/access-context", bytes.NewReader(raw))
	if e != nil {
		return a, Fail(503, "CORE_UNAVAILABLE")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Service-Name", "vod")
	req.Header.Set("X-Service-Token", c.Token)
	if credential != "" {
		req.Header.Set("X-Session-Credential", credential)
	}
	res, e := c.Client.Do(req)
	if e != nil {
		return a, Fail(503, "CORE_UNAVAILABLE")
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		switch res.StatusCode {
		case 401:
			return a, Fail(401, "SESSION_REQUIRED")
		case 403:
			return a, Fail(403, "VOD_FORBIDDEN")
		case 404:
			return a, Fail(404, "CHANNEL_UNAVAILABLE")
		default:
			return a, Fail(503, "CORE_UNAVAILABLE")
		}
	}
	if json.NewDecoder(http.MaxBytesReader(nil, res.Body, 16384)).Decode(&a) != nil {
		return a, Fail(503, "CORE_CONTEXT_INVALID")
	}
	return a, nil
}

// Only explicitly changed catalogue selections are revalidated; retained tombstones remain readable.
func (c *Core) Catalog(ctx context.Context, channel, credential string, category *string, tags *[]string) error {
	payload := map[string]any{"channelId": channel, "operation": "WRITE"}
	if category != nil {
		payload["categoryId"] = *category
	}
	if tags != nil {
		payload["tagIds"] = *tags
	}
	raw, _ := json.Marshal(payload)
	req, e := http.NewRequestWithContext(ctx, "POST", c.BaseURL+"/internal/core/access-context", bytes.NewReader(raw))
	if e != nil {
		return Fail(503, "CORE_UNAVAILABLE")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Service-Name", "vod")
	req.Header.Set("X-Service-Token", c.Token)
	req.Header.Set("X-Session-Credential", credential)
	res, e := c.Client.Do(req)
	if e != nil {
		return Fail(503, "CORE_UNAVAILABLE")
	}
	defer res.Body.Close()
	if res.StatusCode == 200 {
		return nil
	}
	if res.StatusCode == 400 || res.StatusCode == 422 {
		return Fail(422, "CATALOG_SELECTION_INVALID")
	}
	if res.StatusCode == 401 {
		return Fail(401, "SESSION_REQUIRED")
	}
	if res.StatusCode == 403 {
		return Fail(403, "VOD_FORBIDDEN")
	}
	return Fail(503, "CORE_UNAVAILABLE")
}
