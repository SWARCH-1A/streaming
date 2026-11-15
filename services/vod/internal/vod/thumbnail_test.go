package vod

import (
	"bytes"
	"context"
	"image/jpeg"
	"testing"
)

func TestThumbnailDecodesVerifiedRecordingSegment(t *testing.T) {
	raw := video(t, 1280, 720)
	s := &Service{Objects: probeFixtureObjects{data: raw}}
	segment := Segment{Object: refBytes(raw, "video/mp2t")}
	segment.Object.ObjectID = "obj_fixture"
	image, err := s.renderThumbnail(context.Background(), segment)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := jpeg.Decode(bytes.NewReader(image))
	if err != nil || decoded.Bounds().Dx() != 640 || decoded.Bounds().Dy() != 360 {
		t.Fatal("invalid poster", err)
	}
	segment.Object.SHA256 = string(bytes.Repeat([]byte("0"), 64))
	if _, err = s.renderThumbnail(context.Background(), segment); err == nil {
		t.Fatal("decoded corrupted source")
	}
}

func TestThumbnailCacheBoundsAndDoesNotCacheFailures(t *testing.T) {
	var cache thumbnailCache
	calls := 0
	build := func() ([]byte, error) { calls++; return []byte("jpeg"), nil }
	for range 2 {
		if _, err := cache.get(context.Background(), "fixture", build); err != nil {
			t.Fatal(err)
		}
	}
	if calls != 1 {
		t.Fatal("duplicate render")
	}
	for i := range 40 {
		_, _ = cache.get(context.Background(), string(rune(i)), build)
	}
	if len(cache.items) != 32 {
		t.Fatal("unbounded cache")
	}
	_, _ = cache.get(context.Background(), "failed", func() ([]byte, error) { return nil, Fail(503, "UNAVAILABLE") })
	if _, ok := cache.items["failed"]; ok {
		t.Fatal("cached failure")
	}
}
