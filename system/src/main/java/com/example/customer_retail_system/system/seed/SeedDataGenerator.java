package com.example.customer_retail_system.system.seed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import com.example.customer_retail_system.system.seed.SeedData.CategorySeed;
import com.example.customer_retail_system.system.seed.SeedData.CustomerSeed;
import com.example.customer_retail_system.system.seed.SeedData.LineSeed;
import com.example.customer_retail_system.system.seed.SeedData.OrderSeed;
import com.example.customer_retail_system.system.seed.SeedData.ProductSeed;
import com.example.customer_retail_system.system.seed.SeedData.StaffSeed;

/**
 * Pure, deterministic demo-data generator (G19): the same random seed and clock always produce
 * the same data. Delivery dates cover every day from 0 to 60 days ago, so both sides of every
 * return-window boundary (10/11 and 30/31 days) are present; every 25th order is undelivered.
 */
final class SeedDataGenerator {

	static final int CUSTOMER_COUNT = 500;
	static final int ORDER_COUNT = 2_000;
	static final int MAX_DELIVERY_DAYS_AGO = 60;
	static final int UNDELIVERED_EVERY = 25;

	private static final List<CategorySeed> CATEGORIES = List.of(
			new CategorySeed("ELECTRONICS", "Electronics", true, 10),
			new CategorySeed("APPAREL", "Apparel", true, 30),
			new CategorySeed("HOME", "Home & Kitchen", true, 30),
			new CategorySeed("BEAUTY", "Beauty", true, 30),
			new CategorySeed("INNERWEAR", "Innerwear", false, null),
			new CategorySeed("PERISHABLES", "Perishables", false, null));

	/** name, category, min and max price in whole rupees. */
	private record ProductSpec(String name, String category, String skuPrefix, int minRupees, int maxRupees) {
	}

	private static final List<ProductSpec> PRODUCTS = List.of(
			// Electronics: first four below the ₹10,000 approval threshold, last four above it (G6).
			new ProductSpec("Wireless Earbuds", "ELECTRONICS", "ELE", 999, 4_999),
			new ProductSpec("Bluetooth Speaker", "ELECTRONICS", "ELE", 1_499, 6_999),
			new ProductSpec("Fitness Smartwatch", "ELECTRONICS", "ELE", 2_999, 9_499),
			new ProductSpec("Noise-Cancelling Headphones", "ELECTRONICS", "ELE", 4_999, 9_899),
			new ProductSpec("Android Smartphone", "ELECTRONICS", "ELE", 12_999, 39_999),
			new ProductSpec("13-inch Laptop", "ELECTRONICS", "ELE", 45_999, 79_999),
			new ProductSpec("4K Action Camera", "ELECTRONICS", "ELE", 10_999, 24_999),
			new ProductSpec("10-inch Tablet", "ELECTRONICS", "ELE", 14_999, 29_999),
			new ProductSpec("Cotton Kurta", "APPAREL", "APP", 499, 1_999),
			new ProductSpec("Slim-Fit Jeans", "APPAREL", "APP", 899, 2_999),
			new ProductSpec("Linen Shirt", "APPAREL", "APP", 799, 2_499),
			new ProductSpec("Running Shoes", "APPAREL", "APP", 1_499, 4_999),
			new ProductSpec("Denim Jacket", "APPAREL", "APP", 1_299, 3_999),
			new ProductSpec("Silk Saree", "APPAREL", "APP", 1_999, 4_999),
			new ProductSpec("Polo T-Shirt", "APPAREL", "APP", 399, 1_299),
			new ProductSpec("Chino Trousers", "APPAREL", "APP", 699, 2_199),
			new ProductSpec("Woollen Sweater", "APPAREL", "APP", 999, 2_999),
			new ProductSpec("Leather Sandals", "APPAREL", "APP", 599, 1_999),
			new ProductSpec("Non-Stick Cookware Set", "HOME", "HOM", 1_499, 5_999),
			new ProductSpec("Electric Kettle", "HOME", "HOM", 699, 1_999),
			new ProductSpec("Cotton Bedsheet Set", "HOME", "HOM", 799, 2_499),
			new ProductSpec("Ceramic Dinner Set", "HOME", "HOM", 1_999, 6_999),
			new ProductSpec("Table Lamp", "HOME", "HOM", 499, 1_999),
			new ProductSpec("Mixer Grinder", "HOME", "HOM", 2_499, 14_999),
			new ProductSpec("Wall Clock", "HOME", "HOM", 299, 1_499),
			new ProductSpec("Storage Containers (Set of 6)", "HOME", "HOM", 299, 999),
			new ProductSpec("Vitamin C Face Serum", "BEAUTY", "BEA", 349, 1_299),
			new ProductSpec("Hair Dryer", "BEAUTY", "BEA", 999, 2_999),
			new ProductSpec("Sunscreen SPF 50", "BEAUTY", "BEA", 149, 699),
			new ProductSpec("Beard Trimmer", "BEAUTY", "BEA", 799, 2_499),
			new ProductSpec("Lipstick Set", "BEAUTY", "BEA", 299, 1_499),
			new ProductSpec("Perfume 100 ml", "BEAUTY", "BEA", 599, 2_999),
			new ProductSpec("Cotton Briefs (Pack of 3)", "INNERWEAR", "INW", 199, 699),
			new ProductSpec("Sports Bra", "INNERWEAR", "INW", 399, 1_499),
			new ProductSpec("Thermal Vest", "INNERWEAR", "INW", 299, 999),
			new ProductSpec("Boxer Shorts (Pack of 2)", "INNERWEAR", "INW", 299, 899),
			new ProductSpec("Alphonso Mangoes (1 kg)", "PERISHABLES", "PER", 399, 999),
			new ProductSpec("Assorted Dry Fruits (500 g)", "PERISHABLES", "PER", 449, 999),
			new ProductSpec("Artisan Chocolate Box", "PERISHABLES", "PER", 299, 899),
			new ProductSpec("Darjeeling Tea (250 g)", "PERISHABLES", "PER", 99, 599));

