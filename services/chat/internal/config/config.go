// Package config lee la configuración de Chat desde variables de entorno. Los secretos no tienen
// valores por defecto y nunca se imprimen.
package config

import (
	"fmt"
	"os"
	"strings"
	"time"
)

type Config struct {
	EventsTransport     string
	BrokerURL           string
	BrokerCAFile        string
	PublicAddr          string
	InternalAddr        string
	PublicTLSCert       string
	PublicTLSKey        string
	InternalTLSCert     string
	InternalTLSKey      string
	RedisURL            string
	AllowedOrigins      []string
	SessionCookie       string
	CoreBaseURL         string
	CoreServiceToken    string
	CoreCAFile          string
	CoreDevelopmentHTTP bool
	CoreConnect         time.Duration
	CoreTimeout         time.Duration
	AuthBudget          time.Duration
	EventsProducer      string
	EventsToken         string
	EndedRetention      time.Duration
	RoomIdleTTL         time.Duration
	InboxTTL            time.Duration
	ReaderBlock         time.Duration
	RoomSweepInterval   time.Duration
	LogLevel            string
	// P2 (ADR-024): sin DatabaseURL Chat conserva el perfil P1 sin archivo ni moderación.
	DatabaseURL          string
	UnlinkedRetention    time.Duration
	ArchiveClaimIdle     time.Duration
	IdempotencyRetention time.Duration
	// Replay (ADR-022/024): VOD autoriza la lectura y entrega el timeline. Sin VodBaseURL el replay
	// responde REPLAY_UNAVAILABLE y el resto de P2 sigue disponible.
	VodBaseURL         string
	VodServiceToken    string
	VodCAFile          string
	VodDevelopmentHTTP bool
	VodConnect         time.Duration
	VodTimeout         time.Duration
}

