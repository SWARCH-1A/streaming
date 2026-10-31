package vod

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type grant struct {
	VodID          string `json:"vodId"`
	Version        int64  `json:"version"`
	MappingVersion int64  `json:"mappingVersion"`
	AccountVersion int64  `json:"accountVersion"`
	Exp            int64  `json:"exp"`
}

func (s *Service) seal(kind string, value any) string {
	raw, _ := json.Marshal(value)
	body := base64.RawURLEncoding.EncodeToString(raw)
	h := hmac.New(sha256.New, s.Secret)
	h.Write([]byte(kind + ":" + body))
	return body + "." + base64.RawURLEncoding.EncodeToString(h.Sum(nil))
}
func (s *Service) unseal(kind, token string, out any) error {
	parts := strings.Split(token, ".")
	if len(parts) != 2 || len(token) > 8192 {
		return Fail(401, "PERMISSION_INVALID")
	}
	sig, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil {
		return Fail(401, "PERMISSION_INVALID")
	}
	h := hmac.New(sha256.New, s.Secret)
	h.Write([]byte(kind + ":" + parts[0]))
	if !hmac.Equal(sig, h.Sum(nil)) {
		return Fail(401, "PERMISSION_INVALID")
	}
	raw, e := base64.RawURLEncoding.DecodeString(parts[0])
	if e != nil || json.Unmarshal(raw, out) != nil {
		return Fail(401, "PERMISSION_INVALID")
	}
	return nil
}
func credential(r *http.Request) (string, error) {
	cookie := ""
	if c, e := r.Cookie("stream_session"); e == nil {
		cookie = c.Value
	}
	authorization := r.Header.Get("Authorization")
	if authorization == "" {
		return cookie, nil
	}
	if !strings.HasPrefix(authorization, "Bearer ") || strings.TrimPrefix(authorization, "Bearer ") == "" {
		return "", Fail(401, "SESSION_REQUIRED")
	}
	bearer := strings.TrimPrefix(authorization, "Bearer ")
	if cookie != "" && cookie != bearer {
		return "", Fail(401, "CREDENTIAL_CONFLICT")
	}
	return bearer, nil
}
func response(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if value != nil {
		_ = json.NewEncoder(w).Encode(value)
	}
}
func respondError(w http.ResponseWriter, r *http.Request, err error) {
	status, code := 503, "DEPENDENCY_UNAVAILABLE"
	var f *Fault
	if errors.As(err, &f) {
		status, code = f.Status, f.Code
	}
	body := map[string]any{"code": code, "message": "No se pudo completar la operación VOD.", "requestId": requestID(r)}
	if status == 422 && strings.HasPrefix(code, "SUBTITLE_") {
		field := "file"
		if code == "SUBTITLE_METADATA_INVALID" {
			field = "metadata"
		}
		body["fieldErrors"] = map[string]string{field: "Comprueba el formato, los límites y los tiempos de la pista."}
	}
	response(w, status, body)
}
func requestID(r *http.Request) string {
	id := r.Header.Get("X-Request-Id")
	if !ValidID(id) {
		id = ID("req_")
	}
	return id
}
func decode(r *http.Request, v any) error {
	raw, e := io.ReadAll(io.LimitReader(r.Body, 16385))
	if e != nil {
		return Fail(400, "VALIDATION_ERROR")
	}
	if len(raw) > 16384 {
		return Fail(413, "BODY_TOO_LARGE")
	}
	d := json.NewDecoder(strings.NewReader(string(raw)))
	d.DisallowUnknownFields()
	if e := d.Decode(v); e != nil {
		return Fail(400, "VALIDATION_ERROR")
	}
	var tail any
	if d.Decode(&tail) != io.EOF {
		return Fail(400, "VALIDATION_ERROR")
	}
	return nil
}
func publicRecording(r Recording) map[string]any {
	raw, _ := json.Marshal(r)
	var m map[string]any
	_ = json.Unmarshal(raw, &m)
	delete(m, "autopublish")
	delete(m, "preferenceVersion")
	return m
}
func (s *Service) PublicHandler() http.Handler {
	mux := http.NewServeMux()
	root := http.NewServeMux()
	root.Handle("/", mux)
	handle := func(pattern string, f func(http.ResponseWriter, *http.Request) error) {
		target := mux
		if strings.HasPrefix(pattern, "GET /api/vods/operations/") {
			target = root
		}
		target.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("X-Content-Type-Options", "nosniff")
			if err := f(w, r); err != nil {
				respondError(w, r, err)
			}
		})
	}
	handle("GET /healthz", func(w http.ResponseWriter, r *http.Request) error {
		response(w, 200, map[string]string{"status": "UP"})
		return nil
	})
	handle("GET /readyz", func(w http.ResponseWriter, r *http.Request) error {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		if e := s.Store.DB.PingContext(ctx); e != nil {
			return e
		}
		if e := s.Objects.Health(ctx); e != nil {
			return e
		}
		response(w, 200, map[string]string{"status": "READY"})
		return nil
	})
	handle("GET /api/vods", s.list(false))
	handle("GET /api/me/vods", s.list(true))
	handle("GET /api/vods/operations/{operationId}", func(w http.ResponseWriter, r *http.Request) error {
		c, e := credential(r)
		if e != nil {
			return e
		}
		a, e := s.Authority.Check(r.Context(), "", c, "SESSION")
		if e != nil {
			return e
		}
		result, e := s.Store.Operation(r.Context(), r.PathValue("operationId"), a.UserID)
		if e != nil {
			return e
		}
		response(w, 200, result)
		return nil
	})
	for _, prefix := range []string{"/api/vods/", "/api/me/vods/"} {
		handle("GET "+prefix+"{vodId}", s.detail)
	}
	handle("GET /api/vods/{vodId}/timeline", func(w http.ResponseWriter, r *http.Request) error {
		rec, e := s.Store.Get(r.Context(), r.PathValue("vodId"))
		if e != nil {
			return e
		}
		c, e := credential(r)
		if e != nil {
			return e
		}
		if _, e = s.access(r.Context(), rec, c, false); e != nil {
			return e
		}
		t, e := s.Store.Timeline(r.Context(), rec.VodID)
		if e != nil {
			return e
		}
		response(w, 200, t)
		return nil
	})
	handle("PATCH /api/vods/{vodId}", s.mutate("PATCH"))
	handle("POST /api/vods/{vodId}/publication", s.mutate("PUBLISH"))
	handle("DELETE /api/vods/{vodId}", s.mutate("DELETE"))
	handle("GET /vod-media/{vodId}/{assetPath...}", s.media)
	handle("POST /api/vods/{vodId}/subtitles", s.uploadTrack)
	handle("GET /api/vods/{vodId}/subtitles", s.listTracks)
	handle("DELETE /api/vods/{vodId}/subtitles/{trackId}", s.deleteTrack)
	return root
}
func (s *Service) detail(w http.ResponseWriter, r *http.Request) error {
	rec, e := s.Store.Get(r.Context(), r.PathValue("vodId"))
	if e != nil {
		return e
	}
	c, e := credential(r)
	if e != nil {
		return e
	}
	if strings.HasPrefix(r.URL.Path, "/api/me/") && c == "" {
		return Fail(401, "SESSION_REQUIRED")
	}
	a, e := s.access(r.Context(), rec, c, false)
	if e != nil {
		return e
	}
	if strings.HasPrefix(r.URL.Path, "/api/me/") && a.UserID != rec.OwnerUserID {
		return Fail(404, "VOD_NOT_FOUND")
	}
	response(w, 200, publicRecording(rec))
	return nil
}

