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
)

// Error es un rechazo estable con código de contrato. RetryAfter solo aplica a RATE_LIMITED.
type Error struct {
	Code       string
	Message    string
	RetryAfter time.Duration
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
}

// Room es el estado local conocido de una sala.
type Room struct {
	Known            bool
	Status           RoomStatus
	StreamGeneration int64
	SessionVersion   int64
}