	private static final List<String> FIRST_NAMES = List.of(
			"Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Krishna", "Ishaan", "Rohan",
			"Ananya", "Diya", "Aadhya", "Saanvi", "Pari", "Meera", "Kavya", "Priya", "Neha", "Asha");

	private static final List<String> LAST_NAMES = List.of(
			"Sharma", "Verma", "Iyer", "Reddy", "Nair", "Patel", "Gupta", "Mehta", "Rao", "Das",
			"Kulkarni", "Joshi", "Menon", "Singh", "Chopra", "Bose", "Pillai", "Malhotra", "Shetty", "Khan");

	private static final List<String> PAYMENT_METHODS = List.of("CARD", "UPI", "NETBANKING", "WALLET");

	private static final List<StaffSeed> STAFF = List.of(
			new StaffSeed("warehouse", "WAREHOUSE"),
			new StaffSeed("finance", "FINANCE"),
			new StaffSeed("manager", "MANAGER"));

	private SeedDataGenerator() {
	}

	static SeedData generate(long randomSeed, Clock clock, ZoneId zone) {
		Random rng = new Random(randomSeed);
		Instant now = clock.instant();
		LocalDate today = LocalDate.ofInstant(now, zone);

		List<ProductSeed> products = products(rng);
		List<CustomerSeed> customers = customers(rng, now);
		List<OrderSeed> orders = orders(rng, now, today, zone, products, customers);
		return new SeedData(CATEGORIES, products, customers, orders, STAFF);
	}

