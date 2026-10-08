package transport

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"streaming/chat/internal/chat"
	"streaming/chat/internal/store"
)

// Hub mantiene un lector por sala con clientes locales. Cada lector consume el Redis Stream de la
// sala, así que toda réplica entrega lo que cualquier réplica confirmó, incluso tras un crash.
type Hub struct {
	store *store.Store
	log   *slog.Logger
	block time.Duration

	mu     sync.Mutex
	rooms  map[string]*roomFeed
	closed bool
}

type roomFeed struct {
	clients map[*client]struct{}
	cancel  context.CancelFunc
}

func NewHub(s *store.Store, log *slog.Logger, block time.Duration) *Hub {
	return &Hub{store: s, log: log, block: block, rooms: map[string]*roomFeed{}}
}

// Join registra al cliente y le encola chat.ready bajo el mismo bloqueo que la difusión, así que
// chat.ready siempre es el primer frame. Todo mensaje confirmado después llega en vivo y lo anterior
// está en el historial que el cliente pide a continuación.
func (h *Hub) Join(ctx context.Context, sid string, c *client, ready func(lastSequence int64) []byte) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return context.Canceled
	}
	last, err := h.store.LastSequence(ctx, sid)
	if err != nil {
		return err
	}
	feed, ok := h.rooms[sid]
	if !ok {
		room, err := h.store.Room(ctx, sid)
		if err != nil {
			return err
		}
		fctx, cancel := context.WithCancel(context.Background())
		feed = &roomFeed{clients: map[*client]struct{}{}, cancel: cancel}
		h.rooms[sid] = feed
		go h.run(fctx, sid, last, room.Status)
	}
	c.enqueue(ready(last))
	feed.clients[c] = struct{}{}
	return nil
}

// Leave detiene el lector cuando la sala no tiene clientes locales.
func (h *Hub) Leave(sid string, c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	feed, ok := h.rooms[sid]
	if !ok {
		return
	}
	delete(feed.clients, c)
	if len(feed.clients) == 0 {
		feed.cancel()
		delete(h.rooms, sid)
	}
}

// Close detiene lectores y cierra conexiones al apagar el proceso.
func (h *Hub) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closed = true
	for sid, feed := range h.rooms {
		feed.cancel()
		for c := range feed.clients {
			c.shutdown()
		}
		delete(h.rooms, sid)
	}
}

func (h *Hub) broadcast(sid string, frame []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	feed, ok := h.rooms[sid]
	if !ok {
		return
	}
	for c := range feed.clients {
		c.enqueue(frame)
	}
}

func (h *Hub) run(ctx context.Context, sid string, after int64, status chat.RoomStatus) {
	backoff := 100 * time.Millisecond
	for ctx.Err() == nil {
		msgs, err := h.store.ReadAfter(ctx, sid, after, h.block, 100)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			h.log.Warn("lector de sala", "sessionId", sid, "err", err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(backoff):
			}
			backoff = min(backoff*2, 2*time.Second)
			continue
		}
		backoff = 100 * time.Millisecond
		for _, m := range msgs {
			if m.Sequence <= after {
				continue
			}
			after = m.Sequence
			h.broadcast(sid, mustJSON(createdFrame{Type: "message.created", Message: m}))
		}
		// El estado viaja por evento/snapshot a Redis; se observa aquí para avisar READ_ONLY a todas
		// las réplicas sin otro canal.
		if room, err := h.store.Room(ctx, sid); err == nil && room.Known && room.Status != status {
			status = room.Status
			h.broadcast(sid, mustJSON(statusFrame{Type: "chat.status", SessionID: sid, RoomStatus: status}))
		}
	}
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}
