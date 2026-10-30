package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"streaming/vod/internal/vod"
	"testing"
)

func TestCapturePersistsBeforeUploadAndFencesGeneration(t *testing.T) {
	content := []byte("actual-fixture-segment-bytes")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Source-Generation") != "1" {
			t.Error("generation absent")
		}
		if r.Header.Get("Authorization") != "Bearer fixture" {
			t.Error("credential absent")
		}
		switch r.URL.Path {
		case "/hls/ses_fixture/index.m3u8":
			_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=160x90,CODECS=\"avc1\"\nvariant.m3u8\n"))
		case "/hls/ses_fixture/variant.m3u8":
			_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PROGRAM-DATE-TIME:2026-10-29T12:00:00Z\n#EXTINF:1.000,\nsegment.ts\n#EXT-X-ENDLIST\n"))
		case "/hls/ses_fixture/segment.ts":
			_, _ = w.Write(content)
		default:
			w.WriteHeader(404)
		}
	}))
	defer server.Close()
	var src source
	if e := json.Unmarshal([]byte(`{"start":{"sessionId":"ses_fixture","streamId":"str_fixture","channelId":"chn_fixture","streamGeneration":1},"sourceGeneration":1,"startedAtUtc":"2026-10-29T12:00:00Z"}`), &src); e != nil {
		t.Fatal(e)
	}
	s := &state{Start: src.Start, Renditions: map[string]vod.Rendition{}, Next: map[string]int64{}, Correction: map[int64]int64{}, Finalized: map[string]bool{}}
	r := &recorder{ctx: context.Background(), dir: t.TempDir(), media: server.URL, streaming: server.URL, client: server.Client(), streamingToken: "fixture", limit: 1 << 20, states: map[string]*state{"ses_fixture": s}}
	if e := r.capture(src); e != nil {
		t.Fatal(e)
	}
	if e := r.capture(src); e != nil {
		t.Fatal(e)
	}
	if len(s.Segments) != 1 || s.Segments[0].Sent || s.Segments[0].Segment.Object.ObjectID != "" {
		t.Fatal("capture claimed upload or duplicated")
	}
	raw, e := os.ReadFile(s.Segments[0].File)
	if e != nil || string(raw) != string(content) {
		t.Fatal(e)
	}
	checkpoint, e := os.ReadFile(filepath.Join(r.dir, "ses_fixture.json"))
	if e != nil {
		t.Fatal(e)
	}
	var restored state
	if json.Unmarshal(checkpoint, &restored) != nil || len(restored.Segments) != 1 || len(restored.Finalized) != 1 {
		t.Fatal("checkpoint incomplete")
	}
}
func TestPlaylistRejectsUnclockedMediaAndURIEscape(t *testing.T) {
	if _, e := playlist("#EXTM3U\n#EXTINF:1,\nsegment.ts\n"); e == nil {
		t.Fatal("unclocked source accepted")
	}
	for _, target := range []string{"https://elsewhere.invalid/a.ts", "../other/a.ts", "%2e%2e/a.ts", "segment.ts?credential=x"} {
		if _, e := resolve("https://media/hls/ses_fixture/index.m3u8", target, "ses_fixture"); e == nil {
			t.Fatal(target)
		}
	}
}
