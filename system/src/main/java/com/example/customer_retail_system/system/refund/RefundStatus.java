package com.example.customer_retail_system.system.refund;

/** Refund lifecycle (docs/architecture.md §5). Every change is a conditional update. */
public enum RefundStatus {
	/** Ready to execute. */
	PENDING,
	/** Total above the threshold: waiting for finance. */
	AWAITING_APPROVAL,
	/** Claimed by an executor; the gateway call is in flight. */
	PROCESSING,
	/** Timeout/5xx: we don't know if money moved. Only the reconciler or a retry settles it. */
	UNKNOWN,
	SUCCEEDED,
	/** Declined, or gave up after max attempts. Finance may retry with the same key. */
	FAILED,
	/** Rejected by finance. */
	REJECTED
}
