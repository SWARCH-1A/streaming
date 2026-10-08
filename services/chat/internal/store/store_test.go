package store

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"streaming/chat/internal/chat"
)

func newStore(t *testing.T) (*Store, *miniredis.Miniredis) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { rdb.Close() })
	return New(rdb, Options{
		EndedRetention: 5 * time.Minute, IdleTTL: 12 * time.Hour, InboxTTL: 24 * time.Hour,
	}), mr
}

func input(sid, user, cmid, text string) AcceptInput {
	return AcceptInput{
		SessionID: sid, UserID: user, ClientMessageID: cmid, TextHash: chat.TextFingerprint(text),
		WriteAllowed: true,
		Message: chat.Message{
			MessageID: "msg_" + cmid, SessionID: sid, Text: text,
			Author:             chat.Author{UserID: user, Handle: user, DisplayName: user},
			ServerCreatedAtUTC: time.UnixMilli(1_700_000_000_000).UTC(), StreamOffsetMs: 1234,
		},
	}
}

func TestAcceptAssignsIncreasingSequencePerSession(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	for i, u := range []string{"u1", "u2", "u3"} {
		res, err := s.Accept(ctx, input("ses_a", u, "c-"+u, "hola"))
		if err != nil || res.Outcome != Accepted || res.Ack.Sequence != int64(i+1) {
			t.Fatalf("msg %d: %+v %v", i, res, err)
		}
	}
	res, _ := s.Accept(ctx, input("ses_b", "u4", "c-u4", "otra sala"))
	if res.Ack.Sequence != 1 {
		t.Fatalf("la secuencia debe ser independiente por sesión, got %d", res.Ack.Sequence)
	}
	if ttl := mr.TTL("chat:{ses_a}:msgs"); ttl != 0 {
		t.Fatalf("mensajes activos no deben expirar por inactividad: %v", ttl)
	}
}

func TestQuietLiveSessionPreservesMessagesDedupeAndSequenceBeyondStateCacheTTL(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	s.ApplyState(ctx, state("ses_a", 1, 1, chat.RoomOpen))
	first, err := s.Accept(ctx, input("ses_a", "u1", "c1", "hola"))
	if err != nil || first.Outcome != Accepted {
		t.Fatalf("%+v %v", first, err)
	}
	mr.FastForward(13 * time.Hour)
	// State is recoverable from Core. Confirmed messages and their sequence/dedupe are not.
	s.ApplyState(ctx, state("ses_a", 1, 2, chat.RoomOpen))
	duplicate, err := s.Accept(ctx, input("ses_a", "u1", "c1", "hola"))
	if err != nil || duplicate.Outcome != Duplicate || duplicate.Ack != first.Ack {
		t.Fatalf("%+v %v", duplicate, err)
	}
	next, err := s.Accept(ctx, input("ses_a", "u2", "c2", "después"))
	if err != nil || next.Outcome != Accepted || next.Ack.Sequence != 2 {
		t.Fatalf("%+v %v", next, err)
	}
	s.ApplyState(ctx, state("ses_a", 1, 3, chat.RoomReadOnly))
	mr.FastForward(4 * time.Minute)
	s.ApplyEvent(ctx, "late-end", "fp", state("ses_a", 1, 3, chat.RoomReadOnly))
	if snapshot, messages, err := s.History(ctx, "ses_a", 50); err != nil || snapshot != 2 || len(messages) != 2 {
		t.Fatalf("%d %+v %v", snapshot, messages, err)
	}
	mr.FastForward(time.Minute + time.Millisecond)
	if snapshot, messages, err := s.History(ctx, "ses_a", 50); err != nil || snapshot != 0 || len(messages) != 0 {
		t.Fatalf("retención renovada: %d %+v %v", snapshot, messages, err)
	}
}

func TestAcceptedMessagesAreNotTrimmedWhileRoomExists(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	const total = 2500 // más que la carga por sala de 10 min a 20 msg/s repartidos en cinco salas
	for i := 0; i < total; i++ {
		u := fmt.Sprintf("u%d", i)
		if res, err := s.Accept(ctx, input("ses_a", u, "c-"+u, "m")); err != nil || res.Outcome != Accepted {
			t.Fatalf("msg %d: %+v %v", i, res, err)
		}
	}
	if n, _ := s.rdb.XLen(ctx, msgsKey("ses_a")).Result(); n != total {
		t.Fatalf("mensajes con ACK recortados: quedan %d de %d", n, total)
	}
	msgs, err := s.ReadAfter(ctx, "ses_a", 0, time.Millisecond, 1)
	if err != nil || len(msgs) != 1 || msgs[0].Sequence != 1 {
		t.Fatalf("el primer mensaje confirmado debe seguir legible: %+v %v", msgs, err)
	}
}

