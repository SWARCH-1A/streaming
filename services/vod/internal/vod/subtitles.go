package vod

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

type Track struct {
	TrackID         string `json:"trackId"`
	VodID           string `json:"vodId"`
	Language        string `json:"language"`
	Label           string `json:"label"`
	Kind            string `json:"kind"`
	Format          string `json:"format"`
	State           string `json:"state"`
	TrackVersion    int64  `json:"trackVersion"`
	MappingVersion  int64  `json:"mappingVersion"`
	MediaDurationMS int64  `json:"mediaDurationMs"`
	ContentPath     string `json:"contentPath"`
}
type trackAction struct {
	Track  Track
	Asset  Asset
	Remove bool
}

var language = regexp.MustCompile(`^[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$`)
var cueTime = regexp.MustCompile(`^(?:([0-9]{2,}):)?([0-5][0-9]):([0-5][0-9])\.([0-9]{3})$`)
var entity = regexp.MustCompile(`&(amp|lt|gt|nbsp|lrm|rlm);`)

func timestamp(s string) (int64, bool) {
	m := cueTime.FindStringSubmatch(s)
	if m == nil {
		return 0, false
	}
	h, _ := strconv.ParseInt(m[1], 10, 64)
	min, _ := strconv.ParseInt(m[2], 10, 64)
	sec, _ := strconv.ParseInt(m[3], 10, 64)
	ms, _ := strconv.ParseInt(m[4], 10, 64)
	if len(m[1]) > 10 {
		return 0, false
	}
	return ((h*60+min)*60+sec)*1000 + ms, true
}
func ValidateWebVTT(raw []byte, duration int64) error {
	bad := func() error { return Fail(422, "SUBTITLE_FORMAT_INVALID") }
	if len(raw) == 0 || len(raw) > 1<<20 || !utf8.Valid(raw) {
		return bad()
	}
	text := strings.TrimPrefix(string(raw), "\ufeff")
	text = strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
	if strings.IndexFunc(text, func(r rune) bool { return (r < 32 && r != '\n' && r != '\t') || r == 127 }) >= 0 {
		return bad()
	}
	lines := strings.Split(text, "\n")
	if len(lines) < 2 || (lines[0] != "WEBVTT" && !strings.HasPrefix(lines[0], "WEBVTT ") && !strings.HasPrefix(lines[0], "WEBVTT\t")) || strings.Contains(lines[0], "-->") || lines[1] != "" {
		return bad()
	}
	count := 0
	var previous int64 = -1
	for i := 2; i < len(lines); {
		if lines[i] == "" {
			i++
			continue
		}
		start := i
		for i < len(lines) && lines[i] != "" {
			i++
		}
		block := lines[start:i]
		if block[0] == "NOTE" || strings.HasPrefix(block[0], "NOTE ") || strings.HasPrefix(block[0], "NOTE\t") {
			if strings.Contains(strings.Join(block, "\n"), "-->") {
				return bad()
			}
			continue
		}
		if block[0] == "STYLE" || block[0] == "REGION" {
			return bad()
		}
		timing := 0
		if !strings.Contains(block[0], "-->") {
			timing = 1
		}
		if timing >= len(block)-1 {
			return bad()
		}
		p := regexp.MustCompile(`[ \t]+-->[ \t]+`).Split(block[timing], -1)
		if len(p) != 2 {
			return bad()
		}
		from, ok := timestamp(p[0])
		if !ok {
			return bad()
		}
		to, ok := timestamp(p[1])
		if !ok || from >= to || to > duration || from < previous {
			return bad()
		}
		previous = from
		body := strings.Join(block[timing+1:], "\n")
		if strings.TrimSpace(body) == "" || (strings.Contains(body, "<") || strings.Contains(body, "-->")) || strings.Contains(entity.ReplaceAllString(body, ""), "&") {
			return bad()
		}
		count++
		if count > 10000 {
			return bad()
		}
	}
	if count == 0 {
		return bad()
	}
	return nil
}
func (s *Service) uploadTrack(w http.ResponseWriter, r *http.Request) error {
	rec, a, key, e := s.writeAccess(r)
	if e != nil {
		return e
	}
	if rec.Processing != "READY" {
		return Fail(409, "VOD_NOT_READY")
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1114112)
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil {
		return Fail(400, "MULTIPART_INVALID")
	}
	if mediaType != "multipart/form-data" {
		return Fail(415, "CONTENT_TYPE_INVALID")
	}
	reader, e := r.MultipartReader()
	if e != nil {
		return Fail(400, "MULTIPART_REQUIRED")
	}
	var metadata struct {
		Language               string `json:"language"`
		Label                  string `json:"label"`
		ExpectedMappingVersion int64  `json:"expectedMappingVersion"`
	}
	var raw []byte
	seen := map[string]bool{}
	for {
		part, e := reader.NextPart()
		if e == io.EOF {
			break
		}
		if e != nil {
			return Fail(400, "MULTIPART_INVALID")
		}
		name := part.FormName()
		if (name != "metadata" && name != "file") || seen[name] {
			part.Close()
			return Fail(400, "MULTIPART_INVALID")
		}
		partType, params, err := mime.ParseMediaType(part.Header.Get("Content-Type"))
		expectedType := "text/vtt"
		if name == "metadata" {
			expectedType = "application/json"
		}
		if err != nil || partType != expectedType || (params["charset"] != "" && !strings.EqualFold(params["charset"], "utf-8")) {
			part.Close()
			return Fail(415, "CONTENT_TYPE_INVALID")
		}
		seen[name] = true
		limit := int64(1 << 20)
		if name == "metadata" {
			limit = 16384
		}
		body, e := io.ReadAll(io.LimitReader(part, limit+1))
		part.Close()
		if e != nil || int64(len(body)) > limit {
			return Fail(413, "SUBTITLE_TOO_LARGE")
		}
		if name == "file" {
			raw = body
		} else {
			dec := json.NewDecoder(bytes.NewReader(body))
			dec.DisallowUnknownFields()
			var tail any
			if dec.Decode(&metadata) != nil || dec.Decode(&tail) != io.EOF {
				return Fail(422, "SUBTITLE_METADATA_INVALID")
			}
		}
	}
	if len(seen) != 2 || !language.MatchString(metadata.Language) || metadata.Label == "" || !cleanText(metadata.Label, 80) || strings.IndexFunc(metadata.Label, unicode.IsControl) >= 0 {
		return Fail(422, "SUBTITLE_METADATA_INVALID")
	}
	if metadata.ExpectedMappingVersion != rec.MappingVersion {
		return Fail(409, "MAPPING_VERSION_CONFLICT")
	}
	if e = ValidateWebVTT(raw, rec.DurationMS); e != nil {
		return e
	}
	meta, _ := json.Marshal(metadata)
	fp := fingerprint(append(meta, raw...))
	commandFingerprint := rec.VodID + ":SUBTITLE:" + fp
	if previous, found, err := s.Store.Previous(r.Context(), a.UserID, key, commandFingerprint); err != nil {
		return err
	} else if found {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(201)
		_, err = w.Write(previous)
		return err
	}
	trackID := "sub_" + fingerprint([]byte(a.UserID + ":" + key))[:32]
	ref, e := s.upload(r.Context(), rec, "subtitle:"+trackID, refBytes(raw, "text/vtt"), bytes.NewReader(raw))
	if e != nil {
		return e
	}
	track := Track{trackID, rec.VodID, metadata.Language, metadata.Label, "subtitles", "WEBVTT", "READY", 1, rec.MappingVersion, rec.DurationMS, "/vod-media/" + rec.VodID + "/subtitles/" + trackID + ".vtt"}
	cred, err := credential(r)
	if err != nil {
		return err
	}
	if _, err = s.access(r.Context(), rec, cred, true); err != nil {
		return err
	}
	out, e := s.Store.Command(r.Context(), rec.VodID, a.UserID, key, commandFingerprint, "SUBTITLE_UPLOAD", func(current *Recording) (any, error) {
		if current.Deleted() || current.Restriction != "NONE" {
			return nil, Fail(404, "VOD_UNAVAILABLE")
		}
		if current.MappingVersion != track.MappingVersion {
			return nil, Fail(409, "MAPPING_VERSION_CONFLICT")
		}
		return trackAction{Track: track, Asset: Asset{"subtitles/" + trackID + ".vtt", ref, track.MappingVersion}}, nil
	})
	if e != nil {
		return e
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(201)
	_, e = w.Write(out)
	return e
}
func (s *Service) listTracks(w http.ResponseWriter, r *http.Request) error {
	rec, e := s.Store.Get(r.Context(), r.PathValue("vodId"))
	if e != nil {
		return e
	}
	c, e := credential(r)
	if e != nil {
		return e
	}
	a, e := s.access(r.Context(), rec, c, false)
	if e != nil {
		return e
	}
	q := r.URL.Query()
	for k, v := range q {
		if (k != "limit" && k != "cursor") || len(v) != 1 {
			return Fail(400, "QUERY_INVALID")
		}
	}
	limit := 20
	if q.Has("limit") {
		limit, e = strconv.Atoi(q.Get("limit"))
		if e != nil || limit < 1 || limit > 50 {
			return Fail(400, "LIMIT_INVALID")
		}
	}
	binding := fmt.Sprintf("%s:%s:%d:%d", rec.VodID, a.UserID, rec.MappingVersion, limit)
	after := ""
	if token := q.Get("cursor"); token != "" {
		var c cursor
		if s.unseal("subtitle-cursor", token, &c) != nil || c.Binding != binding || c.Exp <= time.Now().Unix() {
			return Fail(410, "CURSOR_INVALID")
		}
		after = c.After
	}
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT data FROM vod_tracks WHERE vod_id=$1 AND NOT removed AND track_id>$2 AND (data->>'mappingVersion')::bigint=$3 ORDER BY track_id LIMIT $4", rec.VodID, after, rec.MappingVersion, limit+1)
	if e != nil {
		return e
	}
	defer rows.Close()
	items := []Track{}
	for rows.Next() {
		var raw []byte
		var t Track
		if e = rows.Scan(&raw); e != nil {
			return e
		}
		if e = json.Unmarshal(raw, &t); e != nil {
			return e
		}
		items = append(items, t)
	}
	if e = rows.Err(); e != nil {
		return e
	}
	var next *string
	if len(items) > limit {
		token := s.seal("subtitle-cursor", cursor{items[limit-1].TrackID, binding, time.Now().Add(5 * time.Minute).Unix()})
		next = &token
		items = items[:limit]
	}
	response(w, 200, map[string]any{"items": items, "nextCursor": next})
	return nil
}
func (s *Service) deleteTrack(w http.ResponseWriter, r *http.Request) error {
	rec, a, key, e := s.writeAccess(r)
	if e != nil {
		return e
	}
	if r.Header.Get("If-Match") == "" {
		return Fail(428, "IF_MATCH_REQUIRED")
	}
	expected, e := strconv.ParseInt(strings.Trim(r.Header.Get("If-Match"), "\""), 10, 64)
	if e != nil || expected < 1 {
		return Fail(400, "IF_MATCH_REQUIRED")
	}
	id := r.PathValue("trackId")
	if !ValidID(id) {
		return Fail(404, "TRACK_NOT_FOUND")
	}
	_, e = s.Store.Command(r.Context(), rec.VodID, a.UserID, key, fmt.Sprintf("%s:%s:%d", rec.VodID, id, expected), "SUBTITLE_DELETE", func(current *Recording) (any, error) {
		return trackAction{Track: Track{VodID: rec.VodID, TrackID: id, TrackVersion: expected}, Remove: true}, nil
	})
	if e != nil {
		return e
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(204)
	return nil
}
func (s *Store) TrackReadable(ctx context.Context, id, path string, version int64) (bool, error) {
	trackID := strings.TrimSuffix(strings.TrimPrefix(path, "subtitles/"), ".vtt")
	var ok bool
	e := s.DB.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM vod_tracks WHERE vod_id=$1 AND track_id=$2 AND NOT removed AND (data->>'mappingVersion')::bigint=$3)", id, trackID, version).Scan(&ok)
	return ok, e
}
func trackCommand(ctx context.Context, tx *sql.Tx, t trackAction) (any, error) {
	if t.Remove {
		var raw []byte
		var old Track
		e := tx.QueryRowContext(ctx, "SELECT data FROM vod_tracks WHERE vod_id=$1 AND track_id=$2 AND NOT removed FOR UPDATE", t.Track.VodID, t.Track.TrackID).Scan(&raw)
		if e != nil {
			return nil, Fail(404, "TRACK_NOT_FOUND")
		}
		if json.Unmarshal(raw, &old) != nil {
			return nil, Fail(503, "TRACK_INVALID")
		}
		if old.TrackVersion != t.Track.TrackVersion {
			return nil, Fail(412, "TRACK_VERSION_CONFLICT")
		}
		old.TrackVersion++
		old.State = "REMOVED"
		raw, _ = json.Marshal(old)
		if _, e = tx.ExecContext(ctx, "UPDATE vod_tracks SET removed=true,data=$2 WHERE track_id=$1", old.TrackID, raw); e != nil {
			return nil, e
		}
		if _, e = job(ctx, tx, old.VodID, "TRACK:"+old.TrackID); e != nil {
			return nil, e
		}
		return map[string]any{}, nil
	}
	raw, _ := json.Marshal(t.Track)
	var confirmed bool
	if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM vod_objects WHERE vod_id=$1 AND object_id=$2 AND confirmed AND ref=$3::jsonb)", t.Track.VodID, t.Asset.Object.ObjectID, mustJSON(t.Asset.Object)).Scan(&confirmed); err != nil {
		return nil, err
	}
	if !confirmed {
		return nil, Fail(503, "OBJECT_NOT_CONFIRMED")
	}
	if _, e := tx.ExecContext(ctx, "INSERT INTO vod_tracks(vod_id,track_id,data) VALUES($1,$2,$3)", t.Track.VodID, t.Track.TrackID, raw); e != nil {
		return nil, e
	}
	ref, _ := json.Marshal(t.Asset.Object)
	_, e := tx.ExecContext(ctx, "INSERT INTO vod_assets(vod_id,path,ref,mapping_version) VALUES($1,$2,$3,$4)", t.Track.VodID, t.Asset.Path, ref, t.Track.MappingVersion)
	return t.Track, e
}

func mustJSON(value any) []byte { raw, _ := json.Marshal(value); return raw }
