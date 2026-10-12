package service

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
	"streaming/chat/internal/store"
)

// A successful transport can complete late when decoding/scheduling exhausts its deadline.
// The application must reject that context before touching persistence.
type lateCore struct{}

func (lateCore) MessageContext(context.Context, string, string, string) (core.MessageContext, *chat.Error) {
	time.Sleep(450 * time.Millisecond)
	position := int64(1000)
	return core.MessageContext{UserID: "usr_fixture", SessionID: "ses_fixture", WriteAllowed: true, TimelinePositionMs: &position}, nil
}

func (lateCore) Snapshot(context.Context, string) (core.SessionSnapshot, *chat.Error) {
	panic("unexpected snapshot")
}

func TestLateCoreContextNeverAttemptsPersistence(t *testing.T) {
	// A wider overall budget isolates the separate 400 ms context contract. A nil store also
	// makes an unintended persistence attempt fail immediately instead of accepting a fixture.
	svc := New(lateCore{}, nil, slog.New(slog.NewTextHandler(io.Discard, nil)), 2*time.Second)
	_, fresh, err := svc.Send(context.Background(), "ses_fixture", "fixture-cookie",
		"550e8400-e29b-41d4-a716-446655440000", "Texto válido")
	if err == nil || err.Code != chat.CodeCoreUnavailable || fresh {
		t.Fatalf("late context must fail closed, fresh=%v err=%v", fresh, err)
	}
}

// snapshotCore responde snapshots por sesión; fail simula los errores que Core puede devolver.
type snapshotCore struct {
	status map[string]string
	fail   map[string]string
}

func (snapshotCore) MessageContext(context.Context, string, string, string) (core.MessageContext, *chat.Error) {
	panic("unexpected message context")
}

func (f snapshotCore) Snapshot(_ context.Context, sid string) (core.SessionSnapshot, *chat.Error) {
	if code, ok := f.fail[sid]; ok {
		return core.SessionSnapshot{}, chat.Fail(code)
	}
	return core.SessionSnapshot{SessionID: sid, StreamGeneration: 1, SessionVersion: 9, Status: f.status[sid], Availability: "OFFLINE"}, nil
}

func TestReconcileStartsRetentionForRoomsWhoseEndWasNeverObserved(t *testing.T) {
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { rdb.Close() })
	st := store.New(rdb, store.Options{EndedRetention: 5 * time.Minute, IdleTTL: 12 * time.Hour, InboxTTL: time.Hour})
	ctx := context.Background()
	for _, sid := range []string{"ses_ended", "ses_gone", "ses_live", "ses_core_down"} {
		st.ApplyState(ctx, chat.SessionState{SessionID: sid, StreamGeneration: 1, SessionVersion: 1, Status: chat.RoomOpen})
		st.Accept(ctx, store.AcceptInput{SessionID: sid, UserID: "u_" + sid, ClientMessageID: "c", TextHash: chat.TextFingerprint("hola"),
			WriteAllowed: true, Message: chat.Message{MessageID: "m", SessionID: sid, Text: "hola", ServerCreatedAtUTC: time.Now()}})
	}
	fc := snapshotCore{
		status: map[string]string{"ses_ended": "ENDED", "ses_live": "LIVE"},
		fail:   map[string]string{"ses_gone": chat.CodeSessionNotFound, "ses_core_down": chat.CodeCoreUnavailable},
	}
	// Sala terminada cuyas claves perdieron el TTL: ApplyState la ignora por ser READ_ONLY terminal.
	st.ApplyState(ctx, chat.SessionState{SessionID: "ses_ended_no_ttl", StreamGeneration: 1, SessionVersion: 1, Status: chat.RoomOpen})
	st.Accept(ctx, store.AcceptInput{SessionID: "ses_ended_no_ttl", UserID: "u_x", ClientMessageID: "c", TextHash: chat.TextFingerprint("hola"),
		WriteAllowed: true, Message: chat.Message{MessageID: "m", SessionID: "ses_ended_no_ttl", Text: "hola", ServerCreatedAtUTC: time.Now()}})
	st.ApplyState(ctx, chat.SessionState{SessionID: "ses_ended_no_ttl", StreamGeneration: 1, SessionVersion: 2, Status: chat.RoomReadOnly})
	rdb.Persist(ctx, "chat:{ses_ended_no_ttl}:seq")
	rdb.Persist(ctx, "chat:{ses_ended_no_ttl}:msgs")
	fc.status["ses_ended_no_ttl"] = "ENDED"
	svc := New(fc, st, slog.New(slog.NewTextHandler(io.Discard, nil)), time.Second)

	svc.ReconcileUnendedRooms(ctx)
	mr.FastForward(5 * time.Minute)

	for sid, kept := range map[string]bool{"ses_ended": false, "ses_gone": false, "ses_ended_no_ttl": false, "ses_live": true, "ses_core_down": true} {
		_, msgs, err := st.History(ctx, sid, 50)
		if err != nil || (len(msgs) == 1) != kept {
			t.Fatalf("%s: conservado=%v, mensajes=%d err=%v", sid, kept, len(msgs), err)
		}
	}
	if room, _ := st.Room(ctx, "ses_live"); !room.Known || room.Status != chat.RoomOpen {
		t.Fatalf("una sesión LIVE sigue abierta: %+v", room)
	}
}
