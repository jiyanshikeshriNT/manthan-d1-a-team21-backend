package com.example.customer_retail_system.gateway.api;

public enum PaymentStatus {
	/** Money has moved. */
	SUCCEEDED,
	/** Definitely not paid (e.g. issuer refused). Retrying the same key will not succeed by itself. */
	DECLINED
}
