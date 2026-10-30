package vod

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	amqp "github.com/rabbitmq/amqp091-go"
	"log/slog"
	"os"
	"sync"
	"time"
)

type Broker struct {
	URL, CA   string
	Service   *Service
	mu        sync.Mutex
	publisher *amqp.Channel
	returns   <-chan amqp.Return
}

func (b *Broker) publish(ctx context.Context, exchange, key string, msg amqp.Publishing) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.publisher == nil {
		return fmt.Errorf("broker unavailable")
	}
	confirm, e := b.publisher.PublishWithDeferredConfirmWithContext(ctx, exchange, key, true, false, msg)
	if e != nil {
		return e
	}
	wait, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	ok, e := confirm.WaitContext(wait)
	if e != nil || !ok {
		return fmt.Errorf("broker confirmation failed")
	}
	select {
	case <-b.returns:
		return fmt.Errorf("broker unroutable")
	default:
		return nil
	}
}
func (b *Broker) Publish(ctx context.Context, e Envelope, body []byte) error {
	return b.publish(ctx, "p2.events", e.EventType, amqp.Publishing{ContentType: "application/json", DeliveryMode: amqp.Persistent, MessageId: e.EventID, Type: e.EventType, Body: body})
}
func (b *Broker) Run(ctx context.Context) {
	for ctx.Err() == nil {
		if e := b.session(ctx); e != nil {
			slog.Warn("vod broker disconnected")
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}
func (b *Broker) session(ctx context.Context) error {
	roots, e := x509.SystemCertPool()
	if e != nil {
		roots = x509.NewCertPool()
	}
	if b.CA != "" {
		raw, e := os.ReadFile(b.CA)
		if e != nil {
			return e
		}
		if !roots.AppendCertsFromPEM(raw) {
			return fmt.Errorf("invalid broker CA")
		}
	}
	conn, e := amqp.DialConfig(b.URL, amqp.Config{Heartbeat: 30 * time.Second, TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}})
	if e != nil {
		return e
	}
	defer conn.Close()
	pub, e := conn.Channel()
	if e != nil {
		return e
	}
	if e = pub.Confirm(false); e != nil {
		return e
	}
	b.mu.Lock()
	b.publisher = pub
	b.returns = pub.NotifyReturn(make(chan amqp.Return, 32))
	b.mu.Unlock()
	defer func() { b.mu.Lock(); b.publisher = nil; b.mu.Unlock() }()
	failed := make(chan struct{}, 4)
	for _, queue := range []string{"vod.lifecycle", "vod.segments", "vod.recording", "vod.account"} {
		ch, e := conn.Channel()
		if e != nil {
			return e
		}
		if e = ch.Qos(8, 0, false); e != nil {
			return e
		}
		deliveries, e := ch.Consume(queue, "", false, false, false, false, nil)
		if e != nil {
			return e
		}
		go func(queue string, ch *amqp.Channel, deliveries <-chan amqp.Delivery) {
			defer func() { failed <- struct{}{} }()
			for msg := range deliveries {
				err := b.Service.Event(ctx, queue, msg.Body)
				if err != nil {
					attempt := int64(0)
					switch n := msg.Headers["x-p2-attempt"].(type) {
					case int64:
						attempt = n
					case int32:
						attempt = int64(n)
					case int:
						attempt = int64(n)
					}
					if attempt < 0 {
						attempt = 0
					}
					headers := amqp.Table{}
					for k, v := range msg.Headers {
						headers[k] = v
					}
					headers["x-p2-attempt"] = attempt + 1
					headers["x-p2-consumer"] = queue
					headers["x-p2-cause"] = "HANDLER_FAILED"
					headers["x-p2-original-route"] = msg.RoutingKey
					exchange, key := queue+".dead", "failed"
					if !IsPermanent(err) && attempt < 3 {
						exchange = queue + ".retry"
						key = []string{"1", "5", "30"}[attempt]
					}
					err = b.publish(ctx, exchange, key, amqp.Publishing{ContentType: msg.ContentType, DeliveryMode: amqp.Persistent, MessageId: msg.MessageId, Type: msg.Type, Body: msg.Body, Headers: headers})
				}
				if err == nil {
					_ = msg.Ack(false)
				} else {
					_ = ch.Close()
					return
				}
			}
		}(queue, ch, deliveries)
	}
	closed := conn.NotifyClose(make(chan *amqp.Error, 1))
	publisherClosed := pub.NotifyClose(make(chan *amqp.Error, 1))
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-closed:
			return fmt.Errorf("connection closed")
		case <-publisherClosed:
			return fmt.Errorf("publisher closed")
		case <-failed:
			return fmt.Errorf("subscription closed")
		case <-ticker.C:
			_ = b.Service.Store.Outbox(ctx, func(ev Envelope, raw []byte) error { return b.Publish(ctx, ev, raw) })
		}
	}
}
