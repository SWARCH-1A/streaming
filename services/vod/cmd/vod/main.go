package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"streaming/vod/internal/vod"
	"strings"
	"syscall"
	"time"
)

func required(name string) string {
	v := os.Getenv(name)
	if v == "" {
		panic("missing configuration: " + name)
	}
	return v
}
func env(name, value string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return value
}
func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if len(os.Args) == 2 && os.Args[1] == "health" {
		client := &http.Client{Timeout: 3 * time.Second}
		res, e := client.Get(env("VOD_HEALTH_URL", "https://localhost:8098/readyz"))
		if e != nil {
			os.Exit(1)
		}
		res.Body.Close()
		if res.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	if e := run(); e != nil {
		slog.Error("vod stopped; inspect dependency health and configuration")
		os.Exit(1)
	}
}
func run() error {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	secret := required("VOD_SIGNING_SECRET")
	if len(secret) < 32 {
		return fmt.Errorf("signing secret too short")
	}
	development := os.Getenv("VOD_DEVELOPMENT_HTTP") == "true"
	coreURL := required("VOD_CORE_URL")
	s3URL := required("VOD_S3_ENDPOINT")
	if !development && (!strings.HasPrefix(coreURL, "https://") || !strings.HasPrefix(s3URL, "https://")) {
		return fmt.Errorf("TLS required")
	}
	start, stop := context.WithTimeout(ctx, 30*time.Second)
	defer stop()
	store, e := vod.Open(start, required("VOD_DATABASE_URL"))
	if e != nil {
		return e
	}
	defer store.DB.Close()
	objects, e := vod.NewS3(s3URL, required("VOD_S3_BUCKET"), env("VOD_S3_PREFIX", "vod"), env("VOD_S3_REGION", "us-east-1"), os.Getenv("VOD_S3_ACCESS_KEY"), os.Getenv("VOD_S3_SECRET_KEY"))
	if e != nil {
		return e
	}
	svc := &vod.Service{Store: store, Objects: objects, Authority: vod.NewCore(coreURL, required("VOD_CORE_TOKEN")), Secret: []byte(secret), Origin: required("VOD_PUBLIC_ORIGIN"), Development: development}
	svc.Probe = svc.ProbeMedia
	mediaURL := required("VOD_MEDIA_URL")
	if !development && !strings.HasPrefix(mediaURL, "https://") {
		return fmt.Errorf("Media TLS required")
	}
	svc.Delivery = vod.NewMediaDelivery(mediaURL, required("MEDIA_VOD_DELIVERY_TOKEN"))
	go svc.Workers(ctx)
	broker := &vod.Broker{URL: required("VOD_BROKER_URL"), CA: os.Getenv("VOD_BROKER_CA_FILE"), Service: svc}
	go broker.Run(ctx)
	servers := []*http.Server{{Addr: env("VOD_PUBLIC_ADDR", ":8098"), Handler: svc.PublicHandler(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 16384}, {Addr: env("VOD_INTERNAL_ADDR", ":8099"), Handler: svc.InternalHandler(required("VOD_CAPTURE_TOKEN"), required("VOD_CHAT_TOKEN"), required("VOD_GATEWAY_TOKEN")), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 16384}}
	failed := make(chan error, 2)
	for _, server := range servers {
		server.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
		go func(server *http.Server) {
			if development {
				failed <- server.ListenAndServe()
			} else {
				failed <- server.ListenAndServeTLS(required("VOD_TLS_CERT_FILE"), required("VOD_TLS_KEY_FILE"))
			}
		}(server)
	}
	var err error
	select {
	case <-ctx.Done():
	case err = <-failed:
	}
	cancel()
	shutdown, end := context.WithTimeout(context.Background(), 10*time.Second)
	defer end()
	for _, server := range servers {
		_ = server.Shutdown(shutdown)
	}
	return err
}
