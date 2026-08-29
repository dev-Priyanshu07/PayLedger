# PayLedger

PayLedger is a small payment orchestration backend built with Java 21 and
Spring Boot. It records merchant orders, accepts idempotent payment intents,
and processes those payments asynchronously through a mock gateway.

The project is currently focused on the core payment safety model:
idempotency, merchant isolation, database-owned state transitions, gateway
attempt history, and worker lease recovery.

## Tech Stack

- Java 21
- Spring Boot 4.1
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- Flyway
- Gradle Kotlin DSL
- Docker Compose for local Postgres

## Current Features

- Merchant-scoped API key authentication with `X-API-Key`
- Idempotent order creation by `merchantOrderId`
- Idempotent payment creation by `Idempotency-Key`
- Request hashing to reject reused idempotency keys with different bodies
- Flyway-managed database schema
- Asynchronous payment processing with a scheduled worker
- Gateway attempt recording
- Safe handling of indeterminate gateway results
- Lease reaper for payments stuck in `PROCESSING`
- Mock gateway that always succeeds

## Project Structure

```text
src/main/java/com/payg/payg
  dto/          Request and response records
  entity/       JPA entities
  gateway/      Gateway abstraction and mock gateway
  repository/   Spring Data repositories and guarded update queries
  security/     Static API-key authentication
  service/      Order and payment business logic
  web/          REST controllers and error handling
  worker/       Scheduled payment worker and lease reaper

src/main/resources/db/migration
  V1__init.sql                Orders and payments
  V2__gateway_attempts.sql    Gateway call history
  V3__worker_lease.sql        Worker claim and lease columns
```

## Local Setup

Start PostgreSQL:

```powershell
docker compose up -d
```

Run the application:

```powershell
.\gradlew bootRun
```

Run tests:

```powershell
.\gradlew test
```

By default the app connects to:

```text
jdbc:postgresql://localhost:5432/payg
user: payg
password: payg
```

These can be overridden with environment variables:

```text
DB_URL
DB_USER
DB_PASSWORD
```

## Test API Keys

Configured in `src/main/resources/application.properties`:

```text
sk_test_merchant_a -> merchant_a
sk_test_merchant_b -> merchant_b
```

All `/v1/**` endpoints require:

```http
X-API-Key: sk_test_merchant_a
```

## API

### Create Order

```http
POST /v1/orders
X-API-Key: sk_test_merchant_a
Content-Type: application/json
```

```json
{
  "merchantOrderId": "order_1001",
  "amountMinor": 50000,
  "currency": "INR"
}
```

First creation returns `201 Created`. Replaying the same `merchantOrderId` for
the same merchant returns `200 OK` with the existing order.

### Get Order

```http
GET /v1/orders/{orderId}
X-API-Key: sk_test_merchant_a
```

Orders are scoped to the merchant resolved from the API key.

### Create Payment

```http
POST /v1/payments
X-API-Key: sk_test_merchant_a
Idempotency-Key: idem_1001
Content-Type: application/json
```

```json
{
  "orderId": "00000000-0000-0000-0000-000000000000",
  "customerRef": "cust_123"
}
```

The endpoint returns `202 Accepted`. The payment starts as `INITIATED`; the
scheduled worker later claims it, calls the gateway, records a gateway attempt,
and transitions it to `SUCCESS`, `FAILED`, or `UNKNOWN`.

Replaying the same idempotency key with the same request returns the existing
payment. Reusing the same idempotency key with a different request returns
`409 Conflict`.

## Payment States

```text
INITIATED  -> payment intent recorded
PROCESSING -> worker has claimed the payment and is calling the gateway
SUCCESS    -> gateway confirmed the charge
FAILED     -> gateway returned a definite failure
UNKNOWN    -> outcome is indeterminate or a worker lease expired
```

`UNKNOWN` is intentional. If a gateway call may have reached the provider but
the app did not receive or persist the result, retrying automatically could
double-charge the customer.

## Worker Settings

Configured with `payg.worker.*` properties:

```properties
payg.worker.lease-seconds=30
payg.worker.poll-interval-ms=500
payg.worker.batch-size=20
payg.worker.reap-interval-ms=5000
```

The worker claims the oldest `INITIATED` payment using `FOR UPDATE SKIP LOCKED`,
so multiple workers can process payments without taking the same row.

## Current Limitations

- Only INR is accepted.
- Gateway integration is a mock that always succeeds.
- There is no public `GET /v1/payments/{id}` endpoint yet.
- There is no ledger, settlement matching, webhook delivery, or outbox yet.
- `UNKNOWN` payments are not reconciled automatically yet.
- API keys are statically configured for local/demo use.

## Useful Commands

```powershell
docker compose up -d
.\gradlew bootRun
.\gradlew test
git status
```

