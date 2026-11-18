# payflow

Payment service for creating payments, receiving partner callbacks, and reconciling
transactions that end up in an unknown state.

Built with Java 17 and Spring Boot 3.3. Postgres, Redis and Kafka are optional. By default
it runs on an in-memory H2 database, so it starts without any setup.

## Stack

Java 17 · Spring Boot 3.3 · Spring Data JPA · Flyway · Postgres/H2 · Kafka · Redis ·
Resilience4j · ShedLock · JUnit 5, Testcontainers, WireMock, ArchUnit

## Running it

Just the app:

```
mvn spring-boot:run
```

It runs on 8080 with H2 and an in-memory cache. Flyway migrations run on startup.

Everything (Postgres, Redis, Kafka and the app):

```
docker compose up --build
```

With the gateway too:

```
docker compose --profile gateway up --build
```

The gateway listens on 8081 and expects `X-Api-Key: local-dev-key`.

If you'd rather run the app from your IDE against the Docker infrastructure:

```
docker compose up -d postgres redis kafka
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres,redis,kafka"
```

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/payments` | requires `Idempotency-Key` |
| GET | `/api/v1/payments/{id}` | |
| POST | `/api/v1/webhooks/partner` | HMAC-SHA256, no API key |
| GET | `/api/v1/reports/settlements?from=&to=` | settlement report |
| GET | `/actuator/health` | |

Everything under `/api/v1` except the webhook needs `X-Api-Key`.

Create a payment:

```
curl -X POST http://localhost:8080/api/v1/payments \
  -H "X-Api-Key: local-dev-key" \
  -H "Idempotency-Key: 4a1c0b2e-7f1d-4b0f-9a3e-9c1f0d2b5a77" \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-1","amount":150000,"currency":"IDR","description":"invoice 12"}'
```

Sending the same `Idempotency-Key` with the same body returns the first response and sets
`Idempotent-Replayed: true`. The same key with a different body returns 409.

## Settlement report

`GET /api/v1/reports/settlements?from=2026-11-01&to=2026-11-30`

One row per day and currency, with the settled amount accumulated per currency, plus a
total for each currency.

The report is a single native query:

```sql
WITH daily AS (
    SELECT CAST(created_at AS DATE) AS settled_on,
           currency,
           SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,
           SUM(CASE WHEN status = 'FAILED'    THEN 1 ELSE 0 END) AS failed,
           SUM(CASE WHEN status = 'IN_DOUBT'  THEN 1 ELSE 0 END) AS in_doubt,
           SUM(CASE WHEN status = 'SUCCEEDED' THEN amount ELSE 0 END) AS settled_amount
    FROM payments
    WHERE created_at >= ? AND created_at < ?
    GROUP BY CAST(created_at AS DATE), currency
)
SELECT settled_on,
       currency,
       succeeded,
       failed,
       in_doubt,
       settled_amount,
       SUM(settled_amount) OVER (PARTITION BY currency ORDER BY settled_on) AS running_total
FROM daily
ORDER BY currency, settled_on
```

The CTE does the per-day aggregation and the window function adds the running total
without a second pass. `PARTITION BY currency` matters: summing IDR and USD into one
running total would be meaningless.

The service then groups the rows per currency with streams to build the totals and the
best day, so everything comes out of the same result set.

```json
{
  "from": "2026-11-01",
  "to": "2026-11-03",
  "days": [
    {
      "day": "2026-11-01",
      "currency": "IDR",
      "succeeded": 2,
      "failed": 1,
      "inDoubt": 0,
      "settledAmount": 150000.00,
      "runningTotal": 150000.00
    }
  ],
  "totals": [
    {
      "currency": "IDR",
      "succeeded": 3,
      "failed": 1,
      "inDoubt": 0,
      "settledAmount": 350000.00,
      "bestDay": "2026-11-02",
      "bestDayAmount": 200000.00
    }
  ]
}
```

## How a payment moves

```
PENDING --partner ok--> SUCCEEDED
PENDING --declined----> FAILED
PENDING --timeout-----> IN_DOUBT --reconciliation--> SUCCEEDED or FAILED
```

`IN_DOUBT` is for the case where we don't know whether the partner processed the charge.
The scheduled reconciliation job picks those up and asks the partner again. It runs under
a ShedLock lock so multiple instances don't reconcile the same payment at once.

The sandbox partner under `/sandbox/partner/**` makes this easy to trigger locally. The
last two digits of the amount pick the scenario: `.77` times out, `.99` returns 500, `.55`
is declined, anything else succeeds.

## Events

State changes are written to an `outbox_events` table in the same transaction as the
payment. A relay then publishes them to Kafka. That way a broker outage doesn't roll back
a payment, and a crash between commit and publish doesn't lose the event.

The consumer writes each event into `payment_event_log`, keyed by `event_id`, so a
redelivered event is a no-op. Messages that keep failing are retried twice and then sent
to `payments.events.DLT`.

Without the `kafka` profile the app uses a log-based publisher and none of this runs.

## Tests

```
mvn test
```

Covers the use cases, the HTTP layer (MockMvc + WireMock), the outbox relay, the settlement
query, and the layering rules enforced by ArchUnit.

Tests tagged `docker` need Docker and are skipped by default:

```
mvn -Pdockertests test
```

## Notes

- The sandbox partner keeps its state in memory, so it resets on restart.
- Refunds aren't implemented.
- The settlement report reads the `payments` table directly. On a large table that would
  need a daily rollup instead, otherwise a wide date range gets slow.
- The API key and HMAC secret are in `application.yml` for local runs. A real deployment
  would pull them from a secret manager.
