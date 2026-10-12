package transport

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	"streaming/chat/internal/chat"
	"streaming/chat/internal/core"
)

const (
	readLimit    = 16 << 10 // 500 puntos de código escapados caben con holgura
	sendBuffer   = 256
	writeTimeout = 5 * time.Second
	pingInterval = 25 * time.Second
)

// client es una conexión WS. Todo frame sale por la cola send para conservar un único escritor.
type client struct {
	conn *websocket.Conn
	send chan []byte
	done chan struct{}
	once sync.Once
}

func newClient(conn *websocket.Conn) *client {
	return &client{conn: conn, send: make(chan []byte, sendBuffer), done: make(chan struct{})}
}

// enqueue no bloquea: un cliente lento se desconecta y recupera por historial al reconectar.
func (c *client) enqueue(frame []byte) {
	select {
	case <-c.done:
	case c.send <- frame:
	default:
		c.closeWith(websocket.StatusPolicyViolation, "cliente lento")
	}
}

func (c *client) closeWith(code websocket.StatusCode, reason string) {
	c.once.Do(func() {
		close(c.done)
		go c.conn.Close(code, reason)
	})
}

func (c *client) shutdown() { c.closeWith(websocket.StatusGoingAway, "reinicio de chat") }

func (c *client) writeLoop(ctx context.Context) {
	ping := time.NewTicker(pingInterval)
	defer ping.Stop()
	for {
		select {
		case <-c.done:
			return
		case <-ctx.Done():
			return
		case frame := <-c.send:
			wctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.conn.Write(wctx, websocket.MessageText, frame)
			cancel()
			if err != nil {
				c.closeWith(websocket.StatusInternalError, "")
				return
			}
		case <-ping.C:
			pctx, cancel := context.WithTimeout(ctx, 2*writeTimeout)
			err := c.conn.Ping(pctx)
			cancel()
			if err != nil {
				c.closeWith(websocket.StatusGoingAway, "sin respuesta")
				return
			}
		}
	}
}

// handleRealtime implementa WS /realtime/chat/sessions/{sessionId}. Errores previos al Upgrade son
// HTTP; posteriores son frames. La cookie se usa solo para pedir contexto por mensaje.
func (s *Server) handleRealtime(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sessionId")
	if !chat.ValidSessionID(sid) {
		s.writeError(w, r, http.StatusNotFound, chat.Fail(chat.CodeSessionNotFound))
		return
	}
	if !s.originAllowed(r.Header.Get("Origin")) {
		s.writeError(w, r, http.StatusForbidden, &chat.Error{Code: "ORIGIN_NOT_ALLOWED", Message: "Origen no permitido."})
		return
	}
	ctx := core.WithRequestID(r.Context(), requestID(r))
	status, cerr := s.svc.ResolveRoom(ctx, sid, true)
	if cerr != nil {
		s.writeError(w, r, httpStatus(cerr.Code), cerr)
		return
	}
	if status == chat.RoomNotOpen {
		s.writeError(w, r, http.StatusConflict, chat.Fail(chat.CodeChatNotOpen))
		return
	}
	var credential string
	if ck, err := r.Cookie(s.cfg.SessionCookie); err == nil {
		credential = ck.Value
	}

	// Origin ya se validó contra la lista exacta configurada.
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		return
	}
	conn.SetReadLimit(readLimit)
	c := newClient(conn)
	connCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go c.writeLoop(connCtx)

	err = s.hub.Join(connCtx, sid, c, func(last int64) []byte {
		return mustJSON(readyFrame{Type: "chat.ready", SessionID: sid, RoomStatus: status, LastSequence: last})
	})
	if err != nil {
		s.log.Error("unir cliente a sala", "sessionId", sid, "err", err)
		c.closeWith(websocket.StatusTryAgainLater, chat.CodeChatUnavailable)
		return
	}
	defer s.hub.Leave(sid, c)

	for {
		typ, data, err := conn.Read(connCtx)
		if err != nil {
			// La librería ya cerró con el código adecuado (p. ej. límite de lectura excedido).
			c.closeWith(websocket.StatusNormalClosure, "")
			return
		}
		if typ != websocket.MessageText {
			c.enqueue(mustJSON(newErrorFrame("", chat.Fail(chat.CodeValidation))))
			continue
		}
		var in inboundFrame
		if err := json.Unmarshal(data, &in); err != nil || in.Type != "message.send" {
			c.enqueue(mustJSON(newErrorFrame("", chat.Fail(chat.CodeValidation))))
			continue
		}
		sendCtx := core.WithRequestID(connCtx, newRequestID())
		ack, fresh, serr := s.svc.Send(sendCtx, sid, credential, in.ClientMessageID, in.Text)
		if serr != nil {
			c.enqueue(mustJSON(newErrorFrame(in.ClientMessageID, serr)))
			continue
		}
		s.log.Debug("mensaje aceptado", "sessionId", sid, "sequence", ack.Sequence, "duplicate", !fresh)
		c.enqueue(mustJSON(acceptedFrame{Type: "message.accepted", Ack: ack}))
	}
}
