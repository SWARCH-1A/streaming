package transport

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/coder/websocket"
	"github.com/redis/go-redis/v9"

	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
	"streaming/chat/internal/service"
	"streaming/chat/internal/store"
)

const (
	webOrigin   = "http://localhost:3000"
	eventsToken = "events-token"
)

// fakeCore simula el contrato privado Core (contexto + snapshot) con el estado de emisión de Streaming.
type fakeCore struct {
	mu       sync.Mutex
	status   string
	version  int64
	users    map[string]string // credencial -> userId
	delay    time.Duration
	failWith int
	failCode string
	calls    int
}

func (f *fakeCore) set(fn func(*fakeCore)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn(f)
}

func (f *fakeCore) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	status, version, delay, failWith, failCode := f.status, f.version, f.delay, f.failWith, f.failCode
	user := f.users[r.Header.Get("X-Session-Credential")]
	f.calls++
	f.mu.Unlock()
	time.Sleep(delay)
	if r.Header.Get("X-Service-Token") != "core-token" {
		w.WriteHeader(http.StatusForbidden)
		return
	}
	if failWith != 0 {
		w.WriteHeader(failWith)
		json.NewEncoder(w).Encode(map[string]string{"code": failCode})
		return
	}
	sid := strings.TrimPrefix(r.URL.Path, "/internal/core/chat/sessions/")
	if r.Method == http.MethodGet {
		if sid != "ses_live" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		json.NewEncoder(w).Encode(core.SessionSnapshot{SessionID: sid, StreamGeneration: 1, SessionVersion: version, Status: status, Availability: "PLAYABLE"})
		return
	}
	var body struct{ SessionID string }
	json.NewDecoder(r.Body).Decode(&body)
	if user == "" {
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]string{"code": "AUTH_REQUIRED"})
		return
	}
	pos := int64(61_000)
	mc := core.MessageContext{
		UserID: user, Handle: user, DisplayName: "", SessionID: body.SessionID, StreamGeneration: 1,
		SessionVersion: version, Availability: "PLAYABLE", AuthorizedAtUTC: time.Now().UTC(), TimelinePositionMs: &pos,
	}
	switch status {
	case "LIVE", "RECONNECT_GRACE":
		mc.WriteAllowed = true
	case "ENDED":
		code := chat.CodeChatReadOnly
		mc.DenialCode = &code
	default:
		code := chat.CodeChatNotOpen
		mc.DenialCode = &code
	}
	json.NewEncoder(w).Encode(mc)
}

type harness struct {
	public   *httptest.Server
	internal *httptest.Server
	store    *store.Store
	core     *fakeCore
	mr       *miniredis.Miniredis
}

