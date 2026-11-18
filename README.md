# payflow

Payment service for creating payments, receiving partner callbacks, and reconciling
transactions that end up in an unknown state.

Java 17 / Spring Boot 3.3. Postgres, Kafka and Redis are optional. By default it runs on
an in-memory H2 database so it starts with no setup.

## Running

```
docker compose up -d
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres,redis,kafka"
```

Without those profiles it uses H2, an in-memory cache, and a log-based event publisher.

## API

All `/api/v1/payments/**` endpoints need an `X-Api-Key` header. Partner webhooks use
HMAC-SHA256 instead of an API key.

There is an `api-gateway/` folder with a small Spring Cloud Gateway in front of the
service (port 8081).
