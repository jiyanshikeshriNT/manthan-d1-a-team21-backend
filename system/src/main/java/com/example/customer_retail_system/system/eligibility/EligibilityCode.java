package com.example.customer_retail_system.system.eligibility;

/** Why an order line can or cannot be returned (LLD §4 eligibility microcopy). */
public enum EligibilityCode {
	ELIGIBLE,
	CATEGORY_NOT_RETURNABLE,
	NOT_DELIVERED,
	WINDOW_EXPIRED,
	ALREADY_IN_RETURN
}
