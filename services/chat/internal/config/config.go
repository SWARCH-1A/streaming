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
	PublicAddr          string
	InternalAddr        string
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
	LogLevel            string
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
		PublicAddr:          str("CHAT_PUBLIC_ADDR", ":8085"),
		InternalAddr:        str("CHAT_INTERNAL_ADDR", ":8086"),
		InternalTLSCert:     str("CHAT_INTERNAL_TLS_CERT_FILE", ""),
		InternalTLSKey:      str("CHAT_INTERNAL_TLS_KEY_FILE", ""),
		RedisURL:            required("CHAT_REDIS_URL"),
		SessionCookie:       str("CHAT_SESSION_COOKIE", "stream_session"),
		CoreBaseURL:         required("CHAT_CORE_BASE_URL"),
		CoreServiceToken:    required("CHAT_CORE_SERVICE_TOKEN"),
		CoreCAFile:          str("CHAT_CORE_CA_FILE", ""),
		CoreDevelopmentHTTP: str("CHAT_CORE_DEVELOPMENT_HTTP", "false") == "true",
		CoreConnect:         dur("CHAT_CORE_CONNECT_TIMEOUT", "100ms"),
		CoreTimeout:         dur("CHAT_CORE_TIMEOUT", "400ms"),
		AuthBudget:          dur("CHAT_AUTH_BUDGET", "500ms"),
		EventsProducer:      str("CHAT_SESSION_EVENTS_PRODUCER", "streaming"),
		EventsToken:         required("CHAT_SESSION_EVENTS_TOKEN"),
		EndedRetention:      dur("CHAT_ENDED_RETENTION", "5m"),
		RoomIdleTTL:         dur("CHAT_ROOM_IDLE_TTL", "12h"),
		InboxTTL:            dur("CHAT_EVENT_INBOX_TTL", "24h"),
		ReaderBlock:         dur("CHAT_READER_BLOCK", "2s"),
		LogLevel:            str("CHAT_LOG_LEVEL", "info"),
	}
	for _, o := range strings.Split(required("CHAT_ALLOWED_ORIGINS"), ",") {
		if o = strings.TrimRight(strings.TrimSpace(o), "/"); o != "" {
			c.AllowedOrigins = append(c.AllowedOrigins, o)
		}
	}
	if (c.InternalTLSCert == "") != (c.InternalTLSKey == "") {
		errs = append(errs, "CHAT_INTERNAL_TLS_CERT_FILE y CHAT_INTERNAL_TLS_KEY_FILE van juntos")
	}
	if len(errs) > 0 {
		return Config{}, fmt.Errorf("configuración inválida: %s", strings.Join(errs, "; "))
	}
	return c, nil
}
