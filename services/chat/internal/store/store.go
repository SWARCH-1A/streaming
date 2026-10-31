// Package store es el único repositorio de escritura de Chat. Usa Redis con scripts Lua para que
// dedupe, cuota, secuencia, mensaje y entrega (Redis Stream) se confirmen en una sola operación atómica.
//
// Claves por sesión (todas con la misma etiqueta {sessionId}):
//
//	chat:{sid}:room   hash   estado de sala (status, generation, version, availability)
//	chat:{sid}:seq    string último sequence asignado
//	chat:{sid}:dedupe hash   userId|clientMessageId -> textHash|sequence|messageId|createdAtMs
//	chat:{sid}:msgs    stream mensajes con ID 0-<sequence>; historial y outbox de distribución
//	chat:{sid}:ids     hash   messageId -> sequence, para moderar por ID
//	chat:{sid}:removed hash   messageId -> marca de retiro (sin texto)
//	chat:{sid}:mod     stream retiros para difundirlos a todas las réplicas
//	chat:{sid}:modver  string última moderationVersion de la sala
//
// Claves globales: chat:quota:{userId} (ventana de 1000 ms), chat:inbox:{eventId} (dedupe de eventos),
// chat:block:{channelId}:{userId} (copia de aplicación de un bloqueo; PostgreSQL es la fuente) y
// chat:archive (stream con grupo archiver: outbox hacia PostgreSQL, ADR-021).
// Al terminar la sesión todas las claves de la sala expiran tras la retención configurada; el
// archivo es independiente y solo se borra una entrada después de confirmarla en PostgreSQL.
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
	IdleTTL        time.Duration // caché de estado; nunca limita mensajes confirmados de una sesión activa
	InboxTTL       time.Duration // retención de eventIds para deduplicar reintentos de session-events
}

// Store encapsula Redis; ningún otro paquete construye claves ni comandos.
type Store struct {
	rdb  redis.UniversalClient
	opts Options
}

func New(rdb redis.UniversalClient, opts Options) *Store { return &Store{rdb: rdb, opts: opts} }

func roomKey(sid string) string    { return "chat:{" + sid + "}:room" }
func seqKey(sid string) string     { return "chat:{" + sid + "}:seq" }
func dedupeKey(sid string) string  { return "chat:{" + sid + "}:dedupe" }
func msgsKey(sid string) string    { return "chat:{" + sid + "}:msgs" }
func idsKey(sid string) string     { return "chat:{" + sid + "}:ids" }
func removedKey(sid string) string { return "chat:{" + sid + "}:removed" }
func modKey(sid string) string     { return "chat:{" + sid + "}:mod" }
func modverKey(sid string) string  { return "chat:{" + sid + "}:modver" }
func quotaKey(uid string) string   { return "chat:quota:" + uid }
func inboxKey(eid string) string   { return "chat:inbox:" + eid }
func blockKey(cid, uid string) string {
	return "chat:block:" + cid + ":" + uid
}

// ArchiveStream y ArchiveGroup forman el outbox hacia PostgreSQL.
const (
	ArchiveStream = "chat:archive"
	ArchiveGroup  = "archiver"
)

// noBlockKey se usa cuando la sala no conoce su canal (perfil P1): nunca se escribe.
const noBlockKey = "chat:block:-"

// roomKeys son todas las claves que expiran con la sala; la primera es el estado.
func roomKeys(sid string) []string {
	return []string{roomKey(sid), seqKey(sid), dedupeKey(sid), msgsKey(sid), idsKey(sid), removedKey(sid), modKey(sid), modverKey(sid)}
}

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
	ChannelID       string       // canal de la sala; vacío en P1 omite bloqueos y archivo
	Message         chat.Message // sin Sequence; lo asigna el script
}

type Outcome int

const (
	Accepted Outcome = iota
	Duplicate
	Conflict
	Denied
	RateLimited
	Blocked
)

type AcceptResult struct {
	Outcome      Outcome
	Ack          chat.Ack
	DenialCode   string
	RetryAfter   time.Duration
	BlockedUntil *time.Time // nil con Blocked significa permanente
}

