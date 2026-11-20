# ReturnRight — Architecture

Status: draft for team review (Phase 1). Source of truth for schema and API details is [LLD.md](../LLD.md);
this document shows the shape of the system and the return lifecycle.

## 1. System context

Who uses ReturnRight and what it talks to.

```mermaid
flowchart LR
    customer(["Customer<br/>finds order, requests return,<br/>tracks status"])
    agent(["Warehouse agent<br/>scans label, grades, accepts/rejects"])
    finance(["Finance reviewer<br/>approves refunds > ₹10,000"])
    manager(["Operations manager<br/>dashboard, flagged-customer review"])

    rr["ReturnRight<br/>(returns & refunds portal)"]
    gw["Payment gateway<br/>(mocked; in-process module)"]
    obs["Prometheus + Grafana<br/>(metrics, alerts)"]

    customer -- "REST /api/v1/customer" --> rr
    agent -- "REST /api/v1/warehouse" --> rr
    finance -- "REST /api/v1/finance" --> rr
    manager -- "REST /api/v1/manager" --> rr
    rr -- "pay / status by idempotency key" --> gw
    obs -- "scrapes /actuator/prometheus" --> rr
```

## 2. Containers (Docker Compose)

```mermaid
flowchart TB
    subgraph compose["docker compose"]
        app["app<br/>Spring Boot 4.2 · Java 17<br/>portal + gateway-mock module<br/>:8080"]
        db[("postgres:17<br/>portal tables + gw_* tables")]
        prom["prometheus<br/>:9090<br/>alert rules"]
        graf["grafana<br/>:3000<br/>refund dashboard"]
        vol[/"photos volume<br/>/data/photos"/]
    end
    app -- "JDBC (Flyway-managed)" --> db
    app -- "writes return photos" --> vol
    prom -- "scrape" --> app
    graf -- "query" --> prom
```

The gateway runs **in the app container** by decision (see [ADR-002](adr/ADR-002-modular-monolith-gateway.md)).
It is still a separate build module with its own tables, so it can become its own container later.

## 3. Modules inside the app

```mermaid
flowchart LR
    subgraph portal[":  (portal, bootJar)"]
        security --> order
        order --> eligibility
        returns --> order
        returns --> eligibility
        returns --> customer
        returns --> audit
        inspection --> returns
        inspection --> refund
        refund --> ledger
        refund --> returns
        refund --> audit
        dashboard --> returns
        dashboard --> customer
        catalog
        shared
    end
    subgraph gwapi[":gateway-api (java-library)"]
        port["PaymentGateway port + DTOs"]
    end
    subgraph gwmock[":gateway-mock (java-library, runtimeOnly)"]
        impl["MockPaymentGateway<br/>gw_payment, fault modes"]
    end
    refund -- "compile dependency" --> port
    impl -- "implements" --> port
```

Boundary rules:
- Gradle: portal → `gateway-api` is `implementation`; portal → `gateway-mock` is `runtimeOnly`. The compiler
  rejects any import of mock classes from portal code.
- Spring Modulith (`ModularityTest`): each top-level package is a module; only its root package is public,
  `internal` sub-packages are private; no cycles.
- Every module may use `shared` (errors, `Clock`, `Money`, properties).

## 4. Return state machine

Single source of truth: `returns.ReturnStateMachine`. Every transition writes an `audit_log` row in the same
transaction.

```mermaid
stateDiagram-v2
    [*] --> REQUESTED : customer submits (all items eligible)
    REQUESTED --> APPROVED : auto (customer not flagged)
    REQUESTED --> PENDING_REVIEW : auto (customer flagged: >3 returns / 30 days)
    REQUESTED --> CANCELLED : customer
    PENDING_REVIEW --> APPROVED : manager approves
    PENDING_REVIEW --> REJECTED : manager rejects
    PENDING_REVIEW --> CANCELLED : customer
    APPROVED --> IN_TRANSIT : customer ships (carrier handoff)
    APPROVED --> CANCELLED : customer
    IN_TRANSIT --> RECEIVED : agent scans label
    RECEIVED --> INSPECTED : agent records grade + decision per item
    INSPECTED --> REJECTED : all items rejected (refund = 0)
    INSPECTED --> AWAITING_FINANCE : refund total > ₹10,000
    INSPECTED --> REFUND_IN_PROGRESS : refund total ≤ ₹10,000
    AWAITING_FINANCE --> REFUND_IN_PROGRESS : finance approves
    AWAITING_FINANCE --> REJECTED : finance rejects (reason required)
    REFUND_IN_PROGRESS --> REFUNDED : gateway success / store credit posted
    REFUNDED --> [*]
    REJECTED --> [*]
    CANCELLED --> [*]
```

Customer timeline mapping (the seven steps from the brief):

| Timeline step | Internal states |
|---|---|
| Requested | REQUESTED, PENDING_REVIEW ("under review") |
| Approved | APPROVED |
| In transit | IN_TRANSIT |
| Received | RECEIVED |
| Inspected | INSPECTED, AWAITING_FINANCE ("awaiting refund approval"), REFUND_IN_PROGRESS ("refund processing") |
| Refunded | REFUNDED |
| Rejected / Cancelled | REJECTED, CANCELLED (shown as the final step, with the reason) |

## 5. Refund execution (idempotent)

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller (inspection / finance / reconciler)
    participant R as RefundExecutor
    participant DB as Database
    participant G as PaymentGateway (mock)

    C->>R: execute(refundId)
    R->>DB: tx1: UPDATE refund SET status=PROCESSING, attempts+1<br/>WHERE id=? AND status IN (PENDING, UNKNOWN)
    alt 0 rows updated (already processing / done)
        R-->>C: current refund state (no gateway call)
    else 1 row updated
        R->>G: pay(key = RR-REFUND-{returnId}, amount)  [outside any tx, 3 s timeout]
        alt success
            G-->>R: txnId
            R->>DB: tx2: refund SUCCEEDED, ledger DEBIT/CREDIT pair,<br/>return → REFUNDED, audit
        else timeout / 5xx
            R->>DB: refund UNKNOWN, next_attempt_at = backoff
            Note over R,G: Reconciler later calls status(key);<br/>found → SUCCEEDED, not found → pay(same key)
        else declined
            R->>DB: refund FAILED, audit, metric (alert)
        end
    end
```

Store-credit refunds skip the gateway call but use the same refund row, the same conditional update and the
same ledger posting (credit to `STORE_CREDIT_LIABILITY`).

## 6. Ledger design

- Append-only, double-entry. Each successful refund posts exactly one balanced pair; `UNIQUE(refund_id,
  account, direction)` makes a second posting impossible.
- Amounts are `BIGINT` paise, always positive; the direction carries the sign.

| Refund method | Debit | Credit |
|---|---|---|
| Original payment | REFUND_EXPENSE | GATEWAY_CLEARING |
| Store credit | REFUND_EXPENSE | STORE_CREDIT_LIABILITY (per customer) |

Invariant checked in tests: for every refund, Σ debits = Σ credits = `refund.amount_paise`.
