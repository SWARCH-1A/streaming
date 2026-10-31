package store

import (
	"context"
	"strconv"
	"testing"
	"time"

	"streaming/chat/internal/chat"
)

func channelInput(sid, channel, user, cmid, text string) AcceptInput {
	in := input(sid, user, cmid, text)
	in.ChannelID = channel
	return in
}

func TestBlockedUserCannotSendInAnySessionOfTheChannelOnly(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	until := time.Now().Add(10 * time.Minute).UTC().Truncate(time.Millisecond)
	if err := s.SetBlock(ctx, "chn_a", "u1", &until, 1); err != nil {
		t.Fatal(err)
	}
	for _, sid := range []string{"ses_1", "ses_2"} {
		res, err := s.Accept(ctx, channelInput(sid, "chn_a", "u1", "c-"+sid, "hola"))
		if err != nil || res.Outcome != Blocked || res.BlockedUntil == nil || !res.BlockedUntil.Equal(until) {
			t.Fatalf("%s: %+v %v", sid, res, err)
		}
	}
	if seq, _ := s.LastSequence(ctx, "ses_1"); seq != 0 {
		t.Fatal("un envío bloqueado no debe asignar secuencia")
	}
	// Otro canal conserva su propia política y el bloqueo no consumió la cuota.
	if res, _ := s.Accept(ctx, channelInput("ses_3", "chn_b", "u1", "c3", "hola")); res.Outcome != Accepted {
		t.Fatalf("otro canal: %+v", res)
	}
	mr.FastForward(time.Second)
	if err := s.LiftBlock(ctx, "chn_a", "u1", 2); err != nil {
		t.Fatal(err)
	}
	if res, _ := s.Accept(ctx, channelInput("ses_1", "chn_a", "u1", "c4", "ya puedo")); res.Outcome != Accepted {
		t.Fatalf("retirar el bloqueo restablece el envío: %+v", res)
	}
}

func TestPermanentBlockAndTemporaryExpiry(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	s.SetBlock(ctx, "chn_a", "u1", nil, 1)
	if res, _ := s.Accept(ctx, channelInput("ses_1", "chn_a", "u1", "c1", "x")); res.Outcome != Blocked || res.BlockedUntil != nil {
		t.Fatalf("permanente: %+v", res)
	}
	until := time.Now().Add(time.Minute)
	s.SetBlock(ctx, "chn_a", "u2", &until, 1)
	mr.SetTime(until.Add(time.Second))
	mr.FastForward(time.Minute + time.Second)
	if res, _ := s.Accept(ctx, channelInput("ses_1", "chn_a", "u2", "c2", "x")); res.Outcome != Accepted {
		t.Fatalf("un bloqueo temporal vencido no impide escribir: %+v", res)
	}
}

func TestDuplicateOfAcceptedMessageStillAcksAfterBlock(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	first, _ := s.Accept(ctx, channelInput("ses_1", "chn_a", "u1", "c1", "hola"))
	s.SetBlock(ctx, "chn_a", "u1", nil, 1)
	again, err := s.Accept(ctx, channelInput("ses_1", "chn_a", "u1", "c1", "hola"))
	if err != nil || again.Outcome != Duplicate || again.Ack != first.Ack {
		t.Fatalf("el reintento conserva su ACK: %+v %v", again, err)
	}
}

func TestAcceptedMessageIsQueuedForArchiveUntilAcked(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	if err := s.EnsureArchiveGroup(ctx); err != nil {
		t.Fatal(err)
	}
	if err := s.EnsureArchiveGroup(ctx); err != nil {
		t.Fatal("crear el grupo debe ser idempotente:", err)
	}
	s.Accept(ctx, channelInput("ses_1", "chn_a", "u1", "c1", "hola"))
	s.Accept(ctx, input("ses_p1", "u2", "c2", "perfil P1 sin canal")) // no se archiva
	entries, err := s.ReadArchive(ctx, "a", 10, time.Millisecond)
	if err != nil || len(entries) != 1 || entries[0].ChannelID != "chn_a" || entries[0].Message.Sequence != 1 || entries[0].Message.Text != "hola" {
		t.Fatalf("%+v %v", entries, err)
	}
	// Sin ACK, otro consumidor la reclama tras el tiempo de inactividad.
	claimed, err := s.ClaimArchive(ctx, "b", 0, 10)
	if err != nil || len(claimed) != 1 || claimed[0].ID != entries[0].ID {
		t.Fatalf("reclamo: %+v %v", claimed, err)
	}
	if err := s.AckArchive(ctx, []string{entries[0].ID}); err != nil {
		t.Fatal(err)
	}
	if n, _ := s.rdb.XLen(ctx, ArchiveStream).Result(); n != 0 {
		t.Fatalf("una entrada confirmada se borra: quedan %d", n)
	}
	// La expiración de la sala no toca el archivo.
	s.Accept(ctx, channelInput("ses_1", "chn_a", "u3", "c3", "otro"))
	s.ExpireRoom(ctx, "ses_1")
	if ttl, _ := s.rdb.PTTL(ctx, ArchiveStream).Result(); ttl != -1 {
		t.Fatalf("el archivo no expira con la sala: %v", ttl)
	}
}

