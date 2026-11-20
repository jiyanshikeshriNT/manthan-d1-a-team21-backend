# State-machine failure modes and mitigations

These are the failure modes we found in the return and refund state machines before writing any
state-machine code. Each one names the mitigation and the test that will prove it (the phase in
brackets is when the test lands, per [PLAN.md](../PLAN.md)).

Shared mechanisms referenced below:

- **Transition table:** `ReturnStateMachine.transition(...)` is the only way to change a return's
  status. It rejects any pair that is not in the table with `409 INVALID_TRANSITION`.
- **Locks:** `@Version` optimistic locking on `return_request`, `customer_order` and `refund`;
  `SELECT … FOR UPDATE` on the order when a return is created.
- **Conditional updates:** `UPDATE … WHERE id = ? AND status IN (...)`; 0 rows means "someone else got
  there first", so the caller returns the current state.
- **One refund per return:** `refund.return_id UNIQUE`.
- **Deterministic gateway key:** `RR-REFUND-{returnId}` ([ADR-001](adr/ADR-001-refund-idempotency.md)).
- **Reconciler:** every 30 s it settles `UNKNOWN` refunds and `PROCESSING` refunds older than 2 minutes.

| # | Failure mode | What could go wrong | Mitigation | Test |
|---|---|---|---|---|
| 1 | Two scans race on RECEIVED | Two warehouse devices scan the same label together; two RECEIVED audit rows, or a version error shown to staff | Scanning a return that is already RECEIVED is idempotent: no error and no new audit row. A true race is resolved by `@Version`; the loser re-reads and sees RECEIVED | Re-scan is a no-op [P5] |
| 2 | Finance approves twice | Double click or two finance users; the refund executes twice | Approve is a conditional update `AWAITING_APPROVAL → PENDING`; the second call updates 0 rows and returns the current state. Execution claims the refund with its own conditional update | Double approve → one payment [P6] |
| 3 | Approve and reject race | One finance user approves while another rejects; the money moves but the return says REJECTED | Both are conditional updates from `AWAITING_APPROVAL`; exactly one wins. The loser gets `409 INVALID_TRANSITION` with the winning state | Concurrent approve/reject → one outcome [P6] |
| 4 | Refund triggered before INSPECTED | A bug or crafted call creates a refund for a return that has not been graded | A refund is only created inside the INSPECTED transition, from inspected item amounts. Finance endpoints act only on existing refund rows. Transition table forbids skipping states | Transition-table test [P4]; refund on RECEIVED return refused [P6] |
| 5 | All items rejected but a ₹0 refund is created | Empty ledger postings, a refund row stuck forever, misleading dashboard | If the inspected total is 0, the return goes `INSPECTED → REJECTED` and no refund row is created. `ledger_entry.amount_paise > 0` is a CHECK constraint | All-rejected → REJECTED, no refund [P5] |
| 6 | Gateway times out after charging | We mark FAILED, finance retries, the customer is paid twice | A timeout or 5xx sets `UNKNOWN`, never FAILED. The reconciler asks `status(key)` before any new `pay`, and any `pay` reuses the same key, which the gateway dedupes | `TIMEOUT` then reconcile → SUCCEEDED once [P6] |
| 7 | DB commit fails after gateway success | Money moved but we still have PROCESSING; a retry pays again | tx2 failing leaves the refund in PROCESSING. After 2 minutes the reconciler finds it, `status(key)` returns the original payment, and tx2 is re-applied. The ledger's `UNIQUE(refund_id, account, direction)` blocks a double posting | `FAIL_AFTER_CHARGE` → no double pay [P6] |
| 8 | Customer cancels after shipping | The item is on its way to the warehouse but the return is CANCELLED; it cannot be received | CANCELLED is allowed only from REQUESTED, PENDING_REVIEW and APPROVED. From IN_TRANSIT the response is `409 INVALID_TRANSITION` with copy explaining that the parcel is already on its way | Cancel after ship refused [P4, E2E P8] |
| 9 | Label from return X scanned while working on Y | Wrong return marked RECEIVED or inspected; refund paid against the wrong order | Every warehouse action is addressed by the label code (HMAC-signed `RR-{rma}-{hmac8}`), and the server resolves the return from it. A forged or mismatched label gets `400 LABEL_INVALID`; a return in the wrong state gets `409` | Forged label → 400; wrong-state scan → 409 [P5] |
| 10 | Manager approves a return no longer in PENDING_REVIEW | The customer cancelled meanwhile; the approve resurrects a cancelled return | Manager approve/reject go through the transition table from PENDING_REVIEW only, guarded by `@Version`. A stale approve gets `409 INVALID_TRANSITION` | Approve after cancel → 409 [P7] |
| 11 | A crash leaves a refund PENDING/PROCESSING forever | The customer never gets paid and nobody notices | The reconciler sweeps `PROCESSING` older than 2 minutes and `UNKNOWN` with `next_attempt_at` due. The `returnright_refund_pending` gauge and the alert rules make stuck refunds visible; after 5 attempts the refund goes FAILED and appears in the finance queue | Reconciler picks up stale PROCESSING [P6]; SRE drill [P10] |

## Invariants these mitigations protect

1. A return has at most one refund row.
2. A refund causes at most one successful gateway payment (one `gw_payment` per key).
3. Each refund posts at most one balanced ledger pair, and only once it is SUCCEEDED (or store credit is posted).
4. Every return status change has exactly one audit row, written in the same transaction.
5. Terminal states (REFUNDED, REJECTED, CANCELLED) are never left.
