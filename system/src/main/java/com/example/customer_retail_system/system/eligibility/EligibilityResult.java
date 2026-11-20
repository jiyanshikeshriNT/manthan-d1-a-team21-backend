package com.example.customer_retail_system.system.eligibility;

import java.time.LocalDate;

/**
 * @param returnableQty units that can still be returned (0 unless eligible)
 * @param deadline      last day (Asia/Kolkata) a return can be started; null if not applicable
 */
public record EligibilityResult(
		boolean eligible,
		EligibilityCode code,
		String message,
		int returnableQty,
		LocalDate deadline) {
}
