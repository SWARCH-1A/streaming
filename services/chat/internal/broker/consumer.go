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
	"streaming/chat/internal/archive"
	"streaming/chat/internal/chat"
	"streaming/chat/internal/store"
)

type EventStore interface {
	ApplyBrokerEvent(context.Context, string, string, string, chat.SessionState) (store.ApplyResult, error)
}

// VodApplier registra el vínculo VOD–sesión de forma durable.
type VodApplier interface {
	ApplyVodEvent(context.Context, string, string, archive.VodLink) (archive.VodOutcome, error)
}

// handler procesa una entrega; cause vacío significa confirmada.
type handler func(ctx context.Context, d amqp.Delivery) (cause string, permanent bool)

type Consumer struct {
	address string
	tls     *tls.Config
	queue   string
	handle  handler
	log     *slog.Logger
}

// New consume chat.lifecycle: el ciclo de sesión de Streaming.
func New(address, caFile string, st EventStore, log *slog.Logger) (*Consumer, error) {
	c, err := dial(address, caFile, log)
	if err != nil {
		return nil, err
	}
	c.queue = "chat.lifecycle"
	c.handle = func(ctx context.Context, delivery amqp.Delivery) (string, bool) {
		event, state, fingerprint, validation := decode(delivery.Body)
		if validation != nil || delivery.ContentType != "application/json" {
			return "INVALID_EVENT", true
		}
		work, cancel := context.WithTimeout(ctx, 3*time.Second)
		result, applyErr := st.ApplyBrokerEvent(work, event.EventID, fingerprint, event.stateFingerprint, state)
		cancel()
		if applyErr != nil {
			return "STORE_UNAVAILABLE", false
		}
		if result == store.EventConflict {
			return "EVENT_ID_CONFLICT", true
		}
		occurred, _ := utc(event.OccurredAtUTC)
		c.log.Info("broker consumido", "consumer", c.queue, "eventId", event.EventID,
			"correlationId", event.CorrelationID, "outcome", result, "ageMs", max(0, time.Since(occurred).Milliseconds()))
		return "", false
	}
	return c, nil
}

// NewVod consume chat.vod: disponibilidad y vínculo de VODs publicados por VOD.
func NewVod(address, caFile string, svc VodApplier, log *slog.Logger) (*Consumer, error) {
	c, err := dial(address, caFile, log)
	if err != nil {
		return nil, err
	}
	c.queue = "chat.vod"
	c.handle = func(ctx context.Context, delivery amqp.Delivery) (string, bool) {
		event, link, fingerprint, validation := decodeVod(delivery.Body)
		if validation != nil || delivery.ContentType != "application/json" {
			return "INVALID_EVENT", true
		}
		work, cancel := context.WithTimeout(ctx, 3*time.Second)
		result, applyErr := svc.ApplyVodEvent(work, event.EventID, fingerprint, link)
		cancel()
		if applyErr != nil {
			return "STORE_UNAVAILABLE", false
		}
		if result == archive.VodConflict {
			return "EVENT_ID_CONFLICT", true
		}
		c.log.Info("broker consumido", "consumer", c.queue, "eventId", event.EventID,
			"correlationId", event.CorrelationID, "outcome", result)
		return "", false
	}
	return c, nil
}

func dial(address, caFile string, log *slog.Logger) (*Consumer, error) {
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
	return &Consumer{address: address, tls: &tls.Config{RootCAs: roots, ServerName: uri.Hostname(), MinVersion: tls.VersionTLS12}, log: log}, nil
}

func (c *Consumer) Run(ctx context.Context) {
	for ctx.Err() == nil {
		// Error strings from the AMQP library may contain connection information.
		if err := c.consume(ctx); err != nil && ctx.Err() == nil {
			c.log.Warn("broker no disponible", "consumer", c.queue)
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
	deliveries, err := channel.Consume(c.queue, "", false, false, false, false, nil)
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
			cause, permanent := c.handle(ctx, delivery)
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
	exchange, key := c.queue+".dead", "failed"
	if !dead {
		exchange = c.queue + ".retry"
		key = strconv.Itoa([]int{1, 5, 30}[attempt])
	}
	headers["x-p2-consumer"] = c.queue
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
	c.log.Warn("broker entrega fallida", "consumer", c.queue, "cause", cause, "attempt", attempt+1, "deadLetter", dead)
	return nil
}
