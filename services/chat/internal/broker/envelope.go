// Package broker implements the P2 transport; domain state is owned by Chat's store.
package broker

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"streaming/chat/internal/chat"
)

type Envelope struct {
	stateFingerprint string
	EventID          string `json:"eventId"`
	EventType        string `json:"eventType"`
	SchemaVersion    int    `json:"schemaVersion"`
	Producer         string `json:"producer"`
	AggregateID      string `json:"aggregateId"`
	Sequence         int64  `json:"sequence"`
	OccurredAtUTC    string `json:"occurredAtUtc"`
	CorrelationID    string `json:"correlationId"`
	Payload          struct {
		StreamID         string  `json:"streamId"`
		ChannelID        string  `json:"channelId"`
		SessionID        string  `json:"sessionId"`
		StreamGeneration *int64  `json:"streamGeneration"`
		SessionVersion   *int64  `json:"sessionVersion"`
		Status           string  `json:"status"`
		Availability     string  `json:"availability"`
		FirstLiveAtUTC   *string `json:"firstLiveAtUtc"`
		InitialLive      *bool   `json:"initialLive"`
	} `json:"payload"`
}

var opaqueID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

func decode(body []byte) (Envelope, chat.SessionState, string, error) {
	var event Envelope
	invalid := errors.New("INVALID_EVENT")
	if len(body) > 65536 {
		return event, chat.SessionState{}, "", invalid
	}
	unique := json.NewDecoder(bytes.NewReader(body))
	unique.UseNumber()
	if err := uniqueJSON(unique); err != nil {
		return event, chat.SessionState{}, "", invalid
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&event); err != nil {
		return event, chat.SessionState{}, "", invalid
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return event, chat.SessionState{}, "", invalid
	}
	p := event.Payload
	_, err := uuid.Parse(event.EventID)
	if err != nil || len(event.EventID) != 36 || event.EventType != "stream.session.changed.v1" || event.SchemaVersion != 1 ||
		event.Producer != "streaming" || !opaqueID.MatchString(event.CorrelationID) || !opaqueID.MatchString(p.StreamID) ||
		!opaqueID.MatchString(p.ChannelID) || !chat.ValidSessionID(p.SessionID) || p.StreamGeneration == nil ||
		p.SessionVersion == nil || *p.StreamGeneration < 1 || *p.StreamGeneration > 9007199254740991 ||
		*p.SessionVersion < 1 || *p.SessionVersion > 9007199254740991 || event.Sequence != *p.SessionVersion ||
		event.AggregateID != "session:"+p.SessionID || p.InitialLive == nil {
		return event, chat.SessionState{}, "", invalid
	}
	if _, err = utc(event.OccurredAtUTC); err != nil {
		return event, chat.SessionState{}, "", invalid
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(body, &fields) != nil || !keys(fields, "eventId", "eventType", "schemaVersion", "producer", "aggregateId", "sequence", "occurredAtUtc", "correlationId", "payload") {
		return event, chat.SessionState{}, "", invalid
	}
	var payloadFields map[string]json.RawMessage
	if json.Unmarshal(fields["payload"], &payloadFields) != nil || !keys(payloadFields, "streamId", "channelId", "sessionId", "streamGeneration", "sessionVersion", "status", "availability", "firstLiveAtUtc", "initialLive") {
		return event, chat.SessionState{}, "", invalid
	}
	if _, present := payloadFields["firstLiveAtUtc"]; !present {
		return event, chat.SessionState{}, "", invalid
	}
	if p.FirstLiveAtUTC != nil {
		if _, err = utc(*p.FirstLiveAtUTC); err != nil {
			return event, chat.SessionState{}, "", invalid
		}
	}
	expected := map[string]string{"PREPARING": "OFFLINE", "LIVE": "PLAYABLE", "RECONNECT_GRACE": "RECONNECTING", "ENDED": "OFFLINE"}
	if expected[p.Status] == "" || expected[p.Status] != p.Availability ||
		((p.Status == "LIVE" || p.Status == "RECONNECT_GRACE") && p.FirstLiveAtUTC == nil) ||
		(*p.InitialLive && (p.Status != "LIVE" || p.FirstLiveAtUTC == nil)) {
		return event, chat.SessionState{}, "", invalid
	}
	status, ok := chat.RoomStatusFromSession(p.Status)
	if !ok {
		return event, chat.SessionState{}, "", invalid
	}
	// Hash all envelope fields, with deterministic object ordering and without float conversion.
	var canonical any
	canonDecoder := json.NewDecoder(bytes.NewReader(body))
	canonDecoder.UseNumber()
	if err = canonDecoder.Decode(&canonical); err != nil {
		return event, chat.SessionState{}, "", invalid
	}
	encoded, err := json.Marshal(canonical)
	if err != nil {
		return event, chat.SessionState{}, "", invalid
	}
	hash := sha256.Sum256(encoded)
	stateBytes, _ := json.Marshal(canonical.(map[string]any)["payload"])
	stateHash := sha256.Sum256(stateBytes)
	event.stateFingerprint = hex.EncodeToString(stateHash[:])
	return event, chat.SessionState{SessionID: p.SessionID, StreamGeneration: *p.StreamGeneration,
		SessionVersion: *p.SessionVersion, Status: status, Availability: p.Availability}, hex.EncodeToString(hash[:]), nil
}

func keys(fields map[string]json.RawMessage, names ...string) bool {
	if len(fields) != len(names) {
		return false
	}
	for _, name := range names {
		if _, ok := fields[name]; !ok {
			return false
		}
	}
	return true
}

// JSON duplicate properties are rejected before decoding into structs/maps.
func uniqueJSON(decoder *json.Decoder) error {
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delim, compound := token.(json.Delim)
	if !compound {
		return nil
	}
	switch delim {
	case '{':
		seen := map[string]bool{}
		for decoder.More() {
			key, err := decoder.Token()
			if err != nil {
				return err
			}
			name, ok := key.(string)
			if !ok || seen[name] {
				return errors.New("duplicate JSON field")
			}
			seen[name] = true
			if err = uniqueJSON(decoder); err != nil {
				return err
			}
		}
	case '[':
		for decoder.More() {
			if err = uniqueJSON(decoder); err != nil {
				return err
			}
		}
	default:
		return errors.New("invalid JSON")
	}
	_, err = decoder.Token()
	return err
}

func utc(raw string) (time.Time, error) {
	if !strings.HasSuffix(raw, "Z") && !strings.HasSuffix(raw, "+00:00") {
		return time.Time{}, errors.New("INVALID_TIMESTAMP")
	}
	value, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil || value.Year() < 1970 || value.Year() > 9999 {
		return time.Time{}, errors.New("INVALID_TIMESTAMP")
	}
	return value, nil
}