func TestRemovalsHideTextInHistoryAndReachTheFeed(t *testing.T) {
	s, mr := newStore(t)
	ctx := context.Background()
	s.ApplyState(ctx, state("ses_1", 1, 1, chat.RoomOpen))
	for _, u := range []string{"u1", "u2", "u3"} {
		s.Accept(ctx, channelInput("ses_1", "chn_a", u, "c-"+u, "texto de "+u))
		mr.FastForward(time.Second)
	}
	msg, found, err := s.MessageByID(ctx, "ses_1", "msg_c-u2")
	if err != nil || !found || msg.Sequence != 2 {
		t.Fatalf("%+v %v %v", msg, found, err)
	}
	modID, _ := s.LastModID(ctx, "ses_1")
	removal := chat.Removal{MessageID: msg.MessageID, SessionID: "ses_1", Sequence: 2, ModerationVersion: 1, RemovedAtUTC: time.Now().UTC()}
	for range 2 { // idempotente: el segundo intento no difunde otra vez
		if err := s.ApplyRemovals(ctx, "ses_1", 1, []chat.Removal{removal}); err != nil {
			t.Fatal(err)
		}
	}
	view, err := s.HistoryView(ctx, "ses_1", 50)
	if err != nil || view.ModerationVersion != 1 || len(view.Items) != 2 || len(view.Removed) != 1 || view.Removed[0].Sequence != 2 {
		t.Fatalf("%+v %v", view, err)
	}
	for _, m := range view.Items {
		if m.MessageID == msg.MessageID {
			t.Fatal("el historial no devuelve el texto retirado")
		}
	}
	feed, err := s.ReadFeed(ctx, "ses_1", 3, modID, time.Millisecond, 100)
	if err != nil || len(feed.Messages) != 0 || len(feed.Removals) != 1 || feed.ModID == modID {
		t.Fatalf("%+v %v", feed, err)
	}
	authored, err := s.MessagesByAuthor(ctx, "ses_1", "u3", 3)
	if err != nil || len(authored) != 1 || authored[0].Sequence != 3 {
		t.Fatalf("%+v %v", authored, err)
	}
	if upTo, _ := s.MessagesByAuthor(ctx, "ses_1", "u3", 2); len(upTo) != 0 {
		t.Fatal("el corte de secuencia excluye mensajes posteriores")
	}
	// Al terminar, las claves de moderación expiran con la sala.
	s.ApplyState(ctx, state("ses_1", 1, 2, chat.RoomReadOnly))
	for _, k := range []string{removedKey("ses_1"), modKey("ses_1"), modverKey("ses_1"), idsKey("ses_1")} {
		if ttl := mr.TTL(k); ttl <= 0 || ttl > 5*time.Minute {
			t.Fatalf("%s debe expirar con la sala: %v", k, ttl)
		}
	}
}

func TestRemovalAfterRoomExpiryWritesNothingToRedis(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	if err := s.ApplyRemovals(ctx, "ses_gone", 1, []chat.Removal{{MessageID: "msg_x", SessionID: "ses_gone", Sequence: 1}}); err != nil {
		t.Fatal(err)
	}
	if n, _ := s.rdb.Exists(ctx, removedKey("ses_gone"), modKey("ses_gone")).Result(); n != 0 {
		t.Fatal("una sala expirada no se recrea al retirar")
	}
}

func TestBrokerEventRecordsChannelForTheSession(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	st := state("ses_1", 1, 1, chat.RoomOpen)
	st.ChannelID = "chn_a"
	if res, err := s.ApplyBrokerEvent(ctx, "evt-1", "fp", "sfp", st); err != nil || res != Applied {
		t.Fatalf("%v %v", res, err)
	}
	room, err := s.Room(ctx, "ses_1")
	if err != nil || room.ChannelID != "chn_a" || room.Status != chat.RoomOpen {
		t.Fatalf("%+v %v", room, err)
	}
}

func TestBlockCopiesNeverRegressToAnOlderVersion(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	blocked := func() bool {
		res, _ := s.Accept(ctx, channelInput("ses_1", "chn_a", "u1", uuidLike(), "x"))
		return res.Outcome == Blocked
	}
	s.SetBlock(ctx, "chn_a", "u1", nil, 1)
	// Retiro v2 y luego un mantenimiento con la foto vieja (v1) intenta reponerlo.
	s.LiftBlock(ctx, "chn_a", "u1", 2)
	s.SetBlock(ctx, "chn_a", "u1", nil, 1)
	if blocked() {
		t.Fatal("una foto vieja no repone un bloqueo ya retirado")
	}
	// La marca de retiro no la borra la limpieza: vence sola.
	s.DropStaleBlock(ctx, "chn_a", "u1", 5)
	if n, _ := s.rdb.Exists(ctx, blockKey("chn_a", "u1")).Result(); n != 1 {
		t.Fatal("la marca de retiro se conserva")
	}
	s.SetBlock(ctx, "chn_a", "u1", nil, 3)
	if !blocked() {
		t.Fatal("un bloqueo nuevo (v3) rige")
	}
}

func TestDropStaleBlockKeepsANewerCopy(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	// La reconciliación leyó v1 retirado, pero entre tanto se confirmó un bloqueo v2.
	s.SetBlock(ctx, "chn_a", "u1", nil, 2)
	if err := s.DropStaleBlock(ctx, "chn_a", "u1", 1); err != nil {
		t.Fatal(err)
	}
	if n, _ := s.rdb.Exists(ctx, blockKey("chn_a", "u1")).Result(); n != 1 {
		t.Fatal("una copia más nueva que la lectura se conserva")
	}
	if err := s.DropStaleBlock(ctx, "chn_a", "u1", 2); err != nil {
		t.Fatal(err)
	}
	if n, _ := s.rdb.Exists(ctx, blockKey("chn_a", "u1")).Result(); n != 0 {
		t.Fatal("una copia no más nueva que PostgreSQL se borra")
	}
	ids, _ := s.BlockIDs(ctx)
	if len(ids) != 0 {
		t.Fatalf("%v", ids)
	}
}

var uuidSeq int

func uuidLike() string {
	uuidSeq++
	return "cmid-" + strconv.Itoa(uuidSeq)
}
