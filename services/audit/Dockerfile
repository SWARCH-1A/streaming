FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /out/audit ./cmd/audit
FROM alpine:3.23
RUN apk add --no-cache ca-certificates && adduser -D -u 10001 audit
COPY --from=build /out/audit /usr/local/bin/audit
USER audit
EXPOSE 8096
HEALTHCHECK --interval=10s --timeout=3s CMD ["audit", "health"]
ENTRYPOINT ["audit"]
