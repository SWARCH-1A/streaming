package vod

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"time"
)

const maxThumbnailBytes = 512 << 10

// A bounded, disposable cache. Authorization is checked before every lookup, including hits.
// Serial generation limits FFmpeg CPU use and coalesces concurrent requests for the same image.
type thumbnailCache struct {
	once  sync.Once
	gate  chan struct{}
	items map[string][]byte
	order []string
}

func (c *thumbnailCache) get(ctx context.Context, key string, build func() ([]byte, error)) ([]byte, error) {
	c.once.Do(func() { c.gate = make(chan struct{}, 1); c.items = map[string][]byte{} })
	select {
	case c.gate <- struct{}{}:
		defer func() { <-c.gate }()
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	if image, ok := c.items[key]; ok {
		return image, nil
	}
	image, err := build()
	if err != nil {
		return nil, err
	}
	if len(c.order) == 32 {
		delete(c.items, c.order[0])
		c.order = c.order[1:]
	}
	c.items[key] = image
	c.order = append(c.order, key)
	return image, nil
}

func (s *Service) thumbnail(w http.ResponseWriter, r *http.Request) error {
	ctx, cancel := context.WithTimeout(r.Context(), 7*time.Second)
	defer cancel()
	rec, err := s.Store.Get(ctx, r.PathValue("vodId"))
	if err != nil {
		return err
	}
	cred, err := credential(r)
	if err != nil {
		return err
	}
	if _, err = s.access(ctx, rec, cred, false); err != nil {
		return err
	}
	if rec.Processing != "READY" {
		return Fail(404, "THUMBNAIL_UNAVAILABLE")
	}
	key := fmt.Sprintf("%s:%d:%d", rec.VodID, rec.MappingVersion, rec.Version)
	image, err := s.thumbnails.get(ctx, key, func() ([]byte, error) {
		index, err := s.index(ctx, rec)
		if err != nil {
			return nil, err
		}
		segment, err := thumbnailSegment(index)
		if err != nil {
			return nil, err
		}
		return s.renderThumbnail(ctx, segment)
	})
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Length", fmt.Sprint(len(image)))
	_, err = w.Write(image)
	return err
}

func thumbnailSegment(index Index) (Segment, error) {
	// Prefer a source near 640px to avoid decoding the largest quality just for a card.
	var selected *Rendition
	distance := func(width int) int {
		if width >= 640 {
			return width - 640
		}
		return (640 - width) * 2
	}
	for i := range index.Renditions {
		r := &index.Renditions[i]
		if selected == nil || distance(r.Width) < distance(selected.Width) {
			selected = r
		}
	}
	var first *Segment
	if selected != nil {
		for i := range index.Segments {
			seg := &index.Segments[i]
			if seg.RenditionID == selected.RenditionID && seg.SessionStartMS+seg.DurationMS > 0 &&
				(first == nil || seg.SessionStartMS < first.SessionStartMS) {
				first = seg
			}
		}
	}
	if first == nil {
		return Segment{}, Fail(404, "THUMBNAIL_UNAVAILABLE")
	}
	return *first, nil
}

type thumbnailBuffer struct{ bytes.Buffer }

func (b *thumbnailBuffer) Write(p []byte) (int, error) {
	if b.Len()+len(p) > maxThumbnailBytes {
		return 0, Fail(503, "THUMBNAIL_TOO_LARGE")
	}
	return b.Buffer.Write(p)
}

func (s *Service) renderThumbnail(ctx context.Context, segment Segment) ([]byte, error) {
	file, err := os.CreateTemp("", "vod-thumbnail-*.mp4")
	if err != nil {
		return nil, err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	refs := []ObjectRef{}
	if segment.Initialization != nil {
		refs = append(refs, *segment.Initialization)
	}
	refs = append(refs, segment.Object)
	for _, ref := range refs {
		if !ref.Valid() || ref.ByteLength > MaxSegmentBytes {
			return nil, Fail(422, "OBJECT_INVALID")
		}
		body, _, err := s.Objects.Get(ctx, ref, "")
		if err != nil {
			return nil, err
		}
		hash := sha256.New()
		n, err := io.Copy(io.MultiWriter(file, hash), io.LimitReader(body, ref.ByteLength+1))
		body.Close()
		if err != nil {
			return nil, err
		}
		if n != ref.ByteLength || hex.EncodeToString(hash.Sum(nil)) != ref.SHA256 {
			return nil, Fail(422, "OBJECT_CHECKSUM_CONFLICT")
		}
	}
	if err = file.Close(); err != nil {
		return nil, err
	}
	// Only the verified local segment is decoded; FFmpeg cannot open network URLs.
	command := exec.CommandContext(ctx, "ffmpeg", "-nostdin", "-v", "error", "-protocol_whitelist", "file,crypto,data",
		"-threads", "1", "-i", file.Name(), "-map", "0:v:0", "-frames:v", "1", "-an",
		"-vf", "scale=640:360:force_original_aspect_ratio=decrease", "-q:v", "4", "-threads", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1")
	var output thumbnailBuffer
	command.Stdout = &output
	if err = command.Run(); err != nil || output.Len() < 4 {
		return nil, Fail(503, "THUMBNAIL_UNAVAILABLE")
	}
	return output.Bytes(), nil
}
