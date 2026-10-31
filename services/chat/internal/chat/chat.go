// Package chat contiene las reglas de dominio de Chat: texto, códigos estables, estado de sala y
// el modelo público de mensaje. No depende de Redis, HTTP ni del contrato Core.
package chat

import (
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
	"golang.org/x/text/unicode/norm"
)

// MaxTextCodePoints es el límite de RF-032/D-28 tras normalizar y recortar.
const MaxTextCodePoints = 500

// QuotaWindow es la ventana móvil global por cuenta de RF-033: un mensaje aceptado, sin ráfaga.
const QuotaWindow = 1000 * time.Millisecond

// HistoryMaxLimit es el máximo de mensajes que devuelve el historial (RF-034).
const HistoryMaxLimit = 50

// Códigos estables expuestos en frames WS y envelopes REST.
const (
	CodeAuthRequired         = "AUTH_REQUIRED"
	CodeChatReadOnly         = "CHAT_READ_ONLY"
	CodeChatNotOpen          = "CHAT_NOT_OPEN"
	CodeCoreUnavailable      = "CORE_UNAVAILABLE"
	CodeStreamingUnavailable = "STREAMING_UNAVAILABLE"
	CodeTimelineUnavailable  = "TIMELINE_UNAVAILABLE"
	CodeRateLimited          = "RATE_LIMITED"
	CodeMessageIDConflict    = "MESSAGE_ID_CONFLICT"
	CodeMessageEmpty         = "MESSAGE_EMPTY"
	CodeMessageTooLong       = "MESSAGE_TOO_LONG"
	CodeValidation           = "VALIDATION_ERROR"
	CodeSessionNotFound      = "SESSION_NOT_FOUND"
	CodeChatUnavailable      = "CHAT_UNAVAILABLE"
	CodeChatBlocked          = "CHAT_BLOCKED"
	CodeChannelForbidden     = "CHANNEL_FORBIDDEN"
	CodeChannelNotFound      = "CHANNEL_NOT_FOUND"
	CodeMessageNotFound      = "MESSAGE_NOT_FOUND"
	CodeVodNotFound          = "VOD_NOT_FOUND"
	CodeCannotBlockOwner     = "CANNOT_BLOCK_OWNER"
	CodeCSRFInvalid          = "CSRF_INVALID"
	CodeOriginNotAllowed     = "ORIGIN_NOT_ALLOWED"
	CodeIdempotencyRequired  = "IDEMPOTENCY_KEY_REQUIRED"
	CodeIdempotencyConflict  = "IDEMPOTENCY_CONFLICT"
	CodeCursorExpired        = "CURSOR_EXPIRED"
	CodeOperationNotFound    = "OPERATION_NOT_FOUND"
)

