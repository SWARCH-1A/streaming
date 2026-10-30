FROM golang:1.26-alpine AS build
WORKDIR /src
COPY services/gateway .
RUN CGO_ENABLED=0 go build -trimpath -o /gateway .
FROM alpine:3.23
RUN apk add --no-cache ca-certificates && adduser -D -u 10001 gateway
COPY --from=build /gateway /usr/local/bin/gateway
COPY contracts/generated/graphql-queries.json /app/graphql-queries.json
USER gateway
EXPOSE 8097
ENTRYPOINT ["gateway"]
