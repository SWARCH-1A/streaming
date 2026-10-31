//go:build integration

// Moderación y replay de punta a punta con PostgreSQL real (CHAT_TEST_DATABASE_URL; su esquema
// public se borra) y Redis simulado: go test -tags integration ./internal/transport/
package transport

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/redis/go-redis/v9"

	"streaming/chat/internal/archive"
	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
	"streaming/chat/internal/service"
	"streaming/chat/internal/store"
)

const (
	channelID = "chn_ana"
	xsrf      = "xsrf-fixture"
)

type p2Harness struct {
	*harness
	svc *service.Service
	db  *archive.DB
}

// moderationCore responde moderation-context: usr_ana es propietaria de chn_ana.
func moderationCore(fc *fakeCore) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/", fc)
	mux.HandleFunc("POST /internal/core/chat/moderation-context", func(w http.ResponseWriter, r *http.Request) {
		var body struct{ ChannelID string }
		json.NewDecoder(r.Body).Decode(&body)
		w.Header().Set("Content-Type", "application/json")
		user := fc.users[r.Header.Get("X-Session-Credential")]
		switch {
		case r.Header.Get("X-Service-Token") != "core-token":
			w.WriteHeader(http.StatusForbidden)
		case user == "":
			w.WriteHeader(http.StatusUnauthorized)
			json.NewEncoder(w).Encode(map[string]string{"code": "SESSION_INVALID"})
		case body.ChannelID != channelID || user != "usr_ana":
			w.WriteHeader(http.StatusForbidden)
			json.NewEncoder(w).Encode(map[string]string{"code": "CHANNEL_FORBIDDEN"})
		default:
			json.NewEncoder(w).Encode(core.ModerationContext{UserID: user, ChannelID: body.ChannelID, AuthorizedAtUTC: time.Now().UTC()})
		}
	})
	return mux
}

func newP2Harness(t *testing.T) *p2Harness {
	t.Helper()
	dsn := os.Getenv("CHAT_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("CHAT_TEST_DATABASE_URL no definida")
	}
	ctx := context.Background()
	db, err := archive.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err := resetSchema(ctx, dsn); err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	mr := miniredis.RunT(t)
	fc := &fakeCore{status: "LIVE", version: 2, users: map[string]string{"cookie-ana": "usr_ana", "cookie-luis": "usr_luis"}}
	coreSrv := httptest.NewServer(moderationCore(fc))
	t.Cleanup(coreSrv.Close)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { rdb.Close() })
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	st := store.New(rdb, store.Options{EndedRetention: 5 * time.Minute, IdleTTL: time.Hour, InboxTTL: time.Hour})
	cc, _ := core.New(core.Config{DevelopmentHTTP: true, BaseURL: coreSrv.URL, ServiceToken: "core-token", ConnectTimeout: 100 * time.Millisecond, RequestTimeout: 400 * time.Millisecond})
	svc := service.New(cc, st, log, 300*time.Millisecond)
	svc.EnableP2(service.P2Options{Archive: db, Moderators: cc, RequireChannel: true, UnlinkedRetention: time.Hour, IdempotencyRetention: time.Hour})
	hub := NewHub(st, log, 50*time.Millisecond)
	t.Cleanup(hub.Close)
	srv := NewServer(Config{AllowedOrigins: []string{webOrigin}, SessionCookie: "stream_session", EventsProducer: "streaming", EventsTransport: "amqp"}, svc, hub, st.Ping, log)
	h := &harness{public: httptest.NewServer(srv.PublicHandler()), internal: httptest.NewServer(srv.InternalHandler()), store: st, core: fc, mr: mr}
	t.Cleanup(h.public.Close)
	t.Cleanup(h.internal.Close)
	// El ciclo P2 por broker abre la sala y registra su canal.
	if _, err := st.ApplyBrokerEvent(ctx, uuid.NewString(), "fp", "sfp", chat.SessionState{SessionID: "ses_live",
		StreamGeneration: 1, SessionVersion: 2, Status: chat.RoomOpen, Availability: "PLAYABLE", ChannelID: channelID}); err != nil {
		t.Fatal(err)
	}
	return &p2Harness{harness: h, svc: svc, db: db}
}