func newHarness(t *testing.T, mr *miniredis.Miniredis, fc *fakeCore) *harness {
	t.Helper()
	if mr == nil {
		mr = miniredis.RunT(t)
	}
	if fc == nil {
		fc = &fakeCore{status: "LIVE", version: 2, users: map[string]string{"cookie-ana": "usr_ana", "cookie-luis": "usr_luis"}}
	}
	coreSrv := httptest.NewServer(fc)
	t.Cleanup(coreSrv.Close)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { rdb.Close() })
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	st := store.New(rdb, store.Options{EndedRetention: 5 * time.Minute, IdleTTL: time.Hour, StreamMaxLen: 1000, InboxTTL: time.Hour})
	cc, err := core.New(core.Config{BaseURL: coreSrv.URL, ServiceToken: "core-token", ConnectTimeout: 100 * time.Millisecond, RequestTimeout: 400 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	svc := service.New(cc, st, log, 300*time.Millisecond)
	hub := NewHub(st, log, 50*time.Millisecond)
	t.Cleanup(hub.Close)
	srv := NewServer(Config{AllowedOrigins: []string{webOrigin}, SessionCookie: "stream_session", EventsProducer: "streaming", EventsToken: eventsToken}, svc, hub, st.Ping, log)
	h := &harness{public: httptest.NewServer(srv.PublicHandler()), internal: httptest.NewServer(srv.InternalHandler()), store: st, core: fc, mr: mr}
	t.Cleanup(h.public.Close)
	t.Cleanup(h.internal.Close)
	return h
}

type wsClient struct {
	t    *testing.T
	conn *websocket.Conn
}

func (h *harness) dial(t *testing.T, sid, cookie string) *wsClient {
	t.Helper()
	hdr := http.Header{"Origin": {webOrigin}}
	if cookie != "" {
		hdr.Set("Cookie", "stream_session="+cookie)
	}
	url := "ws" + strings.TrimPrefix(h.public.URL, "http") + "/realtime/chat/sessions/" + sid
	conn, resp, err := websocket.Dial(context.Background(), url, &websocket.DialOptions{HTTPHeader: hdr})
	if err != nil {
		t.Fatalf("dial: %v (%v)", err, resp)
	}
	t.Cleanup(func() { conn.CloseNow() })
	c := &wsClient{t: t, conn: conn}
	ready, ok := c.next(2 * time.Second)
	if !ok || ready["type"] != "chat.ready" || ready["sessionId"] != sid {
		t.Fatalf("el primer frame debe ser chat.ready: %v", ready)
	}
	return c
}

func (c *wsClient) send(cmid, text string) {
	c.t.Helper()
	b, _ := json.Marshal(map[string]string{"type": "message.send", "clientMessageId": cmid, "text": text})
	if err := c.conn.Write(context.Background(), websocket.MessageText, b); err != nil {
		c.t.Fatal(err)
	}
}

func (c *wsClient) next(timeout time.Duration) (map[string]any, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	_, data, err := c.conn.Read(ctx)
	if err != nil {
		return nil, false
	}
	var f map[string]any
	json.Unmarshal(data, &f)
	return f, true
}

// expect lee frames hasta encontrar el tipo pedido; falla si no llega en 2 s.
func (c *wsClient) expect(typ string) map[string]any {
	c.t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		f, ok := c.next(time.Until(deadline))
		if ok && f["type"] == typ {
			return f
		}
	}
	c.t.Fatalf("no llegó %s", typ)
	return nil
}

func (c *wsClient) expectNone(typ string, wait time.Duration) {
	c.t.Helper()
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		f, ok := c.next(time.Until(deadline))
		if ok && f["type"] == typ {
			c.t.Fatalf("frame inesperado %v", f)
		}
	}
}

func (h *harness) history(t *testing.T, sid, query string) (int, historyResponse, errorEnvelope) {
	t.Helper()
	resp, err := http.Get(h.public.URL + "/api/chat/sessions/" + sid + "/messages" + query)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var ok historyResponse
	var bad errorEnvelope
	json.Unmarshal(raw, &ok)
	json.Unmarshal(raw, &bad)
	return resp.StatusCode, ok, bad
}

const (
	cm1 = "11111111-1111-4111-8111-111111111111"
	cm2 = "22222222-2222-4222-8222-222222222222"
	cm3 = "33333333-3333-4333-8333-333333333333"
)

func TestAnonymousReadsLiveMessagesButCannotSend(t *testing.T) {
	h := newHarness(t, nil, nil)
	anon := h.dial(t, "ses_live", "")
	ana := h.dial(t, "ses_live", "cookie-ana")

	anon.send(cm1, "hola")
	if e := anon.expect("error"); e["code"] != chat.CodeAuthRequired || e["clientMessageId"] != cm1 {
		t.Fatalf("anónimo: %v", e)
	}

	ana.send(cm2, "  Hola <b>chat</b>  ")
	ack := ana.expect("message.accepted")
	if ack["sequence"].(float64) != 1 || ack["clientMessageId"] != cm2 || ack["messageId"] == "" {
		t.Fatalf("ack %v", ack)
	}
	created := anon.expect("message.created")["message"].(map[string]any)
	author := created["author"].(map[string]any)
	if created["text"] != "Hola <b>chat</b>" || author["displayName"] != "usr_ana" || author["avatarUri"] != nil ||
		created["streamOffsetMs"].(float64) != 61000 || created["messageId"] != ack["messageId"] {
		t.Fatalf("created %v", created)
	}

	code, hist, _ := h.history(t, "ses_live", "")
	if code != 200 || hist.SnapshotSequence != 1 || len(hist.Items) != 1 || hist.RoomStatus != chat.RoomOpen {
		t.Fatalf("historial %d %+v", code, hist)
	}
}