	private static List<ProductSeed> products(Random rng) {
		List<ProductSeed> products = new ArrayList<>(PRODUCTS.size());
		int[] perPrefix = new int[PRODUCTS.size()];
		List<String> prefixes = new ArrayList<>();
		for (ProductSpec spec : PRODUCTS) {
			int idx = prefixes.indexOf(spec.skuPrefix());
			if (idx < 0) {
				prefixes.add(spec.skuPrefix());
				idx = prefixes.size() - 1;
			}
			String sku = String.format(Locale.ROOT, "%s-%03d", spec.skuPrefix(), ++perPrefix[idx]);
			int rupees = spec.minRupees() + rng.nextInt(spec.maxRupees() - spec.minRupees() + 1);
			rupees = Math.min(rupees / 10 * 10 + 9, spec.maxRupees()); // retail-style ₹…9 prices
			products.add(new ProductSeed(sku, spec.name(), spec.category(), rupees * 100L));
		}
		return List.copyOf(products);
	}

	private static List<CustomerSeed> customers(Random rng, Instant now) {
		List<CustomerSeed> customers = new ArrayList<>(CUSTOMER_COUNT);
		for (int i = 0; i < CUSTOMER_COUNT; i++) {
			String first = FIRST_NAMES.get(rng.nextInt(FIRST_NAMES.size()));
			String last = LAST_NAMES.get(rng.nextInt(LAST_NAMES.size()));
			String email = (first + "." + last + "." + (i + 1) + "@example.com").toLowerCase(Locale.ROOT);
			String phone = String.format(Locale.ROOT, "+91%d%09d", 6 + rng.nextInt(4), rng.nextInt(1_000_000_000));
			Instant createdAt = now.minus(Duration.ofDays(MAX_DELIVERY_DAYS_AGO + 10 + rng.nextInt(340)))
					.minusSeconds(rng.nextInt(86_400));
			customers.add(new CustomerSeed(email, first + " " + last, phone, createdAt));
		}
		return List.copyOf(customers);
	}

	private static List<OrderSeed> orders(Random rng, Instant now, LocalDate today, ZoneId zone,
			List<ProductSeed> products, List<CustomerSeed> customers) {
		List<OrderSeed> orders = new ArrayList<>(ORDER_COUNT);
		for (int i = 0; i < ORDER_COUNT; i++) {
			CustomerSeed customer = customers.get(rng.nextInt(customers.size()));
			Instant orderedAt;
			Instant deliveredAt;
			if (i % UNDELIVERED_EVERY == UNDELIVERED_EVERY - 1) {
				deliveredAt = null;
				orderedAt = now.minusSeconds(rng.nextInt(3 * 86_400));
			}
			else {
				// i % 61 walks every day 0..60, guaranteeing each window boundary appears.
				int daysAgo = i % (MAX_DELIVERY_DAYS_AGO + 1);
				Instant candidate = today.minusDays(daysAgo).atTime(8, 0).plusMinutes(rng.nextInt(12 * 60))
						.atZone(zone).toInstant();
				deliveredAt = candidate.isAfter(now) ? now : candidate;
				orderedAt = deliveredAt.minus(Duration.ofDays(2 + rng.nextInt(6))).minusSeconds(rng.nextInt(86_400));
			}
			String paymentMethod = PAYMENT_METHODS.get(rng.nextInt(PAYMENT_METHODS.size()));
			String paymentRef = String.format(Locale.ROOT, "PAY-%012X", rng.nextLong() & 0xFFFF_FFFF_FFFFL);

			int lineCount = 1 + rng.nextInt(3);
			Set<Integer> picked = new LinkedHashSet<>();
			while (picked.size() < lineCount) {
				picked.add(rng.nextInt(products.size()));
			}
			List<LineSeed> lines = new ArrayList<>(lineCount);
			for (int idx : picked) {
				ProductSeed p = products.get(idx);
				int qty = rng.nextInt(10) < 8 ? 1 : 2 + rng.nextInt(2);
				lines.add(new LineSeed(p.sku(), qty, p.pricePaise()));
			}
			orders.add(new OrderSeed("ORD-" + (100_001 + i), customer.email(), orderedAt, deliveredAt,
					paymentMethod, paymentRef, List.copyOf(lines)));
		}
		return List.copyOf(orders);
	}
}
