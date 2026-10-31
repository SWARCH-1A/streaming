package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"streaming/vod/internal/vod"
	"testing"
	"time"
)

func nativeMedia(t *testing.T) []byte {
	t.Helper()
	file := filepath.Join(t.TempDir(), "fixture.ts")
	raw, e := exec.Command("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:size=160x90:rate=25", "-t", "1", "-c:v", "libx264", "-threads", "1", "-preset", "ultrafast", "-g", "25", "-pix_fmt", "yuv420p", "-f", "mpegts", file).CombinedOutput()
	if e != nil {
		t.Fatalf("media fixture: %v %s", e, raw)
	}
	raw, e = os.ReadFile(file)
	if e != nil {
		t.Fatal(e)
	}
	return raw
}

func TestCaptureCannotUploadBytesDifferentFromItsCheckpoint(t *testing.T) {
	r := &recorder{}
	file := filepath.Join(t.TempDir(), "fixture.media")
	original := []byte("confirmed captured bytes")
	if e := os.WriteFile(file, []byte("different bytes"), 0600); e != nil {
		t.Fatal(e)
	}
	_, e := r.uploadObject("ses_fixture", "segment:rend_source:1:0", file, "video/mp2t", vod.Segment{Object: vod.ObjectRef{ByteLength: int64(len(original)), SHA256: hash(original)}})
	if !errors.Is(e, errCaptureBytes) {
		t.Fatal("corrupt capture reached upload")
	}
}