type cursor struct {
	After, Binding string
	Exp            int64
}

func (s *Service) list(mine bool) func(http.ResponseWriter, *http.Request) error {
	return func(w http.ResponseWriter, r *http.Request) error {
		q := r.URL.Query()
		for key, values := range q {
			if !strings.Contains("|channelId|title|categoryId|tagId|limit|cursor|", "|"+key+"|") || len(values) != 1 {
				return Fail(400, "QUERY_INVALID")
			}
		}
		limit := 20
		var e error
		if q.Has("limit") {
			limit, e = strconv.Atoi(q.Get("limit"))
			if e != nil || limit < 1 || limit > 50 {
				return Fail(400, "LIMIT_INVALID")
			}
		}
		query := Query{ChannelID: q.Get("channelId"), Title: q.Get("title"), CategoryID: q.Get("categoryId"), TagID: q.Get("tagId"), Limit: limit}
		for _, id := range []string{query.ChannelID, query.CategoryID, query.TagID} {
			if id != "" && !ValidID(id) {
				return Fail(400, "QUERY_INVALID")
			}
		}
		if !cleanText(query.Title, 140) {
			return Fail(400, "QUERY_INVALID")
		}
		c, e := credential(r)
		if e != nil {
			return e
		}
		actor := "public"
		if mine || c != "" {
			a, e := s.Authority.Check(r.Context(), "", c, "SESSION")
			if e != nil {
				return e
			}
			actor = a.UserID
			if mine {
				query.Owner = actor
			}
		}
		binding := fingerprint([]byte(fmt.Sprintf("%s:%t:%s:%s:%s:%s:%d", actor, mine, query.ChannelID, query.Title, query.CategoryID, query.TagID, limit)))
		if token := q.Get("cursor"); token != "" {
			var p cursor
			if s.unseal("cursor", token, &p) != nil || p.Exp < time.Now().Unix() || p.Binding != binding {
				return Fail(410, "CURSOR_INVALID")
			}
			query.After = p.After
		}
		records, e := s.Store.List(r.Context(), query)
		if e != nil {
			return e
		}
		var next *string
		if len(records) > limit {
			token := s.seal("cursor", cursor{records[limit-1].VodID, binding, time.Now().Add(5 * time.Minute).Unix()})
			next = &token
			records = records[:limit]
		}
		items := []any{}
		for _, rec := range records {
			items = append(items, publicRecording(rec))
		}
		response(w, 200, map[string]any{"items": items, "nextCursor": next})
		return nil
	}
}

