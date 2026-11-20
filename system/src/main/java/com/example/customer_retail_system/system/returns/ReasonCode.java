package com.example.customer_retail_system.system.returns;

/** Why the customer is returning an item (dashboard "top reasons" groups by this). */
public enum ReasonCode {
	DAMAGED,
	DEFECTIVE,
	WRONG_ITEM,
	WRONG_SIZE,
	NOT_AS_DESCRIBED,
	CHANGED_MIND,
	/** Requires a free-text reason. */
	OTHER
}