var acceptScript = redis.NewScript(`
local prev = redis.call('HGET', KEYS[3], ARGV[1])
if prev then
  if string.sub(prev, 1, 64) ~= ARGV[2] then return {'CONFLICT'} end
  return {'DUPLICATE', prev}
end
if ARGV[3] ~= '1' then return {'DENIED', ARGV[4]} end
if redis.call('HGET', KEYS[1], 'status') == 'READ_ONLY' then return {'DENIED', 'CHAT_READ_ONLY'} end
local block = redis.call('GET', KEYS[6])
if block and (block == 'PERMANENT' or tonumber(block) > tonumber(ARGV[10])) then return {'BLOCKED', block} end
if not redis.call('SET', KEYS[5], '1', 'PX', ARGV[8], 'NX') then
  return {'RATE_LIMITED', tostring(redis.call('PTTL', KEYS[5]))}
end
local seq = redis.call('INCR', KEYS[2])
redis.call('XADD', KEYS[4], '0-' .. seq, 'm', ARGV[7])
redis.call('HSET', KEYS[7], ARGV[5], seq)
if ARGV[9] ~= '' then
  redis.call('XADD', KEYS[8], '*', 'c', ARGV[9], 's', seq, 'm', ARGV[7])
end
local rec = ARGV[2] .. '|' .. seq .. '|' .. ARGV[5] .. '|' .. ARGV[6]
redis.call('HSET', KEYS[3], ARGV[1], rec)
return {'ACCEPTED', rec}
`)

// Accept busca primero un resultado previo (aunque writeAllowed=false), luego exige permiso, sala no
// terminada, ausencia de bloqueo y cuota, y finalmente asigna sequence y guarda mensaje+dedupe+entrega
// y la entrada del archivo antes del ACK. PostgreSQL recibe la copia en segundo plano.
func (s *Store) Accept(ctx context.Context, in AcceptInput) (AcceptResult, error) {
	payload, err := json.Marshal(in.Message)
	if err != nil {
		return AcceptResult{}, err
	}
	allowed := "0"
	if in.WriteAllowed {
		allowed = "1"
	}
	block := noBlockKey
	if in.ChannelID != "" {
		block = blockKey(in.ChannelID, in.UserID)
	}
	keys := []string{roomKey(in.SessionID), seqKey(in.SessionID), dedupeKey(in.SessionID), msgsKey(in.SessionID),
		quotaKey(in.UserID), block, idsKey(in.SessionID), ArchiveStream}
	raw, err := acceptScript.Run(ctx, s.rdb, keys,
		in.UserID+"|"+in.ClientMessageID, in.TextHash, allowed, in.DenialCode,
		in.Message.MessageID, in.Message.ServerCreatedAtUTC.UnixMilli(), payload,
		chat.QuotaWindow.Milliseconds(), in.ChannelID, time.Now().UnixMilli(),
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
	case "BLOCKED":
		res := AcceptResult{Outcome: Blocked}
		if v := raw[1].(string); v != "PERMANENT" {
			ms, _ := strconv.ParseInt(v, 10, 64)
			until := time.UnixMilli(ms).UTC()
			res.BlockedUntil = &until
		}
		return res, nil
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

// HistoryView es una lectura atómica de la ventana reciente: mensajes vigentes y marcas de los
// retirados dentro de la misma ventana de sequence.
type HistoryView struct {
	SnapshotSequence  int64
	ModerationVersion int64
	Items             []chat.Message
	Removed           []chat.Removal
}

// History devuelve los últimos limit mensajes vigentes en sequence ascendente y el sequence observado
// en la misma lectura atómica (snapshotSequence).
func (s *Store) History(ctx context.Context, sid string, limit int) (int64, []chat.Message, error) {
	view, err := s.HistoryView(ctx, sid, limit)
	return view.SnapshotSequence, view.Items, err
}

// HistoryView lee las últimas limit entradas; una entrada retirada se devuelve solo como marca.
func (s *Store) HistoryView(ctx context.Context, sid string, limit int) (HistoryView, error) {
	var seqCmd, modverCmd *redis.StringCmd
	var rangeCmd *redis.XMessageSliceCmd
	var removedCmd *redis.MapStringStringCmd
	_, err := s.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		seqCmd = p.Get(ctx, seqKey(sid))
		rangeCmd = p.XRevRangeN(ctx, msgsKey(sid), "+", "-", int64(limit))
		removedCmd = p.HGetAll(ctx, removedKey(sid))
		modverCmd = p.Get(ctx, modverKey(sid))
		return nil
	})
	if err != nil && !errors.Is(err, redis.Nil) {
		return HistoryView{}, err
	}
	var view HistoryView
	if view.SnapshotSequence, err = seqCmd.Int64(); err != nil && !errors.Is(err, redis.Nil) {
		return HistoryView{}, err
	}
	if view.ModerationVersion, err = modverCmd.Int64(); err != nil && !errors.Is(err, redis.Nil) {
		return HistoryView{}, err
	}
	entries, err := rangeCmd.Result()
	if err != nil && !errors.Is(err, redis.Nil) {
		return HistoryView{}, err
	}
	removed, err := removedCmd.Result()
	if err != nil && !errors.Is(err, redis.Nil) {
		return HistoryView{}, err
	}
	view.Items = make([]chat.Message, 0, len(entries))
	view.Removed = []chat.Removal{}
	for i := len(entries) - 1; i >= 0; i-- {
		m, err := decodeEntry(entries[i])
		if err != nil {
			return HistoryView{}, err
		}
		if raw, ok := removed[m.MessageID]; ok {
			var r chat.Removal
			if err := json.Unmarshal([]byte(raw), &r); err != nil {
				return HistoryView{}, fmt.Errorf("marca de retiro ilegible: %w", err)
			}
			view.Removed = append(view.Removed, r)
			continue
		}
		view.Items = append(view.Items, m)
	}
	return view, nil
}

