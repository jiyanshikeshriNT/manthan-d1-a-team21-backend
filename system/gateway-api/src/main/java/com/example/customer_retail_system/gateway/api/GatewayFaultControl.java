package com.example.customer_retail_system.gateway.api;

import java.util.Objects;

/**
 * Optional operations port offered by test/mock gateways only, so the portal's non-prod admin
 * endpoint and tests can inject faults without depending on any implementation.
 */
public interface GatewayFaultControl {

	FaultSettings current();

	void apply(FaultSettings settings);

	record FaultSettings(FaultMode mode, long latencyMs) {

		public FaultSettings {
			Objects.requireNonNull(mode, "mode");
			if (latencyMs < 0 || latencyMs > 60_000) {
				throw new IllegalArgumentException("latencyMs must be within 0..60000");
			}
		}

		public static FaultSettings none() {
			return new FaultSettings(FaultMode.NONE, 0);
		}
	}
}
