FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/chat ./cmd/chat

FROM alpine:3.22
RUN adduser -D -u 10001 chat
COPY --from=build /out/chat /usr/local/bin/chat
USER 10001:10001
EXPOSE 8085 8086
ENTRYPOINT ["/usr/local/bin/chat"]
