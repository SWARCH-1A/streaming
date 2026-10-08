// Package store es el único repositorio de escritura de Chat. Usa Redis con scripts Lua para que
// dedupe, cuota, secuencia, mensaje y entrega (Redis Stream) se confirmen en una sola operación atómica.
//
// Claves por sesión (todas con la misma etiqueta {sessionId}):
//
//	chat:{sid}:room   hash   estado de sala (status, generation, version, availability)
//	chat:{sid}:seq    string último sequence asignado
//	chat:{sid}:dedupe hash   userId|clientMessageId -> textHash|sequence|messageId|createdAtMs
//	chat:{sid}:msgs   stream mensajes con ID 0-<sequence>; historial y outbox de distribución
//
// Claves globales: chat:quota:{userId} (ventana de 1000 ms) y chat:inbox:{eventId} (dedupe de eventos).
// Al terminar la sesión todas las claves de la sala expiran tras la retención configurada.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"streaming/chat/internal/chat"
)

// Options fija retenciones y límites; los valores salen de configuración.
type Options struct {
	EndedRetention time.Duration // tiempo que una sala READ_ONLY sigue legible antes de borrarse
	IdleTTL        time.Duration // vencimiento de seguridad de salas sin actividad ni evento de fin
	InboxTTL       time.Duration // retención de eventIds para deduplicar reintentos de session-events
}

// Store encapsula Redis; ningún otro paquete construye claves ni comandos.
type Store struct {
	rdb  redis.UniversalClient
	opts Options
}

func New(rdb redis.UniversalClient, opts Options) *Store { return &Store{rdb: rdb, opts: opts} }

func roomKey(sid string) string   { return "chat:{" + sid + "}:room" }
func seqKey(sid string) string    { return "chat:{" + sid + "}:seq" }
func dedupeKey(sid string) string { return "chat:{" + sid + "}:dedupe" }
func msgsKey(sid string) string   { return "chat:{" + sid + "}:msgs" }
func quotaKey(uid string) string  { return "chat:quota:" + uid }
func inboxKey(eid string) string  { return "chat:inbox:" + eid }

// Ping comprueba disponibilidad para readiness.
func (s *Store) Ping(ctx context.Context) error { return s.rdb.Ping(ctx).Err() }

// ---- Envío ----

// AcceptInput es un mensaje nuevo ya validado y autorizado por el contexto Core.
type AcceptInput struct {
	SessionID       string
	UserID          string
	ClientMessageID string
	TextHash        string
	WriteAllowed    bool
	DenialCode      string
	Message         chat.Message // sin Sequence; lo asigna el script
}

type Outcome int

const (
	Accepted Outcome = iota
	Duplicate
	Conflict
	Denied
	RateLimited
)

type AcceptResult struct {
	Outcome    Outcome
	Ack        chat.Ack
	DenialCode string
	RetryAfter time.Duration
}

var acceptScript = redis.NewScript(`
local prev = redis.call('HGET', KEYS[3], ARGV[1])
if prev then
  if string.sub(prev, 1, 64) ~= ARGV[2] then return {'CONFLICT'} end
  return {'DUPLICATE', prev}
end
if ARGV[3] ~= '1' then return {'DENIED', ARGV[4]} end
if redis.call('HGET', KEYS[1], 'status') == 'READ_ONLY' then return {'DENIED', 'CHAT_READ_ONLY'} end
if not redis.call('SET', KEYS[5], '1', 'PX', ARGV[8], 'NX') then
  return {'RATE_LIMITED', tostring(redis.call('PTTL', KEYS[5]))}
end
local seq = redis.call('INCR', KEYS[2])
redis.call('XADD', KEYS[4], '0-' .. seq, 'm', ARGV[7])
local rec = ARGV[2] .. '|' .. seq .. '|' .. ARGV[5] .. '|' .. ARGV[6]
redis.call('HSET', KEYS[3], ARGV[1], rec)
for i = 2, 4 do redis.call('PEXPIRE', KEYS[i], ARGV[9]) end
return {'ACCEPTED', rec}
`)

