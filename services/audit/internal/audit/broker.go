package audit

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	amqp "github.com/rabbitmq/amqp091-go"
	"log/slog"
	"os"
	"time"
)

func RunConsumer(ctx context.Context, s *Store, address, ca, producer string, development bool) {
	for ctx.Err() == nil {
		if err := consume(ctx, s, address, ca, producer, development); err != nil {
			slog.Warn("audit consumer reconnecting", "producer", producer)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}
func consume(ctx context.Context, s *Store, address, ca, producer string, development bool) error {
	uri, err := amqp.ParseURI(address)
	if err != nil || (!development && uri.Scheme != "amqps") {
		return ErrInvalid
	}
	roots, err := x509.SystemCertPool()
	if err != nil {
		roots = x509.NewCertPool()
	}
	if ca != "" {
		raw, err := os.ReadFile(ca)
		if err != nil {
			return err
		}
		if !roots.AppendCertsFromPEM(raw) {
			return ErrInvalid
		}
	}
	conn, err := amqp.DialConfig(address, amqp.Config{Heartbeat: 30 * time.Second, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots}, Dial: amqp.DefaultDial(3 * time.Second)})
	if err != nil {
		return err
	}
	defer conn.Close()
	ch, err := conn.Channel()
	if err != nil {
		return err
	}
	defer ch.Close()
	if err = ch.Qos(16, 0, false); err != nil {
		return err
	}
	pub, err := conn.Channel()
	if err != nil {
		return err
	}
	defer pub.Close()
	if err = pub.Confirm(false); err != nil {
		return err
	}
	returns := pub.NotifyReturn(make(chan amqp.Return, 1))
	queue := "audit." + producer
	deliveries, err := ch.Consume(queue, "", false, false, false, false, nil)
	if err != nil {
		return err
	}
	for {
		select {
		case <-ctx.Done():
			return nil
		case msg, ok := <-deliveries:
			if !ok {
				return fmt.Errorf("consumer closed")
			}
			event, err := Parse(msg.Body, producer)
			if msg.RoutingKey != "security.audit."+producer+".v1" && msg.Exchange == "p2.events" {
				err = ErrInvalid
			}
			if msg.MessageId != event.EventID || msg.ContentType != "application/json" {
				err = ErrInvalid
			}
			if err == nil {
				call, cancel := context.WithTimeout(ctx, 3*time.Second)
				err = s.Append(call, event)
				cancel()
			}
			if errors.Is(err, ErrInvalid) || errors.Is(err, ErrConflict) {
				wait, cancel := context.WithTimeout(ctx, 3*time.Second)
				confirm, e := pub.PublishWithDeferredConfirmWithContext(wait, queue+".dead", "failed", true, false, amqp.Publishing{ContentType: "application/json", DeliveryMode: amqp.Persistent, MessageId: msg.MessageId, Body: msg.Body})
				if e == nil {
					var ack bool
					ack, e = confirm.WaitContext(wait)
					if !ack && e == nil {
						e = fmt.Errorf("not confirmed")
					}
				}
				cancel()
				if e != nil {
					return e
				}
				select {
				case <-returns:
					return fmt.Errorf("unroutable dead letter")
				default:
				}
				if e = msg.Ack(false); e != nil {
					return e
				}
				slog.Warn("audit event quarantined", "producer", producer)
			} else if err != nil {
				// Keep the original delivery unacknowledged; reconnect backs off instead of spinning.
				return err
			} else if err = msg.Ack(false); err != nil {
				return err
			}
		}
	}
}
