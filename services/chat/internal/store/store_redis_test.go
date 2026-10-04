//go:build integration

// Pruebas contra un Redis real: go test -tags integration ./internal/store/ con CHAT_TEST_REDIS_URL.
// Usan una base dedicada que se vacía al empezar; nunca apuntar a un Redis con datos.
package store

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"

	"streaming/chat/internal/chat"
)

func realStore(t *testing.T) (*Store, *redis.Client) {
	t.Helper()
	url := os.Getenv("CHAT_TEST_REDIS_URL")
	if url == "" {
		t.Skip("CHAT_TEST_REDIS_URL no definida")
	}
	opts, err := redis.ParseURL(url)
	if err != nil {
		t.Fatal(err)
	}
	rdb := redis.NewClient(opts)
	t.Cleanup(func() { rdb.Close() })
	if err := rdb.FlushDB(context.Background()).Err(); err != nil {
		t.Fatal(err)
	}
	return New(rdb, Options{EndedRetention: 5 * time.Minute, IdleTTL: 12 * time.Hour, StreamMaxLen: 1000, InboxTTL: time.Hour}), rdb
}

func TestRealRedisAcceptDedupeQuotaAndSequence(t *testing.T) {
	s, _ := realStore(t)
	ctx := context.Background()
	first, err := s.Accept(ctx, input("ses_r", "u1", "c1", "hola"))
	if err != nil || first.Outcome != Accepted || first.Ack.Sequence != 1 {
		t.Fatalf("%+v %v", first, err)
	}
	if res, _ := s.Accept(ctx, input("ses_otra", "u1", "c2", "x")); res.Outcome != RateLimited || res.RetryAfter > time.Second {
		t.Fatalf("cuota global: %+v", res)
	}
	if res, _ := s.Accept(ctx, input("ses_r", "u1", "c1", "hola")); res.Outcome != Duplicate || res.Ack != first.Ack {
		t.Fatalf("dedupe: %+v", res)
	}
	if res, _ := s.Accept(ctx, input("ses_r", "u1", "c1", "otro")); res.Outcome != Conflict {
		t.Fatalf("conflicto: %+v", res)
	}
	time.Sleep(chat.QuotaWindow)
	if res, _ := s.Accept(ctx, input("ses_r", "u1", "c3", "después")); res.Outcome != Accepted || res.Ack.Sequence != 2 {
		t.Fatalf("tras la ventana: %+v", res)
	}
	snap, msgs, err := s.History(ctx, "ses_r", 50)
	if err != nil || snap != 2 || len(msgs) != 2 || msgs[0].Sequence != 1 || msgs[1].Text != "después" {
		t.Fatalf("historial %d %+v %v", snap, msgs, err)
	}
}

func TestRealRedisBlockingReadDeliversQuickly(t *testing.T) {
	s, _ := realStore(t)
	ctx := context.Background()
	got := make(chan time.Duration, 1)
	go func() {
		start := time.Now()
		msgs, err := s.ReadAfter(ctx, "ses_r", 0, 2*time.Second, 10)
		if err == nil && len(msgs) == 1 {
			got <- time.Since(start)
		}
		close(got)
	}()
	time.Sleep(200 * time.Millisecond)
	s.Accept(ctx, input("ses_r", "u1", "c1", "en vivo"))
	d, ok := <-got
	if !ok || d > time.Second {
		t.Fatalf("lectura bloqueante: ok=%v %v", ok, d)
	}
}

func TestRealRedisEndedSetsRetentionOnAllRoomKeys(t *testing.T) {
	s, rdb := realStore(t)
	ctx := context.Background()
	s.ApplyState(ctx, state("ses_r", 1, 1, chat.RoomOpen))
	s.Accept(ctx, input("ses_r", "u1", "c1", "hola"))
	if got, _ := s.ApplyEvent(ctx, "evt-1", "fp", state("ses_r", 1, 2, chat.RoomReadOnly)); got != Applied {
		t.Fatalf("evento: %s", got)
	}
	if got, _ := s.ApplyEvent(ctx, "evt-1", "fp", state("ses_r", 1, 2, chat.RoomReadOnly)); got != EventDuplicate {
		t.Fatalf("duplicado: %s", got)
	}
	for _, k := range []string{roomKey("ses_r"), seqKey("ses_r"), dedupeKey("ses_r"), msgsKey("ses_r")} {
		ttl := rdb.PTTL(ctx, k).Val()
		if ttl <= 4*time.Minute || ttl > 5*time.Minute {
			t.Fatalf("%s TTL %v", k, ttl)
		}
	}
	if res, _ := s.Accept(ctx, input("ses_r", "u2", "c2", "tarde")); res.Outcome != Denied || res.DenialCode != chat.CodeChatReadOnly {
		t.Fatalf("sala terminada: %+v", res)
	}
}
