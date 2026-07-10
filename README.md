# Payment Orchestration Layer (POL)

A production-grade payment orchestration system built with Java, Spring Boot, MySQL, and Redis. Routes payment requests across multiple gateways (Razorpay, Stripe, PayU, UPI) with intelligent routing, circuit breaking, idempotency guarantees, and webhook reconciliation.

---

## Architecture Overview

```
Client
  │
  ▼
PaymentController          ← receives POST /api/payment
  │
  ▼
PaymentOrchestrationService
  ├── Idempotency Check    ← UNIQUE constraint + INSERT-as-check pattern
  ├── RoutingService       ← weighted random selection based on gateway scores
  │     ├── Redis sliding window (last 20 outcomes per gateway)
  │     └── score = 0.6*successRate + 0.4*latencyScore
  ├── CircuitBreakerService← CLOSED / OPEN / HALF_OPEN per gateway (Redis-backed)
  ├── GatewayAdapter       ← Strategy pattern, one adapter per gateway
  │     ├── RazorpayAdapter
  │     ├── StripeAdapter
  │     ├── PayUAdapter
  │     └── UPIAdapter
  └── TransactionStateMachine ← enforces valid state transitions

WebhookController          ← receives async POST /webhooks/{gateway}
  └── ReconciliationService← deduplication + double-charge detection
```

---

## Key Design Decisions

### 1. Idempotency — INSERT-as-check Pattern

Every payment request carries a client-generated `idempotencyKey`. Instead of "check if exists, then insert" (which has a race condition gap), the system attempts a direct `INSERT`. If the database throws a `UNIQUE` constraint violation, the request is a duplicate and the existing record's status drives the response.

```
First request  → INSERT succeeds         → 201 Created
Duplicate      → INSERT fails (UNIQUE)   → fetch existing row → 409 / 200 based on status
```

This is the same pattern used by Stripe's idempotency implementation.

### 2. Weighted Random Gateway Selection

Gateways are not selected by always picking the highest scorer (which would starve lower-ranked gateways and leave them "cold" for failover). Instead, scores act as weights in a probability distribution:

```
score = w1 * successRate + w2 * latencyScore
latencyScore = max(0, 1 - (avgLatency / maxLatency))

Selection probability ∝ gateway score
```

This ensures every gateway receives traffic proportional to its health, keeping all gateways warm and ready for failover.

### 3. Sliding Window Success Rate

Success rate is computed over the last 20 calls (configurable), not all-time. This is implemented as a Redis List with a fixed trim:

```
LPUSH gateway:razorpay:outcome "SUCCESS:180"
LTRIM gateway:razorpay:outcome 0 19
```

An all-time average would be slow to react to sudden gateway degradation. A sliding window reflects current health immediately.

### 4. Three-State Circuit Breaker

Each gateway independently tracks a circuit state stored in Redis (survives app restarts):

```
CLOSED    → healthy, eligible for normal routing
OPEN      → success rate dropped below threshold, excluded from routing
HALF_OPEN → cooldown elapsed, cautiously testing recovery
```

Transitions are event-driven: every recorded outcome triggers a re-evaluation of the gateway's success rate against the configured threshold. No polling or scheduled checks needed for CLOSED ↔ OPEN transitions.

### 5. GATEWAY_TIMEOUT as a Distinct State

A timeout is not the same as a failure. When a gateway call times out, the outcome is genuinely unknown — the gateway may have processed the charge before the response was lost. Treating a timeout as FAILED and retrying immediately on another gateway risks double-charging the customer.

```
PROCESSING → GATEWAY_TIMEOUT → investigate (status check API)
                              → SUCCESS   (gateway confirms it went through)
                              → RETRYING  (confirmed safe to retry on next gateway)
```

### 6. Transaction State Machine

All status transitions are validated centrally. Invalid transitions (e.g., SUCCESS → INITIATED) throw an exception rather than silently corrupting data.

```
INITIATED       → PROCESSING
PROCESSING      → SUCCESS | FAILED | GATEWAY_TIMEOUT
GATEWAY_TIMEOUT → SUCCESS | RETRYING
RETRYING        → PROCESSING
SUCCESS         → REFUNDED
```

### 7. Webhook Reconciliation + Double-Charge Detection

Every gateway call includes the internal `idempotencyKey` as a merchant reference (Razorpay: `receipt`, Stripe: `metadata`). When a late webhook arrives, it carries that key back, allowing the system to match it against the internal transaction record.

If a success webhook arrives from Gateway A for a transaction already marked SUCCESS via Gateway B, a double charge is detected and a refund is automatically triggered against Gateway A.

Webhooks are deduplicated via Redis — each processed webhook ID is stored with a TTL, and duplicate deliveries are silently skipped.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| ORM | Spring Data JPA / Hibernate |
| Primary DB | MySQL |
| Cache / State | Redis (via Spring Data Redis) |
| Build Tool | Maven |
| Boilerplate | Lombok |

---

## Project Structure

