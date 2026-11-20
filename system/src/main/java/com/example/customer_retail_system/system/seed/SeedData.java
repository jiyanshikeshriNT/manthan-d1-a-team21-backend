package com.example.customer_retail_system.system.seed;

import java.time.Instant;
import java.util.List;

/** Immutable, database-independent demo data set produced by {@link SeedDataGenerator}. */
record SeedData(
		List<CategorySeed> categories,
		List<ProductSeed> products,
		List<CustomerSeed> customers,
		List<OrderSeed> orders,
		List<StaffSeed> staff) {

	record CategorySeed(String code, String name, boolean returnable, Integer returnWindowDays) {
	}

	record ProductSeed(String sku, String name, String categoryCode, long pricePaise) {
	}

	record CustomerSeed(String email, String name, String phone, Instant createdAt) {
	}

	/** {@code deliveredAt} is null for orders that have not been delivered yet. */
	record OrderSeed(String orderNumber, String customerEmail, Instant orderedAt, Instant deliveredAt,
			String paymentMethod, String paymentRef, List<LineSeed> lines) {
	}

	record LineSeed(String sku, int quantity, long unitPricePaise) {
	}

	/** Passwords are not part of the data set; the loader hashes the configured one. */
	record StaffSeed(String username, String role) {
	}

	int lineCount() {
		return orders.stream().mapToInt(o -> o.lines().size()).sum();
	}
}