// MessageByID devuelve un mensaje de la sala viva; found=false si no existe o la sala ya expiró.
func (s *Store) MessageByID(ctx context.Context, sid, messageID string) (chat.Message, bool, error) {
	seq, err := s.rdb.HGet(ctx, idsKey(sid), messageID).Result()
	if errors.Is(err, redis.Nil) {
		return chat.Message{}, false, nil
	}
	if err != nil {
		return chat.Message{}, false, err
	}
	entries, err := s.rdb.XRange(ctx, msgsKey(sid), "0-"+seq, "0-"+seq).Result()
	if err != nil || len(entries) == 0 {
		return chat.Message{}, false, err
	}
	m, err := decodeEntry(entries[0])
	return m, err == nil, err
}

// MessagesByAuthor recorre la sala viva y devuelve los mensajes de userID con sequence <= upTo.
func (s *Store) MessagesByAuthor(ctx context.Context, sid, userID string, upTo int64) ([]chat.Message, error) {
	var out []chat.Message
	start := "-"
	end := "0-" + strconv.FormatInt(upTo, 10)
	for {
		entries, err := s.rdb.XRangeN(ctx, msgsKey(sid), start, end, 500).Result()
		if err != nil {
			return nil, err
		}
		for _, e := range entries {
			m, err := decodeEntry(e)
			if err != nil {
				return nil, err
			}
			if m.Author.UserID == userID {
				out = append(out, m)
			}
		}
		if len(entries) < 500 {
			return out, nil
		}
		start = "(" + entries[len(entries)-1].ID
	}
}

// removeScript marca retiros en la sala viva y los difunde. Si la sala ya expiró no escribe nada:
// el retiro durable vive en PostgreSQL. Las claves heredan la retención restante de la sala.
var removeScript = redis.NewScript(`
local ttl = redis.call('PTTL', KEYS[1])
if ttl == -2 then return 0 end
local added = 0
for i = 2, #ARGV, 2 do
  if redis.call('HSETNX', KEYS[2], ARGV[i], ARGV[i + 1]) == 1 then
    redis.call('XADD', KEYS[3], '*', 'r', ARGV[i + 1])
    added = added + 1
  end
end
local current = tonumber(redis.call('GET', KEYS[4]) or '0')
if tonumber(ARGV[1]) > current then redis.call('SET', KEYS[4], ARGV[1]) end
if ttl > 0 then
  for k = 2, 4 do redis.call('PEXPIRE', KEYS[k], ttl) end
end
return added
`)

// ApplyRemovals refleja en la sala viva retiros ya confirmados en PostgreSQL. Es idempotente.
func (s *Store) ApplyRemovals(ctx context.Context, sid string, moderationVersion int64, removals []chat.Removal) error {
	if len(removals) == 0 {
		return nil
	}
	args := []any{moderationVersion}
	for _, r := range removals {
		raw, err := json.Marshal(r)
		if err != nil {
			return err
		}
		args = append(args, r.MessageID, raw)
	}
	return removeScript.Run(ctx, s.rdb, []string{seqKey(sid), removedKey(sid), modKey(sid), modverKey(sid)}, args...).Err()
}