func TestQuotaIsGlobalAcrossRoomsWithoutBurst(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	if res, _ := s.Accept(ctx, input("ses_a", "u1", "c1", "uno")); res.Outcome != Accepted {
		t.Fatalf("primer envío %+v", res)
	}
	res, _ := s.Accept(ctx, input("ses_b", "u1", "c2", "otra sala"))
	if res.Outcome != RateLimited || res.RetryAfter <= 0 || res.RetryAfter > time.Second {
		t.Fatalf("debe limitar entre salas: %+v", res)
	}
	if seq, _ := s.LastSequence(ctx, "ses_b"); seq != 0 {
		t.Fatal("un envío rechazado no debe asignar secuencia")
	}
	mr.FastForward(999 * time.Millisecond)
	if res, _ := s.Accept(ctx, input("ses_b", "u1", "c3", "aún no")); res.Outcome != RateLimited {
		t.Fatalf("999 ms sigue dentro de la ventana: %+v", res)
	}
	mr.FastForward(time.Millisecond)
	if res, _ := s.Accept(ctx, input("ses_b", "u1", "c4", "ya")); res.Outcome != Accepted {
		t.Fatalf("a los 1000 ms se acepta: %+v", res)
	}
	if res, _ := s.Accept(ctx, input("ses_b", "u2", "c5", "otra cuenta")); res.Outcome != Accepted {
		t.Fatalf("la cuota es por cuenta: %+v", res)
	}
}

func TestDuplicateReturnsSameAckWithoutQuotaOrNewMessage(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	first, _ := s.Accept(ctx, input("ses_a", "u1", "c1", "hola"))
	mr.FastForward(5 * time.Second)
	other, _ := s.Accept(ctx, input("ses_a", "u2", "c9", "otro"))

	retry := input("ses_a", "u1", "c1", "hola")
	retry.Message.MessageID = "msg_distinto"
	retry.WriteAllowed = false // p. ej. la sala terminó después del primer envío
	retry.DenialCode = chat.CodeChatReadOnly
	dup, err := s.Accept(ctx, retry)
	if err != nil || dup.Outcome != Duplicate || dup.Ack != first.Ack {
		t.Fatalf("dup %+v first %+v err %v", dup, first, err)
	}
	if seq, _ := s.LastSequence(ctx, "ses_a"); seq != other.Ack.Sequence {
		t.Fatal("un duplicado no crea mensaje")
	}
	// El duplicado no consumió cuota: u1 puede enviar uno nuevo ya.
	if res, _ := s.Accept(ctx, input("ses_a", "u1", "c2", "nuevo")); res.Outcome != Accepted {
		t.Fatalf("duplicado consumió cuota: %+v", res)
	}
}

func TestSameClientMessageIDWithOtherTextConflicts(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	s.Accept(ctx, input("ses_a", "u1", "c1", "hola"))
	res, _ := s.Accept(ctx, input("ses_a", "u1", "c1", "adiós"))
	if res.Outcome != Conflict {
		t.Fatalf("got %+v", res)
	}
}

func TestDeniedWithoutWritePermissionDoesNotPersist(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	in := input("ses_a", "u1", "c1", "hola")
	in.WriteAllowed, in.DenialCode = false, chat.CodeChatNotOpen
	res, _ := s.Accept(ctx, in)
	if res.Outcome != Denied || res.DenialCode != chat.CodeChatNotOpen {
		t.Fatalf("got %+v", res)
	}
	if res, _ := s.Accept(ctx, input("ses_a", "u1", "c2", "x")); res.Outcome != Accepted || res.Ack.Sequence != 1 {
		t.Fatalf("un rechazo no consume cuota ni secuencia: %+v", res)
	}
}

func TestHistoryReturnsLastNAscendingWithSnapshotSequence(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	for i := 0; i < 60; i++ {
		u := "u" + string(rune('a'+i%26)) + string(rune('a'+i/26))
		s.Accept(ctx, input("ses_a", u, "c"+u, "m"))
		mr.FastForward(time.Second)
	}
	s.Accept(ctx, input("ses_b", "zz", "czz", "ajeno"))
	snap, msgs, err := s.History(ctx, "ses_a", 50)
	if err != nil || snap != 60 || len(msgs) != 50 {
		t.Fatalf("snap %d len %d err %v", snap, len(msgs), err)
	}
	for i, m := range msgs {
		if m.Sequence != int64(11+i) || m.SessionID != "ses_a" {
			t.Fatalf("pos %d: seq %d session %s", i, m.Sequence, m.SessionID)
		}
	}
	if msgs[0].StreamOffsetMs != 1234 || msgs[0].Author.UserID == "" {
		t.Fatalf("campos de replay incompletos: %+v", msgs[0])
	}
	snap, msgs, _ = s.History(ctx, "ses_vacia", 50)
	if snap != 0 || len(msgs) != 0 {
		t.Fatal("sala sin mensajes")
	}
}

