package transport

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestNativeUpgradeValidatesBearerWithoutInventedOrigin(t *testing.T) {
	h := newHarness(t, nil, nil)
	endpoint := "ws" + strings.TrimPrefix(h.public.URL, "http") + "/realtime/chat/sessions/ses_live"
	headers := http.Header{"Authorization": {"Bearer cookie-ana"}}
	conn, response, err := websocket.Dial(context.Background(), endpoint, &websocket.DialOptions{HTTPHeader: headers})
	if err != nil {
		t.Fatalf("native Upgrade: %v (%v)", err, response)
	}
	defer conn.CloseNow()
	client := &wsClient{t: t, conn: conn}
	ready, ok := client.next(time.Second)
	if !ok || ready["type"] != "chat.ready" {
		t.Fatalf("ready: %v", ready)
	}
	h.core.set(func(core *fakeCore) { delete(core.users, "cookie-ana") })
	client.send("00000000-0000-4000-8000-000000000091", "after revocation")
	frame, ok := client.next(time.Second)
	if !ok || frame["code"] != "AUTH_REQUIRED" {
		t.Fatalf("revoked principal must fail next Send: %v", frame)
	}
}

func TestNativeAndBrowserCredentialBoundaries(t *testing.T) {
	h := newHarness(t, nil, nil)
	endpoint := "ws" + strings.TrimPrefix(h.public.URL, "http") + "/realtime/chat/sessions/ses_live"
	for name, headers := range map[string]http.Header{
		"revoked":               {"Authorization": {"Bearer missing"}},
		"wrong scheme":          {"Authorization": {"Basic abc"}},
		"contradictory":         {"Authorization": {"Bearer cookie-ana"}, "Cookie": {"stream_session=cookie-luis"}},
		"browser origin":        {"Authorization": {"Bearer cookie-ana"}, "Origin": {"https://evil.example.test"}},
		"cookie without origin": {"Cookie": {"stream_session=cookie-ana"}},
	} {
		t.Run(name, func(t *testing.T) {
			conn, response, err := websocket.Dial(context.Background(), endpoint, &websocket.DialOptions{HTTPHeader: headers})
			if conn != nil {
				conn.CloseNow()
			}
			if err == nil || response == nil || (response.StatusCode != 401 && response.StatusCode != 403) {
				t.Fatalf("unexpected acceptance: %v (%v)", err, response)
			}
		})
	}
	conn, response, err := websocket.Dial(context.Background(), endpoint, nil)
	if err != nil {
		t.Fatalf("anonymous native read: %v (%v)", err, response)
	}
	conn.CloseNow()
}