// Accept busca primero un resultado previo (aunque writeAllowed=false), luego exige permiso, sala no
// terminada y cuota, y finalmente asigna sequence y guarda mensaje+dedupe+entrega antes del ACK.
func (s *Store) Accept(ctx context.Context, in AcceptInput) (AcceptResult, error) {
	payload, err := json.Marshal(in.Message)
	if err != nil {
		return AcceptResult{}, err
	}
	allowed := "0"
	if in.WriteAllowed {
		allowed = "1"
	}
	keys := []string{roomKey(in.SessionID), seqKey(in.SessionID), dedupeKey(in.SessionID), msgsKey(in.SessionID), quotaKey(in.UserID)}
	raw, err := acceptScript.Run(ctx, s.rdb, keys,
		in.UserID+"|"+in.ClientMessageID, in.TextHash, allowed, in.DenialCode,
		in.Message.MessageID, in.Message.ServerCreatedAtUTC.UnixMilli(), payload,
		chat.QuotaWindow.Milliseconds(), s.opts.IdleTTL.Milliseconds(),
	).Slice()
	if err != nil {
		return AcceptResult{}, err
	}
	kind, _ := raw[0].(string)
	switch kind {
	case "ACCEPTED", "DUPLICATE":
		ack, err := parseDedupeRecord(raw[1].(string), in.SessionID, in.ClientMessageID)
		if err != nil {
			return AcceptResult{}, err
		}
		out := Accepted
		if kind == "DUPLICATE" {
			out = Duplicate
		}
		return AcceptResult{Outcome: out, Ack: ack}, nil
	case "CONFLICT":
		return AcceptResult{Outcome: Conflict}, nil
	case "DENIED":
		return AcceptResult{Outcome: Denied, DenialCode: raw[1].(string)}, nil
	case "RATE_LIMITED":
		ms, _ := strconv.ParseInt(raw[1].(string), 10, 64)
		if ms <= 0 {
			ms = 1
		}
		return AcceptResult{Outcome: RateLimited, RetryAfter: time.Duration(ms) * time.Millisecond}, nil
	}
	return AcceptResult{}, fmt.Errorf("resultado de script desconocido %q", kind)
}

func parseDedupeRecord(rec, sid, cmid string) (chat.Ack, error) {
	parts := strings.Split(rec, "|")
	if len(parts) != 4 {
		return chat.Ack{}, fmt.Errorf("registro de dedupe inválido")
	}
	seq, err1 := strconv.ParseInt(parts[1], 10, 64)
	ms, err2 := strconv.ParseInt(parts[3], 10, 64)
	if err1 != nil || err2 != nil {
		return chat.Ack{}, fmt.Errorf("registro de dedupe inválido")
	}
	return chat.Ack{
		ClientMessageID:    cmid,
		MessageID:          parts[2],
		SessionID:          sid,
		Sequence:           seq,
		ServerCreatedAtUTC: time.UnixMilli(ms).UTC(),
	}, nil
}

// ---- Lectura ----

// History devuelve los últimos limit mensajes en sequence ascendente y el sequence observado en la
// misma lectura atómica (snapshotSequence).
func (s *Store) History(ctx context.Context, sid string, limit int) (int64, []chat.Message, error) {
	var seqCmd *redis.StringCmd
	var rangeCmd *redis.XMessageSliceCmd
	_, err := s.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		seqCmd = p.Get(ctx, seqKey(sid))
		rangeCmd = p.XRevRangeN(ctx, msgsKey(sid), "+", "-", int64(limit))
		return nil
	})
	if err != nil && !errors.Is(err, redis.Nil) {
		return 0, nil, err
	}
	snapshot, err := seqCmd.Int64()
	if err != nil && !errors.Is(err, redis.Nil) {
		return 0, nil, err
	}
	entries, err := rangeCmd.Result()
	if err != nil && !errors.Is(err, redis.Nil) {
		return 0, nil, err
	}
	msgs := make([]chat.Message, 0, len(entries))
	for i := len(entries) - 1; i >= 0; i-- {
		m, err := decodeEntry(entries[i])
		if err != nil {
			return 0, nil, err
		}
		msgs = append(msgs, m)
	}
	return snapshot, msgs, nil
}

// LastSequence es el último sequence asignado en la sala (0 si no hay mensajes).
func (s *Store) LastSequence(ctx context.Context, sid string) (int64, error) {
	n, err := s.rdb.Get(ctx, seqKey(sid)).Int64()
	if errors.Is(err, redis.Nil) {
		return 0, nil
	}
	return n, err
}

// ReadAfter bloquea hasta block esperando mensajes con sequence mayor que after. Es la lectura del
// outbox: cualquier réplica entrega lo confirmado aunque el proceso que lo aceptó haya caído.
func (s *Store) ReadAfter(ctx context.Context, sid string, after int64, block time.Duration, count int64) ([]chat.Message, error) {
	res, err := s.rdb.XRead(ctx, &redis.XReadArgs{
		Streams: []string{msgsKey(sid), "0-" + strconv.FormatInt(after, 10)},
		Count:   count,
		Block:   block,
	}).Result()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var msgs []chat.Message
	for _, st := range res {
		for _, e := range st.Messages {
			m, err := decodeEntry(e)
			if err != nil {
				return nil, err
			}
			msgs = append(msgs, m)
		}
	}
	return msgs, nil
}

