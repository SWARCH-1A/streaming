package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
)

var errInvalid = errors.New("invalid WAF audit record")
var requestID = regexp.MustCompile(`^[A-Za-z0-9._-]{1,80}$`)
var unsafeTarget = regexp.MustCompile(`[^A-Za-z0-9_-]+`)

// record holds only the audit-record fields the relay reads. Client address, headers, matched
// data and rule messages are never decoded into the event.
type record struct {
	Transaction *struct {
		TimeStamp     string `json:"time_stamp"`
		UniqueID      string `json:"unique_id"`
		IsInterrupted bool   `json:"is_interrupted"`
		Request       struct {
			URI string `json:"uri"`
		} `json:"request"`
		Response struct {
			HTTPCode int `json:"http_code"`
		} `json:"response"`
		Messages []struct {
			Details struct {
				RuleID string `json:"ruleId"`
			} `json:"details"`
		} `json:"messages"`
	} `json:"transaction"`
}

type payload struct {
	ActorID    *string `json:"actorId"`
	Action     string  `json:"action"`
	TargetType string  `json:"targetType"`
	TargetID   string  `json:"targetId"`
	Outcome    string  `json:"outcome"`
	ReasonCode string  `json:"reasonCode"`
}

type event struct {
	EventID       string  `json:"eventId"`
	EventType     string  `json:"eventType"`
	SchemaVersion int     `json:"schemaVersion"`
	Producer      string  `json:"producer"`
	AggregateID   string  `json:"aggregateId"`
	Sequence      int     `json:"sequence"`
	OccurredAtUTC string  `json:"occurredAtUtc"`
	CorrelationID string  `json:"correlationId"`
	Payload       payload `json:"payload"`
}

// OWASP CRS rule families (first three digits of the rule ID) and the category reported to Audit.
var categories = map[string]string{
	"911": "METHOD_ENFORCEMENT", "913": "SCANNER_DETECTION", "920": "PROTOCOL_VIOLATION",
	"921": "PROTOCOL_ATTACK", "922": "MULTIPART_ATTACK", "930": "PATH_TRAVERSAL",
	"931": "REMOTE_FILE_INCLUSION", "932": "COMMAND_INJECTION", "933": "PHP_INJECTION",
	"934": "GENERIC_INJECTION", "941": "XSS", "942": "SQL_INJECTION", "943": "SESSION_FIXATION",
	"944": "JAVA_INJECTION", "950": "DATA_LEAKAGE", "951": "SQL_ERROR_LEAKAGE",
	"952": "JAVA_LEAKAGE", "953": "PHP_LEAKAGE", "954": "IIS_LEAKAGE", "955": "WEB_SHELL",
}

// Category returns the family of the first rule that contributed to the block; the blocking
// evaluation rules (949, 959, 980) only sum scores and never name the attack.
func category(ids []string) string {
	for _, id := range ids {
		if len(id) == 6 {
			if name, ok := categories[id[:3]]; ok {
				return name
			}
		}
	}
	return "ANOMALY_THRESHOLD"
}

// Target keeps the first two path segments, without query or fragment, in the opaque-ID alphabet.
func target(uri string) string {
	path := uri
	if i := strings.IndexAny(path, "?#"); i >= 0 {
		path = path[:i]
	}
	var parts []string
	for _, segment := range strings.Split(path, "/") {
		if segment == "" {
			continue
		}
		if clean := strings.Trim(unsafeTarget.ReplaceAllString(segment, "_"), "_"); clean != "" {
			parts = append(parts, clean)
		} else {
			parts = append(parts, "x")
		}
		if len(parts) == 2 {
			break
		}
	}
	if len(parts) == 0 {
		return "root"
	}
	value := strings.Join(parts, "_")
	if len(value) > 128 {
		value = value[:128]
	}
	return value
}

// eventID derives a stable UUID (version 5 layout) from the request ID: a retried record publishes
// the same event, which Audit stores once.
func eventID(request string) string {
	sum := sha256.Sum256([]byte("waf-audit:" + request))
	b := sum[:16]
	b[6] = b[6]&0x0f | 0x50
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return fmt.Sprintf("%s-%s-%s-%s-%s", h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])
}

var ruleID = regexp.MustCompile(`^[0-9]{6}$`)

// Rules lists the CRS rule IDs that matched, for the relay's operational log.
func Rules(raw []byte) []string {
	var r record
	ids := []string{}
	if json.Unmarshal(raw, &r) != nil || r.Transaction == nil {
		return ids
	}
	for _, m := range r.Transaction.Messages {
		if ruleID.MatchString(m.Details.RuleID) {
			ids = append(ids, m.Details.RuleID)
		}
	}
	return ids
}

// Convert maps one ModSecurity audit record to a security.audit.waf.v1 event. publish is false for
// records that are not blocks (detections below the threshold, upstream 5xx).
func Convert(raw []byte) (body []byte, id string, publish bool, err error) {
	var r record
	if err = json.Unmarshal(raw, &r); err != nil || r.Transaction == nil {
		return nil, "", false, errInvalid
	}
	t := r.Transaction
	if !t.IsInterrupted || t.Response.HTTPCode != 403 {
		return nil, "", false, nil
	}
	if !requestID.MatchString(t.UniqueID) {
		return nil, "", false, errInvalid
	}
	// libmodsecurity writes ctime-style local time; the container runs in UTC.
	at, err := time.ParseInLocation(time.ANSIC, t.TimeStamp, time.UTC)
	if err != nil {
		return nil, "", false, errInvalid
	}
	ids := make([]string, 0, len(t.Messages))
	for _, m := range t.Messages {
		ids = append(ids, m.Details.RuleID)
	}
	id = eventID(t.UniqueID)
	body, err = json.Marshal(event{
		EventID: id, EventType: "security.audit.waf.v1", SchemaVersion: 1, Producer: "waf",
		AggregateID: "audit:" + id, Sequence: 1, OccurredAtUTC: at.UTC().Format(time.RFC3339),
		CorrelationID: t.UniqueID,
		Payload: payload{Action: "WAF_BLOCK", TargetType: "ROUTE", TargetID: target(t.Request.URI),
			Outcome: "DENIED", ReasonCode: category(ids)},
	})
	return body, id, err == nil, err
}
