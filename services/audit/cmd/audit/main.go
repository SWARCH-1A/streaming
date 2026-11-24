package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"streaming/audit/internal/audit"
	"strings"
	"syscall"
	"time"
)

func required(k string) string {
	v := os.Getenv(k)
	if v == "" {
		panic("missing configuration: " + k)
	}
	return v
}
func verifiedDatabaseURL(address string) bool {
	parsed, err := url.Parse(address)
	if err != nil || (parsed.Scheme != "postgres" && parsed.Scheme != "postgresql") || parsed.Host == "" {
		return false
	}
	values, err := url.ParseQuery(parsed.RawQuery)
	modes := values["sslmode"]
	return err == nil && len(modes) == 1 && modes[0] == "verify-full"
}
func run() error {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	development := os.Getenv("AUDIT_DEVELOPMENT_HTTP") == "true"
	writer, reader := required("AUDIT_WRITER_DATABASE_URL"), required("AUDIT_READER_DATABASE_URL")
	if !development && (!verifiedDatabaseURL(writer) || !verifiedDatabaseURL(reader)) {
		return fmt.Errorf("verified database TLS required")
	}
	start, stop := context.WithTimeout(ctx, 15*time.Second)
	defer stop()
	store, err := audit.Open(start, writer, reader)
	if err != nil {
		return err
	}
	defer store.Close()
	token := required("AUDIT_QUERY_TOKEN")
	if len(token) < 32 {
		return fmt.Errorf("query token too short")
	}
	broker := required("AUDIT_BROKER_URL")
	if !development && !strings.HasPrefix(broker, "amqps://") {
		return fmt.Errorf("broker TLS required")
	}
	for _, producer := range []string{"core", "vod", "waf"} {
		go audit.RunConsumer(ctx, store, broker, os.Getenv("AUDIT_BROKER_CA_FILE"), producer, development)
	}
	server := &http.Server{Addr: ":8096", Handler: audit.Handler(store, token), ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	failed := make(chan error, 1)
	go func() {
		if development {
			failed <- server.ListenAndServe()
		} else {
			failed <- server.ListenAndServeTLS(required("AUDIT_TLS_CERT_FILE"), required("AUDIT_TLS_KEY_FILE"))
		}
	}()
	select {
	case <-ctx.Done():
	case err = <-failed:
	}
	cancel()
	end, done := context.WithTimeout(context.Background(), 5*time.Second)
	defer done()
	_ = server.Shutdown(end)
	return err
}
func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if len(os.Args) == 2 && os.Args[1] == "health" {
		scheme := "https"
		if os.Getenv("AUDIT_DEVELOPMENT_HTTP") == "true" {
			scheme = "http"
		}
		client := http.Client{Timeout: 2 * time.Second}
		r, e := client.Get(scheme + "://localhost:8096/readyz")
		if e != nil {
			os.Exit(1)
		}
		r.Body.Close()
		if r.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	if run() != nil {
		slog.Error("audit stopped; inspect configuration and dependency health")
		os.Exit(1)
	}
}
