package vod

import (
	"bytes"
	"context"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

type probeFixtureObjects struct {
	Objects
	data []byte
}

func (o probeFixtureObjects) Get(context.Context, ObjectRef, string) (io.ReadCloser, int64, error) {
	return io.NopCloser(bytes.NewReader(o.data)), int64(len(o.data)), nil
}

func TestProbeOnlyAllowsMissingAACInVerifiedShortGenerationTail(t *testing.T) {
	file := filepath.Join(t.TempDir(), "tail.ts")
	raw, err := exec.Command("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:size=160x90:rate=25", "-frames:v", "1", "-c:v", "libx264", "-threads", "1", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-f", "mpegts", file).CombinedOutput()
	if err != nil {
		t.Fatalf("ffmpeg fixture: %v %s", err, raw)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	service := &Service{Objects: probeFixtureObjects{data: data}}
	audio := "mp4a.40.2"
	rendition := Rendition{Width: 160, Height: 90, VideoCodec: "avc1.42c01e", AudioCodec: &audio}
	segment := Segment{DurationMS: 40, Object: ObjectRef{ByteLength: int64(len(data))}}
	if err = service.ProbeMedia(context.Background(), rendition, segment); err == nil {
		t.Fatal("interior audio loss accepted")
	}
	terminal := context.WithValue(context.Background(), generationTailKey{}, true)
	if err = service.ProbeMedia(terminal, rendition, segment); err != nil {
		t.Fatal("actual sealed single-frame tail rejected", err)
	}
	segment.DurationMS = 251
	if err = service.ProbeMedia(terminal, rendition, segment); err == nil {
		t.Fatal("unbounded audio loss accepted")
	}
}
