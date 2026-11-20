package com.example.customer_retail_system.system.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.example.customer_retail_system.system.seed.SeedData.OrderSeed;
import com.example.customer_retail_system.system.seed.SeedData.ProductSeed;
import org.junit.jupiter.api.Test;

class SeedDataGeneratorTest {

	static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	// 00:20 IST: shortly after midnight, so "today" deliveries are clamped to now.
	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T18:50:00Z"), IST);
	static final long SEED = 20261120L;

	final SeedData data = SeedDataGenerator.generate(SEED, CLOCK, IST);

	@Test
	void producesExactCounts() {
		assertThat(data.categories()).hasSize(6);
		assertThat(data.products()).hasSize(40);
		assertThat(data.customers()).hasSize(500);
		assertThat(data.orders()).hasSize(2_000);
		assertThat(data.staff()).extracting(SeedData.StaffSeed::role)
				.containsExactlyInAnyOrder("WAREHOUSE", "FINANCE", "MANAGER");
	}

	@Test
	void isDeterministicForTheSameSeedAndClock() {
		assertThat(SeedDataGenerator.generate(SEED, CLOCK, IST)).isEqualTo(data);
		assertThat(SeedDataGenerator.generate(SEED + 1, CLOCK, IST)).isNotEqualTo(data);
	}

	@Test
	void categoryPoliciesMatchTheDesign() {
		Map<String, SeedData.CategorySeed> byCode = data.categories().stream()
				.collect(Collectors.toMap(SeedData.CategorySeed::code, Function.identity()));
		assertThat(byCode.get("ELECTRONICS").returnWindowDays()).isEqualTo(10);
		assertThat(byCode.get("APPAREL").returnWindowDays()).isEqualTo(30);
		assertThat(byCode.get("INNERWEAR").returnable()).isFalse();
		assertThat(byCode.get("INNERWEAR").returnWindowDays()).isNull();
		assertThat(byCode.get("PERISHABLES").returnable()).isFalse();
		assertThat(data.products()).extracting(ProductSeed::categoryCode).allMatch(byCode::containsKey);
		assertThat(data.products().stream().collect(Collectors.groupingBy(ProductSeed::categoryCode, Collectors.counting())))
				.containsEntry("ELECTRONICS", 8L).containsEntry("APPAREL", 10L).containsEntry("HOME", 8L)
				.containsEntry("BEAUTY", 6L).containsEntry("INNERWEAR", 4L).containsEntry("PERISHABLES", 4L);
	}

	@Test
	void electronicsPricesStraddleTheFinanceApprovalThreshold() {
		var electronics = data.products().stream().filter(p -> p.categoryCode().equals("ELECTRONICS")).toList();
		assertThat(electronics).anyMatch(p -> p.pricePaise() > 1_000_000);
		assertThat(electronics).anyMatch(p -> p.pricePaise() <= 1_000_000);
		assertThat(data.products()).allMatch(p -> p.pricePaise() > 0);
	}

	@Test
	void deliveryDatesCoverEveryWindowBoundaryAndNeverTheFuture() {
		Instant now = CLOCK.instant();
		LocalDate today = LocalDate.ofInstant(now, IST);
		Set<Long> daysAgo = data.orders().stream()
				.map(OrderSeed::deliveredAt).filter(Objects::nonNull)
				.peek(d -> assertThat(d).isBeforeOrEqualTo(now))
				.map(d -> ChronoUnit.DAYS.between(LocalDate.ofInstant(d, IST), today))
				.collect(Collectors.toSet());
		assertThat(daysAgo).containsAll(IntStream.rangeClosed(0, 60).mapToObj(Long::valueOf).toList());
		assertThat(daysAgo).contains(10L, 11L, 30L, 31L).allMatch(d -> d >= 0 && d <= 60);
	}

	@Test
	void everyTwentyFifthOrderIsUndelivered() {
		assertThat(data.orders().stream().filter(o -> o.deliveredAt() == null).count()).isEqualTo(80);
		assertThat(data.orders()).allMatch(o -> o.deliveredAt() == null || !o.deliveredAt().isBefore(o.orderedAt()));
	}

	@Test
	void ordersHaveValidDistinctLinesAtProductPrice() {
		Map<String, Long> prices = data.products().stream()
				.collect(Collectors.toMap(ProductSeed::sku, ProductSeed::pricePaise));
		for (OrderSeed o : data.orders()) {
			assertThat(o.lines()).hasSizeBetween(1, 3);
			assertThat(o.lines()).extracting(SeedData.LineSeed::sku).doesNotHaveDuplicates();
			assertThat(o.lines()).allSatisfy(l -> {
				assertThat(l.quantity()).isBetween(1, 3);
				assertThat(l.unitPricePaise()).isEqualTo(prices.get(l.sku()));
			});
		}
		assertThat(data.orders()).anyMatch(o -> o.lines().stream().anyMatch(l -> l.quantity() > 1));
	}

	@Test
	void identifiersAreUniqueAndEmailsLowerCase() {
		assertThat(data.orders()).extracting(OrderSeed::orderNumber).doesNotHaveDuplicates();
		assertThat(data.products()).extracting(ProductSeed::sku).doesNotHaveDuplicates();
		assertThat(data.customers()).extracting(SeedData.CustomerSeed::email)
				.doesNotHaveDuplicates()
				.allMatch(e -> e.equals(e.toLowerCase()));
		Set<String> emails = data.customers().stream().map(SeedData.CustomerSeed::email).collect(Collectors.toSet());
		assertThat(data.orders()).allMatch(o -> emails.contains(o.customerEmail()));
	}

	@Test
	void someCustomersHaveEnoughOrdersToExerciseFlagging() {
		Map<String, Long> ordersPerCustomer = data.orders().stream()
				.collect(Collectors.groupingBy(OrderSeed::customerEmail, Collectors.counting()));
		assertThat(ordersPerCustomer.values()).anyMatch(n -> n > 3);
	}
}
