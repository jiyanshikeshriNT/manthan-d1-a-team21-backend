# ADR-002: Mock payment gateway as an in-process module, separated by the build tool

- **Status:** Accepted
- **Date:** 2026-11-20
- **Deciders:** ReturnRight team

## Context

ReturnRight must refund to the customer's original payment method through a payment gateway. For the
project we use a mock gateway that must:

- be idempotent by key (see [ADR-001](ADR-001-refund-idempotency.md));
- support fault injection (`NONE`, `FAIL`, `TIMEOUT`, `SLOW`, `FAIL_AFTER_CHARGE`) for tests and the
  SRE drill;
- be replaceable by a real provider, or moved into its own service, without touching portal code.

The original brief suggested running the gateway as a separate container in Docker Compose.

## Options considered

### A. Separate gateway service (own container, HTTP between them)
- **Pros:** a real network boundary, so real timeouts and partial failures; mirrors production.
- **Cons:** a second deployable, second build, more Compose wiring and slower tests; contract drift
  needs contract tests; adds operational work for a mock.

### B. Gateway classes inside the portal source tree, separated by package only
- **Pros:** simplest.
- **Cons:** nothing stops portal code from calling mock internals (for example, flipping a fault flag
  directly or reading `gw_payment`). The "replaceable" promise would erode quietly.

### C. Modular monolith with Gradle-enforced boundaries (chosen)
- `:gateway-api`: a plain `java-library` with the `PaymentGateway` port and DTOs. No Spring web, no JPA.
- `:gateway-mock`: a `java-library` implementing the port, with Spring auto-configuration, its own
  `gw_` tables and its own Flyway location `classpath:db/migration/gateway`.
- The portal depends on `gateway-api` as `implementation` and on `gateway-mock` **only as `runtimeOnly`**.

## Decision

Option C. The mock runs in the same JVM as the portal, but the **compiler** stops portal code from
referencing `com.example.customer_retail_system.gateway.mock..`, because that module is not on the
portal's compile classpath. `ModularityTest` also has an ArchUnit rule that fails if any portal class
depends on the mock package.

Faults are injected inside the mock: latency, timeouts and a "charge then fail" mode. This gives the
refund executor the same failure shapes it would see over a network.

## Consequences

- **Positive:** one container and one build; fast in-process integration tests that still exercise
  timeouts and lost responses.
- **Positive:** swapping in a real provider means adding a `gateway-<provider>` module that implements
  `PaymentGateway` and changing one `runtimeOnly` line. Portal code is unchanged.
- **Positive:** extracting the mock into its own service later means adding an HTTP adapter that
  implements the same port.
- **Negative (deviation from brief):** there is no separate gateway container in Compose, so network
  failures (DNS, connection resets) are simulated rather than real.
- **Negative:** the mock shares the portal's database (in its own `gw_` tables). Portal code must not
  query those tables; this is a review rule backed by the package boundary, not a database permission.
- **Negative:** the fault-injection admin endpoint lives in the same app and must stay disabled in the
  `prod` profile.
