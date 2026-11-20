package com.example.customer_retail_system.gateway.api;

/**
 * A definite gateway answer.
 *
 * @param transactionId gateway transaction id (null when declined)
 * @param message       human-readable detail (decline reason)
 */
public record PaymentResult(String idempotencyKey, PaymentStatus status, String transactionId, long amountPaise,
		String message) {
}
