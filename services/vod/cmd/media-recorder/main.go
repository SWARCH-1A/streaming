// media-recorder is a Media worker: durable capture/upload outbox, never a second VOD database.
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	amqp "github.com/rabbitmq/amqp091-go"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"streaming/vod/internal/vod"
	"strings"
	"sync"
	"syscall"
	"time"
)

type upstreamError struct{ status int }

var errCaptureBytes = errors.New("capture bytes differ from durable inventory")

func (e *upstreamError) Error() string { return fmt.Sprintf("upstream status %d", e.status) }

type source struct {
	Start              vod.Start  `json:"start"`
	SourceGeneration   int64      `json:"sourceGeneration"`
	Status             string     `json:"status"`
	Availability       string     `json:"availability"`
	StartedAt          *time.Time `json:"startedAtUtc"`
	TimelinePositionMS int64      `json:"timelinePositionMs"`
}
type pending struct {
	Segment                 vod.Segment
	File, InitFile, InitKey string
	Hash                    string
	Size                    int64
	EventID                 string
	Sent                    bool
	Unusable                bool
}
type state struct {
	Start                                   vod.Start
	Renditions                              map[string]vod.Rendition
	Segments                                []pending
	Next                                    map[string]int64
	Correction                              map[int64]int64
	Ended, Closed, Registered, Acknowledged bool
	Finalized                               map[string]bool
	Expected                                map[string]bool
	Failure                                 string
	Index                                   *vod.ObjectRef
	CloseID                                 string
	EndObservedAt                           *time.Time
	LastSourceGeneration                    int64
	Retired                                 bool
}
type recorder struct {
	mu                                                                       sync.Mutex
	states                                                                   map[string]*state
	running                                                                  map[string]bool
	dir, streaming, media, destination, streamingToken, captureToken, broker string
	nativeDir                                                                string
	publisher                                                                *amqp.Channel
	publisherConnection                                                      *amqp.Connection
	returns                                                                  <-chan amqp.Return
	client                                                                   *http.Client
	ctx                                                                      context.Context
	limit                                                                    int64
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
func uuid(key string) string {
	h := sha256.Sum256([]byte(key))
	h[6] = (h[6] & 15) | 64
	h[8] = (h[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", h[:4], h[4:6], h[6:8], h[8:10], h[10:16])
}
func hash(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if len(os.Args) == 2 && os.Args[1] == "health" {
		client := http.Client{Timeout: 3 * time.Second}
		res, e := client.Get("https://localhost:8890/healthz")
		if e != nil {
			os.Exit(1)
		}
		res.Body.Close()
		if res.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	r := &recorder{ctx: ctx, dir: env("RECORDER_SPOOL", "/data/recordings"), streaming: env("RECORDER_STREAMING_URL", "https://live:8091"), media: env("RECORDER_MEDIA_URL", "https://live:8888"), destination: env("RECORDER_VOD_URL", "https://vod:8099"), streamingToken: os.Getenv("STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN"), captureToken: os.Getenv("VOD_CAPTURE_TOKEN"), broker: os.Getenv("RECORDER_BROKER_URL"), states: map[string]*state{}, running: map[string]bool{}, limit: 8 << 30, client: &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	if env("RECORDER_CAPTURE_MODE", "native") == "native" {
		r.nativeDir = env("RECORDER_NATIVE_DIR", "/data/native")
	}
	if n, e := strconv.ParseInt(env("RECORDER_SPOOL_MAX_BYTES", "8589934592"), 10, 64); e == nil && n > 0 {
		r.limit = n
	}
	for _, endpoint := range []string{r.streaming, r.media, r.destination} {
		if !strings.HasPrefix(endpoint, "https://") {
			slog.Error("recorder upstream TLS required")
			os.Exit(1)
		}
	}
	if r.streamingToken == "" || r.captureToken == "" || r.broker == "" {
		slog.Error("recorder configuration incomplete")
		os.Exit(1)
	}
	if e := os.MkdirAll(r.dir, 0700); e != nil {
		os.Exit(1)
	}
	files, _ := filepath.Glob(filepath.Join(r.dir, "*.json"))
	for _, file := range files {
		var s state
		raw, e := os.ReadFile(file)
		if e != nil || json.Unmarshal(raw, &s) != nil || !vod.ValidID(s.Start.SessionID) {
			slog.Error("recorder checkpoint unreadable")
			os.Exit(1)
		}
		r.states[s.Start.SessionID] = &s
	}
	go r.serveMedia(ctx)
	done := make(chan struct{})
	go func() { defer close(done); r.uploadLoop() }()
	defer func() { <-done; r.closePublisher() }()
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for ctx.Err() == nil {
		r.poll()
		select {
		case <-ctx.Done():
		case <-ticker.C:
		}
	}
}
func atomicFile(path string, raw []byte) error {
	tmp, e := os.CreateTemp(filepath.Dir(path), ".capture-")
	if e != nil {
		return e
	}
	defer os.Remove(tmp.Name())
	if e = tmp.Chmod(0600); e == nil {
		_, e = tmp.Write(raw)
	}
	if e == nil {
		e = tmp.Sync()
	}
	closeErr := tmp.Close()
	if e == nil {
		e = closeErr
	}
	if e == nil {
		e = os.Rename(tmp.Name(), path)
	}
	if e == nil {
		dir, err := os.Open(filepath.Dir(path))
		if err != nil {
			return err
		}
		e = dir.Sync()
		dir.Close()
	}
	return e
}
func (r *recorder) save(s *state) error {
	raw, e := json.Marshal(s)
	if e != nil {
		return e
	}
	return atomicFile(filepath.Join(r.dir, s.Start.SessionID+".json"), raw)
}
func (r *recorder) request(method, endpoint, token string, body io.Reader, length int64, headers map[string]string) (*http.Response, error) {
	req, e := http.NewRequestWithContext(r.ctx, method, endpoint, body)
	if e != nil {
		return nil, e
	}
	req.ContentLength = length
	if (r.streaming != "" && strings.HasPrefix(endpoint, r.streaming)) || (r.media != "" && strings.HasPrefix(endpoint, r.media)) {
		req.Header.Set("Authorization", "Bearer "+token)
	} else {
		req.Header.Set("X-Service-Name", "media-capture")
		req.Header.Set("X-Service-Token", token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, e := r.client.Do(req)
	if e != nil {
		return nil, e
	}
	if res.StatusCode < 200 || res.StatusCode > 299 {
		res.Body.Close()
		return nil, &upstreamError{res.StatusCode}
	}
	return res, nil
}
func (r *recorder) fetch(endpoint string, limit int64) ([]byte, error) {
	res, e := r.request("GET", endpoint, r.streamingToken, nil, 0, nil)
	if e != nil {
		return nil, e
	}
	defer res.Body.Close()
	raw, e := io.ReadAll(io.LimitReader(res.Body, limit+1))
	if int64(len(raw)) > limit {
		return nil, fmt.Errorf("capture size limit")
	}
	return raw, e
}
func (r *recorder) poll() {
	raw, e := r.fetch(r.streaming+"/internal/streaming/recordings", 1<<20)
	if e != nil {
		return
	}
	var response struct {
		Items []source `json:"items"`
	}
	if json.Unmarshal(raw, &response) != nil {
		return
	}
	for _, src := range response.Items {
		r.mu.Lock()
		s := r.states[src.Start.SessionID]
		if s == nil {
			s = &state{Start: src.Start, Renditions: map[string]vod.Rendition{}, Finalized: map[string]bool{}, Next: map[string]int64{}, Correction: map[int64]int64{}, Segments: []pending{}, CloseID: uuid("close:" + src.Start.SessionID)}
			r.states[src.Start.SessionID] = s
		}
		if src.Status == "ENDED" {
			s.Ended = true
			if s.EndObservedAt == nil {
				now := time.Now().UTC()
				s.EndObservedAt = &now
			}
			if e = r.save(s); e != nil {
				s.Failure = "CHECKPOINT_FAILED"
			}
		}
		s.LastSourceGeneration = max(s.LastSourceGeneration, src.SourceGeneration)
		if s.Closed || s.Failure != "" || r.running[src.Start.SessionID] || (r.nativeDir == "" && src.Availability != "PLAYABLE") {
			r.mu.Unlock()
			continue
		}
		r.running[src.Start.SessionID] = true
		r.mu.Unlock()
		go func(src source) {
			defer func() { r.mu.Lock(); r.running[src.Start.SessionID] = false; r.mu.Unlock() }()
			var e error
			if r.nativeDir != "" {
				e = r.captureNative(src)
			} else {
				e = r.capture(src)
			}
			if e != nil {
				slog.Warn("media capture retry", "sessionId", src.Start.SessionID)
			}
		}(src)
	}
}

var attribute = regexp.MustCompile(`([A-Z0-9-]+)=("[^"]*"|[^,]+)`)

func attributes(line string) map[string]string {
	a := map[string]string{}
	for _, m := range attribute.FindAllStringSubmatch(line, -1) {
		a[m[1]] = strings.Trim(m[2], "\"")
	}
	return a
}

type variant struct {
	URL       string
	Rendition vod.Rendition
}

func variants(master, base, session string) ([]variant, error) {
	lines := strings.Split(master, "\n")
	result := []variant{}
	for i, line := range lines {
		if !strings.HasPrefix(line, "#EXT-X-STREAM-INF:") {
			continue
		}
		if i+1 >= len(lines) {
			return nil, fmt.Errorf("master incomplete")
		}
		a := attributes(line)
		w, h := 0, 0
		_, _ = fmt.Sscanf(a["RESOLUTION"], "%dx%d", &w, &h)
		bitrate, _ := strconv.ParseInt(a["BANDWIDTH"], 10, 64)
		codecs := strings.Split(a["CODECS"], ",")
		if w < 1 || h < 1 || bitrate < 1 || len(codecs) == 0 || codecs[0] == "" {
			return nil, fmt.Errorf("rendition metadata absent")
		}
		target, e := resolve(base, strings.TrimSpace(lines[i+1]), session)
		if e != nil {
			return nil, e
		}
		v := vod.Rendition{RenditionID: "rend_" + hash([]byte(target))[0:16], Width: w, Height: h, Bitrate: bitrate, VideoCodec: codecs[0]}
		if len(codecs) > 1 {
			v.AudioCodec = &codecs[1]
		}
		result = append(result, variant{target, v})
	}
	if len(result) < 1 || len(result) > 4 {
		return nil, fmt.Errorf("rendition count invalid")
	}
	return result, nil
}
func resolve(base, relative, session string) (string, error) {
	u, e := url.Parse(base)
	if e != nil {
		return "", e
	}
	v, e := url.Parse(relative)
	if e != nil || v.IsAbs() || v.Host != "" || strings.ContainsAny(relative, "\\%?#") {
		return "", fmt.Errorf("media URI invalid")
	}
	v = u.ResolveReference(v)
	if !strings.HasPrefix(v.Path, "/hls/"+session+"/") || strings.Contains(v.Path, "..") {
		return "", fmt.Errorf("media escaped session")
	}
	return v.String(), nil
}

type mediaSegment struct {
	Sequence, DurationMS int64
	Date                 time.Time
	URI, Init            string
	Discontinuity        bool
}

func playlist(text string) ([]mediaSegment, error) {
	seq := int64(0)
	duration := int64(0)
	initialization := ""
	var date time.Time
	discontinuity := false
	result := []mediaSegment{}
	scanner := bufio.NewScanner(strings.NewReader(text))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		switch {
		case strings.HasPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"):
			n, e := strconv.ParseInt(strings.TrimPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"), 10, 64)
			if e != nil {
				return nil, e
			}
			seq = n
		case strings.HasPrefix(line, "#EXT-X-MAP:"):
			initialization = attributes(line)["URI"]
		case strings.HasPrefix(line, "#EXT-X-PROGRAM-DATE-TIME:"):
			d, e := time.Parse(time.RFC3339Nano, strings.TrimPrefix(line, "#EXT-X-PROGRAM-DATE-TIME:"))
			if e != nil {
				return nil, e
			}
			date = d
		case line == "#EXT-X-DISCONTINUITY":
			discontinuity = true
		case strings.HasPrefix(line, "#EXTINF:"):
			v := strings.Split(strings.TrimPrefix(line, "#EXTINF:"), ",")[0]
			f, e := strconv.ParseFloat(v, 64)
			if e != nil || f <= 0 || f > 60 {
				return nil, fmt.Errorf("segment duration invalid")
			}
			duration = int64(f*1000 + 0.5)
		case line != "" && !strings.HasPrefix(line, "#"):
			if duration == 0 {
				continue
			}
			if date.IsZero() {
				return nil, fmt.Errorf("program date required for recording clock")
			}
			result = append(result, mediaSegment{seq, duration, date, line, initialization, discontinuity})
			date = date.Add(time.Duration(duration) * time.Millisecond)
			seq++
			duration = 0
			discontinuity = false
		}
	}
	return result, scanner.Err()
}
func (r *recorder) capture(src source) error {
	if src.StartedAt == nil {
		return nil
	}
	r.mu.Lock()
	initial := r.states[src.Start.SessionID]
	highest := int64(0)
	for generation := range initial.Correction {
		highest = max(highest, generation)
	}
	if src.SourceGeneration > highest+1 {
		initial.Failure = "CAPTURE_GENERATION_MISSING"
		_ = r.save(initial)
		r.mu.Unlock()
		return fmt.Errorf("generation missing")
	}
	r.mu.Unlock()
	base := r.media + "/hls/" + src.Start.SessionID + "/index.m3u8"
	raw, e := r.fetchGeneration(base, 128<<10, src.SourceGeneration)
	if e != nil {
		return e
	}
	vv, e := variants(string(raw), base, src.Start.SessionID)
	if e != nil {
		return e
	}
	r.mu.Lock()
	known := r.states[src.Start.SessionID]
	if known.Expected == nil {
		known.Expected = map[string]bool{}
	}
	for _, v := range vv {
		known.Renditions[v.Rendition.RenditionID] = v.Rendition
		known.Expected[fmt.Sprintf("%s:%d", v.Rendition.RenditionID, src.SourceGeneration)] = true
	}
	e = r.save(known)
	r.mu.Unlock()
	if e != nil {
		return e
	}
	for _, v := range vv {
		raw, e = r.fetchGeneration(v.URL, 128<<10, src.SourceGeneration)
		if e != nil {
			return e
		}
		segments, e := playlist(string(raw))
		if e != nil {
			return e
		}
		for _, m := range segments {
			r.mu.Lock()
			s := r.states[src.Start.SessionID]
			if s.Closed || s.Failure != "" {
				r.mu.Unlock()
				return nil
			}
			generationKey := fmt.Sprintf("%s:%d", v.Rendition.RenditionID, src.SourceGeneration)
			next, exists := s.Next[generationKey]
			if exists && m.Sequence < next {
				r.mu.Unlock()
				continue
			}
			if exists && m.Sequence > next {
				s.Failure = "CAPTURE_SEGMENT_MISSING"
				_ = r.save(s)
				r.mu.Unlock()
				return fmt.Errorf("segment missing")
			}
			correction, ok := s.Correction[src.SourceGeneration]
			if !ok {
				correction = max(0, src.StartedAt.Sub(m.Date).Milliseconds())
				s.Correction[src.SourceGeneration] = correction
			}
			if !exists && src.SourceGeneration == 1 && m.Date.Sub(*src.StartedAt).Milliseconds()+correction > 1000 {
				s.Failure = "CAPTURE_INITIAL_SEGMENT_MISSING"
				_ = r.save(s)
				r.mu.Unlock()
				return fmt.Errorf("initial segment missing")
			}
			r.mu.Unlock()
			target, e := resolve(v.URL, m.URI, src.Start.SessionID)
			if e != nil {
				return e
			}
			data, e := r.fetchGeneration(target, vod.MaxSegmentBytes, src.SourceGeneration)
			if e != nil {
				return e
			}
			name := hash([]byte(src.Start.SessionID + ":" + generationKey + ":" + strconv.FormatInt(m.Sequence, 10)))
			file := filepath.Join(r.dir, name+".media")
			if e = r.space(int64(len(data))); e != nil {
				r.mu.Lock()
				s.Failure = "CAPTURE_SPOOL_FULL"
				_ = r.save(s)
				r.mu.Unlock()
				return e
			}
			if e = atomicFile(file, data); e != nil {
				return e
			}
			initFile := ""
			if m.Init != "" {
				target, e = resolve(v.URL, m.Init, src.Start.SessionID)
				if e != nil {
					return e
				}
				initFile = filepath.Join(r.dir, hash([]byte(src.Start.SessionID+":"+generationKey))+".init")
				if _, err := os.Stat(initFile); err != nil {
					data, e := r.fetchGeneration(target, 8<<20, src.SourceGeneration)
					if e != nil {
						return e
					}
					if e = atomicFile(initFile, data); e != nil {
						return e
					}
				}
			}
			mime := "video/iso.segment"
			if strings.HasSuffix(m.URI, ".ts") {
				mime = "video/mp2t"
			}
			seg := vod.Segment{SessionID: src.Start.SessionID, RenditionID: v.Rendition.RenditionID, SourceGeneration: src.SourceGeneration, Sequence: m.Sequence, SessionStartMS: max(0, m.Date.Sub(*src.StartedAt).Milliseconds()+correction), DurationMS: m.DurationMS, Discontinuity: (!exists && len(s.Segments) > 0) || m.Discontinuity, Object: vod.ObjectRef{ByteLength: int64(len(data)), SHA256: hash(data), ContentType: mime}}
			r.mu.Lock()
			s.Renditions[v.Rendition.RenditionID] = v.Rendition
			s.Segments = append(s.Segments, pending{Segment: seg, File: file, InitFile: initFile, InitKey: "init:" + generationKey, Hash: hash(data), Size: int64(len(data)), EventID: uuid("segment:" + seg.Key())})
			s.Next[generationKey] = m.Sequence + 1
			e = r.save(s)
			r.mu.Unlock()
			if e != nil {
				return e
			}
		}

		if strings.Contains(string(raw), "#EXT-X-ENDLIST") {
			r.mu.Lock()
			s := r.states[src.Start.SessionID]
			if s.Finalized == nil {
				s.Finalized = map[string]bool{}
			}
			s.Finalized[fmt.Sprintf("%s:%d", v.Rendition.RenditionID, src.SourceGeneration)] = true
			e = r.save(s)
			r.mu.Unlock()
			if e != nil {
				return e
			}
		}
	}
	return nil
}
func (r *recorder) space(extra int64) error {
	var size int64
	entries, e := os.ReadDir(r.dir)
	if e != nil {
		return e
	}
	for _, entry := range entries {
		if info, err := entry.Info(); err == nil {
			size += info.Size()
		}
	}
	if size+extra > r.limit {
		return fmt.Errorf("capture spool full")
	}
	return nil
}
func (r *recorder) uploadObject(session, key, path, mime string, segment ...vod.Segment) (vod.ObjectRef, error) {
	var ref vod.ObjectRef
	raw, e := os.ReadFile(path)
	if e != nil {
		return ref, e
	}
	if len(segment) > 0 && (segment[0].Object.ByteLength != int64(len(raw)) || segment[0].Object.SHA256 != hash(raw)) {
		return ref, errCaptureBytes
	}
	headers := map[string]string{"Content-Type": mime, "X-Object-SHA256": hash(raw), "Idempotency-Key": uuid(session + ":" + key)}
	if len(segment) > 0 {
		body, _ := json.Marshal(segment[0])
		var metadata map[string]any
		_ = json.Unmarshal(body, &metadata)
		delete(metadata, "object")
		body, _ = json.Marshal(metadata)
		headers["X-Segment-Metadata"] = base64.RawURLEncoding.EncodeToString(body)
	}
	res, e := r.request("PUT", r.destination+"/internal/vod/recordings/"+session+"/objects/"+key, r.captureToken, bytes.NewReader(raw), int64(len(raw)), headers)
	if e != nil {
		return ref, e
	}
	defer res.Body.Close()
	decoder := json.NewDecoder(io.LimitReader(res.Body, 16384))
	decoder.DisallowUnknownFields()
	var tail any
	e = decoder.Decode(&ref)
	if e == nil && (decoder.Decode(&tail) != io.EOF || !ref.Valid() || ref.ByteLength != int64(len(raw)) || ref.SHA256 != hash(raw) || ref.ContentType != mime) {
		e = fmt.Errorf("object confirmation differs from uploaded bytes")
	}
	return ref, e
}
func (r *recorder) uploadLoop() {
	for r.ctx.Err() == nil {
		r.mu.Lock()
		ids := []string{}
		for id := range r.states {
			ids = append(ids, id)
		}
		r.mu.Unlock()
		sort.Strings(ids)
		for _, id := range ids {
			if e := r.transfer(id); e != nil {
				var upstream *upstreamError
				if errors.As(e, &upstream) && upstream.status == 410 {
					r.mu.Lock()
					s := r.states[id]
					s.Closed = true
					s.Retired = true
					_ = r.save(s)
					r.mu.Unlock()
					continue
				}
				slog.Warn("recording delivery pending", "sessionId", id)
			}
		}
		select {
		case <-r.ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}
func (r *recorder) transfer(id string) error {
	r.mu.Lock()
	original := r.states[id]
	raw, _ := json.Marshal(original)
	r.mu.Unlock()
	var s state
	_ = json.Unmarshal(raw, &s)
	if s.Closed {
		if s.Retired {
			if e := r.retireNative(id); e != nil {
				return e
			}
		}
		if e := r.cleanup(&s); e != nil {
			return e
		}
		if s.Ended && !s.Acknowledged {
			return r.acknowledge(original)
		}
		return nil
	}
	if !s.Registered {
		body, _ := json.Marshal(s.Start)
		res, e := r.request("PUT", r.destination+"/internal/vod/recordings/"+id, r.captureToken, bytes.NewReader(body), int64(len(body)), map[string]string{"Content-Type": "application/json", "Idempotency-Key": uuid("start:" + id)})
		if e != nil {
			return e
		}
		res.Body.Close()
		r.mu.Lock()
		original.Registered = true
		e = r.save(original)
		r.mu.Unlock()
		if e != nil {
			return e
		}
	}
	if s.Failure != "" {
		body, _ := json.Marshal(map[string]string{"code": s.Failure})
		res, e := r.request("POST", r.destination+"/internal/vod/recordings/"+id+"/failure", r.captureToken, bytes.NewReader(body), int64(len(body)), map[string]string{"Content-Type": "application/json", "Idempotency-Key": uuid("failure:" + id)})
		if e != nil {
			return e
		}
		res.Body.Close()
	}
	for i, p := range s.Segments {
		if p.Sent || p.Unusable {
			continue
		}
		if p.InitFile != "" && p.Segment.Initialization == nil {
			ref, e := r.uploadObject(id, p.InitKey, p.InitFile, "video/mp4")
			if e != nil {
				return e
			}
			p.Segment.Initialization = &ref
		}
		if p.Segment.Object.ObjectID == "" {
			ref, e := r.uploadObject(id, "segment:"+strings.TrimPrefix(p.Segment.Key(), id+":"), p.File, p.Segment.Object.ContentType, p.Segment)
			if e != nil {
				if errors.Is(e, errCaptureBytes) {
					r.mu.Lock()
					original.Segments[i].Unusable = true
					if original.Failure == "" {
						original.Failure = "CAPTURE_SPOOL_CHECKSUM_CONFLICT"
					}
					saveErr := r.save(original)
					r.mu.Unlock()
					if saveErr != nil {
						return saveErr
					}
				}
				return e
			}
			p.Segment.Object = ref
		}
		r.mu.Lock()
		original.Segments[i].Segment = p.Segment
		e := r.save(original)
		r.mu.Unlock()
		if e != nil {
			return e
		}
		if e = r.publish("recording.segment.available.v1", p.EventID, "segment:"+p.Segment.Key(), p.Segment.Sequence+1, p.Segment); e != nil {
			return e
		}
		r.mu.Lock()
		original.Segments[i].Sent = true
		e = r.save(original)
		r.mu.Unlock()
		if e != nil {
			return e
		}
		_ = os.Remove(p.File)
	}
	r.mu.Lock()
	raw, _ = json.Marshal(original)
	r.mu.Unlock()
	_ = json.Unmarshal(raw, &s)
	r.mu.Lock()
	capturing := r.running[id]
	r.mu.Unlock()
	if !s.Ended || capturing {
		return nil
	}

	if s.Failure != "" {
		return r.acknowledge(original)
	}
	if len(s.Segments) == 0 && r.nativeDir == "" {
		return r.captureFailure(original, "CAPTURE_EMPTY")
	}
	// ENDED may arrive before the native reader has drained its final file. Give the
	// producer time to seal, never manufacture a close from elapsed time.
	if r.nativeDir != "" {
		complete := len(s.Expected) > 0
		for generation := int64(1); generation <= s.LastSourceGeneration; generation++ {
			key := fmt.Sprintf("rend_source:%d", generation)
			if !s.Finalized[key] {
				complete = false
			}
		}
		if !complete {
			if s.EndObservedAt != nil && time.Since(*s.EndObservedAt) < 30*time.Second {
				return nil
			}
			return r.captureFailure(original, "CAPTURE_FINAL_INDEX_UNAVAILABLE")
		}
	}
	// Every rendition/generation needs producer-certified completion.
	for key := range s.Expected {
		if !s.Finalized[key] {
			return r.captureFailure(original, "CAPTURE_FINAL_INDEX_UNAVAILABLE")
		}
	}
	for _, p := range s.Segments {
		if !p.Sent {
			return nil
		}
	}
	if s.Index == nil {
		idx := vod.Index{SessionID: id, StreamGeneration: s.Start.StreamGeneration, Renditions: []vod.Rendition{}, Segments: []vod.Segment{}}
		for _, v := range s.Renditions {
			idx.Renditions = append(idx.Renditions, v)
		}
		sort.Slice(idx.Renditions, func(i, j int) bool { return idx.Renditions[i].RenditionID < idx.Renditions[j].RenditionID })
		for _, p := range s.Segments {
			idx.Segments = append(idx.Segments, p.Segment)
		}
		body, _ := json.Marshal(idx)
		file := filepath.Join(r.dir, id+".index")
		if e := atomicFile(file, body); e != nil {
			return e
		}
		ref, e := r.uploadObject(id, "index", file, "application/json")
		if e != nil {
			return e
		}
		s.Index = &ref
		r.mu.Lock()
		original.Index = &ref
		e = r.save(original)
		r.mu.Unlock()
		if e != nil {
			return e
		}
	}
	if e := r.publish("recording.closed.v1", s.CloseID, "recording:"+id, s.Start.StreamGeneration, map[string]any{"sessionId": id, "streamGeneration": s.Start.StreamGeneration, "index": s.Index}); e != nil {
		return e
	}
	return r.acknowledge(original)
}
func (r *recorder) acknowledge(original *state) error {
	id := original.Start.SessionID
	res, e := r.request("PUT", r.streaming+"/internal/streaming/recordings/"+id+"/acknowledged", r.streamingToken, nil, 0, map[string]string{"Idempotency-Key": uuid("ack:" + id)})
	if e != nil {
		return e
	}
	res.Body.Close()
	r.mu.Lock()
	original.Closed = true
	original.Acknowledged = true
	e = r.save(original)
	r.mu.Unlock()
	return e
}

func (r *recorder) publish(kind, id, aggregate string, sequence int64, payload any) error {
	body, _ := json.Marshal(payload)
	event := vod.Envelope{EventID: id, EventType: kind, SchemaVersion: 1, Producer: "streaming", AggregateID: aggregate, Sequence: sequence, OccurredAt: time.Now().UTC(), CorrelationID: id, Payload: body}
	checkpoint := filepath.Join(r.dir, id+".event")
	if saved, e := os.ReadFile(checkpoint); e == nil {
		if json.Unmarshal(saved, &event) != nil {
			return fmt.Errorf("outbox corrupt")
		}
	} else {
		raw, _ := json.Marshal(event)
		if e = atomicFile(checkpoint, raw); e != nil {
			return e
		}
	}
	raw, _ := json.Marshal(event)
	if r.publisher == nil || r.publisherConnection.IsClosed() {
		r.closePublisher()
		roots, e := x509.SystemCertPool()
		if e != nil {
			roots = x509.NewCertPool()
		}
		conn, e := amqp.DialConfig(r.broker, amqp.Config{Heartbeat: 30 * time.Second, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots}})
		if e != nil {
			return e
		}
		r.publisherConnection = conn
		ch, e := conn.Channel()
		if e != nil {
			r.closePublisher()
			return e
		}
		if e = ch.Confirm(false); e != nil {
			r.closePublisher()
			return e
		}
		r.publisher = ch
		r.returns = ch.NotifyReturn(make(chan amqp.Return, 1))
	}
	ch := r.publisher
	confirm, e := ch.PublishWithDeferredConfirmWithContext(r.ctx, "p2.events", kind, true, false, amqp.Publishing{ContentType: "application/json", DeliveryMode: amqp.Persistent, MessageId: id, Type: kind, Body: raw})
	if e != nil {
		r.closePublisher()
		return e
	}
	ctx, cancel := context.WithTimeout(r.ctx, 3*time.Second)
	defer cancel()
	ok, e := confirm.WaitContext(ctx)
	if e != nil || !ok {
		r.closePublisher()
		return fmt.Errorf("broker confirmation missing")
	}
	select {
	case <-r.returns:
		return fmt.Errorf("broker unroutable")
	default:
		return nil
	}
}

func (r *recorder) fetchGeneration(endpoint string, limit, generation int64) ([]byte, error) {
	res, e := r.request("GET", endpoint, r.streamingToken, nil, 0, map[string]string{"X-Source-Generation": strconv.FormatInt(generation, 10)})
	if e != nil {
		return nil, e
	}
	defer res.Body.Close()
	raw, e := io.ReadAll(io.LimitReader(res.Body, limit+1))
	if int64(len(raw)) > limit {
		return nil, fmt.Errorf("capture size limit")
	}
	return raw, e
}

func (r *recorder) closePublisher() {
	if r.publisherConnection != nil {
		_ = r.publisherConnection.Close()
	}
	r.publisher = nil
	r.publisherConnection = nil
}
func (r *recorder) captureFailure(s *state, code string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if s.Failure == "" {
		s.Failure = code
	}
	return r.save(s)
}
func (r *recorder) cleanup(s *state) error {
	remove := func(path string) error {
		e := os.Remove(path)
		if os.IsNotExist(e) {
			return nil
		}
		return e
	}
	for _, p := range s.Segments {
		if e := remove(p.File); e != nil {
			return e
		}
		if p.InitFile != "" {
			if e := remove(p.InitFile); e != nil {
				return e
			}
		}
		if e := remove(filepath.Join(r.dir, p.EventID+".event")); e != nil {
			return e
		}
	}
	if e := remove(filepath.Join(r.dir, s.Start.SessionID+".index")); e != nil {
		return e
	}
	if e := remove(filepath.Join(r.dir, s.CloseID+".event")); e != nil {
		return e
	}
	if e := r.cleanupNative(s); e != nil {
		return e
	}
	// Keep a small durable closed checkpoint so a stale poll cannot recreate a recording.
	if len(s.Segments) > 0 {
		r.mu.Lock()
		original := r.states[s.Start.SessionID]
		original.Segments = nil
		e := r.save(original)
		r.mu.Unlock()
		return e
	}
	return nil
}
func (r *recorder) serveMedia(ctx context.Context) {
	objects, e := vod.NewS3(env("MEDIA_S3_ENDPOINT", "https://minio:9000"), env("MEDIA_S3_BUCKET", "streaming-vod"), env("MEDIA_S3_PREFIX", "vod"), env("MEDIA_S3_REGION", "us-east-1"), os.Getenv("MEDIA_S3_ACCESS_KEY"), os.Getenv("MEDIA_S3_SECRET_KEY"))
	if e != nil {
		slog.Error("media delivery configuration invalid")
		return
	}
	server := &http.Server{Addr: ":8890", Handler: vod.MediaDeliveryHandler(objects, os.Getenv("MEDIA_VOD_DELIVERY_TOKEN")), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 90 * time.Second, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	go func() {
		<-ctx.Done()
		stop, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(stop)
	}()
	if e = server.ListenAndServeTLS(os.Getenv("MEDIA_TLS_CERT_FILE"), os.Getenv("MEDIA_TLS_KEY_FILE")); e != nil && ctx.Err() == nil {
		slog.Error("media delivery listener unavailable")
		os.Exit(1)
	}
}
