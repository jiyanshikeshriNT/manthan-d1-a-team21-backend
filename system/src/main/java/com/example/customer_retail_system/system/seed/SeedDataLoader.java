package com.example.customer_retail_system.system.seed;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.shared.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the deterministic demo data set on startup when the {@code seed} profile is active.
 * Runs in a single transaction and is a no-op when any core data already exists, so restarts
 * and reruns never duplicate data. Uses plain JDBC so it depends on no other module's internals.
 */
@Component
@Profile("seed")
class SeedDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final AppProperties properties;

	SeedDataLoader(JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock, AppProperties properties) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(txManager);
		this.clock = clock;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (seedIfEmpty()) {
			log.info("Seed data loaded (random seed {})", properties.seed().randomSeed());
		}
		else {
			log.info("Seed data skipped: database already contains data");
		}
	}

	/** @return true if data was inserted, false if the database already had data. */
	boolean seedIfEmpty() {
		return Boolean.TRUE.equals(tx.execute(status -> {
			if (hasData()) {
				return false;
			}
			SeedData data = SeedDataGenerator.generate(properties.seed().randomSeed(), clock, properties.zone());
			persist(data);
			log.info("Seeded {} categories, {} products, {} customers, {} orders, {} order lines, {} staff users",
					data.categories().size(), data.products().size(), data.customers().size(),
					data.orders().size(), data.lineCount(), data.staff().size());
			return true;
		}));
	}

	private boolean hasData() {
		for (String table : List.of("category", "product", "customer", "customer_order", "staff_user")) {
			Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
			if (count != null && count > 0) {
				return true;
			}
		}
		return false;
	}

	private void persist(SeedData data) {
		jdbc.batchUpdate("INSERT INTO category (code, name, returnable, return_window_days) VALUES (?, ?, ?, ?)",
				data.categories().stream()
						.map(c -> new Object[] { c.code(), c.name(), c.returnable(), c.returnWindowDays() })
						.toList(),
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.BOOLEAN, Types.INTEGER });
		Map<String, Long> categoryIds = idsBy("SELECT code, id FROM category");

		jdbc.batchUpdate("INSERT INTO product (sku, name, category_id, price_paise) VALUES (?, ?, ?, ?)",
				data.products().stream()
						.map(p -> new Object[] { p.sku(), p.name(), categoryIds.get(p.categoryCode()), p.pricePaise() })
						.toList(),
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BIGINT });
		Map<String, Long> productIds = idsBy("SELECT sku, id FROM product");

		jdbc.batchUpdate("INSERT INTO customer (email, name, phone, created_at) VALUES (?, ?, ?, ?)",
				data.customers().stream()
						.map(c -> new Object[] { c.email(), c.name(), c.phone(), utc(c.createdAt()) })
						.toList(),
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP_WITH_TIMEZONE });
		Map<String, Long> customerIds = idsBy("SELECT email, id FROM customer");

		jdbc.batchUpdate("INSERT INTO customer_order (order_number, customer_id, ordered_at, delivered_at, "
				+ "payment_method, payment_ref, version) VALUES (?, ?, ?, ?, ?, ?, 0)",
				data.orders().stream()
						.map(o -> new Object[] { o.orderNumber(), customerIds.get(o.customerEmail()),
								utc(o.orderedAt()), utc(o.deliveredAt()), o.paymentMethod(), o.paymentRef() })
						.toList(),
				new int[] { Types.VARCHAR, Types.BIGINT, Types.TIMESTAMP_WITH_TIMEZONE,
						Types.TIMESTAMP_WITH_TIMEZONE, Types.VARCHAR, Types.VARCHAR });
		Map<String, Long> orderIds = idsBy("SELECT order_number, id FROM customer_order");

		jdbc.batchUpdate("INSERT INTO order_line (order_id, product_id, quantity, unit_price_paise) VALUES (?, ?, ?, ?)",
				data.orders().stream()
						.flatMap(o -> o.lines().stream().map(l -> new Object[] { orderIds.get(o.orderNumber()),
								productIds.get(l.sku()), l.quantity(), l.unitPricePaise() }))
						.toList(),
				new int[] { Types.BIGINT, Types.BIGINT, Types.INTEGER, Types.BIGINT });

		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
		String hash = encoder.encode(properties.seed().staffPassword());
		jdbc.batchUpdate("INSERT INTO staff_user (username, password_hash, role) VALUES (?, ?, ?)",
				data.staff().stream().map(s -> new Object[] { s.username(), hash, s.role() }).toList(),
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.VARCHAR });
	}

	private Map<String, Long> idsBy(String sql) {
		Map<String, Long> ids = new HashMap<>();
		jdbc.query(sql, rs -> {
			ids.put(rs.getString(1), rs.getLong(2));
		});
		return ids;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}
}
