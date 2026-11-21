package audit

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"strings"
	"time"
)

var ErrInvalid = errors.New("invalid audit event")
var ErrConflict = errors.New("audit event identity conflict")
var opaque = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
var requestID = regexp.MustCompile(`^[A-Za-z0-9._-]{1,80}$`)
var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var code = regexp.MustCompile(`^[A-Z_]{1,64}$`)

type Payload struct {
	ActorID    *string `json:"actorId"`
	Action     string  `json:"action"`
	TargetType string  `json:"targetType"`
	TargetID   string  `json:"targetId"`
	Outcome    string  `json:"outcome"`
	ReasonCode string  `json:"reasonCode"`
}
type Event struct {
	EventID       string  `json:"eventId"`
	EventType     string  `json:"eventType"`
	SchemaVersion int     `json:"schemaVersion"`
	Producer      string  `json:"producer"`
	AggregateID   string  `json:"aggregateId"`
	Sequence      int     `json:"sequence"`
	OccurredAtUTC string  `json:"occurredAtUtc"`
	CorrelationID string  `json:"correlationId"`
	Payload       Payload `json:"payload"`
}

// Reject duplicate keys at every depth, rather than accepting the last ambiguous value.
func unique(d *json.Decoder) error {
	token, err := d.Token()
	if err != nil {
		return err
	}
	delim, ok := token.(json.Delim)
	if !ok {
		return nil
	}
	switch delim {
	case '{':
		seen := map[string]bool{}
		for d.More() {
			k, err := d.Token()
			if err != nil {
				return err
			}
			key, ok := k.(string)
			if !ok || seen[key] {
				return ErrInvalid
			}
			seen[key] = true
			if err = unique(d); err != nil {
				return err
			}
		}
	case '[':
		for d.More() {
			if err := unique(d); err != nil {
				return err
			}
		}
	default:
		return ErrInvalid
	}
	_, err = d.Token()
	return err
}

func Parse(raw []byte, producer string) (Event, error) {
	var e Event
	if len(raw) > 8192 {
		return e, ErrInvalid
	}
	check := json.NewDecoder(bytes.NewReader(raw))
	if unique(check) != nil {
		return e, ErrInvalid
	}
	if _, err := check.Token(); err != io.EOF {
		return e, ErrInvalid
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if d.Decode(&e) != nil || d.Decode(new(any)) != io.EOF {
		return e, ErrInvalid
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil || len(fields) != 9 {
		return e, ErrInvalid
	}
	for _, name := range []string{"eventId", "eventType", "schemaVersion", "producer", "aggregateId", "sequence", "occurredAtUtc", "correlationId", "payload"} {
		if _, exists := fields[name]; !exists {
			return e, ErrInvalid
		}
	}
	var payloadFields map[string]json.RawMessage
	if json.Unmarshal(fields["payload"], &payloadFields) != nil || len(payloadFields) != 6 {
		return e, ErrInvalid
	}
	for _, name := range []string{"actorId", "action", "targetType", "targetId", "outcome", "reasonCode"} {
		if _, exists := payloadFields[name]; !exists {
			return e, ErrInvalid
		}
	}
	p := e.Payload
	instant, err := time.Parse(time.RFC3339Nano, e.OccurredAtUTC)
	_, offset := instant.Zone()
	if err != nil || offset != 0 || instant.Year() < 1 || (!strings.HasSuffix(e.OccurredAtUTC, "Z") && !strings.HasSuffix(e.OccurredAtUTC, "+00:00")) || !uuid.MatchString(e.EventID) || e.Producer != producer ||
		(producer != "core" && producer != "vod") || e.EventType != "security.audit."+producer+".v1" ||
		e.SchemaVersion != 1 || e.Sequence != 1 || e.AggregateID != "audit:"+e.EventID || !requestID.MatchString(e.CorrelationID) ||
		(p.ActorID != nil && !opaque.MatchString(*p.ActorID)) || !opaque.MatchString(p.TargetID) || !code.MatchString(p.ReasonCode) ||
		(p.Outcome != "SUCCESS" && p.Outcome != "DENIED") {
		return e, ErrInvalid
	}
	valid := false
	if producer == "core" {
		valid = (p.Action == "ADMIN_ACCESS" && p.TargetType == "ADMIN") || (p.Action == "ACCOUNT_STATUS" && p.TargetType == "ACCOUNT")
	}
	if producer == "vod" {
		valid = (p.Action == "VOD_PATCH" || p.Action == "VOD_PUBLISH" || p.Action == "VOD_DELETE" || p.Action == "VOD_REMOVE") && p.TargetType == "VOD"
	}
	if !valid {
		return e, ErrInvalid
	}
	// A null actor is explicitly unknown, never an identity taken from a client header.
	if p.Outcome == "SUCCESS" && p.ActorID == nil {
		return e, ErrInvalid
	}
	return e, nil
}
