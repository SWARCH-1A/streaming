FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /out/waf-relay .
FROM alpine:3.23
RUN apk add --no-cache ca-certificates
COPY --from=build /out/waf-relay /usr/local/bin/waf-relay
# Same uid/gid as the WAF's nginx user: ModSecurity creates the audit files 0640 in 0750 directories.
USER 101:101
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s CMD ["waf-relay", "health"]
ENTRYPOINT ["waf-relay"]
