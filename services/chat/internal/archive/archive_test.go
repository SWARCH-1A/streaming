//go:build integration

// Pruebas contra un PostgreSQL real: go test -tags integration ./internal/archive/ con
// CHAT_TEST_DATABASE_URL. El esquema public de esa base se borra al empezar; usar una base dedicada.
package archive

import (
	"context"
	"os"
	"testing"
	"time"

	"streaming/chat/internal/chat"
)

func testDB(t *testing.T) *DB {
	t.Helper()
	url := os.Getenv("CHAT_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("CHAT_TEST_DATABASE_URL no definida")
	}
	ctx := context.Background()
	db, err := Open(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if _, err := db.pool.Exec(ctx, `DROP SCHEMA public CASCADE; CREATE SCHEMA public`); err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(ctx); err != nil {
		t.Fatal("migrar dos veces debe ser idempotente:", err)
	}
	return db
}

var t0 = time.Date(2026, 10, 28, 20, 0, 0, 0, time.UTC)

func msg(sid string, seq int64, user string, offset int64) chat.Message {
	return chat.Message{MessageID: "msg_" + sid + "_" + string(rune('a'+seq)), SessionID: sid, Sequence: seq,
		Author: chat.Author{UserID: user, Handle: user, DisplayName: user}, Text: "texto " + user,
		ServerCreatedAtUTC: t0.Add(time.Duration(offset) * time.Millisecond), StreamOffsetMs: offset, StreamGeneration: 1}
}

func TestInsertIsIdempotentAndRemovedMessagesAreNotRestored(t *testing.T) {
	db := testDB(t)
	ctx := context.Background()
	m1, m2 := msg("ses_1", 1, "u1", 1000), msg("ses_1", 2, "u2", 2000)
	// m2 se retira antes de que el archivador lo copie.
	res, err := db.RemoveMessages(ctx, "chn_a", "ses_1", "owner", []chat.Message{m2}, t0)
	if err != nil || res.Newly != 1 || res.ModerationVersion != 1 {
		t.Fatalf("%+v %v", res, err)
	}
	rows := []Row{{ChannelID: "chn_a", Message: m1}, {ChannelID: "chn_a", Message: m2}}
	for range 2 {
		if err := db.InsertMessages(ctx, rows); err != nil {
			t.Fatal(err)
		}
	}
	_, removal, found, err := db.ArchivedMessage(ctx, "ses_1", m2.MessageID)
	if err != nil || !found || removal == nil || removal.ModerationVersion != 1 {
		t.Fatalf("el archivo no repone un mensaje retirado: %+v %v %v", removal, found, err)
	}
	var text *string
	db.pool.QueryRow(ctx, `SELECT text FROM chat_messages WHERE message_id=$1`, m2.MessageID).Scan(&text)
	if text != nil {
		t.Fatal("un mensaje retirado no conserva su texto")
	}
	// Repetir el retiro devuelve la misma marca sin otra versión.
	again, err := db.RemoveMessages(ctx, "chn_a", "ses_1", "owner", []chat.Message{m2}, t0.Add(time.Minute))
	if err != nil || again.Newly != 0 || again.ModerationVersion != 1 || again.Removals[0].ModerationVersion != 1 {
		t.Fatalf("%+v %v", again, err)
	}
	if channel, ok, _ := db.SessionChannel(ctx, "ses_1"); !ok || channel != "chn_a" {
		t.Fatal("la sesión archivada conserva su canal")
	}
	if seq, _ := db.LastArchivedSequence(ctx, "ses_1"); seq != 2 {
		t.Fatalf("último sequence archivado: %d", seq)
	}
}

func TestRemoveByAuthorSelectsOnlyThatAuthorUpToCut(t *testing.T) {
	db := testDB(t)
	ctx := context.Background()
	db.InsertMessages(ctx, []Row{{"chn_a", msg("ses_1", 1, "u1", 1)}, {"chn_a", msg("ses_1", 2, "u2", 2)}, {"chn_a", msg("ses_1", 3, "u1", 3)}})
	got, err := db.ArchivedByAuthor(ctx, "ses_1", "u1", 2)
	if err != nil || len(got) != 1 || got[0].Sequence != 1 {
		t.Fatalf("%+v %v", got, err)
	}
}

func TestBlocksLastWriterWinsAndLiftKeepsVersion(t *testing.T) {
	db := testDB(t)
	ctx := context.Background()
	if b, err := db.GetBlock(ctx, "chn_a", "u1"); err != nil || b.Version != 0 || b.Mode != nil {
		t.Fatalf("sin registro: %+v %v", b, err)
	}
	d := "PT1H"
	until := t0.Add(time.Hour)
	b, err := db.PutBlock(ctx, "chn_a", "u1", "owner", chat.BlockTemporary, &d, &until, t0)
	if err != nil || b.Version != 1 || *b.Mode != chat.BlockTemporary || !b.ExpiresAtUTC.Equal(until) || !b.ActiveAt(t0) || b.ActiveAt(until) {
		t.Fatalf("%+v %v", b, err)
	}
	b, err = db.PutBlock(ctx, "chn_a", "u1", "owner", chat.BlockPermanent, nil, nil, t0.Add(time.Minute))
	if err != nil || b.Version != 2 || *b.Mode != chat.BlockPermanent || b.ExpiresAtUTC != nil {
		t.Fatalf("la última escritura gana: %+v %v", b, err)
	}
	active, err := db.ActiveBlocks(ctx, t0.Add(48*time.Hour))
	if err != nil || len(active) != 1 {
		t.Fatalf("un permanente sigue vigente: %+v %v", active, err)
	}
	b, err = db.LiftBlock(ctx, "chn_a", "u1", t0.Add(2*time.Minute))
	if err != nil || b.Version != 3 || b.Mode != nil || b.ActiveAt(t0) {
		t.Fatalf("%+v %v", b, err)
	}
	if active, _ := db.ActiveBlocks(ctx, t0); len(active) != 0 {
		t.Fatal("un bloqueo retirado no es vigente")
	}
	// Otro canal conserva su propia política.
	if b, _ := db.GetBlock(ctx, "chn_b", "u1"); b.Version != 0 {
		t.Fatal("el bloqueo es por canal")
	}
}

func TestIdempotencyReplaysAndRejectsOtherPayload(t *testing.T) {
	db := testDB(t)
	ctx := context.Background()
	calls := 0
	action := func() (Result, error) { calls++; return Result{Status: 200, Body: []byte(`{"ok":true}`)}, nil }
	key := "550e8400-e29b-41d4-a716-446655440000"
	first, err := db.Idempotent(ctx, "owner", key, "fp1", time.Hour, action)
	second, err2 := db.Idempotent(ctx, "owner", key, "fp1", time.Hour, action)
	if err != nil || err2 != nil || calls != 1 || string(second.Body) != string(first.Body) {
		t.Fatalf("%v %v calls=%d", err, err2, calls)
	}
	if _, err := db.Idempotent(ctx, "owner", key, "fp2", time.Hour, action); err != ErrIdempotencyConflict {
		t.Fatalf("otro payload: %v", err)
	}
	if _, err := db.Idempotent(ctx, "other", key, "fp2", time.Hour, action); err != nil || calls != 2 {
		t.Fatal("la clave se liga al usuario")
	}
}

func TestVodLinkReplayRangeAndDeletion(t *testing.T) {
	db := testDB(t)
	ctx := context.Background()
	var rows []Row
	for i := int64(1); i <= 6; i++ {
		rows = append(rows, Row{"chn_a", msg("ses_1", i, "u1", 10_000+i*1000)})
	}
	db.InsertMessages(ctx, rows)
	db.RemoveMessages(ctx, "chn_a", "ses_1", "owner", []chat.Message{rows[2].Message}, t0) // offset 13000
	link := VodLink{VodID: "vod_1", SessionID: "ses_1", ChannelID: "chn_a", Version: 1, MappingVersion: 0,
		Restriction: "NONE", PublicationState: "DRAFT"}
	if out, err := db.ApplyVod(ctx, "evt-1", "fp1", link, t0); err != nil || out != VodApplied {
		t.Fatalf("%v %v", out, err)
	}
	if out, _ := db.ApplyVod(ctx, "evt-1", "fp1", link, t0); out != VodDuplicate {
		t.Fatalf("reentrega: %v", out)
	}
	if out, _ := db.ApplyVod(ctx, "evt-1", "fp-other", link, t0); out != VodConflict {
		t.Fatalf("mismo eventId con otro contenido: %v", out)
	}
	link.Version, link.MappingVersion, link.Available, link.PublicationState = 2, 1, true, "PUBLISHED"
	db.ApplyVod(ctx, "evt-2", "fp2", link, t0)
	old := link
	old.Version, old.Available, old.PublicationState = 1, false, "DRAFT"
	if out, _ := db.ApplyVod(ctx, "evt-old", "fp-old", old, t0); out != VodStale {
		t.Fatalf("una versión vieja no reemplaza la nueva: %v", out)
	}
	stored, found, _ := db.GetVod(ctx, "vod_1")
	if !found || !stored.Available || stored.Version != 2 || stored.MappingVersion != 1 || stored.PublicationState != "PUBLISHED" {
		t.Fatalf("%+v", stored)
	}
	// Tramo [12000, 15000) de la sesión: 12000 y 14000; 13000 fue retirado y no se entrega.
	hi := int64(15_000)
	r := ReplayRange{SessionID: "ses_1", FromOffsetMs: 12_000, ToOffsetMs: &hi}
	items, moderation, err := db.Replay(ctx, r, 0, 1)
	if err != nil || len(items) != 1 || items[0].StreamOffsetMs != 12_000 || moderation != 1 {
		t.Fatalf("%+v %d %v", items, moderation, err)
	}
	next, _, err := db.Replay(ctx, r, items[0].Sequence, 5)
	if err != nil || len(next) != 1 || next[0].StreamOffsetMs != 14_000 {
		t.Fatalf("la secuencia evita duplicados y salta el retirado: %+v %v", next, err)
	}
	// Sin cota superior llega hasta el último mensaje de la sesión.
	if tail, _, _ := db.Replay(ctx, ReplayRange{SessionID: "ses_1", FromOffsetMs: 15_000}, 0, 50); len(tail) != 2 {
		t.Fatalf("%+v", tail)
	}
	if none, moderation, err := db.Replay(ctx, ReplayRange{SessionID: "ses_otra"}, 0, 50); err != nil || len(none) != 0 || moderation != 0 {
		t.Fatalf("sesión sin archivo: %+v %d %v", none, moderation, err)
	}
	link.Version, link.Available, link.Restriction = 3, false, "DELETED"
	if out, _ := db.ApplyVod(ctx, "evt-3", "fp3", link, t0); out != VodApplied {
		t.Fatal("DELETED se aplica")
	}
	if _, ok, _ := db.SessionChannel(ctx, "ses_1"); ok {
		t.Fatal("eliminar el único VOD borra el historial de la sesión")
	}
	link.Version, link.Available, link.Restriction = 4, true, "NONE"
	if out, _ := db.ApplyVod(ctx, "evt-4", "fp4", link, t0); out != VodStale {
		t.Fatal("DELETED es terminal")
	}
}

func TestPurgeUnlinkedKeepsSessionsWithVod(t *testing.T) {
	db := testDB(t)
	ctx := context.Background()
	db.InsertMessages(ctx, []Row{{"chn_a", msg("ses_old", 1, "u1", 1)}, {"chn_a", msg("ses_vod", 1, "u1", 1)}})
	db.ApplyVod(ctx, "evt-1", "fp", VodLink{VodID: "vod_1", SessionID: "ses_vod", ChannelID: "chn_a", Version: 1, Restriction: "NONE", PublicationState: "DRAFT"}, t0)
	n, err := db.PurgeUnlinked(ctx, t0.Add(8*24*time.Hour))
	if err != nil || n != 1 {
		t.Fatalf("%d %v", n, err)
	}
	if _, ok, _ := db.SessionChannel(ctx, "ses_vod"); !ok {
		t.Fatal("una sesión con VOD no se borra por antigüedad")
	}
}
