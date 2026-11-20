package com.example.customer_retail_system.gateway.api;

import java.util.Objects;

/**
 * @param idempotencyKey     deterministic per refund ({@code RR-REFUND-{returnId}}), never per attempt
 * @param amountPaise        amount to pay out, in paise (&gt; 0)
 * @param reference          merchant reference shown on statements (the RMA number)
 * @param originalPaymentRef the order's original payment, which the refund goes back to
 */
public record PaymentRequest(String idempotencyKey, long amountPaise, String reference, String originalPaymentRef) {

	public PaymentRequest {
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		if (idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey must not be blank");
		}
		if (amountPaise <= 0) {
			throw new IllegalArgumentException("amountPaise must be > 0");
		}
	}
}