func resetSchema(ctx context.Context, dsn string) error {
	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		return err
	}
	defer conn.Close(ctx)
	_, err = conn.Exec(ctx, `DROP SCHEMA public CASCADE; CREATE SCHEMA public`)
	return err
}

type call struct {
	method, path, cookie string
	body                 any
	csrf, idem           bool
	key                  string
}

func (h *p2Harness) do(t *testing.T, c call) (int, map[string]any) {
	t.Helper()
	var reader io.Reader
	if c.body != nil {
		raw, _ := json.Marshal(c.body)
		reader = bytes.NewReader(raw)
	}
	req, _ := http.NewRequest(c.method, h.public.URL+c.path, reader)
	req.Header.Set("Origin", webOrigin)
	if c.cookie != "" {
		req.AddCookie(&http.Cookie{Name: "stream_session", Value: c.cookie})
	}
	if c.csrf {
		req.AddCookie(&http.Cookie{Name: "XSRF-TOKEN", Value: xsrf})
		req.Header.Set("X-XSRF-TOKEN", xsrf)
	}
	if c.idem {
		key := c.key
		if key == "" {
			key = uuid.NewString()
		}
		req.Header.Set("Idempotency-Key", key)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func owner(method, path string, body any) call {
	return call{method: method, path: path, cookie: "cookie-ana", body: body, csrf: true, idem: true}
}

func TestBlockPreventsSendingUntilLiftedAndShowsUntilWhen(t *testing.T) {
	h := newP2Harness(t)
	luis := h.dial(t, "ses_live", "cookie-luis")
	path := "/api/chat/channels/" + channelID + "/blocks/usr_luis"

	// Autorización: anónimo 401, tercero 403, sin CSRF 403, sin Idempotency-Key 400.
	for _, c := range []struct {
		call call
		code int
	}{
		{call{method: "PUT", path: path, body: map[string]any{"mode": "PERMANENT"}, csrf: true, idem: true}, 401},
		{call{method: "PUT", path: path, cookie: "cookie-luis", body: map[string]any{"mode": "PERMANENT"}, csrf: true, idem: true}, 403},
		{call{method: "PUT", path: path, cookie: "cookie-ana", body: map[string]any{"mode": "PERMANENT"}, idem: true}, 403},
		{call{method: "PUT", path: path, cookie: "cookie-ana", body: map[string]any{"mode": "PERMANENT"}, csrf: true}, 400},
		{owner("PUT", path, map[string]any{"mode": "TEMPORARY", "duration": "PT5M"}), 400},
		{owner("PUT", path, map[string]any{"mode": "PERMANENT", "duration": "PT1H"}), 400},
		{owner("PUT", "/api/chat/channels/"+channelID+"/blocks/usr_ana", map[string]any{"mode": "PERMANENT"}), 422},
	} {
		if code, body := h.do(t, c.call); code != c.code {
			t.Fatalf("%+v: %d %v", c.call, code, body)
		}
	}

	key := uuid.NewString()
	block := owner("PUT", path, map[string]any{"mode": "TEMPORARY", "duration": "PT10M"})
	block.key = key
	code, b := h.do(t, block)
	if code != 200 || b["active"] != true || b["mode"] != "TEMPORARY" || b["expiresAtUtc"] == nil || b["version"].(float64) != 1 {
		t.Fatalf("%d %v", code, b)
	}
	// Repetir la misma clave devuelve el mismo resultado; otra solicitud con esa clave es 409.
	if code, again := h.do(t, block); code != 200 || again["expiresAtUtc"] != b["expiresAtUtc"] || again["version"].(float64) != 1 {
		t.Fatalf("%d %v", code, again)
	}
	other := owner("PUT", path, map[string]any{"mode": "PERMANENT"})
	other.key = key
	if code, _ := h.do(t, other); code != 409 {
		t.Fatalf("misma clave con otro cuerpo: %d", code)
	}

	luis.send(cm1, "¿me oyen?")
	f := luis.expect("error")
	if f["code"] != chat.CodeChatBlocked || f["blockMode"] != "TEMPORARY" || f["blockedUntilUtc"] != b["expiresAtUtc"] {
		t.Fatalf("el bloqueado ve hasta cuándo dura: %v", f)
	}

	if code, got := h.do(t, call{method: "GET", path: path, cookie: "cookie-ana"}); code != 200 || got["active"] != true {
		t.Fatalf("%d %v", code, got)
	}
	if code, lifted := h.do(t, owner("DELETE", path, nil)); code != 200 || lifted["active"] != false || lifted["version"].(float64) != 2 {
		t.Fatalf("%d %v", code, lifted)
	}
	luis.send(cm2, "ya puedo")
	if ack := luis.expect("message.accepted"); ack["clientMessageId"] != cm2 {
		t.Fatalf("%v", ack)
	}
}

func TestRemoveMessageShowsNoticeLiveAndInHistory(t *testing.T) {
	h := newP2Harness(t)
	ana := h.dial(t, "ses_live", "cookie-ana")
	luis := h.dial(t, "ses_live", "cookie-luis")
	luis.send(cm1, "spam")
	ack := luis.expect("message.accepted")
	ana.expect("message.created")
	messageID := ack["messageId"].(string)

	path := "/api/chat/sessions/ses_live/messages/" + messageID
	if code, _ := h.do(t, call{method: "DELETE", path: path, cookie: "cookie-luis", csrf: true, idem: true}); code != 403 {
		t.Fatalf("solo la propietaria modera: %d", code)
	}
	code, removal := h.do(t, owner("DELETE", path, nil))
	if code != 200 || removal["messageId"] != messageID || removal["moderationVersion"].(float64) != 1 || removal["channelId"] != channelID {
		t.Fatalf("%d %v", code, removal)
	}
	if f := ana.expect("message.removed"); f["messageId"] != messageID || f["sequence"].(float64) != 1 {
		t.Fatalf("%v", f)
	}
	_, hist, _ := h.history(t, "ses_live", "")
	if len(hist.Items) != 0 || len(hist.RemovedMessages) != 1 || hist.ModerationVersion != 1 {
		t.Fatalf("%+v", hist)
	}
	if code, _ := h.do(t, owner("DELETE", "/api/chat/sessions/ses_live/messages/msg_missing", nil)); code != 404 {
		t.Fatalf("mensaje inexistente: %d", code)
	}
}

func TestRemoveUserMessagesOnlyInThisSessionAndArchiveReplay(t *testing.T) {
	h := newP2Harness(t)
	ctx, stop := context.WithCancel(context.Background())
	defer stop()
	go h.svc.RunArchiver(ctx, "test", time.Second)

	luis := h.dial(t, "ses_live", "cookie-luis")
	ana := h.dial(t, "ses_live", "cookie-ana")
	luis.send(cm1, "uno")
	luis.expect("message.accepted")
	h.mr.FastForward(time.Second)
	ana.send(cm2, "hola Luis")
	ana.expect("message.accepted")
	h.mr.FastForward(time.Second)
	luis.send(cm3, "dos")
	luis.expect("message.accepted")

	code, op := h.do(t, owner("POST", "/api/chat/channels/"+channelID+"/message-removals",
		map[string]any{"sessionId": "ses_live", "userId": "usr_luis"}))
	if code != 202 || op["removedCount"].(float64) != 2 || op["status"] != "COMPLETED" || op["scope"] != "SESSION" || op["upToSequence"].(float64) != 3 {
		t.Fatalf("%d %v", code, op)
	}
	if code, status := h.do(t, call{method: "GET", path: op["statusUri"].(string), cookie: "cookie-ana"}); code != 200 || status["operationId"] != op["operationId"] {
		t.Fatalf("%d %v", code, status)
	}
	if code, _ := h.do(t, owner("POST", "/api/chat/channels/"+channelID+"/message-removals",
		map[string]any{"sessionId": "ses_other", "userId": "usr_luis"})); code != 404 {
		t.Fatalf("una sesión ajena al canal: %d", code)
	}
	_, hist, _ := h.history(t, "ses_live", "")
	if len(hist.Items) != 1 || hist.Items[0].Author.UserID != "usr_ana" || len(hist.RemovedMessages) != 2 {
		t.Fatalf("%+v", hist)
	}

	// El archivador copia lo confirmado; los retiros previos no se reponen.
	deadline := time.Now().Add(5 * time.Second)
	for {
		if seq, _ := h.db.LastArchivedSequence(context.Background(), "ses_live"); seq == 3 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("el archivo no recibió los mensajes")
		}
		time.Sleep(50 * time.Millisecond)
	}

	if code, _ := h.do(t, call{method: "GET", path: "/api/chat/vods/vod_1/messages?fromMs=0&toMs=60000"}); code != 404 {
		t.Fatalf("sin VOD publicado no hay replay: %d", code)
	}
	link := archive.VodLink{VodID: "vod_1", SessionID: "ses_live", ChannelID: channelID, VodVersion: 1, Status: "PUBLISHED", TimelineStartMs: 60_000}
	if out, err := h.svc.ApplyVodEvent(context.Background(), uuid.NewString(), "fp", link); err != nil || out != archive.VodApplied {
		t.Fatalf("%v %v", out, err)
	}
	// fakeCore ubica todos los mensajes en el timeline 61000 → 1000 ms del VOD.
	code, page := h.do(t, call{method: "GET", path: "/api/chat/vods/vod_1/messages?fromMs=0&toMs=30000&limit=2"})
	items, _ := page["items"].([]any)
	if code != 200 || len(items) != 2 || page["nextCursor"] == nil {
		t.Fatalf("%d %v", code, page)
	}
	first := items[0].(map[string]any)
	if first["vodOffsetMs"].(float64) != 1000 || first["removed"] != true || first["text"] != nil {
		t.Fatalf("el replay muestra el aviso del moderador sin texto: %v", first)
	}
	if second := items[1].(map[string]any); second["text"] != "hola Luis" || second["removed"] != false {
		t.Fatalf("%v", second)
	}
	code, rest := h.do(t, call{method: "GET", path: "/api/chat/vods/vod_1/messages?fromMs=0&toMs=30000&limit=2&cursor=" +
		url.QueryEscape(page["nextCursor"].(string))})
	if more, _ := rest["items"].([]any); code != 200 || len(more) != 1 || rest["nextCursor"] != nil {
		t.Fatalf("%d %v", code, rest)
	}
	// Buscar otra posición consulta su propia ventana; un cursor de otra ventana se rechaza.
	if code, empty := h.do(t, call{method: "GET", path: "/api/chat/vods/vod_1/messages?fromMs=30000&toMs=60000"}); code != 200 || len(empty["items"].([]any)) != 0 {
		t.Fatalf("%d %v", code, empty)
	}
	if code, _ := h.do(t, call{method: "GET", path: "/api/chat/vods/vod_1/messages?fromMs=30000&toMs=60000&cursor=" +
		url.QueryEscape(page["nextCursor"].(string))}); code != 400 {
		t.Fatalf("cursor de otra ventana: %d", code)
	}
	for _, q := range []string{"fromMs=10&toMs=5", "fromMs=0&toMs=400000", "fromMs=0&toMs=10&extra=1", "fromMs=0&toMs=10&limit=51"} {
		if code, _ := h.do(t, call{method: "GET", path: "/api/chat/vods/vod_1/messages?" + q}); code != 400 {
			t.Fatalf("%s: %d", q, code)
		}
	}
	// Un cambio del mapping invalida los cursores emitidos antes.
	link.VodVersion = 2
	h.svc.ApplyVodEvent(context.Background(), uuid.NewString(), "fp2", link)
	if code, _ := h.do(t, call{method: "GET", path: "/api/chat/vods/vod_1/messages?fromMs=0&toMs=30000&limit=2&cursor=" +
		url.QueryEscape(page["nextCursor"].(string))}); code != 410 {
		t.Fatalf("cursor de un mapping anterior: %d", code)
	}
}

func TestRoomWithoutKnownChannelRejectsSendsUnderBroker(t *testing.T) {
	h := newP2Harness(t)
	// ses_live2 no recibió el ciclo por broker: no se puede verificar un bloqueo.
	h.store.ApplyState(context.Background(), chat.SessionState{SessionID: "ses_live2", StreamGeneration: 1, SessionVersion: 1, Status: chat.RoomOpen, Availability: "PLAYABLE"})
	ack, _, err := h.svc.Send(context.Background(), "ses_live2", "cookie-luis", cm1, "hola")
	if err == nil || err.Code != chat.CodeChatUnavailable || ack.Sequence != 0 {
		t.Fatalf("%+v %v", ack, err)
	}
}
