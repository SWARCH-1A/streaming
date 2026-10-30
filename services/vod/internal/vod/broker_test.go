package vod

import (
	"context"
	"encoding/json"
	amqp "github.com/rabbitmq/amqp091-go"
	"os"
	"testing"
	"time"
)

func eventually(t *testing.T, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if check() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("broker effect not observed")
}
func TestRabbitDurableInboxDeadLetterAndPublisherRecovery(t *testing.T) {
	endpoint := os.Getenv("VOD_TEST_BROKER_URL")
	if endpoint == "" {
		t.Skip("requires isolated RabbitMQ fixture")
	}
	s, _ := fixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &Broker{URL: endpoint, Service: s}
	done := make(chan struct{})
	go func() { defer close(done); b.Run(ctx) }()
	defer func() { cancel(); <-done }()
	eventually(t, func() bool { b.mu.Lock(); defer b.mu.Unlock(); return b.publisher != nil })
	operator := os.Getenv("VOD_TEST_BROKER_OPERATOR_URL")
	if operator == "" {
		operator = endpoint
	}
	conn, e := amqp.Dial(operator)
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	ch, e := conn.Channel()
	if e != nil {
		t.Fatal(e)
	}
	defer ch.Close()
	// Scope queue purging to the disposable fixture URL supplied explicitly by the runner.
	if _, e = ch.QueuePurge("vod.account.dlq", false); e != nil {
		t.Fatal(e)
	}
	event := Envelope{EventID: ID("evt_"), EventType: "account.status.changed.v1", SchemaVersion: 1, Producer: "core", AggregateID: "account:usr_fixture", Sequence: 1, OccurredAt: time.Now().UTC(), CorrelationID: "req_fixture", Payload: json.RawMessage(`{"userId":"usr_fixture","status":"SUSPENDED","accountVersion":1}`)}
	raw, _ := json.Marshal(event)
	publish := func(body []byte) {
		t.Helper()
		if e = ch.PublishWithContext(ctx, "p2.events", event.EventType, false, false, amqp.Publishing{ContentType: "application/json", DeliveryMode: amqp.Persistent, Body: body}); e != nil {
			t.Fatal(e)
		}
	}
	publish(raw)
	publish(raw)
	eventually(t, func() bool { active, e := s.Store.Active(ctx, "usr_fixture"); return e == nil && !active })
	var count int
	if e = s.Store.DB.QueryRowContext(ctx, "SELECT count(*) FROM vod_inbox WHERE consumer='vod.account'").Scan(&count); e != nil || count != 1 {
		t.Fatal("duplicate effect", count, e)
	}
	publish([]byte(`{"invalid":true}`))
	eventually(t, func() bool {
		msg, ok, e := ch.Get("vod.account.dlq", false)
		if e != nil || !ok {
			return false
		}
		_ = msg.Ack(false)
		return msg.Headers["x-p2-cause"] == "HANDLER_FAILED"
	})
	b.mu.Lock()
	old := b.publisher
	b.mu.Unlock()
	if e = old.Close(); e != nil {
		t.Fatal(e)
	}
	eventually(t, func() bool { b.mu.Lock(); defer b.mu.Unlock(); return b.publisher != nil && b.publisher != old })
	event.EventID = ID("evt_")
	event.Sequence = 2
	event.Payload = json.RawMessage(`{"userId":"usr_fixture","status":"ACTIVE","accountVersion":2}`)
	raw, _ = json.Marshal(event)
	publish(raw)
	eventually(t, func() bool { active, e := s.Store.Active(ctx, "usr_fixture"); return e == nil && active })
}
