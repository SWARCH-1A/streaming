package broker

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"log/slog"
	"net/url"
	"os"
	"strconv"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"streaming/chat/internal/chat"
	"streaming/chat/internal/store"
)

type EventStore interface {
	ApplyBrokerEvent(context.Context, string, string, string, chat.SessionState) (store.ApplyResult, error)
}

type Consumer struct {
	address string
	tls     *tls.Config
	store   EventStore
	log     *slog.Logger
}

func New(address, caFile string, st EventStore, log *slog.Logger) (*Consumer, error) {
	uri, err := url.Parse(address)
	if err != nil || uri.Scheme != "amqps" || uri.Hostname() == "" {
		return nil, errors.New("CHAT_BROKER_URL debe usar AMQPS")
	}
	ca, err := os.ReadFile(caFile)
	if err != nil {
		return nil, errors.New("CHAT_BROKER_CA_FILE no se puede leer")
	}
	roots, err := x509.SystemCertPool()
	if err != nil {
		roots = x509.NewCertPool()
	}
	if !roots.AppendCertsFromPEM(ca) {
		return nil, errors.New("CA de broker inválida")
	}
	return &Consumer{address: address, tls: &tls.Config{RootCAs: roots, ServerName: uri.Hostname(), MinVersion: tls.VersionTLS12}, store: st, log: log}, nil
}

func (c *Consumer) Run(ctx context.Context) {
	for ctx.Err() == nil {
		// Error strings from the AMQP library may contain connection information.
		if err := c.consume(ctx); err != nil && ctx.Err() == nil {
			c.log.Warn("broker no disponible", "consumer", "chat.lifecycle")
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

func (c *Consumer) consume(ctx context.Context) error {
	conn, err := amqp.DialConfig(c.address, amqp.Config{TLSClientConfig: c.tls, Heartbeat: 30 * time.Second, Dial: amqp.DefaultDial(3 * time.Second)})
	if err != nil {
		return err
	}
	defer conn.Close()
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-conn.NotifyClose(make(chan *amqp.Error, 1)):
		}
	}()
	channel, err := conn.Channel()
	if err != nil {
		return err
	}
	defer channel.Close()
	if err = channel.Qos(16, 0, false); err != nil {
		return err
	}
	publisher, err := conn.Channel()
	if err != nil {
		return err
	}
	defer publisher.Close()
	if err = publisher.Confirm(false); err != nil {
		return err
	}
	returns := publisher.NotifyReturn(make(chan amqp.Return, 1))
	deliveries, err := channel.Consume("chat.lifecycle", "", false, false, false, false, nil)
	if err != nil {
		return err
	}
	for {
		select {
		case <-ctx.Done():
			return nil
		case delivery, ok := <-deliveries:
			if !ok {
				return errors.New("consumer stopped")
			}
			cause, permanent := "", false
			event, state, fingerprint, validation := decode(delivery.Body)
			if validation != nil || delivery.ContentType != "application/json" {
				cause, permanent = "INVALID_EVENT", true
			} else {
				work, cancel := context.WithTimeout(ctx, 3*time.Second)
				result, applyErr := c.store.ApplyBrokerEvent(work, event.EventID, fingerprint, event.stateFingerprint, state)
				cancel()
				if applyErr != nil {
					cause = "STORE_UNAVAILABLE"
				} else if result == store.EventConflict {
					cause, permanent = "EVENT_ID_CONFLICT", true
				} else {
					occurred, _ := utc(event.OccurredAtUTC)
					c.log.Info("broker consumido", "consumer", "chat.lifecycle", "eventId", event.EventID,
						"correlationId", event.CorrelationID, "outcome", result, "ageMs", max(0, time.Since(occurred).Milliseconds()))
				}
			}
			if cause != "" {
				if err = c.retain(ctx, publisher, returns, delivery, cause, permanent); err != nil {
					return err
				}
			}
			if err = delivery.Ack(false); err != nil {
				return err
			}
		}
	}
}

func (c *Consumer) retain(ctx context.Context, publisher *amqp.Channel, returns <-chan amqp.Return, d amqp.Delivery, cause string, permanent bool) error {
	headers := amqp.Table{}
	for key, value := range d.Headers {
		headers[key] = value
	}
	var attempt int
	switch value := headers["x-p2-attempt"].(type) {
	case int32:
		attempt = int(value)
	case int64:
		attempt = int(value)
	case int:
		attempt = value
	}
	if attempt < 0 {
		attempt = 0
	}
	dead := permanent || attempt >= 3
	exchange, key := "chat.lifecycle.dead", "failed"
	if !dead {
		exchange = "chat.lifecycle.retry"
		key = strconv.Itoa([]int{1, 5, 30}[attempt])
	}
	headers["x-p2-consumer"] = "chat.lifecycle"
	headers["x-p2-cause"] = cause
	headers["x-p2-attempt"] = int32(attempt + 1)
	if _, ok := headers["x-p2-first-failure"]; !ok {
		headers["x-p2-first-failure"] = time.Now().UnixMilli()
	}
	headers["x-p2-last-failure"] = time.Now().UnixMilli()
	if _, ok := headers["x-p2-original-route"]; !ok {
		headers["x-p2-original-route"] = d.RoutingKey
	}
	budget, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	confirm, err := publisher.PublishWithDeferredConfirmWithContext(budget, exchange, key, true, false, amqp.Publishing{
		Headers: headers, ContentType: d.ContentType, DeliveryMode: amqp.Persistent, Body: d.Body,
		MessageId: d.MessageId, CorrelationId: d.CorrelationId, Type: d.Type, Timestamp: d.Timestamp,
	})
	if err != nil {
		return err
	}
	ok, err := confirm.WaitContext(budget)
	if err != nil || !ok {
		return errors.New("retry/DLQ confirmation unknown")
	}
	select {
	case <-returns:
		return errors.New("retry/DLQ unroutable")
	default:
	}
	c.log.Warn("broker entrega fallida", "consumer", "chat.lifecycle", "cause", cause, "attempt", attempt+1, "deadLetter", dead)
	return nil
}
