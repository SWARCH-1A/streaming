package vod

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/minio/minio-go/v7"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fixtureAuthority struct {
	calls  atomic.Int64
	active atomic.Bool
}

func (a *fixtureAuthority) Check(_ context.Context, _ string, credential, operation string) (Access, error) {
	a.calls.Add(1)
	if credential != "" && credential != "owner" && credential != "other" && credential != "admin" {
		return Access{}, Fail(401, "SESSION_REQUIRED")
	}
	if operation != "READ" && credential == "" {
		return Access{}, Fail(401, "SESSION_REQUIRED")
	}
	user := ""
	if credential == "owner" {
		user = "usr_fixture"
	} else if credential == "admin" {
		user = "usr_admin"
	} else if credential == "other" {
		user = "usr_other"
	}
	return Access{UserID: user, OwnerUserID: "usr_fixture", Active: a.active.Load(), Administrator: credential == "admin"}, nil
}
func fixture(t *testing.T) (*Service, *fixtureAuthority) {
	t.Helper()
	dbURL := os.Getenv("VOD_TEST_DATABASE_URL")
	endpoint := os.Getenv("VOD_TEST_S3_ENDPOINT")
	if dbURL == "" || endpoint == "" {
		t.Skip("requires isolated PostgreSQL and MinIO fixture")
	}
	if !strings.Contains(dbURL, "vod_test") {
		t.Fatal("refusing a non-fixture database")
	}
	ctx := context.Background()
	store, e := Open(ctx, dbURL)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = store.DB.ExecContext(ctx, "TRUNCATE vod_recordings,vod_inbox,vod_accounts,vod_commands,vod_outbox RESTART IDENTITY CASCADE"); e != nil {
		t.Fatal(e)
	}
	bucket := "vod-test-" + strings.TrimPrefix(ID(""), "")
	objects, e := NewS3(endpoint, bucket, "vod", "us-east-1", "vodfixture", "fixture-only-vod-password")
	if e != nil {
		t.Fatal(e)
	}
	if e = objects.Client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); e != nil {
		t.Fatal(e)
	}
	auth := &fixtureAuthority{}
	auth.active.Store(true)
	s := &Service{Store: store, Objects: objects, Authority: auth, Secret: []byte(strings.Repeat("fixture-secret-", 4)), Origin: "https://vault.example", Development: true}
	s.Probe = s.ProbeMedia
	media := httptest.NewServer(MediaDeliveryHandler(objects, "fixture-delivery-token"))
	s.Delivery = NewMediaDelivery(media.URL, "fixture-delivery-token")
	t.Cleanup(media.Close)
	t.Cleanup(func() {
		for object := range objects.Client.ListObjects(ctx, bucket, minio.ListObjectsOptions{Recursive: true}) {
			_ = objects.Client.RemoveObject(ctx, bucket, object.Key, minio.RemoveObjectOptions{})
		}
		_ = objects.Client.RemoveBucket(ctx, bucket)
		store.DB.Close()
	})
	return s, auth
}
func video(t *testing.T, width, height int) []byte {
	t.Helper()
	if _, e := exec.LookPath("ffmpeg"); e != nil {
		t.Fatal("FFmpeg is required for actual media validation")
	}
	file := t.TempDir() + "/segment.ts"
	cmd := exec.Command("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", fmt.Sprintf("color=c=blue:size=%dx%d:rate=25", width, height), "-t", "1", "-c:v", "libx264", "-threads", "1", "-preset", "ultrafast", "-g", "25", "-pix_fmt", "yuv420p", "-f", "mpegts", file)
	if raw, e := cmd.CombinedOutput(); e != nil {
		t.Fatalf("fixture media failed: %s %v", raw, e)
	}
	raw, e := os.ReadFile(file)
	if e != nil {
		t.Fatal(e)
	}
	return raw
}
func recording(t *testing.T, s *Service, auto bool, variants int) (Recording, Index) {
	t.Helper()
	ctx := context.Background()
	start := Start{SessionID: ID("ses_"), StreamID: "str_fixture", ChannelID: "chn_fixture", StreamGeneration: 1, Title: "Clase grabada", CategoryID: "cat_fixture", TagIDs: []string{}, Autopublish: auto}
	r, e := s.Start(ctx, start)
	if e != nil {
		t.Fatal(e)
	}
	index := Index{SessionID: r.SessionID, StreamGeneration: 1}
	for n := 0; n < variants; n++ {
		v := Rendition{RenditionID: fmt.Sprintf("rend_%d", n), Width: 160 * (n + 1), Height: 90 * (n + 1), Bitrate: int64(100000 * (n + 1)), VideoCodec: "avc1"}
		index.Renditions = append(index.Renditions, v)
		raw := video(t, v.Width, v.Height)
		for i, offset := range []int64{0, 33000} {
			seg := Segment{SessionID: r.SessionID, RenditionID: v.RenditionID, SourceGeneration: int64(i + 1), Sequence: 0, SessionStartMS: offset, DurationMS: 1000, Discontinuity: i > 0}
			ref, e := s.Upload(ctx, r.SessionID, "segment:"+strings.TrimPrefix(seg.Key(), r.SessionID+":"), refBytes(raw, "video/mp2t"), bytes.NewReader(raw))
			if e != nil {
				t.Fatal(e)
			}
			seg.Object = ref
			index.Segments = append(index.Segments, seg)
		}
	}
	body, _ := json.Marshal(index)
	ref, e := s.Upload(ctx, r.SessionID, "index", refBytes(body, "application/json"), bytes.NewReader(body))
	if e != nil {
		t.Fatal(e)
	}
	r.Index = &ref
	return r, index
}
func closeRecording(t *testing.T, s *Service, r Recording) {
	t.Helper()
	p, _ := json.Marshal(map[string]any{"sessionId": r.SessionID, "streamGeneration": r.StreamGeneration, "index": r.Index})
	event := Envelope{EventID: ID("evt_"), EventType: "recording.closed.v1", SchemaVersion: 1, Producer: "streaming", AggregateID: "recording:" + r.SessionID, Sequence: r.StreamGeneration, OccurredAt: time.Now().UTC(), CorrelationID: "req_fixture", Payload: p}
	body, _ := json.Marshal(event)
	if e := s.Event(context.Background(), "vod.recording", body); e != nil {
		t.Fatal(e)
	}
	if e := s.Event(context.Background(), "vod.recording", body); e != nil {
		t.Fatal("duplicate close", e)
	}
	if e := s.Validate(context.Background(), r); e != nil {
		t.Fatal(e)
	}
}
func call(s *Service, method, path string, body any, headers map[string]string) *httptest.ResponseRecorder {
	var raw []byte
	if body != nil {
		raw, _ = json.Marshal(body)
	}
	req := httptest.NewRequest(method, "https://vault.example"+path, bytes.NewReader(raw))
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	out := httptest.NewRecorder()
	s.PublicHandler().ServeHTTP(out, req)
	return out
}
func TestRealStorageClosurePlaybackAndRevocation(t *testing.T) {
	s, auth := fixture(t)
	r, index := recording(t, s, true, 4)
	closeRecording(t, s, r)
	ready, e := s.Store.Get(context.Background(), r.VodID)
	if e != nil || !ready.Public() || ready.DurationMS != 2000 || len(ready.Renditions) != 4 {
		t.Fatalf("not ready %+v %v", ready, e)
	}
	timeline, e := s.Store.Timeline(context.Background(), r.VodID)
	if e != nil || timeline.MapOffset(15000) != 1000 {
		t.Fatal(timeline, e)
	}
	before := auth.calls.Load()
	master := call(s, "GET", ready.PlaybackPath, nil, nil)
	if master.Code != 200 || !strings.Contains(master.Body.String(), "rend_3/index.m3u8") {
		t.Fatal(master.Code, master.Body.String())
	}
	if auth.calls.Load() != before+1 {
		t.Fatal("manifest must authorize once")
	}
	cookie := master.Result().Cookies()[0]
	asset := fmt.Sprintf("/vod-media/%s/%s/%d/%d.ts", r.VodID, index.Segments[0].RenditionID, index.Segments[0].SourceGeneration, index.Segments[0].Sequence)
	if out := call(s, "GET", asset, nil, nil); out.Code != 401 {
		t.Fatal("segment without permission", out.Code)
	}
	before = auth.calls.Load()
	out := call(s, "GET", asset, nil, map[string]string{"Cookie": cookie.Name + "=" + cookie.Value, "Range": "bytes=0-15"})
	if out.Code != 206 || out.Body.Len() != 16 || auth.calls.Load() != before {
		t.Fatal("range or Core amplification", out.Code, out.Body.Len())
	}
	payload, _ := json.Marshal(map[string]any{"userId": "usr_fixture", "accountVersion": 1, "status": "SUSPENDED"})
	event := Envelope{EventID: ID("evt_"), EventType: "account.status.changed.v1", SchemaVersion: 1, Producer: "core", AggregateID: "account:usr_fixture", Sequence: 1, OccurredAt: time.Now().UTC(), CorrelationID: "req_fixture", Payload: payload}
	raw, _ := json.Marshal(event)
	if e = s.Event(context.Background(), "vod.account", raw); e != nil {
		t.Fatal(e)
	}
	if out = call(s, "GET", asset, nil, map[string]string{"Cookie": cookie.Name + "=" + cookie.Value}); out.Code != 404 {
		t.Fatal("still-valid ticket bypassed suspension", out.Code)
	}
	// Stale reactivation cannot undo a newer suspension.
	event.Sequence = 0
	event.EventID = ID("evt_")
	event.Payload = []byte(`{"userId":"usr_fixture","accountVersion":0,"status":"ACTIVE"}`)
	raw, _ = json.Marshal(event)
	if e = s.Event(context.Background(), "vod.account", raw); e == nil {
		t.Fatal("invalid sequence accepted")
	}
}
func TestPrivateDraftVersionedCommandsAndTerminalDelete(t *testing.T) {
	s, _ := fixture(t)
	r, _ := recording(t, s, false, 1)
	closeRecording(t, s, r)
	ctx := context.Background()
	r, _ = s.Store.Get(ctx, r.VodID)
	if r.Publication != "DRAFT" {
		t.Fatal("autopublished disabled session")
	}
	if out := call(s, "GET", "/api/vods/"+r.VodID, nil, nil); out.Code != 404 {
		t.Fatal("draft public", out.Code)
	}
	owner := map[string]string{"Authorization": "Bearer owner", "Idempotency-Key": "850da8b2-86d1-45dd-8140-2230242099f2"}
	out := call(s, "PATCH", "/api/vods/"+r.VodID, map[string]any{"expectedVersion": r.Version, "title": "Clase editada"}, owner)
	if out.Code != 200 {
		t.Fatal(out.Code, out.Body.String())
	}
	repeat := call(s, "PATCH", "/api/vods/"+r.VodID, map[string]any{"expectedVersion": r.Version, "title": "Clase editada"}, owner)
	if repeat.Body.String() != out.Body.String() {
		t.Fatal("idempotent command changed result")
	}
	conflict := call(s, "PATCH", "/api/vods/"+r.VodID, map[string]any{"expectedVersion": r.Version, "title": "Otro título"}, owner)
	if conflict.Code != 409 {
		t.Fatal("idempotency reuse", conflict.Code)
	}
	r, _ = s.Store.Get(ctx, r.VodID)
	owner["Idempotency-Key"] = "950da8b2-86d1-45dd-8140-2230242099f2"
	out = call(s, "POST", "/api/vods/"+r.VodID+"/publication", map[string]any{"expectedVersion": r.Version}, owner)
	if out.Code != 200 {
		t.Fatal(out.Code, out.Body.String())
	}
	r, _ = s.Store.Get(ctx, r.VodID)
	owner["Idempotency-Key"] = "a50da8b2-86d1-45dd-8140-2230242099f2"
	owner["If-Match"] = fmt.Sprint(r.Version)
	out = call(s, "DELETE", "/api/vods/"+r.VodID, nil, owner)
	if out.Code != 202 {
		t.Fatal(out.Code, out.Body.String())
	}
	r, _ = s.Store.Get(ctx, r.VodID)
	if !r.Deleted() {
		t.Fatal("delete did not remove access before cleanup")
	}
	if _, e := s.Start(ctx, r.Start); e == nil {
		t.Fatal("deleted session resurrected")
	}
	if e := s.Validate(ctx, r); e != nil {
		t.Fatal(e)
	}
	if out = call(s, "GET", r.PlaybackPath, nil, nil); out.Code != 404 {
		t.Fatal("deleted asset public", out.Code)
	}
}
func TestMissingObjectBlocksPublicationAndRestartsKeepIndex(t *testing.T) {
	s, _ := fixture(t)
	r, index := recording(t, s, true, 1)
	if e := s.Objects.Delete(context.Background(), index.Segments[0].Object); e != nil {
		t.Fatal(e)
	}

	payload, _ := json.Marshal(map[string]any{"sessionId": r.SessionID, "streamGeneration": r.StreamGeneration, "index": r.Index})
	ev := Envelope{EventID: ID("evt_"), EventType: "recording.closed.v1", SchemaVersion: 1, Producer: "streaming", AggregateID: "recording:" + r.SessionID, Sequence: r.StreamGeneration, OccurredAt: time.Now().UTC(), CorrelationID: "req_fixture", Payload: payload}
	raw, _ := json.Marshal(ev)
	if e := s.Event(context.Background(), "vod.recording", raw); e != nil {
		t.Fatal(e)
	}
	reopened, e := Open(context.Background(), os.Getenv("VOD_TEST_DATABASE_URL"))
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.DB.Close()
	s.Store = reopened
	r, e = reopened.Get(context.Background(), r.VodID)
	if e != nil || r.Index == nil {
		t.Fatal("restart lost index", e)
	}
	if e := s.Validate(context.Background(), r); e == nil {
		t.Fatal("missing object published")
	}
	persisted, _ := s.Store.Get(context.Background(), r.VodID)
	if persisted.Public() {
		t.Fatal("failed recording published")
	}
}

