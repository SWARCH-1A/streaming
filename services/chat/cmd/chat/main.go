// Command chat inicia el servicio Chat: listener público (historial y WebSocket) y listener interno
// (session-events). Redis es el único almacén.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"streaming/chat/internal/config"
	"streaming/chat/internal/core"
	"streaming/chat/internal/service"
	"streaming/chat/internal/store"
	"streaming/chat/internal/transport"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := config.Load()
	if err != nil {
		log.Error("arranque", "err", err)
		os.Exit(1)
	}
	var level slog.Level
	if err := level.UnmarshalText([]byte(cfg.LogLevel)); err == nil {
		log = slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
	}

	ropts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		log.Error("CHAT_REDIS_URL inválida")
		os.Exit(1)
	}
	// Bound datastore I/O by each command's context; an ambiguous commit is recovered by clientMessageId.
	ropts.ContextTimeoutEnabled = true
	ropts.MaxRetries = -1
	rdb := redis.NewClient(ropts)
	defer rdb.Close()

	coreClient, err := core.New(core.Config{
		BaseURL: cfg.CoreBaseURL, ServiceToken: cfg.CoreServiceToken, CAFile: cfg.CoreCAFile,
		DevelopmentHTTP: cfg.CoreDevelopmentHTTP,
		ConnectTimeout:  cfg.CoreConnect, RequestTimeout: cfg.CoreTimeout,
	})
	if err != nil {
		log.Error("cliente Core", "err", err)
		os.Exit(1)
	}
	st := store.New(rdb, store.Options{
		EndedRetention: cfg.EndedRetention, IdleTTL: cfg.RoomIdleTTL,
		InboxTTL: cfg.InboxTTL,
	})
	svc := service.New(coreClient, st, log, cfg.AuthBudget)
	hub := transport.NewHub(st, log, cfg.ReaderBlock)
	srv := transport.NewServer(transport.Config{
		AllowedOrigins: cfg.AllowedOrigins, SessionCookie: cfg.SessionCookie,
		EventsProducer: cfg.EventsProducer, EventsToken: cfg.EventsToken,
	}, svc, hub, st.Ping, log)

	public := &http.Server{Addr: cfg.PublicAddr, Handler: srv.PublicHandler(), ReadHeaderTimeout: 5 * time.Second, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	internal := &http.Server{Addr: cfg.InternalAddr, Handler: srv.InternalHandler(), ReadHeaderTimeout: 5 * time.Second, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}

	errc := make(chan error, 2)
	go func() {
		if cfg.PublicTLSCert != "" {
			errc <- public.ListenAndServeTLS(cfg.PublicTLSCert, cfg.PublicTLSKey)
			return
		}
		errc <- public.ListenAndServe()
	}()
	go func() {
		if cfg.InternalTLSCert != "" {
			errc <- internal.ListenAndServeTLS(cfg.InternalTLSCert, cfg.InternalTLSKey)
			return
		}
		errc <- internal.ListenAndServe()
	}()
	log.Info("chat iniciado", "public", cfg.PublicAddr, "internal", cfg.InternalAddr, "internalTLS", cfg.InternalTLSCert != "")

	stop, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	select {
	case <-stop.Done():
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			log.Error("listener", "err", err)
		}
	}
	ctx, done := context.WithTimeout(context.Background(), 10*time.Second)
	defer done()
	hub.Close()
	_ = public.Shutdown(ctx)
	_ = internal.Shutdown(ctx)
	log.Info("chat detenido")
}