var commandKey = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func (s *Service) writeAccess(r *http.Request) (Recording, Access, string, error) {
	rec, e := s.Store.Get(r.Context(), r.PathValue("vodId"))
	if e != nil {
		return rec, Access{}, "", e
	}
	c, e := credential(r)
	if e != nil {
		return rec, Access{}, "", e
	}
	if c == "" {
		return rec, Access{}, "", Fail(401, "SESSION_REQUIRED")
	}
	if r.Header.Get("Authorization") == "" && r.Header.Get("Origin") != s.Origin {
		return rec, Access{}, "", Fail(403, "ORIGIN_FORBIDDEN")
	}
	if r.Header.Get("Authorization") == "" && !validCSRF(r) {
		return rec, Access{}, "", Fail(403, "CSRF_REQUIRED")
	}
	var a Access
	if (rec.Deleted() || rec.Restriction == "RETIRED") && r.Method == "DELETE" && r.PathValue("trackId") == "" {
		a, e = s.Authority.Check(r.Context(), rec.ChannelID, c, "WRITE")
		if e == nil && a.UserID != rec.OwnerUserID {
			e = Fail(403, "VOD_FORBIDDEN")
		}
	} else {
		a, e = s.access(r.Context(), rec, c, true)
	}
	if e != nil {
		return rec, a, "", e
	}
	key := r.Header.Get("Idempotency-Key")
	if !commandKey.MatchString(key) {
		return rec, a, key, Fail(400, "IDEMPOTENCY_KEY_REQUIRED")
	}
	return rec, a, key, nil
}
func validCSRF(r *http.Request) bool {
	c, e := r.Cookie("XSRF-TOKEN")
	return e == nil && len(c.Value) >= 20 && hmac.Equal([]byte(c.Value), []byte(r.Header.Get("X-XSRF-TOKEN")))
}

type patch struct {
	ExpectedVersion int64     `json:"expectedVersion"`
	Title           *string   `json:"title,omitempty"`
	Description     *string   `json:"description,omitempty"`
	CategoryID      *string   `json:"categoryId,omitempty"`
	TagIDs          *[]string `json:"tagIds,omitempty"`
}