func TestUploadConflictSubtitlesAndDurableDelete(t *testing.T) {
	s, _ := fixture(t)
	r, index := recording(t, s, false, 1)
	closeRecording(t, s, r)
	ctx := context.Background()
	r, _ = s.Store.Get(ctx, r.VodID)
	seg := index.Segments[0]
	key := "segment:" + strings.TrimPrefix(seg.Key(), r.SessionID+":")
	if _, e := s.Upload(ctx, r.SessionID, key, refBytes([]byte("different"), "video/mp2t"), strings.NewReader("different")); e == nil {
		t.Fatal("changed bytes accepted")
	}
	// Registration retries compare the original snapshot after owner edits.
	original := r.Start
	_, e := s.Store.Command(ctx, r.VodID, "usr_fixture", ID("key_"), "edit", "PATCH", func(current *Recording) (any, error) {
		current.Title = "Otro título"
		current.Version++
		return publicRecording(*current), nil
	})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Start(ctx, original); e != nil {
		t.Fatal("snapshot changed with metadata", e)
	}
	upload := func(credential string) *httptest.ResponseRecorder {
		var buffer bytes.Buffer
		writer := multipart.NewWriter(&buffer)
		part, _ := writer.CreatePart(textproto.MIMEHeader{"Content-Disposition": {`form-data; name="metadata"`}, "Content-Type": {"application/json"}})
		_, _ = part.Write([]byte(fmt.Sprintf(`{"language":"es","label":"Español","expectedMappingVersion":%d}`, r.MappingVersion)))
		file, _ := writer.CreatePart(textproto.MIMEHeader{"Content-Disposition": {`form-data; name="file"; filename="fixture.vtt"`}, "Content-Type": {"text/vtt"}})
		_, _ = file.Write([]byte("WEBVTT\n\n00:00.000 --> 00:00.800\nHola\n"))
		_ = writer.Close()
		req := httptest.NewRequest("POST", "https://vault.example/api/vods/"+r.VodID+"/subtitles", &buffer)
		req.Header.Set("Authorization", "Bearer "+credential)
		req.Header.Set("Idempotency-Key", "a50da8b2-86d1-45dd-8140-2230242099f2")
		req.Header.Set("Content-Type", writer.FormDataContentType())
		out := httptest.NewRecorder()
		s.PublicHandler().ServeHTTP(out, req)
		return out
	}
	if out := upload("other"); out.Code != 403 {
		t.Fatal(out.Code, out.Body.String())
	}
	out := upload("owner")
	if out.Code != 201 {
		t.Fatal(out.Code, out.Body.String())
	}
	var track Track
	_ = json.Unmarshal(out.Body.Bytes(), &track)
	repeat := upload("owner")
	if repeat.Code != 201 || repeat.Body.String() != out.Body.String() {
		t.Fatal("subtitle retry", repeat.Code, repeat.Body.String())
	}
	if got := call(s, "GET", track.ContentPath, nil, map[string]string{"Authorization": "Bearer owner", "Range": "bytes=0-5"}); got.Code != http.StatusPartialContent || got.Body.String() != "WEBVTT" {
		t.Fatal(got.Code, got.Body.String())
	}
	r, _ = s.Store.Get(ctx, r.VodID)
	headers := map[string]string{"Authorization": "Bearer owner", "Idempotency-Key": "b50da8b2-86d1-45dd-8140-2230242099f2", "If-Match": fmt.Sprint(r.Version)}
	out = call(s, "DELETE", "/api/vods/"+r.VodID, nil, headers)
	if out.Code != 202 {
		t.Fatal(out.Code, out.Body.String())
	}
	repeat = call(s, "DELETE", "/api/vods/"+r.VodID, nil, headers)
	if repeat.Code != 202 || repeat.Body.String() != out.Body.String() {
		t.Fatal("delete retry", repeat.Code)
	}
	var op struct {
		OperationID string `json:"operationId"`
	}
	_ = json.Unmarshal(out.Body.Bytes(), &op)
	job, e := s.Store.NextJob(ctx)
	if e != nil {
		t.Fatal(e)
	}
	// The validation job may still be queued because this test validated synchronously.
	if job.Kind == "VALIDATE" {
		_ = s.Store.JobResult(ctx, job, nil)
		job, e = s.Store.NextJob(ctx)
	}
	if e != nil || job.Kind != "DELETE" {
		t.Fatal(job, e)
	}
	_ = s.Store.JobResult(ctx, job, Fail(503, "S3_OFFLINE"))
	status, e := s.Store.Operation(ctx, op.OperationID, "usr_fixture")
	if e != nil || status["status"] != "FAILED" {
		t.Fatal(status, e)
	}
	if got := call(s, "GET", track.ContentPath, nil, map[string]string{"Authorization": "Bearer owner"}); got.Code != 404 {
		t.Fatal("pending cleanup restored access", got.Code)
	}
	_, _ = s.Store.DB.ExecContext(ctx, "UPDATE vod_jobs SET next_at=now() WHERE id=$1", job.ID)
	worker, cancel := context.WithCancel(ctx)
	go s.Workers(worker)
	defer cancel()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		status, e = s.Store.Operation(ctx, op.OperationID, "usr_fixture")
		if e == nil && status["status"] == "COMPLETED" {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if status["status"] != "COMPLETED" {
		t.Fatal(status)
	}
	for _, seg := range index.Segments {
		if s.Objects.Verify(ctx, seg.Object) == nil {
			t.Fatal("object survived cleanup")
		}
	}
}

func TestPrivateHTTPUploadConfirmsBytesAndImmutableTiming(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	start := Start{SessionID: ID("ses_"), StreamID: "str_fixture", ChannelID: "chn_fixture", StreamGeneration: 1, Title: "Captura", CategoryID: "cat_fixture", TagIDs: []string{}}
	r, e := s.Start(ctx, start)
	if e != nil {
		t.Fatal(e)
	}
	handler := s.InternalHandler("capture-fixture", "chat-fixture", "gateway-fixture")
	metadata := map[string]any{"sessionId": r.SessionID, "renditionId": "rend_fixture", "sourceGeneration": 1, "sequence": 0, "sessionStartMs": 0, "durationMs": 1000, "discontinuity": false}
	raw := []byte("binary-fixture")
	ref := refBytes(raw, "video/mp2t")
	put := func(token string) *httptest.ResponseRecorder {
		meta, _ := json.Marshal(metadata)
		req := httptest.NewRequest("PUT", "https://vod/internal/vod/recordings/"+r.SessionID+"/objects/segment:rend_fixture:1:0", bytes.NewReader(raw))
		req.Header.Set("X-Service-Name", "media-capture")
		req.Header.Set("X-Service-Token", token)
		req.Header.Set("Content-Type", ref.ContentType)
		req.Header.Set("X-Object-SHA256", ref.SHA256)
		req.Header.Set("Idempotency-Key", "c50da8b2-86d1-45dd-8140-2230242099f2")
		req.Header.Set("X-Segment-Metadata", base64.RawURLEncoding.EncodeToString(meta))
		out := httptest.NewRecorder()
		handler.ServeHTTP(out, req)
		return out
	}
	if out := put("chat-fixture"); out.Code != 401 {
		t.Fatal("service credential escalated", out.Code)
	}
	out := put("capture-fixture")
	if out.Code != 200 {
		t.Fatal(out.Code, out.Body.String())
	}
	var confirmed ObjectRef
	_ = json.Unmarshal(out.Body.Bytes(), &confirmed)
	if e = s.Objects.Verify(ctx, confirmed); e != nil {
		t.Fatal("acknowledged before storage", e)
	}
	if retry := put("capture-fixture"); retry.Code != 200 || retry.Body.String() != out.Body.String() {
		t.Fatal("retry changed reference", retry.Code)
	}
	metadata["durationMs"] = 2000
	if out = put("capture-fixture"); out.Code != 409 {
		t.Fatal("timing changed under same identity", out.Code, out.Body.String())
	}
}

func TestConcurrentUploadsCannotExhaustLockConnections(t *testing.T) {
	s, _ := fixture(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	start := Start{SessionID: ID("ses_"), StreamID: "str_fixture", ChannelID: "chn_fixture", StreamGeneration: 1, Title: "Concurrente", CategoryID: "cat_fixture", TagIDs: []string{}}
	r, e := s.Start(ctx, start)
	if e != nil {
		t.Fatal(e)
	}
	var wg sync.WaitGroup
	errors := make(chan error, 32)
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			raw := []byte(fmt.Sprintf("bytes-%d", i))
			_, err := s.Upload(ctx, r.SessionID, fmt.Sprintf("segment:rend_fixture:1:%d", i), refBytes(raw, "video/mp2t"), bytes.NewReader(raw))
			errors <- err
		}(i)
	}
	wg.Wait()
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	refs, e := s.Store.Objects(ctx, r.VodID)
	if e != nil || len(refs) != 32 {
		t.Fatal(len(refs), e)
	}
}

