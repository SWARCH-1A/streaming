package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"streaming/vod/internal/vod"
	"strings"
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
	Version    int           `json:"version"`
	Renditions []string      `json:"renditions,omitempty"`
	Closed     bool          `json:"closed"`
	Failure    string        `json:"failure"`
	Segments   []nativeEntry `json:"segments"`
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
		for _, renditionID := range []string{"rend_source", "rend_0", "rend_1", "rend_2", "rend_3"} {
			dir := filepath.Join(r.nativeDir, src.Start.SessionID, strconv.FormatInt(generation, 10), renditionID)
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
			plan := inv.Renditions
			if len(plan) == 0 {
				plan = []string{"rend_source"}
			}
			if !validNativePlan(plan, renditionID) {
				return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_PLAN_INVALID")
			}
			key := fmt.Sprintf("%s:%d", renditionID, generation)
			r.mu.Lock()
			s := r.states[src.Start.SessionID]
			if s.Expected == nil {
				s.Expected = map[string]bool{}
			}
			for expected := range s.Expected {
				id, gen, found := strings.Cut(expected, ":")
				if found && gen == strconv.FormatInt(generation, 10) && !slices.Contains(plan, id) {
					r.mu.Unlock()
					return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_PLAN_CHANGED")
				}
			}
			for _, id := range plan {
				s.Expected[fmt.Sprintf("%s:%d", id, generation)] = true
			}
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
				zeroDTSTail := entry.DurationMS == 0
				if zeroDTSTail {
					// The last native segment can contain a single keyframe: no later
					// DTS exists to measure its extent. Wait for a certified close and
					// resolve duration from the immutable MPEG-TS, never a wall clock.
					if !inv.Closed {
						break
					}
					if i != len(inv.Segments)-1 {
						return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_OBJECT_INVALID")
					}
					r.mu.Lock()
					for _, p := range s.Segments {
						if p.Segment.RenditionID == renditionID && p.Segment.SourceGeneration == generation && p.Segment.Sequence == entry.Sequence && p.Hash == entry.Hash && p.Size == entry.Size {
							entry.DurationMS = p.Segment.DurationMS
							break
						}
					}
					r.mu.Unlock()
					if entry.DurationMS == 0 {
						entry.DurationMS, e = probeNativeDuration(r.ctx, filepath.Join(dir, entry.File))
						if e != nil {
							return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_MEDIA_INVALID")
						}
					}
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
						if p.Segment.RenditionID == renditionID && p.Segment.SourceGeneration == generation && p.Segment.Sequence == entry.Sequence && (p.Hash != entry.Hash || p.Size != entry.Size || p.Segment.DurationMS != entry.DurationMS || p.Segment.SessionStartMS != start) {
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
				rendition.RenditionID = renditionID
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
				segment := vod.Segment{SessionID: src.Start.SessionID, RenditionID: renditionID, SourceGeneration: generation, Sequence: entry.Sequence, SessionStartMS: start, DurationMS: entry.DurationMS, Discontinuity: generation > src.InitialSourceGeneration && i == 0, Object: vod.ObjectRef{ByteLength: entry.Size, SHA256: entry.Hash, ContentType: "video/mp2t"}}
				r.mu.Lock()
				if s.Closed || s.Failure != "" {
					r.mu.Unlock()
					_ = os.Remove(copyFile)
					return nil
				}
				previous, exists := s.Renditions[renditionID]
				if exists {
					// A sealed single-frame video tail may contain no remaining AAC
					// packet. Keep the established stream descriptor; no audio bytes
					// or duration are generated. VOD independently bounds this case.
					if zeroDTSTail && entry.DurationMS <= 250 && rendition.AudioCodec == nil && previous.AudioCodec != nil {
						rendition.AudioCodec = previous.AudioCodec
					}
					var same bool
					if previous, same = sameNativeRendition(previous, rendition); !same {
						r.mu.Unlock()
						return r.nativeFailure(src.Start.SessionID, "CAPTURE_NATIVE_RENDITION_CHANGED")
					}
					s.Renditions[renditionID] = previous
				}
				if !exists {
					s.Renditions[renditionID] = rendition
				} else if rendition.Bitrate > previous.Bitrate {
					previous.Bitrate = rendition.Bitrate
					s.Renditions[renditionID] = previous
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

// Probe only a synchronously closed, checksummed native tail. ffprobe decodes no
// network protocols and never fabricates a frame duration or transcodes media.
func probeNativeDuration(ctx context.Context, file string) (int64, error) {
	budget, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	raw, err := exec.CommandContext(budget, "ffprobe", "-v", "error", "-protocol_whitelist", "file,crypto,data", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file).Output()
	if err != nil {
		return 0, fmt.Errorf("native duration unavailable")
	}
	seconds, err := strconv.ParseFloat(strings.TrimSpace(string(raw)), 64)
	if err != nil || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds <= 0 || seconds > 60 {
		return 0, fmt.Errorf("native duration invalid")
	}
	return int64(math.Round(seconds * 1000)), nil
}

func probeNative(ctx context.Context, file string, size, duration int64) (vod.Rendition, error) {
	budget, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	raw, e := exec.CommandContext(budget, "ffprobe", "-v", "error", "-protocol_whitelist", "file,crypto,data", "-show_entries", "stream=codec_type,codec_name,profile,level,width,height", "-of", "json", file).Output()
	var result struct {
		Streams []struct {
			Type    string `json:"codec_type"`
			Codec   string `json:"codec_name"`
			Profile string `json:"profile"`
			Level   int    `json:"level"`
			Width   int    `json:"width"`
			Height  int    `json:"height"`
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
			codec, ok := avcCodec(stream.Profile, stream.Level)
			if stream.Codec != "h264" || !ok {
				return r, fmt.Errorf("unsupported native video")
			}
			r.VideoCodec = codec
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

// sameNativeRendition reports whether next keeps the stored rendition's format. Checkpoints written
// before avcCodec stored a bare "avc1"; that legacy value matches any H.264 string and is upgraded so
// an unfinished recording survives the recorder upgrade.
func sameNativeRendition(previous, next vod.Rendition) (vod.Rendition, bool) {
	if previous.VideoCodec == "avc1" && strings.HasPrefix(next.VideoCodec, "avc1.") {
		previous.VideoCodec = next.VideoCodec
	}
	return previous, previous.Width == next.Width && previous.Height == next.Height && previous.Codecs() == next.Codecs()
}

// avcCodec builds the RFC 6381 string (avc1.PPCCLL) that browsers require in CODECS; a bare
// "avc1" is rejected by MediaSource.isTypeSupported and hls.js drops the variant.
func avcCodec(profile string, level int) (string, bool) {
	ids := map[string]string{"Constrained Baseline": "42c0", "Baseline": "4200", "Main": "4d40", "Extended": "5800",
		"High": "6400", "High 10": "6e00", "High 4:2:2": "7a00", "High 4:4:4 Predictive": "f400"}
	id, ok := ids[profile]
	if !ok || level < 10 || level > 62 {
		return "", false
	}
	return fmt.Sprintf("avc1.%s%02x", id, level), true
}
func (r *recorder) cleanupNative(s *state) error {
	if r.nativeDir == "" || !vod.ValidID(s.Start.SessionID) {
		return nil
	}
	// A DELETE can precede native Close. Retain the directory while the producer
	// writes it; later polls of the tombstone finish the cleanup after sealing.
	// Completed checkpoints from before initialSourceGeneration may still need cleanup.
	for generation := max(int64(1), s.FirstSourceGeneration); generation <= s.LastSourceGeneration; generation++ {
		for _, renditionID := range []string{"rend_source", "rend_0", "rend_1", "rend_2", "rend_3"} {
			dir := filepath.Join(r.nativeDir, s.Start.SessionID, strconv.FormatInt(generation, 10), renditionID)
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

// A producer publishes the entire immutable plan in every rendition's inventory,
// before writing media. One missing rendition can never become a complete VOD.
func validNativePlan(plan []string, current string) bool {
	if len(plan) == 1 {
		return plan[0] == "rend_source" && current == "rend_source"
	}
	if len(plan) < 2 || len(plan) > 4 {
		return false
	}
	for i, id := range plan {
		if id != fmt.Sprintf("rend_%d", i) {
			return false
		}
	}
	return slices.Contains(plan, current)
}
