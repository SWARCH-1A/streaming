// Package vod owns library rules and language-neutral recording contracts.
package vod

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strings"
	"time"
)

var identifier = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
var digest = regexp.MustCompile(`^[a-f0-9]{64}$`)

type Fault struct {
	Status int
	Code   string
}

func (e *Fault) Error() string           { return e.Code }
func Fail(status int, code string) error { return &Fault{status, code} }
func IsPermanent(err error) bool         { var f *Fault; return errors.As(err, &f) && f.Status < 500 }
func ID(prefix string) string {
	b := make([]byte, 18)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	if prefix == "evt_" {
		b[6] = (b[6] & 15) | 64
		b[8] = (b[8] & 63) | 128
		return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:16])
	}
	return prefix + hex.EncodeToString(b)
}
func ValidID(s string) bool { return identifier.MatchString(s) }

type ObjectRef struct {
	ObjectID    string `json:"objectId"`
	ByteLength  int64  `json:"byteLength"`
	SHA256      string `json:"sha256"`
	ContentType string `json:"contentType"`
}

func (o ObjectRef) Valid() bool {
	return ValidID(o.ObjectID) && o.ByteLength > 0 && digest.MatchString(o.SHA256) && o.ContentType != ""
}

type Rendition struct {
	RenditionID string  `json:"renditionId"`
	Width       int     `json:"width"`
	Height      int     `json:"height"`
	Bitrate     int64   `json:"bitrateBps"`
	VideoCodec  string  `json:"videoCodec"`
	AudioCodec  *string `json:"audioCodec"`
}

func (r Rendition) Codecs() string {
	if r.AudioCodec != nil && *r.AudioCodec != "" {
		return r.VideoCodec + "," + *r.AudioCodec
	}
	return r.VideoCodec
}

type Segment struct {
	SessionID        string     `json:"sessionId"`
	RenditionID      string     `json:"renditionId"`
	SourceGeneration int64      `json:"sourceGeneration"`
	Sequence         int64      `json:"sequence"`
	SessionStartMS   int64      `json:"sessionStartMs"`
	DurationMS       int64      `json:"durationMs"`
	Discontinuity    bool       `json:"discontinuity"`
	Object           ObjectRef  `json:"object"`
	Initialization   *ObjectRef `json:"initialization,omitempty"`
}

func (s Segment) Key() string {
	return fmt.Sprintf("%s:%s:%d:%d", s.SessionID, s.RenditionID, s.SourceGeneration, s.Sequence)
}

type Start struct {
	SessionID         string   `json:"sessionId"`
	StreamID          string   `json:"streamId"`
	ChannelID         string   `json:"channelId"`
	StreamGeneration  int64    `json:"streamGeneration"`
	Title             string   `json:"title"`
	Description       string   `json:"description"`
	CategoryID        string   `json:"categoryId"`
	TagIDs            []string `json:"tagIds"`
	Autopublish       bool     `json:"autopublish"`
	PreferenceVersion int64    `json:"preferenceVersion"`
}
type Recording struct {
	AuditReason string `json:"-"`
	VodID       string `json:"vodId"`
	Start
	OwnerUserID    string      `json:"-"`
	Processing     string      `json:"processingState"`
	Publication    string      `json:"publicationState"`
	Restriction    string      `json:"restriction"`
	Version        int64       `json:"version"`
	MappingVersion int64       `json:"mappingVersion"`
	DurationMS     int64       `json:"durationMs"`
	Renditions     []Rendition `json:"renditions"`
	PlaybackPath   string      `json:"playbackPath"`
	CreatedAt      time.Time   `json:"createdAtUtc"`
	Failure        string      `json:"failureCode,omitempty"`
	Index          *ObjectRef  `json:"-"`
}

func (r Recording) Public() bool {
	return r.Processing == "READY" && r.Publication == "PUBLISHED" && r.Restriction == "NONE"
}
func (r Recording) Deleted() bool { return r.Restriction == "DELETED" }

type Index struct {
	SessionID        string      `json:"sessionId"`
	StreamGeneration int64       `json:"streamGeneration"`
	Renditions       []Rendition `json:"renditions"`
	Segments         []Segment   `json:"segments"`
}
type Interval struct {
	SourceGeneration int64 `json:"sourceGeneration"`
	SessionStartMS   int64 `json:"sessionStartMs"`
	MediaStartMS     int64 `json:"mediaStartMs"`
	DurationMS       int64 `json:"durationMs"`
}
type Timeline struct {
	VodID          string     `json:"vodId"`
	SessionID      string     `json:"sessionId"`
	MappingVersion int64      `json:"mappingVersion"`
	DurationMS     int64      `json:"durationMs"`
	GapPolicy      string     `json:"gapPolicy"`
	Intervals      []Interval `json:"intervals"`
}

// MapOffset clamps gaps to the adjacent compacted seam; sequence breaks ties in Chat.
func (t Timeline) MapOffset(ms int64) int64 {
	for _, i := range t.Intervals {
		if ms < i.SessionStartMS {
			return i.MediaStartMS
		}
		if ms < i.SessionStartMS+i.DurationMS {
			return i.MediaStartMS + ms - i.SessionStartMS
		}
	}
	if t.DurationMS > 0 {
		return t.DurationMS - 1
	}
	return 0
}