// SetBlock copia un bloqueo vigente a Redis para que el script de envío lo aplique. Un bloqueo
// temporal vence en Redis en el mismo instante absoluto que en PostgreSQL.
func (s *Store) SetBlock(ctx context.Context, channelID, userID string, until *time.Time) error {
	if until == nil {
		return s.rdb.Set(ctx, blockKey(channelID, userID), "PERMANENT", 0).Err()
	}
	if !until.After(time.Now()) {
		return s.ClearBlock(ctx, channelID, userID)
	}
	return s.rdb.SetArgs(ctx, blockKey(channelID, userID), strconv.FormatInt(until.UnixMilli(), 10),
		redis.SetArgs{ExpireAt: *until}).Err()
}

// ClearBlock retira la copia de aplicación de un bloqueo.
func (s *Store) ClearBlock(ctx context.Context, channelID, userID string) error {
	return s.rdb.Del(ctx, blockKey(channelID, userID)).Err()
}

// ClearBlocksExcept borra copias de bloqueos que ya no rigen en PostgreSQL (p. ej. un retiro cuya
// escritura en Redis falló). active usa la forma channelId:userId.
func (s *Store) ClearBlocksExcept(ctx context.Context, active map[string]bool) error {
	iter := s.rdb.Scan(ctx, 0, "chat:block:*", 200).Iterator()
	for iter.Next(ctx) {
		key := iter.Val()
		if id := strings.TrimPrefix(key, "chat:block:"); !active[id] && key != noBlockKey {
			if err := s.rdb.Del(ctx, key).Err(); err != nil {
				return err
			}
		}
	}
	return iter.Err()
}

// ---- Archivo hacia PostgreSQL ----

// ArchiveEntry es un mensaje confirmado pendiente de copiar a PostgreSQL.
type ArchiveEntry struct {
	ID        string
	ChannelID string
	Message   chat.Message
	Invalid   bool // entrada ilegible: se confirma sin escribir para no bloquear el archivo
}

// EnsureArchiveGroup crea el grupo de consumo del archivo si no existe.
func (s *Store) EnsureArchiveGroup(ctx context.Context) error {
	err := s.rdb.XGroupCreateMkStream(ctx, ArchiveStream, ArchiveGroup, "0").Err()
	if err != nil && strings.Contains(err.Error(), "BUSYGROUP") {
		return nil
	}
	return err
}

// ReadArchive entrega entradas nuevas a este consumidor; las no confirmadas quedan pendientes.
func (s *Store) ReadArchive(ctx context.Context, consumer string, count int64, block time.Duration) ([]ArchiveEntry, error) {
	res, err := s.rdb.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group: ArchiveGroup, Consumer: consumer, Streams: []string{ArchiveStream, ">"}, Count: count, Block: block,
	}).Result()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []ArchiveEntry
	for _, st := range res {
		for _, e := range st.Messages {
			out = append(out, decodeArchive(e))
		}
	}
	return out, nil
}

// ClaimArchive recupera entradas que otro consumidor leyó y no confirmó en minIdle (p. ej. se cayó).
func (s *Store) ClaimArchive(ctx context.Context, consumer string, minIdle time.Duration, count int64) ([]ArchiveEntry, error) {
	msgs, _, err := s.rdb.XAutoClaim(ctx, &redis.XAutoClaimArgs{
		Stream: ArchiveStream, Group: ArchiveGroup, Consumer: consumer, MinIdle: minIdle, Start: "0-0", Count: count,
	}).Result()
	if err != nil {
		return nil, err
	}
	out := make([]ArchiveEntry, 0, len(msgs))
	for _, e := range msgs {
		out = append(out, decodeArchive(e))
	}
	return out, nil
}

// AckArchive confirma y borra entradas ya escritas en PostgreSQL.
func (s *Store) AckArchive(ctx context.Context, ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	_, err := s.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		p.XAck(ctx, ArchiveStream, ArchiveGroup, ids...)
		p.XDel(ctx, ArchiveStream, ids...)
		return nil
	})
	return err
}

func decodeArchive(e redis.XMessage) ArchiveEntry {
	raw, _ := e.Values["m"].(string)
	channel, _ := e.Values["c"].(string)
	seqRaw, _ := e.Values["s"].(string)
	var m chat.Message
	seq, err := strconv.ParseInt(seqRaw, 10, 64)
	if json.Unmarshal([]byte(raw), &m) != nil || err != nil || seq < 1 || channel == "" {
		return ArchiveEntry{ID: e.ID, Invalid: true}
	}
	m.Sequence = seq
	return ArchiveEntry{ID: e.ID, ChannelID: channel, Message: m}
}