func Load() (Config, error) {
	var errs []string
	str := func(key, def string) string {
		if v := strings.TrimSpace(os.Getenv(key)); v != "" {
			return v
		}
		return def
	}
	required := func(key string) string {
		v := str(key, "")
		if v == "" {
			errs = append(errs, key+" es obligatorio")
		}
		return v
	}
	dur := func(key, def string) time.Duration {
		d, err := time.ParseDuration(str(key, def))
		if err != nil || d <= 0 {
			errs = append(errs, key+" debe ser una duración positiva")
		}
		return d
	}
	c := Config{
		EventsTransport:      str("CHAT_EVENTS_TRANSPORT", "http"),
		BrokerURL:            str("CHAT_BROKER_URL", ""),
		BrokerCAFile:         str("CHAT_BROKER_CA_FILE", ""),
		PublicAddr:           str("CHAT_PUBLIC_ADDR", ":8085"),
		InternalAddr:         str("CHAT_INTERNAL_ADDR", ":8086"),
		PublicTLSCert:        str("CHAT_PUBLIC_TLS_CERT_FILE", ""),
		PublicTLSKey:         str("CHAT_PUBLIC_TLS_KEY_FILE", ""),
		InternalTLSCert:      str("CHAT_INTERNAL_TLS_CERT_FILE", ""),
		InternalTLSKey:       str("CHAT_INTERNAL_TLS_KEY_FILE", ""),
		RedisURL:             required("CHAT_REDIS_URL"),
		SessionCookie:        str("CHAT_SESSION_COOKIE", "stream_session"),
		CoreBaseURL:          required("CHAT_CORE_BASE_URL"),
		CoreServiceToken:     required("CHAT_CORE_SERVICE_TOKEN"),
		CoreCAFile:           str("CHAT_CORE_CA_FILE", ""),
		CoreDevelopmentHTTP:  str("CHAT_CORE_DEVELOPMENT_HTTP", "false") == "true",
		CoreConnect:          dur("CHAT_CORE_CONNECT_TIMEOUT", "100ms"),
		CoreTimeout:          dur("CHAT_CORE_TIMEOUT", "400ms"),
		AuthBudget:           dur("CHAT_AUTH_BUDGET", "500ms"),
		EventsProducer:       str("CHAT_SESSION_EVENTS_PRODUCER", "streaming"),
		EventsToken:          str("CHAT_SESSION_EVENTS_TOKEN", ""),
		EndedRetention:       dur("CHAT_ENDED_RETENTION", "5m"),
		RoomIdleTTL:          dur("CHAT_ROOM_IDLE_TTL", "12h"),
		InboxTTL:             dur("CHAT_EVENT_INBOX_TTL", "24h"),
		ReaderBlock:          dur("CHAT_READER_BLOCK", "2s"),
		RoomSweepInterval:    dur("CHAT_ROOM_SWEEP_INTERVAL", "1m"),
		LogLevel:             str("CHAT_LOG_LEVEL", "info"),
		DatabaseURL:          str("CHAT_DATABASE_URL", ""),
		UnlinkedRetention:    dur("CHAT_UNLINKED_RETENTION", "168h"),
		ArchiveClaimIdle:     dur("CHAT_ARCHIVE_CLAIM_IDLE", "30s"),
		IdempotencyRetention: dur("CHAT_IDEMPOTENCY_RETENTION", "24h"),
		VodBaseURL:           str("CHAT_VOD_BASE_URL", ""),
		VodServiceToken:      str("CHAT_VOD_SERVICE_TOKEN", ""),
		VodCAFile:            str("CHAT_VOD_CA_FILE", ""),
		VodDevelopmentHTTP:   str("CHAT_VOD_DEVELOPMENT_HTTP", "false") == "true",
		VodConnect:           dur("CHAT_VOD_CONNECT_TIMEOUT", "200ms"),
		VodTimeout:           dur("CHAT_VOD_TIMEOUT", "1500ms"),
	}
	if c.VodBaseURL != "" && (c.DatabaseURL == "" || c.VodServiceToken == "") {
		errs = append(errs, "CHAT_VOD_BASE_URL exige CHAT_DATABASE_URL y CHAT_VOD_SERVICE_TOKEN")
	}
	// El archivo P2 necesita el canal de cada sesión, que solo trae el ciclo por broker; con HTTP los
	// envíos no se encolarían para archivar y el replay quedaría vacío sin aviso.
	if c.DatabaseURL != "" && c.EventsTransport != "amqp" {
		errs = append(errs, "CHAT_DATABASE_URL exige CHAT_EVENTS_TRANSPORT=amqp")
	}
	if c.EventsTransport != "http" && c.EventsTransport != "amqp" {
		errs = append(errs, "CHAT_EVENTS_TRANSPORT debe ser http o amqp")
	}
	if c.EventsTransport == "http" && c.EventsToken == "" {
		errs = append(errs, "CHAT_SESSION_EVENTS_TOKEN es obligatorio")
	}
	if c.EventsTransport == "amqp" && (c.BrokerURL == "" || c.BrokerCAFile == "") {
		errs = append(errs, "CHAT_BROKER_URL y CHAT_BROKER_CA_FILE son obligatorios")
	}
	for _, o := range strings.Split(required("CHAT_ALLOWED_ORIGINS"), ",") {
		if o = strings.TrimRight(strings.TrimSpace(o), "/"); o != "" {
			c.AllowedOrigins = append(c.AllowedOrigins, o)
		}
	}
	if (c.PublicTLSCert == "") != (c.PublicTLSKey == "") {
		errs = append(errs, "CHAT_PUBLIC_TLS_CERT_FILE y CHAT_PUBLIC_TLS_KEY_FILE van juntos")
	}
	if (c.InternalTLSCert == "") != (c.InternalTLSKey == "") {
		errs = append(errs, "CHAT_INTERNAL_TLS_CERT_FILE y CHAT_INTERNAL_TLS_KEY_FILE van juntos")
	}
	if len(errs) > 0 {
		return Config{}, fmt.Errorf("configuración inválida: %s", strings.Join(errs, "; "))
	}
	return c, nil
}