func TestReadAfterDeliversCommittedMessagesInOrder(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	s.Accept(ctx, input("ses_a", "u1", "c1", "uno"))
	s.Accept(ctx, input("ses_a", "u2", "c2", "dos"))
	msgs, err := s.ReadAfter(ctx, "ses_a", 1, 0, 10)
	if err != nil || len(msgs) != 1 || msgs[0].Sequence != 2 || msgs[0].Text != "dos" {
		t.Fatalf("%+v %v", msgs, err)
	}
}

func state(sid string, gen, ver int64, status chat.RoomStatus) chat.SessionState {
	return chat.SessionState{SessionID: sid, StreamGeneration: gen, SessionVersion: ver, Status: status, Availability: "PLAYABLE"}
}

func TestApplyStateKeepsNewestAndEndedIsTerminal(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	steps := []struct {
		st   chat.SessionState
		want ApplyResult
		room chat.RoomStatus
	}{
		{state("ses_a", 1, 1, chat.RoomNotOpen), Applied, chat.RoomNotOpen},
		{state("ses_a", 1, 3, chat.RoomOpen), Applied, chat.RoomOpen},
		{state("ses_a", 1, 2, chat.RoomNotOpen), Stale, chat.RoomOpen},
		{state("ses_a", 1, 3, chat.RoomNotOpen), Stale, chat.RoomOpen},
		{state("ses_a", 1, 4, chat.RoomReadOnly), Applied, chat.RoomReadOnly},
		{state("ses_a", 2, 9, chat.RoomOpen), Stale, chat.RoomReadOnly},
	}
	for i, step := range steps {
		got, err := s.ApplyState(ctx, step.st)
		room, _ := s.Room(ctx, "ses_a")
		if err != nil || got != step.want || room.Status != step.room {
			t.Fatalf("paso %d: got %s room %s err %v", i, got, room.Status, err)
		}
	}
}

func TestEndedRoomIsReadOnlyThenExpiresAfterRetention(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	s.ApplyState(ctx, state("ses_a", 1, 1, chat.RoomOpen))
	s.Accept(ctx, input("ses_a", "u1", "c1", "hola"))
	s.ApplyState(ctx, state("ses_a", 1, 2, chat.RoomReadOnly))

	mr.FastForward(2 * time.Second)
	res, _ := s.Accept(ctx, input("ses_a", "u2", "c2", "tarde"))
	if res.Outcome != Denied || res.DenialCode != chat.CodeChatReadOnly {
		t.Fatalf("sala terminada acepta mensajes: %+v", res)
	}
	if res, _ := s.Accept(ctx, input("ses_a", "u1", "c1", "hola")); res.Outcome != Duplicate {
		t.Fatalf("el ACK previo sigue recuperable durante la retención: %+v", res)
	}
	if _, msgs, _ := s.History(ctx, "ses_a", 50); len(msgs) != 1 {
		t.Fatal("historial legible durante la retención")
	}

	mr.FastForward(5 * time.Minute)
	room, _ := s.Room(ctx, "ses_a")
	snap, msgs, _ := s.History(ctx, "ses_a", 50)
	if room.Known || snap != 0 || len(msgs) != 0 {
		t.Fatalf("la sala debe borrarse tras la retención: room %+v snap %d msgs %d", room, snap, len(msgs))
	}
	for _, k := range mr.Keys() {
		if k != "chat:quota:u1" && k != "chat:quota:u2" {
			t.Fatalf("clave residual %s", k)
		}
	}
}

func TestApplyEventDeduplicatesByEventID(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	st := state("ses_a", 1, 1, chat.RoomOpen)
	if got, _ := s.ApplyEvent(ctx, "evt-1", "fp-1", st); got != Applied {
		t.Fatalf("got %s", got)
	}
	if got, _ := s.ApplyEvent(ctx, "evt-1", "fp-1", st); got != EventDuplicate {
		t.Fatalf("got %s", got)
	}
	if got, _ := s.ApplyEvent(ctx, "evt-1", "fp-2", st); got != EventConflict {
		t.Fatalf("got %s", got)
	}
	if got, _ := s.ApplyEvent(ctx, "evt-2", "fp-3", state("ses_a", 1, 1, chat.RoomOpen)); got != Stale {
		t.Fatalf("got %s", got)
	}
}
