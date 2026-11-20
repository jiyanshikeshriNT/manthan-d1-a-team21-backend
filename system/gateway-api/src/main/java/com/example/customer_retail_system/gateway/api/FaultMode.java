package com.example.customer_retail_system.gateway.api;

/** Fault-injection modes of a test/mock gateway (G18). */
public enum FaultMode {
	/** Normal operation. */
	NONE,
	/** Gateway down: every call fails with an unknown outcome and nothing is charged. */
	FAIL,
	/** Charges, then answers only after {@code latencyMs} (default 5 s) — longer than the caller's timeout. */
	TIMEOUT,
	/** Answers normally after {@code latencyMs}. */
	SLOW,
	/** Charges, then fails with an unknown outcome (the response is lost). */
	FAIL_AFTER_CHARGE,
	/** Definitely declines new payments without charging. */
	DECLINE
}
