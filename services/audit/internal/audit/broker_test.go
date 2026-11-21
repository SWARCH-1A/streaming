package audit

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	amqp "github.com/rabbitmq/amqp091-go"
	"os"
	"testing"
	"time"
)

func TestBrokerProducerIsolation(t *testing.T) {
	address := os.Getenv("AUDIT_TEST_VOD_BROKER_URL")
	if address == "" {
		t.Skip("requires disposable TLS broker with production ACLs")
	}
	pem, err := os.ReadFile(os.Getenv("AUDIT_TEST_BROKER_CA"))
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		t.Fatal("invalid CA")
	}
	conn, err := amqp.DialConfig(address, amqp.Config{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	for _, operation := range []string{"forge core", "read audit", "configure"} {
		t.Run(operation, func(t *testing.T) {
			ch, err := conn.Channel()
			if err != nil {
				t.Fatal(err)
			}
			defer ch.Close()
			switch operation {
			case "forge core":
				closed := ch.NotifyClose(make(chan *amqp.Error, 1))
				err = ch.Publish("p2.events", "security.audit.core.v1", true, false, amqp.Publishing{Body: []byte(`{}`)})
				if err == nil {
					select {
					case err = <-closed:
					case <-time.After(3 * time.Second):
						t.Fatal("forged producer accepted")
					}
				}
			case "read audit":
				_, _, err = ch.Get("audit.core", false)
			case "configure":
				_, err = ch.QueueDeclare("audit.forged", true, false, false, false, nil)
			}
			var denied *amqp.Error
			if !errors.As(err, &denied) || denied.Code != 403 {
				t.Fatalf("expected broker ACCESS_REFUSED, got %v", err)
			}
		})
	}
}
