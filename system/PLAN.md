# ReturnRight — Implementation Plan
Rule: each task = code + tests + `.\gradlew.bat build` green → tick → commit. Stop at phase end.

## Phase 0 — Foundations
- [x] Create branch `feature/returnright`; add CLAUDE.md, LLD.md, PLAN.md
- [x] Gradle multi-module: settings include `gateway-api`, `gateway-mock`; portal `implementation project(':gateway-api')`, `runtimeOnly project(':gateway-mock')`
- [x] Dependencies: remove thymeleaf; add springdoc-openapi-starter-webmvc-ui, spring-modulith (core + test) — verify Boot 4 compatibility, fallback ArchUnit
  (springdoc 3.2.0 + Spring Modulith 2.2.0 work on Boot 4.2.0; no fallback needed)
- [x] Profiles: `local` (H2 file), `test` (H2 mem, MODE=PostgreSQL), `docker` (Postgres); `app.*` properties (storage dir, label secret, thresholds)
- [x] `shared`: DomainException, ErrorCode, GlobalExceptionHandler (ProblemDetail), Clock bean, Money util
- [x] Verify: context-loads test, ModularityTest, `build` green

## Phase 1 — Architecture docs
- [x] docs/architecture.md: C4 context + container diagrams (Mermaid), state machine diagram
- [x] docs/adr/ADR-001-refund-idempotency.md (key vs unique refund per return → both)
- [x] docs/adr/ADR-002-modular-monolith-gateway.md
- [x] docs/state-machine-failure-modes.md (11 failure modes + mitigations)
- [ ] Verify: review with team (no code) — docs ready; awaiting human team review

## Phase 2 — Schema & seed data
- [ ] Flyway V1 core (category, product, customer, order, order_line, staff_user)
- [ ] JPA entities + repositories (catalog, customer, order)
- [ ] Seed runner (`seed` profile, fixed RNG): 6 categories, 40 products, 500 customers, 2,000 orders, staff users per role
- [ ] Tests: migration runs on H2; seed counts exact; rerun is a no-op

## Phase 3 — Eligibility & order lookup
- [ ] EligibilityEngine (pure): category, window, delivery, remaining qty → result + microcopy
- [ ] Unit tests: day 0/29/30/31, electronics day 10/11, innerwear, perishables, undelivered, partial qty, IST midnight edge
- [ ] Customer session: lookup, access token (hashed, TTL), rate limiter
- [ ] GET order with per-line eligibility
- [ ] Tests: wrong email → generic 404; 6th attempt → 429; expired token → 401

## Phase 4 — Return requests, state machine, audit, label
- [ ] Flyway V2 (return_request, return_item, return_photo, customer_flag)
- [ ] audit module + AuditLog (same-tx)
- [ ] ReturnStateMachine + transition-table test (every legal/illegal pair)
- [ ] Photo storage (magic bytes, size, UUID names)
- [ ] Create return (order lock, Idempotency-Key replay, flagging, auto-approve vs PENDING_REVIEW)
- [ ] Label code HMAC + QR PNG (ZXing); timeline endpoint; ship; cancel
- [ ] Tests: mixed basket with an ineligible item → 422 with reasons; 4th return in 30 days → flagged; concurrent create over-return prevented; replay same key → same RMA; audit row per transition

## Phase 5 — Warehouse inspection
- [ ] Label lookup (HMAC verify) + QR image decode; receive (idempotent re-scan)
- [ ] Inspection: grade/decision/acceptedQty, refund calc (C = 50%), all-rejected → REJECTED
- [ ] Tests: grade C rounding, partial accept, wrong-state scan → 409, forged label → 400

## Phase 6 — Gateway, refund, finance, ledger
- [ ] gateway-api port + DTOs; gateway-mock impl (gw_payment, idempotent by key, fault modes, latency)
- [ ] Flyway V3 (refund, ledger_entry, audit idx)
- [ ] RefundExecutor (tx1 → gateway → tx2), threshold routing, store credit path
- [ ] Ledger posting (balanced, unique per refund)
- [ ] Reconciler (@Scheduled) + finance approve/reject/retry + ledger query
- [ ] Tests: double approve; 10 concurrent execute calls → 1 gw_payment, 1 ledger pair; TIMEOUT then reconcile → SUCCEEDED once; FAIL_AFTER_CHARGE → no double pay; exactly 10,000 auto vs 10,000.01 needs approval; ledger balances

## Phase 7 — Manager
- [ ] Review queue approve/reject for PENDING_REVIEW
- [ ] Dashboard queries: return rate by category, top reasons, avg cycle time, flagged customers
- [ ] Audit query endpoint; gateway fault admin endpoint
- [ ] Tests: dashboard numbers on a fixed fixture; role access matrix (403s)

## Phase 8 — Testing deliverables
- [ ] docs/testing/e2e-cases.md: 10 written E2E cases (happy path, flagged, grade C, mixed basket, >10k approval, finance reject, double click refund, gateway timeout, cancel after ship refused, forged label)
- [ ] Automate the 10 E2E cases as `@SpringBootTest` + MockMvc
- [ ] docs/testing/defect-log.md (template + defects found)
- [ ] Verify: `build` green, JaCoCo report generated

## Phase 9 — Deployment
- [ ] Dockerfile (multi-stage, JRE 17, non-root)
- [ ] docker-compose.yml: app, postgres (healthcheck), prometheus, grafana; photo volume; `.env.example`
- [ ] .github/workflows/ci.yml: build + test, docker build, upload test reports
- [ ] Verify: fresh clone → `docker compose up --build` → health UP, seed loaded, happy path via curl

## Phase 10 — SRE
- [ ] Custom metrics (refund outcome counter, gateway latency timer, pending gauge)
- [ ] Grafana dashboard JSON provisioned; Prometheus alert rules
- [ ] docs/runbook-refund-failures.md
- [ ] Drill: set gateway FAIL → alert fires; restore → refunds reconcile; record results