// LastSequence es el último sequence asignado en la sala (0 si no hay mensajes).
func (s *Store) LastSequence(ctx context.Context, sid string) (int64, error) {
	n, err := s.rdb.Get(ctx, seqKey(sid)).Int64()
	if errors.Is(err, redis.Nil) {
		return 0, nil
	}
	return n, err
}

// Feed es lo que el hub difunde de una sala: mensajes nuevos y retiros nuevos.
type Feed struct {
	Messages []chat.Message
	Removals []chat.Removal
	ModID    string // último ID leído del stream de retiros
}

// ReadFeed espera a la vez mensajes con sequence > after y retiros posteriores a modAfter.
func (s *Store) ReadFeed(ctx context.Context, sid string, after int64, modAfter string, block time.Duration, count int64) (Feed, error) {
	feed := Feed{ModID: modAfter}
	res, err := s.rdb.XRead(ctx, &redis.XReadArgs{
		Streams: []string{msgsKey(sid), modKey(sid), "0-" + strconv.FormatInt(after, 10), modAfter},
		Count:   count,
		Block:   block,
	}).Result()
	if errors.Is(err, redis.Nil) {
		return feed, nil
	}
	if err != nil {
		return feed, err
	}
	for _, st := range res {
		for _, e := range st.Messages {
			if st.Stream == modKey(sid) {
				var r chat.Removal
				raw, _ := e.Values["r"].(string)
				if err := json.Unmarshal([]byte(raw), &r); err != nil {
					return feed, fmt.Errorf("retiro %s ilegible: %w", e.ID, err)
				}
				feed.Removals = append(feed.Removals, r)
				feed.ModID = e.ID
				continue
			}
			m, err := decodeEntry(e)
			if err != nil {
				return feed, err
			}
			feed.Messages = append(feed.Messages, m)
		}
	}
	return feed, nil
}

// LastModID es el último retiro difundido de la sala ("0-0" si no hay).
func (s *Store) LastModID(ctx context.Context, sid string) (string, error) {
	res, err := s.rdb.XRevRangeN(ctx, modKey(sid), "+", "-", 1).Result()
	if err != nil || len(res) == 0 {
		return "0-0", err
	}
	return res[0].ID, nil
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
// El canal se lee del registro de ciclo P2, que no expira con la sala.
func (s *Store) Room(ctx context.Context, sid string) (chat.Room, error) {
	var roomCmd *redis.SliceCmd
	var channelCmd *redis.StringCmd
	_, err := s.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		roomCmd = p.HMGet(ctx, roomKey(sid), "status", "generation", "version")
		channelCmd = p.HGet(ctx, lifecycleKey(sid), "channel")
		return nil
	})
	if err != nil && !errors.Is(err, redis.Nil) {
		return chat.Room{}, err
	}
	vals, err := roomCmd.Result()
	if err != nil {
		return chat.Room{}, err
	}
	channel, _ := channelCmd.Result()
	status, ok := vals[0].(string)
	if !ok {
		return chat.Room{ChannelID: channel}, nil
	}
	gen, _ := strconv.ParseInt(fmt.Sprint(vals[1]), 10, 64)
	ver, _ := strconv.ParseInt(fmt.Sprint(vals[2]), 10, 64)
	return chat.Room{Known: true, Status: chat.RoomStatus(status), StreamGeneration: gen, SessionVersion: ver, ChannelID: channel}, nil
}

func lifecycleKey(sid string) string { return "chat:p2:lifecycle:{" + sid + "}" }

// applyLua aplica generación/versión mayores; READ_ONLY es terminal y arranca la retención de la sala.
// keysFrom(a) toma las claves de sala desde KEYS[a] hasta el final del bloque de sala.
const applyLua = `
local function keysFrom(a, b)
  local out = {}
  for i = a, b do out[#out + 1] = KEYS[i] end
  return out
end
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
return apply(KEYS[1], keysFrom(2, 8), tonumber(ARGV[1]), tonumber(ARGV[2]), ARGV[3], ARGV[4], ARGV[5], ARGV[6], ARGV[7])
`)

