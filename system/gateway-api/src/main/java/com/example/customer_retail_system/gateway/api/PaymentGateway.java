package com.example.customer_retail_system.gateway.api;

import java.util.Optional;

/**
 * Payment gateway port (LLD §5). Implementations must be idempotent by
 * {@link PaymentRequest#idempotencyKey()}: repeating a key never pays twice and returns the
 * original result.
 */
public interface PaymentGateway {

	/**
	 * Pays out a refund. A definite answer is a {@link PaymentResult} (SUCCEEDED or DECLINED).
	 *
	 * @throws GatewayUnavailableException when the outcome is unknown (5xx, connection lost): the
	 *         payment may or may not have happened, so callers must settle it with {@link #status}.
	 */
	PaymentResult pay(PaymentRequest request);

	/** The recorded result for a key, if the gateway has one. */
	Optional<PaymentResult> status(String idempotencyKey);
}