```
com.adityi.paymentorchestrator
├── payment/
│   ├── controller/     PaymentController
│   ├── service/        PaymentOrchestrationService, TransactionStateMachineService
│   ├── entity/         PaymentAttempt
│   └── repository/     PaymentAttemptRepository
├── routing/
│   └── service/        RoutingService, GatewayScore, GatewayMetrics
├── circuitbreaker/
│   └── service/        CircuitService, CircuitState
├── gateway/
│   ├── GatewayAdapter  (interface)
│   ├── dto/            GatewayPaymentRequest, GatewayResponse, GatewayCallStatus
│   └── mock/           MockRazorpayAdapter, MockStripeAdapter, MockPayUAdapter, MockUpiAdapter
├── timeout/
│   └── service/        GatewayTimeoutInvestigationService
├── webhook/
│   ├── controller/     WebhookController
│   └── service/        ReconciliationService
└── config/             Redis config, RestTemplate beans
```

---

## Setup & Running

### Prerequisites

- Java 21+
- MySQL 8+
- Redis 7+
- Maven 3.8+

### 1. Clone the repository

```bash
git clone https://github.com/Adityapal67/payment-orchestrator
cd payment-orchestrator
```

### 2. Create MySQL database

```sql
CREATE DATABASE payment_orchestrator;
```

### 3. Configure `application.properties`

```properties
# MySQL
spring.datasource.url=jdbc:mysql://localhost:3306/payment_orchestrator
spring.datasource.username=root
spring.datasource.password=yourpassword
spring.jpa.hibernate.ddl-auto=update

# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Routing weights
routing.weight.success=0.6
routing.weight.latency=0.4
routing.max-latency=3000

# Circuit breaker
circuit.success-threshold=0.6

# Mock gateway config
gateway.razorpay.latency-ms=200
gateway.razorpay.success-rate=0.90
gateway.razorpay.timeout-rate=0.02
gateway.razorpay.max-latency-ms=3000

gateway.stripe.latency-ms=400
gateway.stripe.success-rate=0.85
gateway.stripe.timeout-rate=0.05
gateway.stripe.max-latency-ms=3000

gateway.payu.latency-ms=1500
gateway.payu.success-rate=0.60
gateway.payu.timeout-rate=0.10
gateway.payu.max-latency-ms=3000

gateway.upi.latency-ms=100
gateway.upi.success-rate=0.95
gateway.upi.timeout-rate=0.01
gateway.upi.max-latency-ms=3000
```

### 4. Run

```bash
mvn spring-boot:run
```

---

## API Reference

### Payment

```
POST /api/payment
Content-Type: application/json

{
  "idempotencyKey": "client-generated-uuid",
  "amount": 500.00,
  "currency": "INR"
}

Responses:
201 Created      → new payment accepted
200 OK           → duplicate key, original already SUCCESS/FAILED
409 Conflict     → duplicate key, original still PROCESSING
```

### Webhooks

```
POST /webhooks/{gatewayName}
Content-Type: application/json

{
  "idempotencyKey": "original-client-uuid",
  "gatewayReferenceId": "rzp-abc123",
  "status": "SUCCESS"
}

Responses:
200 OK           → processed
200 OK           → duplicate webhook, skipped
```

### Analytics

```
GET /analytics/gateways
→ per-gateway success rate, avg latency, circuit state, transaction count

GET /analytics/transactions
GET /analytics/transactions?status=FAILED
GET /analytics/transactions?gateway=stripe
GET /analytics/transactions?status=SUCCESS&gateway=upi
```

---

## Testing Scenarios

### Scenario 1 — Idempotency
Send `POST /api/payment` twice with the same `idempotencyKey`. Confirm only one row exists in MySQL and the second request returns `409 Conflict`.

### Scenario 2 — Circuit Breaker
Set `gateway.payu.success-rate=0.0`, restart, send 25 payments. Check:
```bash
redis-cli get gateway:payu:circuitState   # → "OPEN"
```
Subsequent payments should never log PayU as selected.

### Scenario 3 — Timeout + Failover
Set `gateway.razorpay.timeout-rate=1.0`, send a payment. Logs should show:
```
Razorpay selected → GATEWAY_TIMEOUT → investigating → retry on Stripe → SUCCESS
```

### Scenario 4 — Webhook Reconciliation
Create a payment that succeeds via Stripe. Then POST a success webhook from Razorpay with the same `idempotencyKey`. Logs should show `DUPLICATE CHARGE DETECTED` and a refund triggered against Razorpay.

### Scenario 5 — Redis sliding window
```bash
redis-cli lrange gateway:razorpay:outcome 0 -1
```
Should show the last 20 outcomes as `"SUCCESS:180"` / `"FAILED:200"` / `"TIMEOUT:3500"` entries.

---

## Open Source Contributions (Author)

- **JabRef** — Windows filename-length truncation fix in `getValidFileName()` ([#15837](https://github.com/JabRef/jabref/pull/15837))
- **AKHQ** — `NonUniqueBeanException` fix via transitive log4j exclusions ([#3009](https://github.com/tchiotludo/akhq/pull/3009))
- **Spring Kafka** — Javadoc improvement on `BatchListenerFailedException`
- **Quarkus** — Logging JSON formatter fix in `QuarkusMainTestExtension.java`

---

## Author

**Aditya Pal**
B.Tech CSE, Galgotia College of Engineering and Technology (2023–27)
[github.com/Adityapal67](https://github.com/Adityapal67)