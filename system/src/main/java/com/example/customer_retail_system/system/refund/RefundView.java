package com.example.customer_retail_system.system.refund;

import java.time.Instant;
import java.util.UUID;

/** Read model of a refund for APIs and other modules. */
public record RefundView(
		long id,
		UUID returnId,
		String rma,
		long amountPaise,
		String method,
		RefundStatus status,
		int attempts,
		boolean requiresApproval,
		String approvedBy,
		Instant approvedAt,
		String gatewayTxnId,
		String lastError,
		Instant nextAttemptAt,
		Instant createdAt,
		Instant updatedAt) {
}
