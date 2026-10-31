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

// VodEnvelope es vod.availability.changed.v1 (P2EventVodAvailabilityChanged), definido y publicado
// por VOD (ADR-022). Chat solo lo consume para vincular la sesión y coordinar la limpieza del replay.
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
		VodID            string `json:"vodId"`
		SessionID        string `json:"sessionId"`
		ChannelID        string `json:"channelId"`
		Version          *int64 `json:"version"`
		MappingVersion   *int64 `json:"mappingVersion"`
		Available        *bool  `json:"available"`
		Restriction      string `json:"restriction"`
		PublicationState string `json:"publicationState"`
	} `json:"payload"`
}

var (
	vodRestrictions  = map[string]bool{"NONE": true, "RETIRED": true, "DELETED": true}
	vodPublications  = map[string]bool{"DRAFT": true, "PUBLISHED": true}
	vodPayloadFields = []string{"vodId", "sessionId", "channelId", "version", "mappingVersion", "available", "restriction", "publicationState"}
)

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
		json.Unmarshal(fields["payload"], &payloadFields) != nil || !keys(payloadFields, vodPayloadFields...) {
		return event, archive.VodLink{}, "", invalid
	}
	p := event.Payload
	_, err := uuid.Parse(event.EventID)
	if err != nil || len(event.EventID) != 36 || event.EventType != "vod.availability.changed.v1" || event.SchemaVersion != 1 ||
		event.Producer != "vod" || !opaqueID.MatchString(event.CorrelationID) || !chat.ValidOpaqueID(p.VodID) ||
		!chat.ValidOpaqueID(p.ChannelID) || !chat.ValidSessionID(p.SessionID) || !vodRestrictions[p.Restriction] ||
		!vodPublications[p.PublicationState] || p.Available == nil ||
		p.Version == nil || *p.Version < 1 || *p.Version > maxInteger || event.Sequence != *p.Version ||
		event.AggregateID != "vod:"+p.VodID || p.MappingVersion == nil || *p.MappingVersion < 0 || *p.MappingVersion > maxInteger ||
		(*p.Available && (p.Restriction != "NONE" || p.PublicationState != "PUBLISHED")) {
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
	return event, archive.VodLink{VodID: p.VodID, SessionID: p.SessionID, ChannelID: p.ChannelID, Version: *p.Version,
		MappingVersion: *p.MappingVersion, Available: *p.Available, Restriction: p.Restriction,
		PublicationState: p.PublicationState}, hex.EncodeToString(hash[:]), nil
}
