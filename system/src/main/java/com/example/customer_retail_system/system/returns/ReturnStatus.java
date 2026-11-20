package com.example.customer_retail_system.system.returns;

/** Return lifecycle states (CLAUDE.md "Return state machine"). */
public enum ReturnStatus {
	REQUESTED,
	PENDING_REVIEW,
	APPROVED,
	IN_TRANSIT,
	RECEIVED,
	INSPECTED,
	AWAITING_FINANCE,
	REFUND_IN_PROGRESS,
	REFUNDED,
	REJECTED,
	CANCELLED;

	public boolean isTerminal() {
		return this == REFUNDED || this == REJECTED || this == CANCELLED;
	}
}
