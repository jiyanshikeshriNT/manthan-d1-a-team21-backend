# ADR-001: Refund idempotency — unique refund per return *and* a deterministic gateway key

- **Status:** Accepted
- **Date:** 2026-11-20
- **Deciders:** ReturnRight team

## Context

Finance has paid duplicate refunds under the current email-and-spreadsheet process. In the new system,
duplicates can still arise from:

1. A double click on "approve" or "retry".
2. A client or proxy retrying after a timeout.
3. Two finance users approving the same refund at the same time.
4. The gateway charging successfully, then our database commit failing (or the process crashing)
   before we record the success.
5. The gateway timing out *after* it has charged.

We need a guarantee that holds across all five, including the cases where our database and the
gateway disagree.

## Options considered

### A. Unique refund per return (database constraint only)
`refund.return_id UNIQUE`, so a return can have at most one refund row.

- **Pros:** a hard guarantee inside our database; concurrent inserts fail fast; simple.
- **Cons:** says nothing about the gateway. In cases 4 and 5 we have one refund row but cannot tell
  whether the money moved. A retry would call the gateway again and could pay twice.

### B. Idempotency key sent to the gateway only
Each call carries a key, and the gateway returns the original result for a repeated key.

- **Pros:** protects the gateway boundary; retries after a timeout are safe.
- **Cons:** only as good as the key. A random key per click, per request or per attempt gives no
  protection. It also does nothing to stop two refund rows being created for one return.

### C. Both (chosen)
- `refund.return_id UNIQUE` guarantees **one refund intent per return** in our database.
- The gateway key is **deterministic**: `RR-REFUND-{returnId}`. It is derived from the return, never
  random, never per click or per attempt, so every retry of the same refund, from any code path
  (executor, reconciler, finance retry), presents the same key. The gateway dedupes on it.

## Decision

Use **both** mechanisms, plus the following execution rules:

1. **Never call the gateway inside a database transaction.**
   - *tx1:* `UPDATE refund SET status='PROCESSING', attempts=attempts+1 WHERE id=? AND status IN ('PENDING','UNKNOWN')`.
     If 0 rows are updated, someone else owns this attempt; return the current state without calling the gateway.
   - *Gateway call* (outside any transaction, 3 s timeout) with key `RR-REFUND-{returnId}`.
   - *tx2:* conditional update to `SUCCEEDED` plus the balanced ledger pair plus return → `REFUNDED`, in one transaction.
2. **An unknown outcome is recorded as `UNKNOWN`, never as `FAILED`.** A timeout or 5xx means we do not
   know whether money moved.
3. **A reconciler settles uncertainty.** Every 30 s it takes refunds in `UNKNOWN` (and `PROCESSING`
   older than 2 minutes, i.e. a crash between tx1 and tx2) and asks `gateway.status(key)`. Found → `SUCCEEDED`.
   Not found → `pay` again **with the same key**. After 5 attempts → `FAILED`; finance may retry
   manually, still with the same key.
4. **The ledger is a second line of defence.** `ledger_entry` has `UNIQUE(refund_id, account, direction)`,
   so even a bug that ran tx2 twice could not post the refund twice.
5. **Store credit** does not call the gateway but uses the same refund row, status rules and ledger
   uniqueness.

## Consequences

- **Positive:** duplicate payment requires both our database *and* the gateway's dedupe to fail. Every
  failure mode in [state-machine-failure-modes.md](../state-machine-failure-modes.md) has a defined outcome.
- **Positive:** retries are always safe, so operations staff can press "retry" without fear.
- **Negative:** one refund per return means a partially refunded return cannot be topped up later with a
  second refund. A correction would need a new, explicitly modelled adjustment flow. This is accepted
  for v1.
- **Negative:** we depend on the gateway honouring idempotency keys for long enough (at least the
  reconciliation horizon). The mock implements this; a real provider's key-retention period must be
  checked during integration.
- **Negative:** `UNKNOWN` adds a visible intermediate state. The customer timeline keeps showing
  "Refund in progress" until it is settled.

## Verification (Phase 6 tests)

- 10 concurrent execute calls → exactly one `gw_payment` row and one ledger pair.
- `TIMEOUT` fault then reconcile → `SUCCEEDED` once.
- `FAIL_AFTER_CHARGE` fault → no second payment.
- Double finance approval → second call is a no-op returning the current state.