func TestFragmentedMP4RequiresInitAndValidatesEveryDuration(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	start := Start{SessionID: ID("ses_"), StreamID: "str_fixture", ChannelID: "chn_fixture", StreamGeneration: 1, Title: "Fragmentos", CategoryID: "cat_fixture", TagIDs: []string{}}
	r, e := s.Start(ctx, start)
	if e != nil {
		t.Fatal(e)
	}
	dir := t.TempDir()
	cmd := exec.Command("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:size=160x90:rate=25", "-t", "2", "-c:v", "libx264", "-threads", "1", "-preset", "ultrafast", "-g", "25", "-pix_fmt", "yuv420p", "-f", "hls", "-hls_segment_type", "fmp4", "-hls_time", "1", "-hls_list_size", "0", filepath.Join(dir, "index.m3u8"))
	if raw, e := cmd.CombinedOutput(); e != nil {
		t.Fatal(string(raw), e)
	}
	init, e := os.ReadFile(filepath.Join(dir, "init.mp4"))
	if e != nil {
		t.Fatal(e)
	}
	ref, e := s.Upload(ctx, r.SessionID, "init:rend_fixture:1", refBytes(init, "video/mp4"), bytes.NewReader(init))
	if e != nil {
		t.Fatal(e)
	}
	variant := Rendition{RenditionID: "rend_fixture", Width: 160, Height: 90, Bitrate: 100000, VideoCodec: "avc1"}
	for i := 0; i < 2; i++ {
		raw, e := os.ReadFile(filepath.Join(dir, fmt.Sprintf("index%d.m4s", i)))
		if e != nil {
			t.Fatal(e)
		}
		obj, e := s.Upload(ctx, r.SessionID, fmt.Sprintf("segment:rend_fixture:1:%d", i), refBytes(raw, "video/iso.segment"), bytes.NewReader(raw))
		if e != nil {
			t.Fatal(e)
		}
		seg := Segment{SessionID: r.SessionID, RenditionID: variant.RenditionID, SourceGeneration: 1, Sequence: int64(i), SessionStartMS: int64(i * 1000), DurationMS: 1000, Object: obj, Initialization: &ref}
		if e = s.ProbeMedia(ctx, variant, seg); e != nil {
			t.Fatal("fmp4 could not decode", i, e)
		}
		seg.DurationMS = 3000
		if e = s.ProbeMedia(ctx, variant, seg); e == nil {
			t.Fatal("invented duration accepted")
		}
	}
}

