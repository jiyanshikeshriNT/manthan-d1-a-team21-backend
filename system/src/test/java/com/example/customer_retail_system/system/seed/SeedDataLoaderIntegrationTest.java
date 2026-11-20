package com.example.customer_retail_system.system.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.example.customer_retail_system.system.order.internal.CustomerOrder;
import com.example.customer_retail_system.system.order.internal.CustomerOrderRepository;
import com.example.customer_retail_system.system.shared.AppProperties;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts the app with the {@code seed} profile on its own in-memory database (so seeded rows never
 * leak into other tests) and checks counts, determinism and rerun idempotency.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seedtest;MODE=PostgreSQL;"
		+ "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@ActiveProfiles({ "test", "seed" })
class SeedDataLoaderIntegrationTest {

	static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");

	@TestConfiguration
	static class FixedClock {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));
		}
	}

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SeedDataLoader loader;

	@Autowired
	AppProperties properties;

	@Autowired
	Clock clock;

	@Autowired
	CustomerOrderRepository orders;

	SeedData expected() {
		return SeedDataGenerator.generate(properties.seed().randomSeed(), clock, properties.zone());
	}

	@Test
	void seedsExactCountsOnStartup() {
		assertThat(count("category")).isEqualTo(6);
		assertThat(count("product")).isEqualTo(40);
		assertThat(count("customer")).isEqualTo(500);
		assertThat(count("customer_order")).isEqualTo(2_000);
		assertThat(count("order_line")).isEqualTo(expected().lineCount());
		assertThat(count("staff_user")).isEqualTo(3);
	}

	@Test
	void rerunIsANoOp() {
		List<Long> before = allCounts();
		assertThat(loader.seedIfEmpty()).isFalse();
		assertThat(allCounts()).isEqualTo(before);
	}

	@Test
	void persistedOrdersMatchTheDeterministicGenerator() {
		List<String> fromDb = jdbc.query("""
				SELECT o.order_number, c.email, o.delivered_at, o.payment_ref,
				       (SELECT COUNT(*) FROM order_line l WHERE l.order_id = o.id) AS line_count
				FROM customer_order o JOIN customer c ON c.id = o.customer_id
				ORDER BY o.order_number""",
				(rs, i) -> String.join("|", rs.getString(1), rs.getString(2),
						String.valueOf(toInstant(rs.getObject(3, OffsetDateTime.class))), rs.getString(4),
						String.valueOf(rs.getLong(5))));
		List<String> generated = expected().orders().stream()
				.map(o -> String.join("|", o.orderNumber(), o.customerEmail(), String.valueOf(o.deliveredAt()),
						o.paymentRef(), String.valueOf(o.lines().size())))
				.sorted()
				.toList();
		assertThat(fromDb).isEqualTo(generated);
	}

	@Test
	void staffUsersHaveBcryptHashOfConfiguredPassword() {
		List<String> hashes = jdbc.queryForList("SELECT password_hash FROM staff_user", String.class);
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
		assertThat(hashes).hasSize(3).allSatisfy(h -> {
			assertThat(h).startsWith("$2");
			assertThat(encoder.matches(properties.seed().staffPassword(), h)).isTrue();
		});
		assertThat(jdbc.queryForList("SELECT role FROM staff_user ORDER BY role", String.class))
				.containsExactly("FINANCE", "MANAGER", "WAREHOUSE");
	}

	@Test
	@Transactional
	void seededOrdersAreReadableThroughJpa() {
		CustomerOrder first = orders.findByOrderNumber("ORD-100001").orElseThrow();
		SeedData.OrderSeed seed = expected().orders().get(0);
		assertThat(first.getLines()).hasSize(seed.lines().size());
		assertThat(first.getDeliveredAt()).isEqualTo(seed.deliveredAt());
		assertThat(first.getOrderedAt()).isEqualTo(seed.orderedAt());
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
	}

	private List<Long> allCounts() {
		return List.of("category", "product", "customer", "customer_order", "order_line", "staff_user").stream()
				.map(this::count).toList();
	}

	private static Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
