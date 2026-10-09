package config

import (
	"strings"
	"testing"
	"time"
)

func setRequired(t *testing.T) {
	t.Setenv("CHAT_REDIS_URL", "redis://localhost:6379/0")
	t.Setenv("CHAT_CORE_BASE_URL", "http://core:8081")
	t.Setenv("CHAT_CORE_SERVICE_TOKEN", "x")
	t.Setenv("CHAT_SESSION_EVENTS_TOKEN", "y")
	t.Setenv("CHAT_ALLOWED_ORIGINS", "http://localhost:3000/, https://stream.example ")
}

func TestLoadDefaults(t *testing.T) {
	setRequired(t)
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.PublicAddr != ":8085" || c.EndedRetention != 5*time.Minute || c.CoreTimeout != 400*time.Millisecond ||
		c.AuthBudget != 500*time.Millisecond || c.EventsProducer != "streaming" || c.SessionCookie != "stream_session" {
		t.Fatalf("%+v", c)
	}
	if len(c.AllowedOrigins) != 2 || c.AllowedOrigins[0] != "http://localhost:3000" || c.AllowedOrigins[1] != "https://stream.example" {
		t.Fatalf("orígenes %v", c.AllowedOrigins)
	}
}

func TestLoadRejectsMissingSecretAndInvalidDuration(t *testing.T) {
	setRequired(t)
	t.Setenv("CHAT_CORE_SERVICE_TOKEN", "")
	t.Setenv("CHAT_SESSION_EVENTS_TOKEN", "secreto-de-prueba")
	t.Setenv("CHAT_ENDED_RETENTION", "-1s")
	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "CHAT_CORE_SERVICE_TOKEN") || !strings.Contains(err.Error(), "CHAT_ENDED_RETENTION") {
		t.Fatalf("%v", err)
	}
	if strings.Contains(err.Error(), "secreto-de-prueba") {
		t.Fatal("el error no debe incluir secretos")
	}
}

func TestLoadRejectsIncompletePublicTLSPair(t *testing.T) {
	setRequired(t)
	t.Setenv("CHAT_PUBLIC_TLS_CERT_FILE", "fictitious.crt")
	t.Setenv("CHAT_PUBLIC_TLS_KEY_FILE", "")
	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "CHAT_PUBLIC_TLS_KEY_FILE") {
		t.Fatalf("%v", err)
	}
}