func TestRetryWithSameClientMessageIDIsNotDuplicated(t *testing.T) {
	h := newHarness(t, nil, nil)
	viewer := h.dial(t, "ses_live", "")
	ana := h.dial(t, "ses_live", "cookie-ana")

	ana.send(cm1, "uno")
	first := ana.expect("message.accepted")
	viewer.expect("message.created")
	h.mr.FastForward(2 * time.Second)

	ana.send(cm1, "uno")
	again := ana.expect("message.accepted")
	if again["messageId"] != first["messageId"] || again["sequence"] != first["sequence"] {
		t.Fatalf("reintento %v vs %v", again, first)
	}
	viewer.expectNone("message.created", 300*time.Millisecond)

	ana.send(cm1, "otro texto")
	if e := ana.expect("error"); e["code"] != chat.CodeMessageIDConflict {
		t.Fatalf("conflicto %v", e)
	}
}

func TestValidationAndRateLimitFrames(t *testing.T) {
	h := newHarness(t, nil, nil)
	ana := h.dial(t, "ses_live", "cookie-ana")

	ana.send(cm1, " 　 ")
	if e := ana.expect("error"); e["code"] != chat.CodeMessageEmpty {
		t.Fatalf("%v", e)
	}
	ana.send(cm1, strings.Repeat("ñ", 501))
	if e := ana.expect("error"); e["code"] != chat.CodeMessageTooLong {
		t.Fatalf("%v", e)
	}
	ana.send("no-uuid", "hola")
	if e := ana.expect("error"); e["code"] != chat.CodeValidation {
		t.Fatalf("%v", e)
	}
	ana.send(cm1, strings.Repeat("ñ", 500))
	ana.expect("message.accepted")
	ana.send(cm2, "demasiado pronto")
	e := ana.expect("error")
	if e["code"] != chat.CodeRateLimited || e["retryAfterMs"].(float64) <= 0 || e["retryAfterMs"].(float64) > 1000 {
		t.Fatalf("%v", e)
	}
}

func TestCoreFailuresRejectWithoutPersisting(t *testing.T) {
	h := newHarness(t, nil, nil)
	ana := h.dial(t, "ses_live", "cookie-ana")
	cases := []struct {
		apply func(*fakeCore)
		want  string
	}{
		{func(f *fakeCore) { f.failWith, f.failCode = 503, "" }, chat.CodeCoreUnavailable},
		{func(f *fakeCore) { f.failWith, f.failCode = 503, chat.CodeStreamingUnavailable }, chat.CodeStreamingUnavailable},
		{func(f *fakeCore) { f.failWith = 0; f.delay = 350 * time.Millisecond }, chat.CodeTimelineUnavailable},
		{func(f *fakeCore) { f.delay = time.Second }, chat.CodeCoreUnavailable},
	}
	for _, tc := range cases {
		h.core.set(tc.apply)
		ana.send(cm1, "hola")
		if e := ana.expect("error"); e["code"] != tc.want {
			t.Fatalf("want %s got %v", tc.want, e)
		}
	}
	if _, hist, _ := h.history(t, "ses_live", ""); len(hist.Items) != 0 || hist.SnapshotSequence != 0 {
		t.Fatalf("un rechazo no persiste: %+v", hist)
	}
	h.core.set(func(f *fakeCore) { f.delay = 0 })
	ana.send(cm1, "hola")
	ana.expect("message.accepted")
}

