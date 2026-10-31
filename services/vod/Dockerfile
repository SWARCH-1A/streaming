FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /out/vod ./cmd/vod && CGO_ENABLED=0 go build -trimpath -o /out/media-recorder ./cmd/media-recorder
FROM alpine:3.23
RUN apk add --no-cache ca-certificates ffmpeg && adduser -D -u 10001 vod && mkdir -p /data/recordings /data/native && chown vod /data/recordings /data/native
COPY --from=build /out/vod /usr/local/bin/vod
COPY --from=build /out/media-recorder /usr/local/bin/media-recorder
USER vod
EXPOSE 8098 8099
HEALTHCHECK --interval=10s --timeout=4s CMD ["vod", "health"]
ENTRYPOINT ["vod"]
