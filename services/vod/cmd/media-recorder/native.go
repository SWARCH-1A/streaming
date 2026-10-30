package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"streaming/vod/internal/vod"
	"time"
)

// Private, Media-owned disk inventory emitted synchronously by the pinned engine.
// No HTTP client or VOD process can write or enumerate this volume.
type nativeEntry struct {
	Sequence   int64     `json:"sequence"`
	File       string    `json:"file"`
	Started    time.Time `json:"startedAtUtc"`
	DurationMS int64     `json:"durationMs"`
	Size       int64     `json:"byteLength"`
	Hash       string    `json:"sha256"`
	Complete   bool      `json:"complete"`
}
type nativeInventory struct {
	Version  int           `json:"version"`
	Closed   bool          `json:"closed"`
	Failure  string        `json:"failure"`
	Segments []nativeEntry `json:"segments"`
}

var nativeName = regexp.MustCompile(`^[0-9]+-[0-9]{6}\.ts$`)

func (r *recorder) captureNative(src source) error {
	if src.StartedAt == nil {
		return nil
	}
	if src.InitialSourceGeneration < 1 || src.SourceGeneration < src.InitialSourceGeneration {
		return r.nativeFailure(src.Start.SessionID, "CAPTURE_GENERATION_RANGE_INVALID")
	}
	for generation := src.InitialSourceGeneration; generation <= src.SourceGeneration; generation++ {
		dir := filepath.Join(r.nativeDir, src.Start.SessionID, strconv.FormatInt(generation, 10), "rend_source")
		raw, e := os.ReadFile(filepath.Join(dir, "index.json"))
		if os.IsNotExist(e) {
			continue
		}
		if e != nil {
			return e
		}
		var inv nativeInventory
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.DisallowUnknownFields()
		var tail any
		if len(raw) > 16<<20 || decoder.Decode(&inv) != nil || decoder.Decode(&tail) != io.EOF || inv.Version != 1 {
			return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_INVENTORY_INVALID")
		}
		if inv.Failure != "" {
			return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_RECORDING_FAILED")
		}
		key := fmt.Sprintf("rend_source:%d", generation)
		r.mu.Lock()
		s := r.states[src.Start.SessionID]
		if s.Expected == nil {
			s.Expected = map[string]bool{}
		}
		s.Expected[key] = true
		r.mu.Unlock()
		var position int64
		if len(inv.Segments) > 0 {
			position = inv.Segments[0].Started.Sub(*src.StartedAt).Milliseconds()
		}
		for i, entry := range inv.Segments {
			if entry.Sequence != int64(i) || !nativeName.MatchString(entry.File) || entry.Started.IsZero() {
				return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_IDENTITY_INVALID")
			}
			if !entry.Complete {
				if inv.Closed {
					return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_OBJECT_INCOMPLETE")
				}
				break
			}
			if entry.DurationMS < 1 || entry.DurationMS > 60000 || entry.Size < 1 || entry.Size > vod.MaxSegmentBytes {
				return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_OBJECT_INVALID")
			}
			// Media DTS provides the continuous clock within a generation. Native NTP
			// timestamps may arrive in bursts (e.g. RTMP codec discovery); only the
			// first anchors this generation. MediaMTX rejects/reset drifts over 5 s;
			// its recorder restart is retained as failure in the durable inventory.
			start := position
			position += entry.DurationMS
			r.mu.Lock()
			next := s.Next[key]
			if entry.Sequence < next {
				// A closed file's identity and bytes cannot change after checkpointing.
				for _, p := range s.Segments {
					if p.Segment.SourceGeneration == generation && p.Segment.Sequence == entry.Sequence && (p.Hash != entry.Hash || p.Size != entry.Size || p.Segment.DurationMS != entry.DurationMS || p.Segment.SessionStartMS != start) {
						r.mu.Unlock()
						return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_IDENTITY_CONFLICT")
					}
				}
				r.mu.Unlock()
				continue
			}
			if s.Closed || s.Failure != "" {
				r.mu.Unlock()
				return nil
			}
			r.mu.Unlock()
			file := filepath.Join(dir, entry.File)
			data, e := os.ReadFile(file)
			if e != nil {
				return e
			}
			if int64(len(data)) != entry.Size || hash(data) != entry.Hash {
				return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_CHECKSUM_CONFLICT")
			}
			rendition, e := probeNative(r.ctx, file, entry.Size, entry.DurationMS)
			if e != nil {
				return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_MEDIA_INVALID")
			}
			if e = r.space(entry.Size); e != nil {
				return r.nativeFailure(src.Start.SessionID, "CAPTURE_SPOOL_FULL")
			}
			copyFile := filepath.Join(r.dir, hash([]byte(src.Start.SessionID+":"+key+":"+strconv.FormatInt(entry.Sequence, 10)))+".media")
			if e = atomicFile(copyFile, data); e != nil {
				return e
			}
			segment := vod.Segment{SessionID: src.Start.SessionID, RenditionID: "rend_source", SourceGeneration: generation, Sequence: entry.Sequence, SessionStartMS: start, DurationMS: entry.DurationMS, Discontinuity: generation > src.InitialSourceGeneration && i == 0, Object: vod.ObjectRef{ByteLength: entry.Size, SHA256: entry.Hash, ContentType: "video/mp2t"}}
			r.mu.Lock()
			if s.Closed || s.Failure != "" {
				r.mu.Unlock()
				_ = os.Remove(copyFile)
				return nil
			}
			previous, exists := s.Renditions["rend_source"]
			if exists && (previous.Width != rendition.Width || previous.Height != rendition.Height || previous.Codecs() != rendition.Codecs()) {
				r.mu.Unlock()
				return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_RENDITION_CHANGED")
			}
			if !exists {
				s.Renditions["rend_source"] = rendition
			} else if rendition.Bitrate > previous.Bitrate {
				previous.Bitrate = rendition.Bitrate
				s.Renditions["rend_source"] = previous
			}
			s.Segments = append(s.Segments, pending{Segment: segment, File: copyFile, Hash: entry.Hash, Size: entry.Size, EventID: uuid("segment:" + segment.Key())})
			s.Next[key] = entry.Sequence + 1
			e = r.save(s)
			r.mu.Unlock()
			if e != nil {
				return e
			}
			// Native bytes may be discarded only after their fsynced capture copy.
			if e = os.Remove(file); e != nil && !os.IsNotExist(e) {
				return e
			}
		}
		r.mu.Lock()
		if inv.Closed && len(inv.Segments) > 0 && s.Next[key] == int64(len(inv.Segments)) {
			if s.Finalized == nil {
				s.Finalized = map[string]bool{}
			}
			s.Finalized[key] = true
		}
		e = r.save(s)
		r.mu.Unlock()
		if e != nil {
			return e
		}
	}
	return nil
}
func (r *recorder) nativeFailure(session, code string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	s := r.states[session]
	if s.Failure == "" {
		s.Failure = code
	}
	if e := r.save(s); e != nil {
		return e
	}
	return fmt.Errorf("native capture failed")
}
func probeNative(ctx context.Context, file string, size, duration int64) (vod.Rendition, error) {
	budget, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	raw, e := exec.CommandContext(budget, "ffprobe", "-v", "error", "-protocol_whitelist", "file,crypto,data", "-show_entries", "stream=codec_type,codec_name,width,height", "-of", "json", file).Output()
	var result struct {
		Streams []struct {
			Type   string `json:"codec_type"`
			Codec  string `json:"codec_name"`
			Width  int    `json:"width"`
			Height int    `json:"height"`
		} `json:"streams"`
	}
	r := vod.Rendition{RenditionID: "rend_source", Bitrate: size * 8000 / duration}
	if e != nil || json.Unmarshal(raw, &result) != nil {
		return r, fmt.Errorf("native probe failed")
	}
	video, audio := 0, 0
	for _, stream := range result.Streams {
		switch stream.Type {
		case "video":
			video++
			r.Width = stream.Width
			r.Height = stream.Height
			if stream.Codec != "h264" {
				return r, fmt.Errorf("unsupported native video")
			}
			r.VideoCodec = "avc1"
		case "audio":
			audio++
			if stream.Codec != "aac" {
				return r, fmt.Errorf("unsupported native audio")
			}
			codec := "mp4a.40.2"
			r.AudioCodec = &codec
		}
	}
	if video != 1 || audio > 1 || r.Width < 1 || r.Height < 1 || r.Bitrate < 1 {
		return r, fmt.Errorf("native streams invalid")
	}
	return r, nil
}
func (r *recorder) cleanupNative(s *state) error {
	if r.nativeDir == "" || !vod.ValidID(s.Start.SessionID) {
		return nil
	}
	// A DELETE can precede native Close. Retain the directory while the producer
	// writes it; later polls of the tombstone finish the cleanup after sealing.
	// Completed checkpoints from before initialSourceGeneration may still need cleanup.
	for generation := max(int64(1), s.FirstSourceGeneration); generation <= s.LastSourceGeneration; generation++ {
		dir := filepath.Join(r.nativeDir, s.Start.SessionID, strconv.FormatInt(generation, 10), "rend_source")
		raw, e := os.ReadFile(filepath.Join(dir, "index.json"))
		if os.IsNotExist(e) {
			continue
		}
		if e != nil {
			return e
		}
		var inv nativeInventory
		if json.Unmarshal(raw, &inv) != nil {
			return fmt.Errorf("native cleanup inventory invalid")
		}
		if inv.Closed || (s.Ended && s.Acknowledged) {
			if e = os.RemoveAll(dir); e != nil {
				return e
			}
		}
	}
	return nil
}

func (r *recorder) retireNative(session string) error {
	if r.nativeDir == "" || !vod.ValidID(session) {
		return nil
	}
	dir := filepath.Join(r.nativeDir, session)
	if e := os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	marker := filepath.Join(dir, "retired")
	if _, e := os.Stat(marker); e == nil {
		return nil
	}
	// Only a trusted VOD 410 creates this permanent capture tombstone. The native
	// reader stops at its next buffer write while the independent live reader runs.
	return atomicFile(marker, []byte("VOD_DELETED\n"))
}
