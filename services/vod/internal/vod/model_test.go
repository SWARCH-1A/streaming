package vod

import (
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestCompactionKeepsAllRenditionsAndMapsGapMessagesToSeam(t *testing.T) {
	r := Recording{VodID: "vod_fixture", Start: Start{SessionID: "ses_fixture", StreamGeneration: 1}}
	index := Index{SessionID: r.SessionID, StreamGeneration: 1}
	for n := 0; n < 4; n++ {
		id := fmt.Sprintf("rend_%d", n)
		index.Renditions = append(index.Renditions, Rendition{RenditionID: id, Width: 320, Height: 180, Bitrate: 100000, VideoCodec: "avc1"})
		for i, offset := range []int64{0, 33000} {
			index.Segments = append(index.Segments, Segment{SessionID: r.SessionID, RenditionID: id, SourceGeneration: int64(i + 1), Sequence: 0, SessionStartMS: offset, DurationMS: 1000, Discontinuity: i > 0, Object: ObjectRef{"obj_fixture", 100, strings.Repeat("a", 64), "video/mp2t"}})
		}
	}
	timeline, e := BuildTimeline(r, index)
	if e != nil {
		t.Fatal(e)
	}
	if timeline.DurationMS != 2000 || timeline.MapOffset(500) != 500 || timeline.MapOffset(15000) != 1000 || timeline.MapOffset(33500) != 1500 || timeline.MapOffset(40000) != 1999 {
		t.Fatalf("wrong compacted mapping: %+v", timeline)
	}
	index.Segments[len(index.Segments)-1].DurationMS = 900
	if _, e = BuildTimeline(r, index); e == nil {
		t.Fatal("different rendition coverage must fail")
	}
}
func TestMissingCoverageCannotBeCompactedWithoutProducerDiscontinuity(t *testing.T) {
	r := Recording{VodID: "vod_fixture", Start: Start{SessionID: "ses_fixture", StreamGeneration: 1}}
	ref := ObjectRef{"obj_fixture", 100, strings.Repeat("b", 64), "video/mp2t"}
	index := Index{SessionID: r.SessionID, StreamGeneration: 1, Renditions: []Rendition{{RenditionID: "rend_fixture", Width: 320, Height: 180, Bitrate: 100000, VideoCodec: "avc1"}}, Segments: []Segment{{SessionID: r.SessionID, RenditionID: "rend_fixture", SourceGeneration: 1, Sequence: 0, DurationMS: 1000, Object: ref}, {SessionID: r.SessionID, RenditionID: "rend_fixture", SourceGeneration: 1, Sequence: 2, SessionStartMS: 2000, DurationMS: 1000, Object: ref}}}
	if _, e := BuildTimeline(r, index); e == nil {
		t.Fatal("unexplained missing interval accepted")
	}
	index.Segments[1].Discontinuity = true
	index.Segments[1].SourceGeneration = 2
	if _, e := BuildTimeline(r, index); e != nil {
		t.Fatal(e)
	}
	index.Segments = append(index.Segments, index.Segments[1])
	if _, e := BuildTimeline(r, index); e == nil {
		t.Fatal("duplicate segment accepted")
	}
}
func TestPreparingPrefixPreservesBytesWithoutMovingChatClock(t *testing.T) {
	r := Recording{VodID: "vod_fixture", Start: Start{SessionID: "ses_fixture", StreamGeneration: 1}}
	ref := ObjectRef{"obj_fixture", 100, strings.Repeat("a", 64), "video/mp2t"}
	index := Index{SessionID: r.SessionID, StreamGeneration: 1, Renditions: []Rendition{{RenditionID: "rend_fixture", Width: 320, Height: 180, Bitrate: 100000, VideoCodec: "avc1"}}}
	for n, offset := range []int64{-1500, -500, 500} {
		index.Segments = append(index.Segments, Segment{SessionID: r.SessionID, RenditionID: "rend_fixture", SourceGeneration: 7, Sequence: int64(n), SessionStartMS: offset, DurationMS: 1000, Object: ref})
	}
	timeline, e := BuildTimeline(r, index)
	if e != nil {
		t.Fatal(e)
	}
	if timeline.DurationMS != 3000 || timeline.Intervals[0].SessionStartMS != 0 || timeline.Intervals[0].MediaStartMS != 1500 || timeline.MapOffset(0) != 1500 || timeline.MapOffset(1000) != 2500 {
		t.Fatalf("preparing prefix shifted Chat: %+v", timeline)
	}
	index.Segments[0].SourceGeneration = 8
	if _, e = BuildTimeline(r, index); e == nil {
		t.Fatal("negative reconnect clock accepted")
	}
}
func TestWebVTTBoundedProfile(t *testing.T) {
	valid := "WEBVTT\n\n00:00.000 --> 00:01.000\nHola &amp; mundo\n"
	if e := ValidateWebVTT([]byte(valid), 1000); e != nil {
		t.Fatal(e)
	}
	for _, raw := range []string{strings.Replace(valid, "00:01.000", "00:02.000", 1), strings.Replace(valid, "Hola &amp; mundo", "<b>markup</b>", 1), strings.Replace(valid, "WEBVTT", "WEBVTT -->", 1), "WEBVTT\n\nSTYLE\n::cue {color:red}\n", valid + "\n00:00.500 --> 00:00.400\nInverso\n"} {
		if e := ValidateWebVTT([]byte(raw), 1000); e == nil {
			t.Fatalf("invalid WebVTT accepted: %q", raw)
		}
	}
}
func TestPlaybackTicketsArePurposeBoundAndTamperProof(t *testing.T) {
	s := &Service{Secret: []byte(strings.Repeat("secret", 8))}
	g := grant{VodID: "vod_fixture", Version: 2, MappingVersion: 1, Exp: time.Now().Add(time.Minute).Unix()}
	token := s.seal("playback", g)
	var read grant
	if s.unseal("playback", token, &read) != nil || read != g {
		t.Fatal("valid ticket failed")
	}
	if s.unseal("cursor", token, &read) == nil || s.unseal("playback", token+"x", &read) == nil {
		t.Fatal("cross-purpose or altered ticket accepted")
	}
}
func TestRangesAndCookieCsrf(t *testing.T) {
	for _, tc := range []struct {
		header     string
		start, end int64
	}{{"bytes=0-3", 0, 3}, {"bytes=2-", 2, 9}, {"bytes=-4", 6, 9}} {
		a, b, e := byteRange(tc.header, 10)
		if e != nil || a != tc.start || b != tc.end {
			t.Fatal(tc, e)
		}
	}
	for _, header := range []string{"bytes=99-", "bytes=2-1", "bytes=1-2,3-4", "bytes=-0"} {
		if _, _, e := byteRange(header, 10); e == nil {
			t.Fatal("invalid range accepted")
		}
	}
	r := httptest.NewRequest("POST", "https://example.test/api/vods/vod_fixture/publication", nil)
	r.Header.Set("X-XSRF-TOKEN", strings.Repeat("a", 32))
	if validCSRF(r) {
		t.Fatal("missing csrf cookie accepted")
	}
	r.Header.Set("Cookie", "XSRF-TOKEN="+strings.Repeat("a", 32))
	if !validCSRF(r) {
		t.Fatal("matching double-submit token rejected")
	}
}
