package com.example.customer_retail_system.system.returns;

/** ORIGINAL_PAYMENT goes through the gateway; STORE_CREDIT is posted to the ledger only (G9). */
public enum RefundMethod {
	ORIGINAL_PAYMENT,
	STORE_CREDIT
}
