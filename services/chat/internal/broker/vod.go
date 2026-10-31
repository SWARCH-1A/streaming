package broker

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"

	"github.com/google/uuid"

	"streaming/chat/internal/archive"
	"streaming/chat/internal/chat"
)

// VodEnvelope es vod.availability.changed.v1 (P2EventVodAvailabilityChanged). VOD es su productor;
// el payload lo propone Chat como consumidor y queda sujeto a revisión del responsable de VOD.
type VodEnvelope struct {
	EventID       string `json:"eventId"`
	EventType     string `json:"eventType"`
	SchemaVersion int    `json:"schemaVersion"`
	Producer      string `json:"producer"`
	AggregateID   string `json:"aggregateId"`
	Sequence      int64  `json:"sequence"`
	OccurredAtUTC string `json:"occurredAtUtc"`
	CorrelationID string `json:"correlationId"`
	Payload       struct {
		VodID           string `json:"vodId"`
		VodVersion      *int64 `json:"vodVersion"`
		ChannelID       string `json:"channelId"`
		SessionID       string `json:"sessionId"`
		Status          string `json:"status"`
		TimelineStartMs *int64 `json:"timelineStartMs"`
		DurationMs      *int64 `json:"durationMs"`
	} `json:"payload"`
}

var vodStatuses = map[string]bool{"PROCESSING": true, "PUBLISHED": true, "UNPUBLISHED": true, "SUSPENDED": true, "DELETED": true}

const maxInteger = 9007199254740991

func decodeVod(body []byte) (VodEnvelope, archive.VodLink, string, error) {
	var event VodEnvelope
	invalid := errors.New("INVALID_EVENT")
	if len(body) > 65536 {
		return event, archive.VodLink{}, "", invalid
	}
	unique := json.NewDecoder(bytes.NewReader(body))
	unique.UseNumber()
	if uniqueJSON(unique) != nil {
		return event, archive.VodLink{}, "", invalid
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&event) != nil || decoder.Decode(new(any)) != io.EOF {
		return event, archive.VodLink{}, "", invalid
	}
	var fields, payloadFields map[string]json.RawMessage
	if json.Unmarshal(body, &fields) != nil || !keys(fields, "eventId", "eventType", "schemaVersion", "producer", "aggregateId", "sequence", "occurredAtUtc", "correlationId", "payload") ||
		json.Unmarshal(fields["payload"], &payloadFields) != nil || !keys(payloadFields, "vodId", "vodVersion", "channelId", "sessionId", "status", "timelineStartMs", "durationMs") {
		return event, archive.VodLink{}, "", invalid
	}
	p := event.Payload
	_, err := uuid.Parse(event.EventID)
	if err != nil || len(event.EventID) != 36 || event.EventType != "vod.availability.changed.v1" || event.SchemaVersion != 1 ||
		event.Producer != "vod" || !opaqueID.MatchString(event.CorrelationID) || !chat.ValidOpaqueID(p.VodID) ||
		!chat.ValidOpaqueID(p.ChannelID) || !chat.ValidSessionID(p.SessionID) || !vodStatuses[p.Status] ||
		p.VodVersion == nil || *p.VodVersion < 1 || *p.VodVersion > maxInteger || event.Sequence != *p.VodVersion ||
		event.AggregateID != "vod:"+p.VodID || p.TimelineStartMs == nil || *p.TimelineStartMs < 0 || *p.TimelineStartMs > maxInteger ||
		(p.DurationMs != nil && (*p.DurationMs < 0 || *p.DurationMs > maxInteger)) {
		return event, archive.VodLink{}, "", invalid
	}
	if _, err := utc(event.OccurredAtUTC); err != nil {
		return event, archive.VodLink{}, "", invalid
	}
	var canonical any
	canonDecoder := json.NewDecoder(bytes.NewReader(body))
	canonDecoder.UseNumber()
	if canonDecoder.Decode(&canonical) != nil {
		return event, archive.VodLink{}, "", invalid
	}
	encoded, err := json.Marshal(canonical)
	if err != nil {
		return event, archive.VodLink{}, "", invalid
	}
	hash := sha256.Sum256(encoded)
	return event, archive.VodLink{VodID: p.VodID, SessionID: p.SessionID, ChannelID: p.ChannelID, VodVersion: *p.VodVersion,
		Status: p.Status, TimelineStartMs: *p.TimelineStartMs, DurationMs: p.DurationMs}, hex.EncodeToString(hash[:]), nil
}