var applyEventScript = redis.NewScript(applyLua + `
local prev = redis.call('GET', KEYS[1])
if prev then
  if prev == ARGV[8] then return 'DUPLICATE' end
  return 'CONFLICT'
end
redis.call('SET', KEYS[1], ARGV[8], 'PX', ARGV[9])
return apply(KEYS[2], keysFrom(3, 9), tonumber(ARGV[1]), tonumber(ARGV[2]), ARGV[3], ARGV[4], ARGV[5], ARGV[6], ARGV[7])
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
	res, err := applyStateScript.Run(ctx, s.rdb, roomKeys(sid), s.stateArgs(st)...).Text()
	return ApplyResult(res), err
}

// UnendedRooms lista las salas con mensajes cuyo fin aún no inició la retención: su sequence no
// tiene TTL. Sirve para reconciliar un ENDED que nunca llegó (p. ej. evento en dead-letter).
func (s *Store) UnendedRooms(ctx context.Context) ([]string, error) {
	var sids []string
	iter := s.rdb.Scan(ctx, 0, "chat:{*}:seq", 100).Iterator()
	for iter.Next(ctx) {
		sid, ok := strings.CutPrefix(iter.Val(), "chat:{")
		if !ok {
			continue
		}
		if sid, ok = strings.CutSuffix(sid, "}:seq"); !ok || !chat.ValidSessionID(sid) {
			continue
		}
		ttl, err := s.rdb.PTTL(ctx, seqKey(sid)).Result()
		if err != nil {
			return nil, err
		}
		if ttl == -1 { // -1: sin expiración; -2: la clave expiró entre SCAN y PTTL
			sids = append(sids, sid)
		}
	}
	return sids, iter.Err()
}

// ExpireRoom inicia la retención de todas las claves de una sala cuya sesión ya no existe.
func (s *Store) ExpireRoom(ctx context.Context, sid string) error {
	_, err := s.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		for _, k := range roomKeys(sid) {
			p.PExpire(ctx, k, s.opts.EndedRetention)
		}
		return nil
	})
	return err
}

// ApplyEvent registra el eventId en la inbox y aplica el estado en la misma operación atómica.
// Reintento idéntico devuelve DUPLICATE; mismo eventId con otro fingerprint devuelve CONFLICT.
func (s *Store) ApplyEvent(ctx context.Context, eventID, fingerprint string, st chat.SessionState) (ApplyResult, error) {
	sid := st.SessionID
	args := append(s.stateArgs(st), fingerprint, s.opts.InboxTTL.Milliseconds())
	res, err := applyEventScript.Run(ctx, s.rdb, append([]string{inboxKey(eventID)}, roomKeys(sid)...), args...).Text()
	return ApplyResult(res), err
}

// P2 preserves identity/fingerprint and the version barrier across room expiry and
// operational redrive. These compact records have no automatic expiry.
var applyBrokerEventScript = redis.NewScript(applyLua + `
local previous = redis.call('GET', KEYS[1])
if previous then
  if previous == ARGV[8] then return 'DUPLICATE' end
  return 'CONFLICT'
end
local state = redis.call('HMGET', KEYS[10], 'generation', 'version', 'status', 'fingerprint')
local gen, ver = tonumber(ARGV[1]), tonumber(ARGV[2])
if state[1] then
  local cg, cv = tonumber(state[1]), tonumber(state[2])
  if gen < cg or (gen == cg and ver < cv) then
    redis.call('SET', KEYS[1], ARGV[8])
    return 'STALE'
  end
  if gen == cg and ver == cv then
    if state[4] ~= ARGV[9] then return 'CONFLICT' end
    redis.call('SET', KEYS[1], ARGV[8])
    return 'STALE'
  end
  if state[3] == 'READ_ONLY' then return 'CONFLICT' end
end
local result = apply(KEYS[2], keysFrom(3, 9), gen, ver, ARGV[3], ARGV[4], ARGV[5], ARGV[6], ARGV[7])
redis.call('HSET', KEYS[10], 'generation', gen, 'version', ver, 'status', ARGV[3], 'fingerprint', ARGV[9])
if ARGV[10] ~= '' then redis.call('HSET', KEYS[10], 'channel', ARGV[10]) end
redis.call('SET', KEYS[1], ARGV[8])
return result
`)

func (s *Store) ApplyBrokerEvent(ctx context.Context, eventID, fingerprint, stateFingerprint string, st chat.SessionState) (ApplyResult, error) {
	sid := st.SessionID
	args := append(s.stateArgs(st), fingerprint, stateFingerprint, st.ChannelID)
	keys := append([]string{"chat:p2:inbox:chat.lifecycle:" + eventID}, roomKeys(sid)...)
	result, err := applyBrokerEventScript.Run(ctx, s.rdb, append(keys, lifecycleKey(sid)), args...).Text()
	return ApplyResult(result), err
}
