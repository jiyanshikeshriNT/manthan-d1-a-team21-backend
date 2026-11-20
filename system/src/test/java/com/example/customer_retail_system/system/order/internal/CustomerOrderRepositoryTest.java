package com.example.customer_retail_system.system.order.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CustomerOrderRepositoryTest {

	static final Instant ORDERED = Instant.parse("2026-11-01T06:30:00Z");
	static final Instant DELIVERED = Instant.parse("2026-11-04T09:15:00Z");

	@Autowired
	CustomerOrderRepository orders;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TestEntityManager em;

	long customerId;
	long productA;
	long productB;

	@BeforeEach
	void fixtures() {
		// Other modules' rows are created with plain SQL: this module only knows their ids.
		jdbc.update("INSERT INTO customer (email, name, created_at) VALUES ('ord.test@example.com', 'T', CURRENT_TIMESTAMP)");
		customerId = jdbc.queryForObject("SELECT id FROM customer WHERE email = 'ord.test@example.com'", Long.class);
		jdbc.update("INSERT INTO category (code, name, returnable, return_window_days) VALUES ('T_ORD', 'T', TRUE, 30)");
		long cat = jdbc.queryForObject("SELECT id FROM category WHERE code = 'T_ORD'", Long.class);
		jdbc.update("INSERT INTO product (sku, name, category_id, price_paise) VALUES ('T-ORD-A', 'A', ?, 50000)", cat);
		jdbc.update("INSERT INTO product (sku, name, category_id, price_paise) VALUES ('T-ORD-B', 'B', ?, 129900)", cat);
		productA = jdbc.queryForObject("SELECT id FROM product WHERE sku = 'T-ORD-A'", Long.class);
		productB = jdbc.queryForObject("SELECT id FROM product WHERE sku = 'T-ORD-B'", Long.class);
	}

	@Test
	void persistsOrderWithLinesAndLoadsThemByOrderNumber() {
		CustomerOrder order = new CustomerOrder("T-ORD-1", customerId, ORDERED, DELIVERED, PaymentMethod.UPI, "PAY-1");
		order.addLine(productA, 2, 50_000);
		order.addLine(productB, 1, 129_900);
		orders.saveAndFlush(order);
		em.clear();

		CustomerOrder found = orders.findByOrderNumber("T-ORD-1").orElseThrow();
		em.clear(); // lines must already be loaded by the entity graph
		assertThat(found.getCustomerId()).isEqualTo(customerId);
		assertThat(found.getOrderedAt()).isEqualTo(ORDERED);
		assertThat(found.getDeliveredAt()).isEqualTo(DELIVERED);
		assertThat(found.getPaymentMethod()).isEqualTo(PaymentMethod.UPI);
		assertThat(found.getLines()).extracting(OrderLine::getProductId, OrderLine::getQuantity, OrderLine::getUnitPricePaise)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(productA, 2, 50_000L),
						org.assertj.core.groups.Tuple.tuple(productB, 1, 129_900L));
	}

	@Test
	void undeliveredOrderHasNullDeliveryDate() {
		orders.saveAndFlush(new CustomerOrder("T-ORD-2", customerId, ORDERED, null, PaymentMethod.CARD, "PAY-2"));
		em.clear();
		assertThat(orders.findByOrderNumber("T-ORD-2").orElseThrow().getDeliveredAt()).isNull();
	}

	@Test
	void versionIncrementsOnUpdate() {
		CustomerOrder order = orders.saveAndFlush(
				new CustomerOrder("T-ORD-3", customerId, ORDERED, null, PaymentMethod.CARD, "PAY-3"));
		long before = order.getVersion();
		order.markDelivered(DELIVERED);
		orders.saveAndFlush(order);
		assertThat(order.getVersion()).isEqualTo(before + 1);
	}

	@Test
	void zeroQuantityLineIsRejected() {
		CustomerOrder order = new CustomerOrder("T-ORD-4", customerId, ORDERED, DELIVERED, PaymentMethod.CARD, "PAY-4");
		order.addLine(productA, 0, 50_000);
		assertThatThrownBy(() -> orders.saveAndFlush(order)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deliveryBeforeOrderIsRejected() {
		assertThatThrownBy(() -> orders.saveAndFlush(new CustomerOrder("T-ORD-5", customerId, DELIVERED, ORDERED,
				PaymentMethod.CARD, "PAY-5"))).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownOrderNumberIsEmpty() {
		assertThat(orders.findByOrderNumber("NOPE")).isEmpty();
	}
}
