package com.example.customer_retail_system.system.order.internal;

/** How the order was paid; refunds to ORIGINAL_PAYMENT go back through the gateway. */
public enum PaymentMethod {
	CARD, UPI, NETBANKING, WALLET
}