func TestOriginIsValidatedBeforeUpgradeEvenForAnonymous(t *testing.T) {
	h := newHarness(t, nil, nil)
	url := "ws" + strings.TrimPrefix(h.public.URL, "http") + "/realtime/chat/sessions/ses_live"
	for _, origin := range []string{"https://evil.example", ""} {
		hdr := http.Header{}
		if origin != "" {
			hdr.Set("Origin", origin)
		}
		_, resp, err := websocket.Dial(context.Background(), url, &websocket.DialOptions{HTTPHeader: hdr})
		if err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
			t.Fatalf("origin %q: %v %v", origin, err, resp)
		}
	}
}

func (h *harness) postEvent(t *testing.T, token string, ev map[string]any) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(ev)
	req, _ := http.NewRequest(http.MethodPost, h.internal.URL+"/internal/chat/session-events", bytes.NewReader(b))
	req.Header.Set("X-Service-Name", "streaming")
	req.Header.Set("X-Service-Token", token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func sessionEventBody(id, status string, version int64) map[string]any {
	return map[string]any{
		"eventId": id, "eventType": "SessionStatusChanged", "schemaVersion": 1, "aggregateId": "session:ses_live",
		"sequence": version, "occurredAtUtc": "2026-10-03T20:00:00Z", "producer": "streaming",
		"payload": map[string]any{"streamId": "str_1", "sessionId": "ses_live", "streamGeneration": 1,
			"sessionVersion": version, "status": status, "availability": "OFFLINE"},
	}
}

func TestEndedSessionEventMakesRoomReadOnlyAndKeepsHistoryForRetention(t *testing.T) {
	h := newHarness(t, nil, nil)
	viewer := h.dial(t, "ses_live", "")
	ana := h.dial(t, "ses_live", "cookie-ana")
	ana.send(cm1, "último mensaje")
	ana.expect("message.accepted")

	if code, _ := h.postEvent(t, "wrong", sessionEventBody("evt-1", "ENDED", 3)); code != http.StatusUnauthorized {
		t.Fatalf("token inválido -> %d", code)
	}
	bad := sessionEventBody("evt-1", "ENDED", 3)
	bad["aggregateId"] = "session:otra"
	if code, _ := h.postEvent(t, eventsToken, bad); code != http.StatusBadRequest {
		t.Fatalf("aggregateId inválido -> %d", code)
	}
	code, out := h.postEvent(t, eventsToken, sessionEventBody("evt-1", "ENDED", 3))
	if code != http.StatusAccepted || out["duplicate"] != false {
		t.Fatalf("primer evento %d %v", code, out)
	}
	if code, out := h.postEvent(t, eventsToken, sessionEventBody("evt-1", "ENDED", 3)); code != http.StatusOK || out["duplicate"] != true {
		t.Fatalf("reintento %d %v", code, out)
	}
	if code, _ := h.postEvent(t, eventsToken, sessionEventBody("evt-1", "LIVE", 3)); code != http.StatusConflict {
		t.Fatalf("eventId reutilizado -> %d", code)
	}
	// Un evento LIVE atrasado no reabre la sala terminada.
	if code, out := h.postEvent(t, eventsToken, sessionEventBody("evt-2", "LIVE", 2)); code != http.StatusAccepted || out["ignored"] != true {
		t.Fatalf("evento viejo %d %v", code, out)
	}

	if st := viewer.expect("chat.status"); st["roomStatus"] != string(chat.RoomReadOnly) {
		t.Fatalf("status %v", st)
	}
	h.core.set(func(f *fakeCore) { f.status = "ENDED"; f.version = 3 })
	h.mr.FastForward(2 * time.Second)
	ana.send(cm2, "tarde")
	if e := ana.expect("error"); e["code"] != chat.CodeChatReadOnly {
		t.Fatalf("%v", e)
	}
	// El ACK de un mensaje previo sigue recuperable con sesión vigente.
	ana.send(cm1, "último mensaje")
	ana.expect("message.accepted")

	h.core.set(func(f *fakeCore) { f.failWith = 503 }) // sala conocida se lee sin Core
	code, hist, _ := h.history(t, "ses_live", "?limit=50")
	if code != 200 || hist.RoomStatus != chat.RoomReadOnly || len(hist.Items) != 1 {
		t.Fatalf("historial READ_ONLY %d %+v", code, hist)
	}

	h.mr.FastForward(5 * time.Minute)
	h.core.set(func(f *fakeCore) { f.failWith = 0 })
	code, hist, _ = h.history(t, "ses_live", "")
	if code != 200 || len(hist.Items) != 0 || hist.RoomStatus != chat.RoomReadOnly {
		t.Fatalf("tras retención el chat se borra: %d %+v", code, hist)
	}
}

func TestHistoryValidationAndRoomResolution(t *testing.T) {
	h := newHarness(t, nil, nil)
	for _, q := range []string{"?limit=0", "?limit=51", "?limit=abc"} {
		if code, _, e := h.history(t, "ses_live", q); code != 400 || e.Code != chat.CodeValidation || e.RequestID == "" {
			t.Fatalf("%s -> %d %+v", q, code, e)
		}
	}
	if code, _, e := h.history(t, "ses_desconocida", ""); code != 404 || e.Code != chat.CodeSessionNotFound {
		t.Fatalf("desconocida -> %d %+v", code, e)
	}
	h.core.set(func(f *fakeCore) { f.failWith = 503 })
	if code, _, e := h.history(t, "ses_live", ""); code != 503 || e.Code != chat.CodeCoreUnavailable {
		t.Fatalf("sala desconocida con Core caído no es 'sala vacía': %d %+v", code, e)
	}
	h.core.set(func(f *fakeCore) { f.failWith = 0; f.status = "PREPARING" })
	if code, _, e := h.history(t, "ses_live", ""); code != 409 || e.Code != chat.CodeChatNotOpen {
		t.Fatalf("PREPARING -> %d %+v", code, e)
	}
}

func TestMessagesCommittedByAnotherReplicaAreDelivered(t *testing.T) {
	mr := miniredis.RunT(t)
	a := newHarness(t, mr, nil)
	b := newHarness(t, mr, a.core)
	viewerOnA := a.dial(t, "ses_live", "")
	luisOnB := b.dial(t, "ses_live", "cookie-luis")

	luisOnB.send(cm1, "desde la réplica B")
	luisOnB.expect("message.accepted")
	if m := viewerOnA.expect("message.created")["message"].(map[string]any); m["text"] != "desde la réplica B" {
		t.Fatalf("%v", m)
	}

	// Un proceso que confirma y cae antes de difundir: la entrada del Stream igual se entrega.
	res, err := a.store.Accept(context.Background(), store.AcceptInput{
		SessionID: "ses_live", UserID: "usr_ana", ClientMessageID: cm3, TextHash: chat.TextFingerprint("huérfano"),
		WriteAllowed: true, Message: chat.Message{MessageID: "msg_x", SessionID: "ses_live", Text: "huérfano", ServerCreatedAtUTC: time.Now().UTC()},
	})
	if err != nil || res.Outcome != store.Accepted {
		t.Fatalf("%+v %v", res, err)
	}
	if m := viewerOnA.expect("message.created")["message"].(map[string]any); m["text"] != "huérfano" || m["sequence"].(float64) != 2 {
		t.Fatalf("%v", m)
	}
}

func TestWebSocketRejectsNotOpenAndUnknownSessions(t *testing.T) {
	h := newHarness(t, nil, nil)
	url := "ws" + strings.TrimPrefix(h.public.URL, "http") + "/realtime/chat/sessions/"
	hdr := http.Header{"Origin": {webOrigin}}
	if _, resp, err := websocket.Dial(context.Background(), url+"ses_nope", &websocket.DialOptions{HTTPHeader: hdr}); err == nil || resp.StatusCode != 404 {
		t.Fatalf("desconocida: %v", resp)
	}
	h.core.set(func(f *fakeCore) { f.status = "PREPARING" })
	if _, resp, err := websocket.Dial(context.Background(), url+"ses_live", &websocket.DialOptions{HTTPHeader: hdr}); err == nil || resp.StatusCode != 409 {
		t.Fatalf("PREPARING: %v", resp)
	}
}
