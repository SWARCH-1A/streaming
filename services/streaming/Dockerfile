# syntax=docker/dockerfile:1
FROM rust:1.98.1-slim-trixie AS builder
WORKDIR /app

COPY Cargo.toml Cargo.lock ./
COPY src ./src
COPY migrations ./migrations
COPY media-adapter/migrations ./media-adapter/migrations
RUN --mount=type=cache,id=streaming-cargo-registry,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,id=streaming-cargo-target,target=/app/target,sharing=locked \
    cargo build --locked --release \
    && install -Dm755 /app/target/release/streaming-service /out/streaming-service \
    && install -Dm755 /app/target/release/media-adapter /out/media-adapter

FROM debian:trixie-slim AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates curl ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 streaming \
    && useradd --system --uid 10001 --gid streaming --no-create-home streaming

COPY --from=builder /out/streaming-service /usr/local/bin/streaming-service
COPY --from=builder /out/media-adapter /usr/local/bin/media-adapter
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/health/ready || exit 1
ENTRYPOINT ["/usr/local/bin/streaming-service"]
