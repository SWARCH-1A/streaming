package vod

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const MaxSegmentBytes int64 = 64 << 20
const MaxIndexBytes int64 = 32 << 20

type Service struct {
	Store       *Store
	Objects     Objects
	Delivery    Delivery
	Authority   Authority
	Secret      []byte
	Origin      string
	Probe       func(context.Context, Rendition, Segment) error
	Development bool
}

func cleanText(s string, max int) bool {
	return utf8.ValidString(s) && utf8.RuneCountInString(s) <= max && strings.TrimSpace(s) == s && strings.IndexFunc(s, func(r rune) bool { return unicode.IsControl(r) && r != '\n' && r != '\t' }) < 0
}
func validateStart(p Start) error {
	if !ValidID(p.SessionID) || !ValidID(p.StreamID) || !ValidID(p.ChannelID) || p.StreamGeneration < 1 || p.PreferenceVersion < 0 || !cleanText(p.Title, 140) || p.Title == "" || !cleanText(p.Description, 4000) || !ValidID(p.CategoryID) || len(p.TagIDs) > 5 {
		return Fail(422, "RECORDING_INVALID")
	}
	seen := map[string]bool{}
	for _, id := range p.TagIDs {
		if !ValidID(id) || seen[id] {
			return Fail(422, "TAG_INVALID")
		}
		seen[id] = true
	}
	return nil
}
func (s *Service) Start(ctx context.Context, p Start) (Recording, error) {
	if err := validateStart(p); err != nil {
		return Recording{}, err
	}
	a, err := s.Authority.Check(ctx, p.ChannelID, "", "READ")
	if err != nil {
		return Recording{}, err
	}
	if !a.Active || a.OwnerUserID == "" {
		return Recording{}, Fail(404, "CHANNEL_UNAVAILABLE")
	}
	return s.Store.Start(ctx, p, a.OwnerUserID)
}
func (s *Service) access(ctx context.Context, r Recording, credential string, write bool) (Access, error) {
	if r.Deleted() {
		return Access{}, Fail(404, "VOD_NOT_FOUND")
	}
	operation := "READ"
	if write {
		operation = "WRITE"
	}
	a, e := s.Authority.Check(ctx, r.ChannelID, credential, operation)
	if e != nil {
		return a, e
	}
	if !a.Active {
		return a, Fail(404, "VOD_UNAVAILABLE")
	}
	active, e := s.Store.Active(ctx, r.OwnerUserID)
	if e != nil {
		return a, e
	}
	if !active {
		return a, Fail(404, "VOD_UNAVAILABLE")
	}
	if a.OwnerUserID != r.OwnerUserID {
		return a, Fail(503, "OWNER_CONTEXT_CONFLICT")
	}
	if write && a.UserID != r.OwnerUserID {
		return a, Fail(403, "VOD_FORBIDDEN")
	}
	if !r.Public() && a.UserID != r.OwnerUserID {
		return a, Fail(404, "VOD_NOT_FOUND")
	}
	if r.Restriction != "NONE" {
		return a, Fail(404, "VOD_UNAVAILABLE")
	}
	return a, nil
}
func (s *Service) upload(ctx context.Context, r Recording, key string, ref ObjectRef, body io.Reader) (ObjectRef, error) {
	var out ObjectRef
	err := s.Store.WithObjectsLock(ctx, r.VodID, func() error {
		var e error
		out, e = s.uploadLocked(ctx, r, key, ref, body)
		return e
	})
	return out, err
}
func (s *Service) uploadLocked(ctx context.Context, r Recording, key string, ref ObjectRef, body io.Reader) (ObjectRef, error) {
	ref.ObjectID = "pending"
	if !ref.Valid() {
		return ref, Fail(422, "OBJECT_INVALID")
	}
	ref, done, e := s.Store.Reserve(ctx, r.VodID, key, ref)
	if e != nil {
		return ref, e
	}
	if !done {
		if e = s.Objects.Put(ctx, ref, body); e != nil {
			return ref, e
		}
		if e = s.Objects.Verify(ctx, ref); e != nil {
			return ref, e
		}
		if e = s.Store.Confirm(ctx, ref.ObjectID); e != nil {
			return ref, e
		}
	} else if e = s.Objects.Verify(ctx, ref); e != nil {
		return ref, e
	}
	latest, e := s.Store.Get(ctx, r.VodID)
	if e != nil {
		return ref, e
	}
	if latest.Deleted() {
		_ = s.Objects.Delete(ctx, ref)
		return ref, Fail(410, "VOD_DELETED")
	}
	return ref, nil
}
func refBytes(body []byte, mime string) ObjectRef {
	h := sha256.Sum256(body)
	return ObjectRef{ByteLength: int64(len(body)), SHA256: hex.EncodeToString(h[:]), ContentType: mime}
}
func (s *Service) Upload(ctx context.Context, session, key string, ref ObjectRef, body io.Reader) (ObjectRef, error) {
	r, e := s.Store.Session(ctx, session)
	if e != nil {
		return ref, e
	}
	return s.upload(ctx, r, key, ref, body)
}
func (s *Service) index(ctx context.Context, r Recording) (Index, error) {
	var index Index
	if r.Index == nil {
		return index, Fail(422, "INDEX_REQUIRED")
	}
	if r.Index.ContentType != "application/json" {
		return index, Fail(422, "INDEX_INVALID")
	}
	if e := s.Store.Owns(ctx, r.VodID, *r.Index); e != nil {
		return index, e
	}
	if e := s.Objects.Verify(ctx, *r.Index); e != nil {
		return index, e
	}
	body, _, e := s.Objects.Get(ctx, *r.Index, "")
	if e != nil {
		return index, e
	}
	defer body.Close()
	decoder := json.NewDecoder(io.LimitReader(body, MaxIndexBytes+1))
	decoder.DisallowUnknownFields()
	if e = decoder.Decode(&index); e != nil {
		return index, Fail(422, "INDEX_INVALID")
	}
	var tail any
	if decoder.Decode(&tail) != io.EOF {
		return index, Fail(422, "INDEX_INVALID")
	}
	return index, nil
}
func (s *Service) Validate(ctx context.Context, r Recording) error {
	if r.Deleted() || r.Processing == "READY" {
		return nil
	}
	if strings.HasPrefix(r.Failure, "CAPTURE_") || r.Failure == "CHECKPOINT_FAILED" {
		return Fail(422, "CAPTURE_INCOMPLETE")
	}
	index, e := s.index(ctx, r)
	if e != nil {
		return e
	}
	timeline, e := BuildTimeline(r, index)
	if e != nil {
		return e
	}
	if e = s.Store.CheckIndexCoverage(ctx, r, index); e != nil {
		return e
	}
	refs := map[string]ObjectRef{}
	for _, seg := range index.Segments {
		refs[seg.Object.ObjectID] = seg.Object
		if seg.Initialization != nil {
			refs[seg.Initialization.ObjectID] = *seg.Initialization
		}
	}
	for _, ref := range refs {
		if e = s.Store.Owns(ctx, r.VodID, ref); e != nil {
			return e
		}
		if e = s.Objects.Verify(ctx, ref); e != nil {
			return e
		}
	}
	if s.Probe == nil {
		return Fail(503, "MEDIA_VALIDATOR_UNAVAILABLE")
	}
	for _, seg := range index.Segments {
		for _, v := range index.Renditions {
			if v.RenditionID == seg.RenditionID {
				if e = s.Probe(ctx, v, seg); e != nil {
					return e
				}
			}
		}
	}
	assets := []Asset{}
	master := "#EXTM3U\n#EXT-X-VERSION:7\n"
	groups := map[string][]Segment{}
	for _, seg := range index.Segments {
		groups[seg.RenditionID] = append(groups[seg.RenditionID], seg)
	}
	for _, v := range index.Renditions {
		segments := groups[v.RenditionID]
		sort.Slice(segments, func(i, j int) bool { return segments[i].SessionStartMS < segments[j].SessionStartMS })
		var target int64 = 1
		for _, seg := range segments {
			d := (seg.DurationMS + 999) / 1000
			if d > target {
				target = d
			}
		}
		playlist := fmt.Sprintf("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n", target)
		initialization := ""
		for n, seg := range segments {
			if n > 0 && seg.Discontinuity {
				playlist += "#EXT-X-DISCONTINUITY\n"
			}
			if seg.Initialization != nil && initialization != seg.Initialization.ObjectID {
				initialization = seg.Initialization.ObjectID
				path := fmt.Sprintf("%s/%d/init.mp4", v.RenditionID, seg.SourceGeneration)
				assets = append(assets, Asset{path, *seg.Initialization, timeline.MappingVersion})
				playlist += fmt.Sprintf("#EXT-X-MAP:URI=\"%s\"\n", "/vod-media/"+r.VodID+"/"+path)
			}
			ext := "m4s"
			if seg.Object.ContentType == "video/mp2t" {
				ext = "ts"
			}
			path := fmt.Sprintf("%s/%d/%d.%s", v.RenditionID, seg.SourceGeneration, seg.Sequence, ext)
			assets = append(assets, Asset{path, seg.Object, timeline.MappingVersion})
			playlist += fmt.Sprintf("#EXTINF:%.3f,\n%s\n", float64(seg.DurationMS)/1000, "/vod-media/"+r.VodID+"/"+path)
		}
		playlist += "#EXT-X-ENDLIST\n"
		path := v.RenditionID + "/index.m3u8"
		ref, e := s.upload(ctx, r, "manifest:"+path, refBytes([]byte(playlist), "application/vnd.apple.mpegurl"), strings.NewReader(playlist))
		if e != nil {
			return e
		}
		assets = append(assets, Asset{path, ref, timeline.MappingVersion})
		master += fmt.Sprintf("#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d,CODECS=\"%s\"\n%s\n", v.Bitrate, v.Width, v.Height, v.Codecs(), "/vod-media/"+r.VodID+"/"+path)
	}
	ref, e := s.upload(ctx, r, "manifest:master", refBytes([]byte(master), "application/vnd.apple.mpegurl"), strings.NewReader(master))
	if e != nil {
		return e
	}
	assets = append(assets, Asset{"master.m3u8", ref, timeline.MappingVersion})
	r.Renditions = index.Renditions
	r.Failure = ""
	return s.Store.Complete(ctx, r, timeline, assets, index.Segments)
}
func (s *Service) Workers(ctx context.Context) {
	timer := time.NewTicker(time.Second)
	defer timer.Stop()
	ticks := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			ticks++
			if ticks == 1 || ticks%30 == 0 {
				_ = s.Store.ScheduleOrphans(ctx)
			}
			j, e := s.Store.NextJob(ctx)
			if e != nil {
				continue
			}
			r, e := s.Store.Get(ctx, j.VodID)
			if e == nil {
				switch j.Kind {
				case "VALIDATE":
					e = s.Validate(ctx, r)
				case "REMOVE":
					e = nil
				case "DELETE":
					e = s.Store.WithObjectsLock(ctx, r.VodID, func() error {
						refs, err := s.Store.Objects(ctx, r.VodID)
						if err != nil {
							return err
						}
						for _, ref := range refs {
							if err = s.Objects.Delete(ctx, ref); err != nil {
								return err
							}
						}
						return nil
					})
				default:
					if strings.HasPrefix(j.Kind, "ORPHAN:") {
						e = s.Store.WithObjectsLock(ctx, r.VodID, func() error {
							return s.Store.CleanOrphan(ctx, r.VodID, strings.TrimPrefix(j.Kind, "ORPHAN:"), func(ref ObjectRef) error { return s.Objects.Delete(ctx, ref) })
						})
					} else if strings.HasPrefix(j.Kind, "TRACK:") {
						var asset Asset
						asset, e = s.Store.Asset(ctx, r.VodID, "subtitles/"+strings.TrimPrefix(j.Kind, "TRACK:")+".vtt")
						if e == nil {
							e = s.Objects.Delete(ctx, asset.Object)
						}
					} else {
						e = Fail(422, "JOB_TYPE_INVALID")
					}
				}
			}
			_ = s.Store.JobResult(ctx, j, e)
			if e != nil {
				slog.Warn("vod job failed", "operationId", j.ID, "kind", j.Kind)
			}
		}
	}
}
func (s *Service) Event(ctx context.Context, consumer string, body []byte) error {
	var e Envelope
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	if len(body) > 1<<20 || dec.Decode(&e) != nil || !ValidID(e.EventID) || e.SchemaVersion != 1 || e.Sequence < 1 || e.OccurredAt.IsZero() || !ValidID(e.CorrelationID) {
		return Fail(422, "EVENT_INVALID")
	}
	var tail any
	if dec.Decode(&tail) != io.EOF {
		return Fail(422, "EVENT_INVALID")
	}
	expected := map[string]string{"vod.lifecycle": "stream.session.changed.v1", "vod.segments": "recording.segment.available.v1", "vod.recording": "recording.closed.v1", "vod.account": "account.status.changed.v1"}
	if expected[consumer] != e.EventType {
		return Fail(422, "EVENT_TYPE_INVALID")
	}
	if (consumer == "vod.account" && e.Producer != "core") || (consumer != "vod.account" && e.Producer != "streaming" && e.Producer != "media") {
		return Fail(422, "EVENT_PRODUCER_INVALID")
	}
	return s.Store.Apply(ctx, consumer, e, body)
}