func TestOrphanTrackCleanupAndRetryCannotDeleteAnAttachedTrack(t *testing.T) {
	s, _ := fixture(t)
	r, _ := recording(t, s, false, 1)
	closeRecording(t, s, r)
	ctx := context.Background()
	body := []byte("WEBVTT\n\n00:00.000 --> 00:00.800\nHola\n")
	orphan, e := s.Upload(ctx, r.SessionID, "subtitle:sub_orphan", refBytes(body, "text/vtt"), bytes.NewReader(body))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Store.DB.ExecContext(ctx, "UPDATE vod_objects SET created_at=now()-interval '31 minutes' WHERE object_id=$1", orphan.ObjectID); e != nil {
		t.Fatal(e)
	}
	if e = s.Store.ScheduleOrphans(ctx); e != nil {
		t.Fatal(e)
	}
	if e = s.Store.WithObjectsLock(ctx, r.VodID, func() error {
		return s.Store.CleanOrphan(ctx, r.VodID, orphan.ObjectID, func(ref ObjectRef) error { return s.Objects.Delete(ctx, ref) })
	}); e != nil {
		t.Fatal(e)
	}
	if s.Objects.Verify(ctx, orphan) == nil {
		t.Fatal("orphan not cleaned")
	}
	restored, e := s.Upload(ctx, r.SessionID, "subtitle:sub_orphan", refBytes(body, "text/vtt"), bytes.NewReader(body))
	if e != nil || restored != orphan {
		t.Fatal("retry lost identity", e)
	}
	raw, _ := json.Marshal(orphan)
	if _, e = s.Store.DB.ExecContext(ctx, "INSERT INTO vod_assets(vod_id,path,ref,mapping_version) VALUES($1,'subtitles/sub_orphan.vtt',$2,1)", r.VodID, raw); e != nil {
		t.Fatal(e)
	}
	if e = s.Store.CleanOrphan(ctx, r.VodID, orphan.ObjectID, func(ObjectRef) error { t.Error("attached object deleted"); return nil }); e != nil {
		t.Fatal(e)
	}
	if e = s.Objects.Verify(ctx, orphan); e != nil {
		t.Fatal(e)
	}
}