// Error es un rechazo estable con código de contrato. RetryAfter solo aplica a RATE_LIMITED y
// BlockedUntil a CHAT_BLOCKED temporal.
type Error struct {
	Code         string
	Message      string
	RetryAfter   time.Duration
	BlockedUntil *time.Time
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

// Fail construye un rechazo con el mensaje por defecto de su código.
func Fail(code string) *Error { return &Error{Code: code, Message: DefaultMessage(code)} }

// DefaultMessage describe cada código en lenguaje comprensible para la UI.
func DefaultMessage(code string) string {
	switch code {
	case CodeAuthRequired:
		return "Inicia sesión para enviar mensajes."
	case CodeChatReadOnly:
		return "La transmisión terminó; el chat es de solo lectura."
	case CodeChatNotOpen:
		return "El chat aún no está abierto para esta sesión."
	case CodeCoreUnavailable:
		return "No es posible validar el envío en este momento."
	case CodeStreamingUnavailable:
		return "No es posible consultar el estado de la emisión en este momento."
	case CodeTimelineUnavailable:
		return "No fue posible ubicar el mensaje en la transmisión; reintenta."
	case CodeRateLimited:
		return "Solo puedes enviar un mensaje por segundo."
	case CodeMessageIDConflict:
		return "El identificador del mensaje ya se usó con otro texto."
	case CodeMessageEmpty:
		return "El mensaje no puede estar vacío."
	case CodeMessageTooLong:
		return "El mensaje supera 500 caracteres."
	case CodeSessionNotFound:
		return "La sesión de chat no existe."
	case CodeChatUnavailable:
		return "El chat no está disponible en este momento."
	case CodeChatBlocked:
		return "Estás bloqueado en el chat de este canal."
	case CodeChannelForbidden:
		return "Solo el propietario del canal puede moderar su chat."
	case CodeChannelNotFound:
		return "El canal no existe."
	case CodeMessageNotFound:
		return "El mensaje no existe."
	case CodeVodNotFound:
		return "El video no existe o no está disponible."
	case CodeCannotBlockOwner:
		return "El propietario del canal no puede bloquearse a sí mismo."
	case CodeCSRFInvalid:
		return "La solicitud no está autorizada."
	case CodeOriginNotAllowed:
		return "Origen no permitido."
	case CodeIdempotencyRequired:
		return "Se requiere Idempotency-Key UUID."
	case CodeIdempotencyConflict:
		return "La Idempotency-Key ya se usó con otra solicitud."
	case CodeCursorExpired:
		return "El cursor ya no corresponde a esta consulta; pide una ventana nueva."
	case CodeOperationNotFound:
		return "La operación de moderación no existe."
	default:
		return "Solicitud inválida."
	}
}

// NormalizeText aplica NFC, recorta whitespace Unicode en los extremos y valida 1–500 puntos de código.
func NormalizeText(raw string) (string, *Error) {
	text := strings.TrimFunc(norm.NFC.String(raw), unicode.IsSpace)
	switch n := utf8.RuneCountInString(text); {
	case n == 0:
		return "", Fail(CodeMessageEmpty)
	case n > MaxTextCodePoints:
		return "", Fail(CodeMessageTooLong)
	}
	return text, nil
}

// TextFingerprint identifica el texto canónico para detectar MESSAGE_ID_CONFLICT sin guardarlo dos veces.
func TextFingerprint(text string) string {
	sum := sha256.Sum256([]byte(text))
	return hex.EncodeToString(sum[:])
}

// ValidClientMessageID exige un UUID en forma canónica de 36 caracteres.
func ValidClientMessageID(id string) bool {
	if len(id) != 36 {
		return false
	}
	_, err := uuid.Parse(id)
	return err == nil
}

var sessionIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

// ValidSessionID acepta IDs opacos seguros para rutas y claves; el significado lo define Core/Streaming.
func ValidSessionID(id string) bool { return sessionIDPattern.MatchString(id) }

// RoomStatus es el estado visible de la sala.
type RoomStatus string

const (
	RoomOpen     RoomStatus = "OPEN"
	RoomNotOpen  RoomStatus = "NOT_OPEN"
	RoomReadOnly RoomStatus = "READ_ONLY"
)

// RoomStatusFromSession traduce el estado de emisión: LIVE/gracia abren, ENDED es solo lectura.
func RoomStatusFromSession(status string) (RoomStatus, bool) {
	switch status {
	case "PREPARING":
		return RoomNotOpen, true
	case "LIVE", "RECONNECT_GRACE":
		return RoomOpen, true
	case "ENDED":
		return RoomReadOnly, true
	default:
		return "", false
	}
}

// Author es el snapshot público del autor en el momento del envío; no cambia al editar el perfil.
type Author struct {
	UserID      string  `json:"userId"`
	Handle      string  `json:"handle"`
	DisplayName string  `json:"displayName"`
	AvatarURI   *string `json:"avatarUri"`
}

// Message es el evento público de chat: historial y message.created comparten esta forma.
type Message struct {
	MessageID          string    `json:"messageId"`
	SessionID          string    `json:"sessionId"`
	Sequence           int64     `json:"sequence"`
	Author             Author    `json:"author"`
	Text               string    `json:"text"`
	ServerCreatedAtUTC time.Time `json:"serverCreatedAtUtc"`
	StreamOffsetMs     int64     `json:"streamOffsetMs"`
	StreamGeneration   int64     `json:"streamGeneration"`
}

// Ack es la confirmación durable que recibe quien envía.
type Ack struct {
	ClientMessageID    string    `json:"clientMessageId"`
	MessageID          string    `json:"messageId"`
	SessionID          string    `json:"sessionId"`
	Sequence           int64     `json:"sequence"`
	ServerCreatedAtUTC time.Time `json:"serverCreatedAtUtc"`
}

// SessionState es el estado de emisión que llega por evento o snapshot; no autoriza escrituras.
type SessionState struct {
	SessionID        string
	StreamGeneration int64
	SessionVersion   int64
	Status           RoomStatus
	Availability     string
	ChannelID        string // solo en el ciclo P2 por broker
}

// Room es el estado local conocido de una sala. ChannelID llega con el ciclo P2; vacío en P1.
type Room struct {
	Known            bool
	Status           RoomStatus
	StreamGeneration int64
	SessionVersion   int64
	ChannelID        string
}

// ValidOpaqueID acepta IDs de canal, usuario, VOD y mensaje seguros para rutas y claves.
func ValidOpaqueID(id string) bool { return sessionIDPattern.MatchString(id) && len(id) <= 64 }

// ---- Moderación (SPEC-05 P2, ADR-024) ----

// BlockMode distingue un bloqueo con vencimiento de uno que dura hasta su retiro manual.
type BlockMode string

const (
	BlockTemporary BlockMode = "TEMPORARY"
	BlockPermanent BlockMode = "PERMANENT"
)

// BlockDurations son las únicas duraciones temporales admitidas (ISO 8601).
var BlockDurations = map[string]time.Duration{
	"PT10M": 10 * time.Minute,
	"PT1H":  time.Hour,
	"PT24H": 24 * time.Hour,
	"P7D":   7 * 24 * time.Hour,
}

// Block es el estado de bloqueo de una cuenta en un canal. Version crece con cada cambio; gana el último.
type Block struct {
	ChannelID       string     `json:"channelId"`
	UserID          string     `json:"userId"`
	Active          bool       `json:"active"`
	Mode            *BlockMode `json:"mode"`
	Duration        *string    `json:"duration"`
	ExpiresAtUTC    *time.Time `json:"expiresAtUtc"`
	BlockedAtUTC    *time.Time `json:"blockedAtUtc"`
	BlockedByUserID *string    `json:"-"`
	Version         int64      `json:"version"`
}

// ActiveAt indica si el bloqueo impide escribir en el instante del servidor now.
func (b Block) ActiveAt(now time.Time) bool {
	if b.Mode == nil {
		return false
	}
	return *b.Mode == BlockPermanent || (b.ExpiresAtUTC != nil && now.Before(*b.ExpiresAtUTC))
}

// BlockedError describe al usuario bloqueado hasta cuándo dura su bloqueo.
func BlockedError(until *time.Time) *Error {
	if until == nil {
		return &Error{Code: CodeChatBlocked, Message: "Estás bloqueado de forma permanente en el chat de este canal."}
	}
	return &Error{Code: CodeChatBlocked, BlockedUntil: until,
		Message: "Estás bloqueado en el chat de este canal hasta el " + until.UTC().Format("02/01/2006 15:04") + " UTC."}
}

// Removal es la marca pública de un mensaje retirado por el moderador; nunca conserva el texto.
type Removal struct {
	MessageID         string    `json:"messageId"`
	SessionID         string    `json:"sessionId"`
	Sequence          int64     `json:"sequence"`
	ModerationVersion int64     `json:"moderationVersion"`
	RemovedAtUTC      time.Time `json:"removedAtUtc"`
}

// ReplayItem es un mensaje vigente del replay (P2ReplayMessage). MediaOffsetMs es su posición en el
// video según el timeline de VOD; StreamOffsetMs y StreamGeneration conservan su origen en la sesión.
type ReplayItem struct {
	MessageID          string    `json:"messageId"`
	SessionID          string    `json:"sessionId"`
	Sequence           int64     `json:"sequence"`
	Author             Author    `json:"author"`
	Text               string    `json:"text"`
	ServerCreatedAtUTC time.Time `json:"serverCreatedAtUtc"`
	StreamOffsetMs     int64     `json:"streamOffsetMs"`
	StreamGeneration   int64     `json:"streamGeneration"`
	MediaOffsetMs      int64     `json:"mediaOffsetMs"`
	ModerationVersion  int64     `json:"moderationVersion"`
}

// ReplayWindowMaxMs limita la ventana temporal de una consulta de replay (5 minutos de video).
const ReplayWindowMaxMs = 5 * 60 * 1000

// ReplayDefaultLimit es el tamaño de página si la consulta no indica limit (P2ReplayQuery).
const ReplayDefaultLimit = 20
