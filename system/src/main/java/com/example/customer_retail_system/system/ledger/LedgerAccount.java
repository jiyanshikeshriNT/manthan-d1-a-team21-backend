package com.example.customer_retail_system.system.ledger;

public enum LedgerAccount {
	REFUND_EXPENSE,
	GATEWAY_CLEARING,
	/** Store credit owed to a customer (keyed by customer id on the entry). */
	STORE_CREDIT_LIABILITY
}
