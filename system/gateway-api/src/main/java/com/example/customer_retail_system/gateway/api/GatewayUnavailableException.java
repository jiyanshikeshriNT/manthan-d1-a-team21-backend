package com.example.customer_retail_system.gateway.api;

/** The gateway did not give a definite answer (5xx, connection reset). The outcome is unknown. */
public class GatewayUnavailableException extends RuntimeException {

	public GatewayUnavailableException(String message) {
		super(message);
	}
}
