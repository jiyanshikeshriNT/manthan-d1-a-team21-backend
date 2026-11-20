package com.example.customer_retail_system.system.catalog;

/** Read model of a product with its category's return policy. */
public record ProductInfo(
		long productId,
		String sku,
		String name,
		String categoryCode,
		String categoryName,
		boolean returnable,
		Integer returnWindowDays) {
}
