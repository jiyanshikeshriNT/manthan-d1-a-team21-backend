package com.example.customer_retail_system.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.jayway.jsonpath.JsonPath;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Committed test fixtures written with plain SQL. The test H2 database is shared by every Spring
 * context in the JVM, so every identifier is unique per call and tests never assume an empty table.
 */
public final class TestData {

	private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
	private static final AtomicInteger IP = new AtomicInteger();

	private final JdbcTemplate jdbc;

	public TestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public static long next() {
		return SEQ.incrementAndGet();
	}

	/** A fresh client IP so the per-IP session rate limit never couples tests together. */
	public static String nextIp() {
		int n = IP.incrementAndGet();
		return "10." + ((n >> 16) & 255) + "." + ((n >> 8) & 255) + "." + (n & 255);
	}

	public long category(String name, boolean returnable, Integer windowDays) {
		String code = name.toUpperCase().replaceAll("[^A-Z]", "") + "_" + next();
		return insert("INSERT INTO category (code, name, returnable, return_window_days) VALUES (?, ?, ?, ?)",
				new Object[] { code, name, returnable, windowDays },
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.BOOLEAN, Types.INTEGER });
	}

	public long product(long categoryId, String name, long pricePaise) {
		return insert("INSERT INTO product (sku, name, category_id, price_paise) VALUES (?, ?, ?, ?)",
				new Object[] { "T-" + next(), name, categoryId, pricePaise },
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BIGINT });
	}

	public long customer(String email) {
		return insert("INSERT INTO customer (email, name, phone, created_at) VALUES (?, ?, ?, ?)",
				new Object[] { email, "Test Customer", "+919800000000", Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC) },
				new int[] { Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP_WITH_TIMEZONE });
	}

	public static String uniqueEmail() {
		return "cust" + next() + "@example.com";
	}

	public static String uniqueOrderNumber() {
		return "TORD-" + next();
	}

	/** An order; {@code deliveredAt} null means undelivered. Ordered two days before delivery. */
	public long order(String orderNumber, long customerId, Instant deliveredAt) {
		Instant orderedAt = (deliveredAt != null ? deliveredAt : Instant.now()).minus(Duration.ofDays(2));
		return insert("INSERT INTO customer_order (order_number, customer_id, ordered_at, delivered_at, payment_method, "
				+ "payment_ref, version) VALUES (?, ?, ?, ?, 'UPI', ?, 0)",
				new Object[] { orderNumber, customerId, orderedAt.atOffset(ZoneOffset.UTC),
						deliveredAt == null ? null : deliveredAt.atOffset(ZoneOffset.UTC), "PAY-" + next() },
				new int[] { Types.VARCHAR, Types.BIGINT, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE,
						Types.VARCHAR });
	}

	public long line(long orderId, long productId, int quantity, long unitPricePaise) {
		return insert("INSERT INTO order_line (order_id, product_id, quantity, unit_price_paise) VALUES (?, ?, ?, ?)",
				new Object[] { orderId, productId, quantity, unitPricePaise },
				new int[] { Types.BIGINT, Types.BIGINT, Types.INTEGER, Types.BIGINT });
	}

	public void staffUser(String username, String rawPassword, String role) {
		jdbc.update("INSERT INTO staff_user (username, password_hash, role) VALUES (?, ?, ?)",
				username, new BCryptPasswordEncoder().encode(rawPassword), role);
	}

	public static Instant daysAgo(int days) {
		return Instant.now().minus(Duration.ofDays(days));
	}

	/** Opens a customer session over HTTP (fresh IP) and returns the bearer token. */
	public static String token(MockMvc mvc, String orderNumber, String email) throws Exception {
		String body = mvc.perform(post("/api/v1/customer/session")
				.with(r -> { r.setRemoteAddr(nextIp()); return r; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderNumber\":\"" + orderNumber + "\",\"email\":\"" + email + "\"}"))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private long insert(String sql, Object[] args, int[] types) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(sql, new String[] { "id" });
			for (int i = 0; i < args.length; i++) {
				if (args[i] == null) {
					ps.setNull(i + 1, types[i]);
				}
				else {
					ps.setObject(i + 1, args[i], types[i]);
				}
			}
			return ps;
		}, keys);
		return keys.getKey().longValue();
	}
}
