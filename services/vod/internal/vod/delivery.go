package vod

import (
	"context"
	"crypto/hmac"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type Delivery interface {
	Get(context.Context, ObjectRef, string) (io.ReadCloser, int64, error)
}
type MediaDelivery struct {
	URL, Token string
	Client     *http.Client
}

func NewMediaDelivery(endpoint, token string) *MediaDelivery {
	return &MediaDelivery{strings.TrimRight(endpoint, "/"), token, &http.Client{Timeout: 90 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}
func (m *MediaDelivery) Get(ctx context.Context, ref ObjectRef, rangeHeader string) (io.ReadCloser, int64, error) {
	request, e := http.NewRequestWithContext(ctx, "GET", m.URL+"/internal/media/vod/objects/"+ref.ObjectID, nil)
	if e != nil {
		return nil, 0, e
	}
	request.Header.Set("X-Service-Name", "vod")
	request.Header.Set("X-Service-Token", m.Token)
	request.Header.Set("X-Object-SHA256", ref.SHA256)
	request.Header.Set("X-Object-Size", strconv.FormatInt(ref.ByteLength, 10))
	request.Header.Set("X-Object-Type", ref.ContentType)
	if rangeHeader != "" {
		request.Header.Set("Range", rangeHeader)
	}
	res, e := m.Client.Do(request)
	if e != nil {
		return nil, 0, e
	}
	expected := 200
	if rangeHeader != "" {
		expected = 206
	}
	start, end := int64(0), ref.ByteLength-1
	if rangeHeader != "" {
		start, end, e = byteRange(rangeHeader, ref.ByteLength)
	}
	if e != nil || res.StatusCode != expected || res.ContentLength != end-start+1 || res.Header.Get("Content-Type") != ref.ContentType {
		res.Body.Close()
		return nil, 0, Fail(503, "MEDIA_DELIVERY_UNAVAILABLE")
	}
	return res.Body, res.ContentLength, nil
}

// Only VOD can request an opaque inventory object. Media has an independent read-only S3 identity.
func MediaDeliveryHandler(objects Objects, token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if objects.Health(r.Context()) != nil {
			w.WriteHeader(503)
			return
		}
		w.WriteHeader(200)
	})
	mux.HandleFunc("GET /internal/media/vod/objects/{objectId}", func(w http.ResponseWriter, r *http.Request) {
		if len(r.Header.Values("X-Service-Name")) != 1 || len(r.Header.Values("X-Service-Token")) != 1 || token == "" || r.Header.Get("X-Service-Name") != "vod" || !hmac.Equal([]byte(token), []byte(r.Header.Get("X-Service-Token"))) {
			respondError(w, r, Fail(401, "SERVICE_UNAUTHORIZED"))
			return
		}
		size, e := strconv.ParseInt(r.Header.Get("X-Object-Size"), 10, 64)
		ref := ObjectRef{ObjectID: r.PathValue("objectId"), ByteLength: size, SHA256: r.Header.Get("X-Object-SHA256"), ContentType: r.Header.Get("X-Object-Type")}
		if e != nil || !ref.Valid() || !strings.HasPrefix(ref.ObjectID, "obj_") {
			respondError(w, r, Fail(400, "OBJECT_INVALID"))
			return
		}
		status := 200
		header := r.Header.Get("Range")
		if header != "" {
			start, end, e := byteRange(header, size)
			if e != nil {
				respondError(w, r, e)
				return
			}
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
			status = 206
		}
		body, length, e := objects.Get(r.Context(), ref, header)
		if e != nil {
			respondError(w, r, e)
			return
		}
		defer body.Close()
		w.Header().Set("Content-Type", ref.ContentType)
		w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(status)
		_, _ = io.Copy(w, body)
	})
	return mux
}
