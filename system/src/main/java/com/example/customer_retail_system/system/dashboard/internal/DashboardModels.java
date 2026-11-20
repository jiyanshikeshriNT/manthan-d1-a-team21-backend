package com.example.customer_retail_system.system.dashboard.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Manager dashboard read models (G17). */
final class DashboardModels {

	private DashboardModels() {
	}

	/** {@code returnRate} = returnedUnits ÷ soldUnits (0..1, 4 dp) for orders placed in the range. */
	record CategoryReturnRate(String category, boolean returnable, long soldUnits, long returnedUnits,
			BigDecimal returnRate) {
	}

	record ReasonCount(String reasonCode, long count) {
	}

	/** Average (closed_at − created_at) of REFUNDED and REJECTED returns closed in the range. */
	record CycleTime(long closedReturns, BigDecimal averageHours) {
	}

	record FlaggedCustomer(long customerId, String maskedEmail, long activeFlags, int highestReturnCount,
			Instant lastFlaggedAt) {
	}

	record Dashboard(LocalDate from, LocalDate to, List<CategoryReturnRate> returnRateByCategory,
			List<ReasonCount> topReasons, CycleTime cycleTime, List<FlaggedCustomer> flaggedCustomers) {
	}
}
