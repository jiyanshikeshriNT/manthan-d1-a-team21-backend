# ReturnRight — Claude working rules

## Build & run (Windows: set JAVA_HOME first)
- $env:JAVA_HOME="C:\Users\Akash Rajpoot\.jdks\jdk-17.0.20.1+1"
- Build + all tests: `.\gradlew.bat build`
- Single test: `.\gradlew.bat test --tests "*EligibilityEngineTest"`
- Run locally (H2 + seed): `.\gradlew.bat bootRun --args="--spring.profiles.active=local,seed"`
- Full stack: `docker compose up --build` (app :8080, postgres :5432, prometheus :9090, grafana :3000)
- API docs: http://localhost:8080/swagger-ui.html

## Modules (modular monolith, enforced by Gradle)
- `:` (root) = portal app. Base package `com.example.customer_retail_system.system`.
- `:gateway-api` = payment port (`PaymentGateway`, DTOs). No Spring web, no JPA.
- `:gateway-mock` = mock gateway implementation + auto-configuration. Portal depends on it
  `runtimeOnly` — portal code must NEVER import `..gateway.mock..`.
- Portal packages are modules: catalog, customer, order, eligibility, returns, inspection,
  refund, ledger, audit, dashboard, security, shared. Cross-module calls go through each
  module's public `*Service`/`*Api` in its root package; `internal` sub-packages are private.
  `ModularityTest` (Spring Modulith) must pass.

## Return state machine (single source of truth: `returns.ReturnStateMachine`)
REQUESTED→APPROVED | PENDING_REVIEW | CANCELLED
PENDING_REVIEW→APPROVED | REJECTED | CANCELLED
APPROVED→IN_TRANSIT | CANCELLED
IN_TRANSIT→RECEIVED
RECEIVED→INSPECTED
INSPECTED→AWAITING_FINANCE | REFUND_IN_PROGRESS | REJECTED (all items rejected)
AWAITING_FINANCE→REFUND_IN_PROGRESS | REJECTED
REFUND_IN_PROGRESS→REFUNDED
Terminal: REFUNDED, REJECTED, CANCELLED.
- Change status ONLY via `ReturnStateMachine.transition(ret, target, actor, reason)`; it
  validates, writes the audit row in the SAME transaction, and bumps `@Version`.
- Never set `status` directly, never add a transition without updating this list, the
  transition table test, and LLD.md.

## Money & refunds (non-negotiable)
- Money = `long` paise. No double/float. Rounding HALF_UP to paisa.
- Grade A/B = 100%, C = 50%. Finance approval when return total > 1_000_000 paise (strict).
- One refund per return: `refund.return_id` UNIQUE. Gateway idempotency key is
  deterministic `RR-REFUND-{returnId}` — never random, never per click.
- Never call the gateway inside a DB transaction. Pattern: tx1 create/lock refund →
  call gateway → tx2 conditional update (`WHERE status IN (...)`) + ledger + state change.
- Timeout/5xx ⇒ refund `UNKNOWN`; only the reconciler or retry endpoint resolves it, reusing
  the same key. Ledger is append-only, double-entry, entries must balance per refund.

## Business rules
- Window anchored on delivery date in Asia/Kolkata; eligible iff daysSince <= window
  (default 30, ELECTRONICS 10). INNERWEAR, PERISHABLES never returnable. Not delivered ⇒ no.
- >3 return requests in rolling 30 days (incl. new one, excl. CANCELLED) ⇒ flag ⇒
  PENDING_REVIEW. Flags route, never block.
- Always inject `java.time.Clock`; never call `LocalDate.now()` without it.

## Coding conventions
- Java 17, constructor injection, records for DTOs, no Lombok.
- Controllers thin; logic in services; JPA entities never leave a module's API — map to DTOs.
- Errors: throw `DomainException(ErrorCode, msg)`; `GlobalExceptionHandler` maps to RFC 7807
  ProblemDetail with `code`. 409 for invalid transition / concurrency.
- DB changes only via new Flyway files `V{n}__desc.sql`; never edit applied ones. SQL must
  run on PostgreSQL and H2 (MODE=PostgreSQL): no jsonb, no PG-only functions.
- Mask emails/phones in logs and audit details.
- API base path `/api/v1/{customer|warehouse|finance|manager|admin}`.

## Testing rules
- Every task ships with tests; `.\gradlew.bat build` must be green before checking a box in
  PLAN.md. Eligibility + refund idempotency changes need boundary/concurrency tests.
- Unit tests use a fixed `Clock`. Integration tests: `@SpringBootTest` + H2 profile `test`.

## Workflow
- Follow PLAN.md one phase at a time; tick `[x]` only after tests pass; stop at phase end
  for review. Commit after each verified task (conventional commits) on branch
  `feature/returnright`. Update LLD.md when a contract or schema changes.