func TestNativeVODTombstoneRetiresCaptureDurably(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusGone) }))
	defer server.Close()
	s := &state{Start: vod.Start{SessionID: "ses_fixture"}}
	r := &recorder{ctx: ctx, dir: t.TempDir(), nativeDir: t.TempDir(), destination: server.URL, captureToken: "fixture", client: server.Client(), states: map[string]*state{"ses_fixture": s}}
	done := make(chan struct{})
	go func() { defer close(done); r.uploadLoop() }()
	defer func() { cancel(); <-done }()
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		if _, e := os.Stat(filepath.Join(r.nativeDir, "ses_fixture", "retired")); e == nil {
			r.mu.Lock()
			retired := s.Closed && s.Retired
			r.mu.Unlock()
			if !retired {
				t.Fatal("native retirement preceded trusted tombstone")
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("trusted 410 did not stop native capture")
}

func TestCaptureCleanupRetainsInventoryUntilFilesAreRemoved(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "fixture.media")
	if e := os.WriteFile(file, []byte("fixture"), 0600); e != nil {
		t.Fatal(e)
	}
	s := &state{Start: vod.Start{SessionID: "ses_fixture"}, Closed: true, Segments: []pending{{File: file}}}
	r := &recorder{dir: dir, states: map[string]*state{"ses_fixture": s}}
	if e := r.save(s); e != nil {
		t.Fatal(e)
	}
	if e := os.Chmod(dir, 0500); e != nil {
		t.Fatal(e)
	}
	defer os.Chmod(dir, 0700)
	if r.cleanup(s) == nil || len(s.Segments) != 1 {
		t.Fatal("failed cleanup discarded durable inventory")
	}
	if e := os.Chmod(dir, 0700); e != nil {
		t.Fatal(e)
	}
	if e := r.cleanup(s); e != nil {
		t.Fatal(e)
	}
	if len(s.Segments) != 0 {
		t.Fatal("completed cleanup did not release inventory")
	}
	if _, e := os.Stat(file); !os.IsNotExist(e) {
		t.Fatal("capture file survived cleanup")
	}
}
func TestNativeCaptureSurvivesRestartAndRequiresProducerClose(t *testing.T) {
	start := time.Date(2026, 10, 29, 12, 0, 0, 0, time.UTC)
	src := source{Start: vod.Start{SessionID: "ses_fixture", StreamID: "str_fixture", StreamGeneration: 2}, InitialSourceGeneration: 7, SourceGeneration: 7, StartedAt: &start}
	s := &state{Start: src.Start, FirstSourceGeneration: 7, LastSourceGeneration: 7, Renditions: map[string]vod.Rendition{}, Next: map[string]int64{}, Finalized: map[string]bool{}}
	r := &recorder{ctx: context.Background(), dir: t.TempDir(), nativeDir: t.TempDir(), limit: 1 << 20, states: map[string]*state{src.Start.SessionID: s}}
	dir := filepath.Join(r.nativeDir, src.Start.SessionID, "7", "rend_source")
	if e := os.MkdirAll(dir, 0700); e != nil {
		t.Fatal(e)
	}
	data := nativeMedia(t)
	inv := nativeInventory{Version: 1, Segments: []nativeEntry{{Sequence: 0, File: "1793275199-750000.ts", Started: start.Add(-250 * time.Millisecond), DurationMS: 1000, Size: int64(len(data)), Hash: hash(data), Complete: true}}}
	write := func() {
		t.Helper()
		raw, _ := json.Marshal(inv)
		if e := atomicFile(filepath.Join(dir, "index.json"), raw); e != nil {
			t.Fatal(e)
		}
	}
	if e := os.WriteFile(filepath.Join(dir, inv.Segments[0].File), data, 0600); e != nil {
		t.Fatal(e)
	}
	write()
	if e := r.captureNative(src); e != nil {
		t.Fatal(e)
	}
	if len(s.Segments) != 1 || s.Finalized["rend_source:7"] || s.Segments[0].Sent || s.Segments[0].Segment.SessionStartMS != -250 {
		t.Fatal("native capture or unsealed generation state invalid")
	}
	checkpoint, e := os.ReadFile(filepath.Join(r.dir, src.Start.SessionID+".json"))
	if e != nil {
		t.Fatal(e)
	}
	var restored state
	if json.Unmarshal(checkpoint, &restored) != nil {
		t.Fatal("bad checkpoint")
	}
	r.states[src.Start.SessionID] = &restored
	inv.Closed = true
	write()
	if e = r.captureNative(src); e != nil {
		t.Fatal(e)
	}
	if len(restored.Segments) != 1 || !restored.Finalized["rend_source:7"] {
		t.Fatal("restart lost sequence or close")
	}
	index := vod.Index{SessionID: src.Start.SessionID, StreamGeneration: src.Start.StreamGeneration, Renditions: []vod.Rendition{restored.Renditions["rend_source"]}, Segments: []vod.Segment{restored.Segments[0].Segment}}
	index.Segments[0].Object.ObjectID = "obj_fixture"
	timeline, e := vod.BuildTimeline(vod.Recording{Start: src.Start}, index)
	if e != nil || timeline.MapOffset(0) != 250 || timeline.DurationMS != 1000 {
		t.Fatalf("native clock: %+v %v", timeline, e)
	}
	inv.Segments[0].Hash = hash([]byte("different bytes"))
	write()
	if r.captureNative(src) == nil || restored.Failure != "CAPTURE_NATIVE_IDENTITY_CONFLICT" {
		t.Fatal("changed native identity accepted")
	}
}

func TestAvcCodecUsesProfileAndLevel(t *testing.T) {
	for _, tc := range []struct {
		profile string
		level   int
		want    string
	}{{"Constrained Baseline", 30, "avc1.42c01e"}, {"Main", 31, "avc1.4d401f"}, {"High", 40, "avc1.640028"}} {
		if got, ok := avcCodec(tc.profile, tc.level); !ok || got != tc.want {
			t.Errorf("%s %d: %q %v", tc.profile, tc.level, got, ok)
		}
	}
	for _, bad := range []struct {
		profile string
		level   int
	}{{"", 30}, {"High", 0}, {"Unknown", 30}} {
		if _, ok := avcCodec(bad.profile, bad.level); ok {
			t.Errorf("%+v debe rechazarse", bad)
		}
	}
}