func (s *Service) mutate(operation string) func(http.ResponseWriter, *http.Request) error {
	return func(w http.ResponseWriter, r *http.Request) error {
		rec, a, key, e := s.writeAccess(r)
		if e != nil {
			return e
		}
		var p patch
		if operation == "DELETE" {
			v, e := strconv.ParseInt(strings.Trim(r.Header.Get("If-Match"), "\""), 10, 64)
			if e != nil {
				return Fail(400, "IF_MATCH_REQUIRED")
			}
			p.ExpectedVersion = v
		} else {
			if e = decode(r, &p); e != nil {
				return e
			}
		}
		if operation == "PATCH" && (p.CategoryID != nil || p.TagIDs != nil) {
			catalog, ok := s.Authority.(interface {
				Catalog(context.Context, string, string, *string, *[]string) error
			})
			if !ok {
				return Fail(503, "CATALOG_CONTEXT_UNAVAILABLE")
			}
			cred, err := credential(r)
			if err != nil {
				return err
			}
			if err = catalog.Catalog(r.Context(), rec.ChannelID, cred, p.CategoryID, p.TagIDs); err != nil {
				return err
			}
		}
		raw, _ := json.Marshal(p)
		fp := fingerprint(append([]byte(rec.VodID+":"+operation+":"), raw...))
		out, e := s.Store.Command(r.Context(), rec.VodID, a.UserID, key, fp, operation, func(current *Recording) (any, error) {
			if current.Deleted() && operation != "DELETE" {
				return nil, Fail(410, "VOD_DELETED")
			}
			if current.Deleted() {
				return nil, nil
			}
			if p.ExpectedVersion != current.Version {
				return nil, Fail(409, "VOD_VERSION_CONFLICT")
			}
			switch operation {
			case "DELETE":
				current.Restriction = "DELETED"
				current.Publication = "DRAFT"
			case "PUBLISH":
				if p.Title != nil || p.Description != nil || p.CategoryID != nil || p.TagIDs != nil {
					return nil, Fail(400, "VALIDATION_ERROR")
				}
				if current.Processing != "READY" || current.Restriction != "NONE" {
					return nil, Fail(409, "VOD_NOT_READY")
				}
				current.Publication = "PUBLISHED"
			case "PATCH":
				if p.Title == nil && p.Description == nil && p.CategoryID == nil && p.TagIDs == nil {
					return nil, Fail(422, "EMPTY_PATCH")
				}
				if p.Title != nil {
					current.Title = *p.Title
				}
				if p.Description != nil {
					current.Description = *p.Description
				}
				if p.CategoryID != nil {
					current.CategoryID = *p.CategoryID
				}
				if p.TagIDs != nil {
					current.TagIDs = *p.TagIDs
				}
				if e := validateStart(current.Start); e != nil {
					return nil, e
				}
			}
			current.Version++
			return publicRecording(*current), nil
		})
		if e != nil {
			return e
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		status := 200
		if operation == "DELETE" {
			status = 202
		}
		w.WriteHeader(status)
		_, e = w.Write(out)
		return e
	}
}
func (s *Service) media(w http.ResponseWriter, r *http.Request) error {
	rec, e := s.Store.Get(r.Context(), r.PathValue("vodId"))
	if e != nil {
		return e
	}
	path := r.PathValue("assetPath")
	if strings.ContainsAny(path, "\\%?#:") || strings.Contains(path, "..") || strings.HasPrefix(path, "/") {
		return Fail(404, "ASSET_NOT_FOUND")
	}
	asset, e := s.Store.Asset(r.Context(), rec.VodID, path)
	if e != nil {
		return e
	}
	if rec.Processing != "READY" || rec.Restriction != "NONE" || asset.MappingVersion != rec.MappingVersion {
		return Fail(404, "VOD_UNAVAILABLE")
	}
	active, e := s.Store.Active(r.Context(), rec.OwnerUserID)
	if e != nil {
		return e
	}
	if !active {
		return Fail(404, "VOD_UNAVAILABLE")
	}
	if strings.HasSuffix(path, ".m3u8") || strings.HasPrefix(path, "subtitles/") {
		c, e := credential(r)
		if e != nil {
			return e
		}
		a, e := s.access(r.Context(), rec, c, false)
		if e != nil {
			return e
		}
		token := s.seal("playback", grant{rec.VodID, rec.Version, rec.MappingVersion, a.AccountVersion, time.Now().Add(time.Minute).Unix()})
		http.SetCookie(w, &http.Cookie{Name: "vod_access", Value: token, Path: "/vod-media/" + rec.VodID + "/", MaxAge: 60, HttpOnly: true, Secure: !s.Development, SameSite: http.SameSiteLaxMode})
		w.Header().Set("X-VOD-Playback-Grant", token)
	} else {
		token := r.Header.Get("X-VOD-Playback-Grant")
		if token == "" {
			if cookie, e := r.Cookie("vod_access"); e == nil {
				token = cookie.Value
			}
		}
		var g grant
		if s.unseal("playback", token, &g) != nil || g.Exp <= time.Now().Unix() || g.VodID != rec.VodID || g.Version != rec.Version || g.MappingVersion != rec.MappingVersion {
			return Fail(401, "PLAYBACK_PERMISSION_REQUIRED")
		}
		var current int64
		err := s.Store.DB.QueryRowContext(r.Context(), "SELECT COALESCE((SELECT version FROM vod_accounts WHERE user_id=$1),0)", rec.OwnerUserID).Scan(&current)
		if err != nil {
			return err
		}
		if current > g.AccountVersion {
			return Fail(401, "PLAYBACK_PERMISSION_EXPIRED")
		}
	}
	if strings.HasPrefix(path, "subtitles/") {
		ok, e := s.Store.TrackReadable(r.Context(), rec.VodID, path, rec.MappingVersion)
		if e != nil {
			return e
		}
		if !ok {
			return Fail(404, "TRACK_NOT_FOUND")
		}
	}
	rangeHeader := r.Header.Get("Range")
	status := 200
	if rangeHeader != "" {
		start, end, e := byteRange(rangeHeader, asset.Object.ByteLength)
		if e != nil {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", asset.Object.ByteLength))
			return e
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, asset.Object.ByteLength))
		status = 206
	}
	if s.Delivery == nil {
		return Fail(503, "MEDIA_DELIVERY_UNAVAILABLE")
	}
	body, length, e := s.Delivery.Get(r.Context(), asset.Object, rangeHeader)
	if e != nil {
		return e
	}
	defer body.Close()
	w.Header().Set("Content-Type", asset.Object.ContentType)
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if r.Method != "HEAD" {
		_, _ = io.Copy(w, body)
	}
	return nil
}
func byteRange(header string, size int64) (int64, int64, error) {
	if !strings.HasPrefix(header, "bytes=") || strings.Contains(header, ",") {
		return 0, 0, Fail(416, "RANGE_INVALID")
	}
	parts := strings.Split(strings.TrimPrefix(header, "bytes="), "-")
	if len(parts) != 2 {
		return 0, 0, Fail(416, "RANGE_INVALID")
	}
	start, end := int64(0), size-1
	var e error
	if parts[0] == "" {
		n, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil || n <= 0 {
			return 0, 0, Fail(416, "RANGE_INVALID")
		}
		start = max(0, size-n)
	} else {
		start, e = strconv.ParseInt(parts[0], 10, 64)
		if e != nil {
			return 0, 0, Fail(416, "RANGE_INVALID")
		}
		if parts[1] != "" {
			end, e = strconv.ParseInt(parts[1], 10, 64)
			if e != nil {
				return 0, 0, Fail(416, "RANGE_INVALID")
			}
			end = min(end, size-1)
		}
	}
	if start < 0 || start >= size || end < start {
		return 0, 0, Fail(416, "RANGE_INVALID")
	}
	return start, end, nil
}
func (s *Service) InternalHandler(token, chatToken, gatewayToken string) http.Handler {
	mux := http.NewServeMux()
	authorized := func(secret, name string, f func(http.ResponseWriter, *http.Request) error) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if len(r.Header.Values("X-Service-Name")) != 1 || len(r.Header.Values("X-Service-Token")) != 1 || r.Header.Get("X-Service-Name") != name || secret == "" || !hmac.Equal([]byte(secret), []byte(r.Header.Get("X-Service-Token"))) {
				respondError(w, r, Fail(401, "SERVICE_UNAUTHORIZED"))
				return
			}
			if e := f(w, r); e != nil {
				respondError(w, r, e)
			}
		}
	}
	mux.HandleFunc("PUT /internal/vod/recordings/{sessionId}", authorized(token, "media-capture", func(w http.ResponseWriter, r *http.Request) error {
		if !commandKey.MatchString(r.Header.Get("Idempotency-Key")) {
			return Fail(400, "IDEMPOTENCY_KEY_REQUIRED")
		}
		var p Start
		if e := decode(r, &p); e != nil {
			return e
		}
		if p.SessionID != r.PathValue("sessionId") {
			return Fail(422, "SESSION_CONFLICT")
		}
		rec, e := s.Start(r.Context(), p)
		if e != nil {
			return e
		}
		response(w, 200, publicRecording(rec))
		return nil
	}))

	mux.HandleFunc("POST /internal/vod/recordings/{sessionId}/failure", authorized(token, "media-capture", func(w http.ResponseWriter, r *http.Request) error {
		var p struct {
			Code string `json:"code"`
		}
		if decode(r, &p) != nil || !ValidID(p.Code) {
			return Fail(400, "VALIDATION_ERROR")
		}
		key := r.Header.Get("Idempotency-Key")
		if !commandKey.MatchString(key) {
			return Fail(400, "IDEMPOTENCY_KEY_REQUIRED")
		}
		rec, e := s.Store.Session(r.Context(), r.PathValue("sessionId"))
		if e != nil {
			return e
		}
		_, e = s.Store.Command(r.Context(), rec.VodID, "service:media-capture", key, rec.VodID+":"+p.Code, "CAPTURE_FAILURE", func(current *Recording) (any, error) {
			if current.Deleted() {
				return nil, Fail(410, "VOD_DELETED")
			}
			if current.Processing == "READY" {
				return nil, Fail(409, "RECORDING_CLOSED")
			}
			current.Processing = "FAILED"
			current.Failure = p.Code
			current.Version++
			return map[string]any{}, nil
		})
		if e != nil {
			return e
		}
		w.WriteHeader(204)
		return nil
	}))
	mux.HandleFunc("PUT /internal/vod/recordings/{sessionId}/objects/{key}", authorized(token, "media-capture", func(w http.ResponseWriter, r *http.Request) error {
		if !commandKey.MatchString(r.Header.Get("Idempotency-Key")) {
			return Fail(400, "IDEMPOTENCY_KEY_REQUIRED")
		}
		key := r.PathValue("key")
		if !regexp.MustCompile(`^(segment:[A-Za-z0-9_-]+:[1-9][0-9]*:[0-9]+|init:[A-Za-z0-9_-]+:[1-9][0-9]*|index)$`).MatchString(key) {
			return Fail(400, "OBJECT_KEY_INVALID")
		}
		var metadata *Segment
		if strings.HasPrefix(key, "segment:") {
			raw, err := base64.RawURLEncoding.DecodeString(r.Header.Get("X-Segment-Metadata"))
			if err != nil || len(raw) > 4096 {
				return Fail(400, "SEGMENT_METADATA_REQUIRED")
			}
			var fields map[string]json.RawMessage
			if json.Unmarshal(raw, &fields) != nil {
				return Fail(422, "SEGMENT_METADATA_INVALID")
			}
			for _, name := range []string{"sessionId", "renditionId", "sourceGeneration", "sequence", "sessionStartMs", "durationMs", "discontinuity"} {
				if _, ok := fields[name]; !ok {
					return Fail(422, "SEGMENT_METADATA_INVALID")
				}
			}
			if _, ok := fields["object"]; ok {
				return Fail(422, "SEGMENT_METADATA_INVALID")
			}
			var segment Segment
			decoder := json.NewDecoder(strings.NewReader(string(raw)))
			decoder.DisallowUnknownFields()
			var tail any
			if decoder.Decode(&segment) != nil || decoder.Decode(&tail) != io.EOF || segment.SessionID != r.PathValue("sessionId") || strings.TrimPrefix(segment.Key(), segment.SessionID+":") != strings.TrimPrefix(key, "segment:") || segment.SessionStartMS < -120000 || (segment.SessionStartMS < 0 && segment.Discontinuity) || segment.DurationMS < 1 || segment.DurationMS > 60000 || segment.Object.ObjectID != "" {
				return Fail(422, "SEGMENT_METADATA_INVALID")
			}
			metadata = &segment
		}
		limit := MaxSegmentBytes
		if key == "index" {
			limit = MaxIndexBytes
		}
		if r.ContentLength <= 0 || r.ContentLength > limit {
			return Fail(413, "OBJECT_SIZE_INVALID")
		}
		ref := ObjectRef{ByteLength: r.ContentLength, SHA256: r.Header.Get("X-Object-SHA256"), ContentType: r.Header.Get("Content-Type")}
		if key == "index" && ref.ContentType != "application/json" {
			return Fail(422, "CONTENT_TYPE_INVALID")
		}
		if key != "index" && ref.ContentType != "video/mp2t" && ref.ContentType != "video/mp4" && ref.ContentType != "video/iso.segment" {
			return Fail(422, "CONTENT_TYPE_INVALID")
		}
		temporary, e := os.CreateTemp("", "vod-upload-")
		if e != nil {
			return e
		}
		defer os.Remove(temporary.Name())
		defer temporary.Close()
		h := sha256.New()
		n, e := io.Copy(io.MultiWriter(temporary, h), io.LimitReader(r.Body, limit+1))
		if e != nil || n != r.ContentLength || fmt.Sprintf("%x", h.Sum(nil)) != ref.SHA256 {
			return Fail(422, "OBJECT_CHECKSUM_CONFLICT")
		}
		if _, e = temporary.Seek(0, io.SeekStart); e != nil {
			return e
		}
		result, e := s.Upload(r.Context(), r.PathValue("sessionId"), key, ref, temporary)
		if e != nil {
			return e
		}
		if metadata != nil {
			metadata.Object = result
			if e = s.Store.RegisterSegment(r.Context(), *metadata); e != nil {
				return e
			}
		}
		response(w, 200, result)
		return nil
	}))
	mux.HandleFunc("GET /internal/vod/vods/{vodId}/replay-context", authorized(chatToken, "chat", func(w http.ResponseWriter, r *http.Request) error {
		rec, e := s.Store.Get(r.Context(), r.PathValue("vodId"))
		if e != nil {
			return e
		}
		if _, e = s.access(r.Context(), rec, r.Header.Get("X-Session-Credential"), false); e != nil {
			return e
		}
		t, e := s.Store.Timeline(r.Context(), rec.VodID)
		if e != nil {
			return e
		}
		response(w, 200, t)
		return nil
	}))
	mux.HandleFunc("POST /internal/vod/content-removals", authorized(gatewayToken, "gateway", func(w http.ResponseWriter, r *http.Request) error {
		var p struct {
			VodID           string `json:"vodId"`
			ExpectedVersion int64  `json:"expectedVersion"`
			Reason          string `json:"reason"`
		}
		if e := decode(r, &p); e != nil {
			return e
		}
		if !cleanText(p.Reason, 500) || strings.TrimSpace(p.Reason) == "" {
			return Fail(422, "REASON_REQUIRED")
		}
		rec, e := s.Store.Get(r.Context(), p.VodID)
		if e != nil {
			return e
		}
		a, e := s.Authority.Check(r.Context(), rec.ChannelID, r.Header.Get("X-Session-Credential"), "ADMIN")
		if e != nil {
			return e
		}
		if !a.Administrator {
			return Fail(403, "ADMIN_REQUIRED")
		}
		key := r.Header.Get("Idempotency-Key")
		if !commandKey.MatchString(key) {
			return Fail(400, "IDEMPOTENCY_KEY_REQUIRED")
		}
		raw, _ := json.Marshal(p)
		out, e := s.Store.Command(r.Context(), rec.VodID, a.UserID, key, fingerprint(raw), "REMOVE", func(current *Recording) (any, error) {
			if current.Version != p.ExpectedVersion {
				return nil, Fail(409, "VOD_VERSION_CONFLICT")
			}
			if current.Deleted() {
				return nil, Fail(410, "VOD_DELETED")
			}
			current.AuditReason = p.Reason
			current.Restriction = "RETIRED"
			current.Version++
			return publicRecording(*current), nil
		})
		if e != nil {
			return e
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(202)
		_, e = w.Write(out)
		return e
	}))
	mux.HandleFunc("GET /metrics", authorized(gatewayToken, "gateway", func(w http.ResponseWriter, r *http.Request) error {
		stats, e := s.Store.Stats(r.Context())
		if e != nil {
			return e
		}
		for name, n := range stats {
			fmt.Fprintf(w, "vod_%s %d\n", name, n)
		}
		return nil
	}))
	return mux
}