// BuildTimeline uses the same coverage for every rendition. Missing expected bytes are not gaps.
func BuildTimeline(r Recording, index Index) (Timeline, error) {
	t := Timeline{VodID: r.VodID, SessionID: r.SessionID, MappingVersion: r.MappingVersion + 1, GapPolicy: "COMPACT_NEAREST_SEAM", Intervals: []Interval{}}
	if index.SessionID != r.SessionID || index.StreamGeneration != r.StreamGeneration || len(index.Renditions) < 1 || len(index.Renditions) > 4 || len(index.Segments) == 0 {
		return t, Fail(422, "RECORDING_INDEX_INVALID")
	}
	groups := map[string][]Segment{}
	seen := map[string]bool{}
	for _, s := range index.Segments {
		if s.SessionID != r.SessionID || !s.Object.Valid() || s.SourceGeneration < 1 || s.Sequence < 0 || s.SessionStartMS < -120000 || (s.SessionStartMS < 0 && s.SourceGeneration != 1) || s.DurationMS <= 0 || s.DurationMS > 60000 || seen[s.Key()] {
			return t, Fail(422, "SEGMENT_INVALID")
		}
		if s.Object.ContentType != "video/mp2t" && (s.Initialization == nil || !s.Initialization.Valid()) {
			return t, Fail(422, "INITIALIZATION_REQUIRED")
		}
		seen[s.Key()] = true
		groups[s.RenditionID] = append(groups[s.RenditionID], s)
	}
	ids := map[string]bool{}
	var reference []Segment
	for n, v := range index.Renditions {
		if !ValidID(v.RenditionID) || ids[v.RenditionID] || v.Width < 1 || v.Height < 1 || v.Bitrate < 1 || v.VideoCodec == "" || (v.AudioCodec != nil && *v.AudioCodec == "") || (v.Codecs() == "" || strings.ContainsAny(v.Codecs(), "\"\r\n")) {
			return t, Fail(422, "RENDITION_INVALID")
		}
		ids[v.RenditionID] = true
		ss := groups[v.RenditionID]
		if len(ss) == 0 {
			return t, Fail(422, "RENDITION_EMPTY")
		}
		sort.Slice(ss, func(i, j int) bool { return ss[i].SessionStartMS < ss[j].SessionStartMS })
		var end int64
		for i, s := range ss {
			if i > 0 && (s.SessionStartMS < end || s.SourceGeneration < ss[i-1].SourceGeneration || (s.SessionStartMS > end && (!s.Discontinuity || s.SourceGeneration == ss[i-1].SourceGeneration)) || (s.SourceGeneration != ss[i-1].SourceGeneration && !s.Discontinuity) || (s.SourceGeneration == ss[i-1].SourceGeneration && s.Sequence != ss[i-1].Sequence+1)) {
				return t, Fail(422, "TIMELINE_INVALID")
			}
			end = s.SessionStartMS + s.DurationMS
		}
		if n == 0 {
			reference = ss
		} else {
			if len(ss) != len(reference) {
				return t, Fail(422, "RENDITIONS_NOT_ALIGNED")
			}
			for i, s := range ss {
				if s.SessionStartMS != reference[i].SessionStartMS || s.DurationMS != reference[i].DurationMS || s.SourceGeneration != reference[i].SourceGeneration {
					return t, Fail(422, "RENDITIONS_NOT_ALIGNED")
				}
			}
		}
	}
	if len(groups) != len(ids) {
		return t, Fail(422, "RENDITION_UNKNOWN")
	}
	for _, s := range reference {
		// Native capture also retains PREPARING media. It has no negative Chat
		// clock: omit that prefix from session intervals while retaining its bytes
		// and media positions. Session offset zero maps to the actual LIVE origin.
		prefix := max(int64(0), -s.SessionStartMS)
		if prefix < s.DurationMS {
			t.Intervals = append(t.Intervals, Interval{s.SourceGeneration, max(int64(0), s.SessionStartMS), t.DurationMS + prefix, s.DurationMS - prefix})
		}
		t.DurationMS += s.DurationMS
	}
	if len(t.Intervals) == 0 {
		return t, Fail(422, "RECORDING_NO_LIVE_MEDIA")
	}
	return t, nil
}

type Access struct {
	UserID         string `json:"userId"`
	OwnerUserID    string `json:"ownerUserId"`
	AccountVersion int64  `json:"accountVersion"`
	Active         bool   `json:"active"`
	Administrator  bool   `json:"administrator"`
}
type Authority interface {
	Check(context.Context, string, string, string) (Access, error)
}
type Objects interface {
	Put(context.Context, ObjectRef, io.Reader) error
	Get(context.Context, ObjectRef, string) (io.ReadCloser, int64, error)
	Verify(context.Context, ObjectRef) error
	Delete(context.Context, ObjectRef) error
	Health(context.Context) error
}
type Envelope struct {
	EventID       string          `json:"eventId"`
	EventType     string          `json:"eventType"`
	SchemaVersion int             `json:"schemaVersion"`
	Producer      string          `json:"producer"`
	AggregateID   string          `json:"aggregateId"`
	Sequence      int64           `json:"sequence"`
	OccurredAt    time.Time       `json:"occurredAtUtc"`
	CorrelationID string          `json:"correlationId"`
	Payload       json.RawMessage `json:"payload"`
}
type Asset struct {
	Path           string
	Object         ObjectRef
	MappingVersion int64
}
type Page struct {
	Items      []Recording `json:"items"`
	NextCursor *string     `json:"nextCursor"`
}
type Query struct {
	ChannelID, Title, CategoryID, TagID, Owner, After string
	Limit                                             int
}