func decodeEntry(e redis.XMessage) (chat.Message, error) {
	raw, _ := e.Values["m"].(string)
	var m chat.Message
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return chat.Message{}, fmt.Errorf("mensaje %s ilegible: %w", e.ID, err)
	}
	_, seqPart, ok := strings.Cut(e.ID, "-")
	seq, err := strconv.ParseInt(seqPart, 10, 64)
	if !ok || err != nil {
		return chat.Message{}, fmt.Errorf("ID de mensaje inválido %s", e.ID)
	}
	m.Sequence = seq
	return m, nil
}

// ---- Estado de sala ----

// Room devuelve el estado local conocido; Known=false si nunca se recibió o ya expiró.
func (s *Store) Room(ctx context.Context, sid string) (chat.Room, error) {
	vals, err := s.rdb.HMGet(ctx, roomKey(sid), "status", "generation", "version").Result()
	if err != nil {
		return chat.Room{}, err
	}
	status, ok := vals[0].(string)
	if !ok {
		return chat.Room{}, nil
	}
	gen, _ := strconv.ParseInt(fmt.Sprint(vals[1]), 10, 64)
	ver, _ := strconv.ParseInt(fmt.Sprint(vals[2]), 10, 64)
	return chat.Room{Known: true, Status: chat.RoomStatus(status), StreamGeneration: gen, SessionVersion: ver}, nil
}

// applyLua aplica generación/versión mayores; READ_ONLY es terminal y arranca la retención de la sala.
const applyLua = `
local function apply(room, others, gen, ver, status, avail, retentionMs, idleMs, nowMs)
  local cur = redis.call('HMGET', room, 'status', 'generation', 'version')
  if cur[1] == 'READ_ONLY' then return 'STALE' end
  if cur[1] then
    local cg = tonumber(cur[2])
    local cv = tonumber(cur[3])
    if gen < cg or (gen == cg and ver <= cv) then return 'STALE' end
  end
  redis.call('HSET', room, 'status', status, 'generation', gen, 'version', ver, 'availability', avail, 'updatedAtMs', nowMs)
  if status == 'READ_ONLY' then
    redis.call('PEXPIRE', room, retentionMs)
    for _, k in ipairs(others) do redis.call('PEXPIRE', k, retentionMs) end
  else
    redis.call('PEXPIRE', room, idleMs)
  end
  return 'APPLIED'
end
`

var applyStateScript = redis.NewScript(applyLua + `
return apply(KEYS[1], {KEYS[2], KEYS[3], KEYS[4]}, tonumber(ARGV[1]), tonumber(ARGV[2]), ARGV[3], ARGV[4], ARGV[5], ARGV[6], ARGV[7])
`)

var applyEventScript = redis.NewScript(applyLua + `
local prev = redis.call('GET', KEYS[1])
if prev then
  if prev == ARGV[8] then return 'DUPLICATE' end
  return 'CONFLICT'
end
redis.call('SET', KEYS[1], ARGV[8], 'PX', ARGV[9])
return apply(KEYS[2], {KEYS[3], KEYS[4], KEYS[5]}, tonumber(ARGV[1]), tonumber(ARGV[2]), ARGV[3], ARGV[4], ARGV[5], ARGV[6], ARGV[7])
`)

// ApplyResult describe el efecto de un estado sobre la sala.
type ApplyResult string

const (
	Applied        ApplyResult = "APPLIED"
	Stale          ApplyResult = "STALE"
	EventDuplicate ApplyResult = "DUPLICATE"
	EventConflict  ApplyResult = "CONFLICT"
)

func (s *Store) stateArgs(st chat.SessionState) []any {
	return []any{st.StreamGeneration, st.SessionVersion, string(st.Status), st.Availability,
		s.opts.EndedRetention.Milliseconds(), s.opts.IdleTTL.Milliseconds(), time.Now().UnixMilli()}
}

// ApplyState reconcilia la sala con un snapshot.
func (s *Store) ApplyState(ctx context.Context, st chat.SessionState) (ApplyResult, error) {
	sid := st.SessionID
	res, err := applyStateScript.Run(ctx, s.rdb,
		[]string{roomKey(sid), seqKey(sid), dedupeKey(sid), msgsKey(sid)}, s.stateArgs(st)...).Text()
	return ApplyResult(res), err
}

// ApplyEvent registra el eventId en la inbox y aplica el estado en la misma operación atómica.
// Reintento idéntico devuelve DUPLICATE; mismo eventId con otro fingerprint devuelve CONFLICT.
func (s *Store) ApplyEvent(ctx context.Context, eventID, fingerprint string, st chat.SessionState) (ApplyResult, error) {
	sid := st.SessionID
	args := append(s.stateArgs(st), fingerprint, s.opts.InboxTTL.Milliseconds())
	res, err := applyEventScript.Run(ctx, s.rdb,
		[]string{inboxKey(eventID), roomKey(sid), seqKey(sid), dedupeKey(sid), msgsKey(sid)}, args...).Text()
	return ApplyResult(res), err
}