func TestCatalogueFiltersCursorPrincipalAndConcurrentEditing(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	var recordings []Recording
	for i := 0; i < 3; i++ {
		r, _ := recording(t, s, i < 2, 1)
		closeRecording(t, s, r)
		_, e := s.Store.Command(ctx, r.VodID, "usr_fixture", ID("key_"), fmt.Sprint(i), "PATCH", func(current *Recording) (any, error) {
			current.Title = fmt.Sprintf("Tema edición %d", i)
			current.TagIDs = []string{"tag_fixture"}
			current.Version++
			return publicRecording(*current), nil
		})
		if e != nil {
			t.Fatal(e)
		}
		recordings = append(recordings, r)
	}
	first := call(s, "GET", "/api/vods?limit=1&categoryId=cat_fixture&tagId=tag_fixture", nil, nil)
	var page struct {
		Items []Recording `json:"items"`
		Next  *string     `json:"nextCursor"`
	}
	_ = json.Unmarshal(first.Body.Bytes(), &page)
	if first.Code != 200 || len(page.Items) != 1 || page.Next == nil {
		t.Fatal(first.Code, first.Body.String())
	}
	cursor := *page.Next
	path := "/api/vods?limit=1&categoryId=cat_fixture&tagId=tag_fixture&cursor=" + cursor
	next := call(s, "GET", path, nil, nil)
	var second struct {
		Items []Recording `json:"items"`
		Next  *string     `json:"nextCursor"`
	}
	_ = json.Unmarshal(next.Body.Bytes(), &second)
	if next.Code != 200 || len(second.Items) != 1 || second.Next != nil || page.Items[0].VodID == second.Items[0].VodID {
		t.Fatal(next.Code, next.Body.String())
	}
	if out := call(s, "GET", path, nil, map[string]string{"Authorization": "Bearer owner"}); out.Code != 410 {
		t.Fatal("cursor changed principal", out.Code)
	}
	if out := call(s, "GET", path+"&title=Otro", nil, nil); out.Code != 410 {
		t.Fatal("cursor changed filter", out.Code)
	}
	out := call(s, "GET", "/api/vods?title=TEMA", nil, nil)
	_ = json.Unmarshal(out.Body.Bytes(), &page)
	if out.Code != 200 || len(page.Items) != 2 {
		t.Fatal("partial title search", out.Code, out.Body.String())
	}
	out = call(s, "GET", "/api/me/vods", nil, map[string]string{"Authorization": "Bearer owner"})
	_ = json.Unmarshal(out.Body.Bytes(), &page)
	if out.Code != 200 || len(page.Items) != 3 {
		t.Fatal("own drafts missing", out.Code, out.Body.String())
	}
	r, _ := s.Store.Get(ctx, recordings[0].VodID)
	results := make(chan int, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			out := call(s, "PATCH", "/api/vods/"+r.VodID, map[string]any{"expectedVersion": r.Version, "title": fmt.Sprintf("Concurrente %d", i)}, map[string]string{"Authorization": "Bearer owner", "Idempotency-Key": ID("evt_")})
			results <- out.Code
		}(i)
	}
	wg.Wait()
	close(results)
	success, conflict := 0, 0
	for code := range results {
		if code == 200 {
			success++
		}
		if code == 409 {
			conflict++
		}
	}
	if success != 1 || conflict != 1 {
		t.Fatal("lost update", success, conflict)
	}
}
func TestAdministrativeRetirementSurvivesReactivationAndOwnerCanDelete(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	r, _ := recording(t, s, true, 1)
	closeRecording(t, s, r)
	r, _ = s.Store.Get(ctx, r.VodID)
	body, _ := json.Marshal(map[string]any{"vodId": r.VodID, "expectedVersion": r.Version, "reason": "Retirada de fixture"})
	req := httptest.NewRequest("POST", "https://vod/internal/vod/content-removals", bytes.NewReader(body))
	req.Header.Set("X-Service-Name", "gateway")
	req.Header.Set("X-Service-Token", "gateway-fixture")
	req.Header.Set("X-Session-Credential", "admin")
	req.Header.Set("Idempotency-Key", ID("evt_"))
	out := httptest.NewRecorder()
	s.InternalHandler("capture-fixture", "chat-fixture", "gateway-fixture").ServeHTTP(out, req)
	if out.Code != 202 {
		t.Fatal(out.Code, out.Body.String())
	}
	for i, status := range []string{"SUSPENDED", "ACTIVE"} {
		payload, _ := json.Marshal(map[string]any{"userId": "usr_fixture", "accountVersion": i + 1, "status": status})
		event := Envelope{EventID: ID("evt_"), EventType: "account.status.changed.v1", SchemaVersion: 1, Producer: "core", AggregateID: "account:usr_fixture", Sequence: int64(i + 1), OccurredAt: time.Now().UTC(), CorrelationID: "req_fixture", Payload: payload}
		raw, _ := json.Marshal(event)
		if e := s.Event(ctx, "vod.account", raw); e != nil {
			t.Fatal(e)
		}
	}
	r, _ = s.Store.Get(ctx, r.VodID)
	if r.Restriction != "RETIRED" || r.Public() {
		t.Fatal("reactivation restored retirement")
	}
	if got := call(s, "GET", r.PlaybackPath, nil, nil); got.Code != 404 {
		t.Fatal("retired URL public", got.Code)
	}
	if got := call(s, "DELETE", "/api/vods/"+r.VodID, nil, map[string]string{"Authorization": "Bearer owner", "If-Match": fmt.Sprint(r.Version), "Idempotency-Key": ID("evt_")}); got.Code != 202 {
		t.Fatal("owner could not clean retired VOD", got.Code, got.Body.String())
	}
}
