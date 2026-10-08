package transport

import (
	"time"

	"streaming/chat/internal/chat"
)

// Frames del contrato WS /realtime/chat/sessions/{sessionId}.

type readyFrame struct {
	Type         string          `json:"type"` // chat.ready
	SessionID    string          `json:"sessionId"`
	RoomStatus   chat.RoomStatus `json:"roomStatus"`
	LastSequence int64           `json:"lastSequence"`
}

type statusFrame struct {
	Type       string          `json:"type"` // chat.status
	SessionID  string          `json:"sessionId"`
	RoomStatus chat.RoomStatus `json:"roomStatus"`
}

type createdFrame struct {
	Type    string       `json:"type"` // message.created
	Message chat.Message `json:"message"`
}

type acceptedFrame struct {
	Type string `json:"type"` // message.accepted
	chat.Ack
}

type errorFrame struct {
	Type            string `json:"type"` // error
	ClientMessageID string `json:"clientMessageId,omitempty"`
	Code            string `json:"code"`
	Message         string `json:"message"`
	RetryAfterMs    *int64 `json:"retryAfterMs,omitempty"`
}

type inboundFrame struct {
	Type            string `json:"type"`
	ClientMessageID string `json:"clientMessageId"`
	Text            string `json:"text"`
}

func newErrorFrame(cmid string, e *chat.Error) errorFrame {
	f := errorFrame{Type: "error", ClientMessageID: cmid, Code: e.Code, Message: e.Message}
	if e.Code == chat.CodeRateLimited {
		ms := max(e.RetryAfter.Milliseconds(), 1)
		f.RetryAfterMs = &ms
	}
	return f
}

// historyResponse es el cuerpo de GET /api/chat/sessions/{sessionId}/messages.
type historyResponse struct {
	SessionID        string          `json:"sessionId"`
	RoomStatus       chat.RoomStatus `json:"roomStatus"`
	SnapshotSequence int64           `json:"snapshotSequence"`
	Items            []chat.Message  `json:"items"`
}

type errorEnvelope struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	RequestID string `json:"requestId"`
}

// sessionEvent es el cuerpo de POST /internal/chat/session-events.
type sessionEvent struct {
	EventID       string    `json:"eventId"`
	EventType     string    `json:"eventType"`
	SchemaVersion int       `json:"schemaVersion"`
	AggregateID   string    `json:"aggregateId"`
	Sequence      int64     `json:"sequence"`
	OccurredAtUTC time.Time `json:"occurredAtUtc"`
	Producer      string    `json:"producer"`
	Payload       struct {
		StreamID         string `json:"streamId"`
		SessionID        string `json:"sessionId"`
		StreamGeneration *int64 `json:"streamGeneration"`
		SessionVersion   *int64 `json:"sessionVersion"`
		Status           string `json:"status"`
		Availability     string `json:"availability"`
	} `json:"payload"`
}
