// waf-relay publishes ModSecurity blocks to the Security Logger (ADR-032).
//
// The WAF writes one audit file per relevant transaction into a volume it shares only with this
// relay (it stays on the edge network). Each block becomes a security.audit.waf.v1 event; the file
// is deleted only after the broker confirms it, so the spool acts as the WAF's outbox.
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	settle      = 250 * time.Millisecond // a truncated record does not parse and is retried
	quarantine  = time.Minute            // an unparseable file this old is dropped, not retried forever
	maxRecord   = 1 << 20                // audit records here are a few KiB
	heartbeat   = "/tmp/waf-relay.alive"
	routingKey  = "security.audit.waf.v1"
	publishWait = 3 * time.Second
)

type publisher interface {
	Publish(ctx context.Context, id string, body []byte) error
}

type relay struct {
	spool      string
	maxBacklog int
	now        func() time.Time
}

type pending struct {
	path string
	mod  time.Time
}

func (r relay) files() ([]pending, error) {
	var out []pending
	err := filepath.WalkDir(r.spool, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				return nil
			}
			return err
		}
		if d.Type().IsRegular() {
			if info, e := d.Info(); e == nil {
				out = append(out, pending{path, info.ModTime()})
			}
		}
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].mod.Before(out[j].mod) })
	return out, err
}

// Pass handles every settled file once. It returns the first publication error so the caller can
// reconnect; files that were not confirmed stay for the next pass.
func (r relay) Pass(ctx context.Context, pub publisher) (published int, err error) {
	files, err := r.files()
	if err != nil {
		return 0, err
	}
	if excess := len(files) - r.maxBacklog; excess > 0 {
		// Bounded disk under a sustained attack: the oldest unpublished records are dropped and counted.
		for _, f := range files[:excess] {
			_ = os.Remove(f.path)
		}
		slog.Warn("waf audit backlog exceeded; oldest records dropped", "dropped", excess)
		files = files[excess:]
	}
	for _, f := range files {
		age := r.now().Sub(f.mod)
		if age < settle {
			continue
		}
		raw, e := readBounded(f.path)
		var body []byte
		var id string
		var publish bool
		if e == nil {
			body, id, publish, e = Convert(raw)
		}
		if e != nil {
			if age > quarantine {
				_ = os.Remove(f.path)
				slog.Warn("waf audit record dropped: unreadable")
			}
			continue
		}
		if publish {
			if pub == nil {
				return published, fmt.Errorf("broker unavailable")
			}
			if e = pub.Publish(ctx, id, body); e != nil {
				return published, e
			}
			published++
			// Operational trace for tuning exclusions: the file (with every matched rule) is deleted
			// next. Only rule IDs and the request ID are logged, never matched data or addresses.
			var event struct {
				CorrelationID string `json:"correlationId"`
			}
			_ = json.Unmarshal(body, &event)
			slog.Info("waf block published", "requestId", event.CorrelationID, "rules", Rules(raw))
		}
		if e = os.Remove(f.path); e != nil && !errors.Is(e, fs.ErrNotExist) {
			return published, e
		}
	}
	r.prune()
	return published, nil
}

// Prune removes empty per-minute directories once ModSecurity can no longer write into them.
func (r relay) prune() {
	var dirs []string
	_ = filepath.WalkDir(r.spool, func(path string, d fs.DirEntry, err error) error {
		if err == nil && d.IsDir() && path != r.spool {
			dirs = append(dirs, path)
		}
		return nil
	})
	sort.Sort(sort.Reverse(sort.StringSlice(dirs)))
	for _, dir := range dirs {
		if info, err := os.Stat(dir); err == nil && r.now().Sub(info.ModTime()) > 5*time.Minute {
			_ = os.Remove(dir) // fails harmlessly while the directory still has records
		}
	}
}

func readBounded(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, maxRecord+1))
	if err != nil || len(raw) > maxRecord {
		return nil, errInvalid
	}
	return raw, nil
}

