package service

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
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
