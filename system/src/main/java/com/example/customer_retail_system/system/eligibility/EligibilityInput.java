package com.example.customer_retail_system.system.eligibility;

import java.time.Instant;

/**
 * Everything the engine needs about one order line.
 *
 * @param categoryName       display name used in refusal copy
 * @param returnable         category policy
 * @param returnWindowDays   category window; null means the configured default
 * @param deliveredAt        null when the order has not been delivered
 * @param orderedQty         units on the order line
 * @param alreadyReturnedQty units already in non-cancelled, non-rejected returns
 */
public record EligibilityInput(
		String categoryName,
		boolean returnable,
		Integer returnWindowDays,
		Instant deliveredAt,
		int orderedQty,
		int alreadyReturnedQty) {
}