type broker struct {
	ch      *amqp.Channel
	returns <-chan amqp.Return
}

func (b *broker) Publish(ctx context.Context, id string, body []byte) error {
	wait, cancel := context.WithTimeout(ctx, publishWait)
	defer cancel()
	confirm, err := b.ch.PublishWithDeferredConfirmWithContext(wait, "p2.events", routingKey, true, false,
		amqp.Publishing{ContentType: "application/json", DeliveryMode: amqp.Persistent, MessageId: id, Type: routingKey, Body: body})
	if err != nil {
		return err
	}
	ok, err := confirm.WaitContext(wait)
	if err != nil || !ok {
		return fmt.Errorf("broker confirmation failed")
	}
	select {
	case <-b.returns:
		return fmt.Errorf("broker unroutable")
	default:
		return nil
	}
}

func connect(address, ca string) (*amqp.Connection, *broker, error) {
	uri, err := amqp.ParseURI(address)
	if err != nil || uri.Scheme != "amqps" {
		return nil, nil, fmt.Errorf("AMQPS broker URL required")
	}
	roots, err := x509.SystemCertPool()
	if err != nil {
		roots = x509.NewCertPool()
	}
	if ca != "" {
		raw, err := os.ReadFile(ca)
		if err != nil {
			return nil, nil, err
		}
		if !roots.AppendCertsFromPEM(raw) {
			return nil, nil, fmt.Errorf("invalid broker CA")
		}
	}
	conn, err := amqp.DialConfig(address, amqp.Config{Heartbeat: 30 * time.Second,
		TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots}, Dial: amqp.DefaultDial(3 * time.Second)})
	if err != nil {
		return nil, nil, err
	}
	ch, err := conn.Channel()
	if err == nil {
		err = ch.Confirm(false)
	}
	if err != nil {
		conn.Close()
		return nil, nil, err
	}
	return conn, &broker{ch: ch, returns: ch.NotifyReturn(make(chan amqp.Return, 1))}, nil
}

func session(ctx context.Context, r relay, address, ca string) error {
	conn, b, err := connect(address, ca)
	if err != nil {
		return err
	}
	defer conn.Close()
	closed := conn.NotifyClose(make(chan *amqp.Error, 1))
	slog.Info("waf relay connected")
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()
	for {
		n, err := r.Pass(ctx, b)
		if n > 0 {
			slog.Info("waf blocks published", "count", n)
		}
		if err != nil {
			return err
		}
		_ = os.WriteFile(heartbeat, nil, 0o600)
		select {
		case <-ctx.Done():
			return nil
		case <-closed:
			return fmt.Errorf("broker connection closed")
		case <-tick.C:
		}
	}
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if len(os.Args) > 1 && os.Args[1] == "health" {
		// Healthy while a broker session completed a pass in the last 30 seconds.
		info, err := os.Stat(heartbeat)
		if err != nil || time.Since(info.ModTime()) > 30*time.Second {
			os.Exit(1)
		}
		return
	}
	address := os.Getenv("WAF_RELAY_BROKER_URL")
	backlog, err := strconv.Atoi(env("WAF_RELAY_MAX_BACKLOG", "5000"))
	if address == "" || err != nil || backlog < 1 {
		slog.Error("WAF_RELAY_BROKER_URL and a positive WAF_RELAY_MAX_BACKLOG are required")
		os.Exit(2)
	}
	r := relay{spool: env("WAF_RELAY_SPOOL", "/spool"), maxBacklog: backlog, now: time.Now}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	for ctx.Err() == nil {
		if err := session(ctx, r, address, os.Getenv("WAF_RELAY_BROKER_CA_FILE")); err != nil {
			// Never log the URL: it carries the broker credential.
			slog.Warn("waf relay reconnecting", "reason", strings.SplitN(err.Error(), ":", 2)[0])
		}
		select {
		case <-ctx.Done():
		case <-time.After(2 * time.Second):
		}
	}
}
