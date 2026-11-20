package com.example.customer_retail_system.system.dashboard.internal;

import static com.example.customer_retail_system.system.ApiFlow.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.example.customer_retail_system.system.ApiFlow;
import com.example.customer_retail_system.system.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dashboard numbers on a fixed fixture (October 2026), in its own in-memory database so that no
 * other test's data can change the expected values.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/returnright_dashtest")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	static boolean loaded;
	static String manager;

	static OffsetDateTime at(String instant) {
		return Instant.parse(instant).atOffset(ZoneOffset.UTC);
	}

	UUID returnRequest(String rma, long orderId, long customerId, String status, String created, String closed) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO return_request (id, rma_number, order_id, customer_id, status, refund_method, flagged, "
				+ "label_code, created_at, updated_at, closed_at, version) VALUES (?, ?, ?, ?, ?, 'STORE_CREDIT', FALSE, ?, ?, ?, ?, 0)",
				id, rma, orderId, customerId, status, "RR-" + rma, at(created), at(created), closed == null ? null : at(closed));
		return id;
	}

	void item(UUID returnId, long orderLineId, int qty, String reason) {
		jdbc.update("INSERT INTO return_item (return_id, order_line_id, requested_qty, reason_code) VALUES (?, ?, ?, ?)",
				returnId, orderLineId, qty, reason);
	}

	@BeforeEach
	void fixture() {
		if (loaded) {
			return;
		}
		TestData data = new TestData(jdbc);
		manager = new ApiFlow(mvc, jdbc).staff("MANAGER");
		long apparel = data.product(data.category("Apparel", true, 30), "Shirt", 100_000);
		long home = data.product(data.category("Home", true, 30), "Kettle", 200_000);
		long beauty = data.product(data.category("Beauty", true, 30), "Serum", 50_000);
		long alice = data.customer("alice.dash@example.com");
		long bob = data.customer("bob.dash@example.com");

		long o1 = data.order("DASH-1", alice, Instant.parse("2026-10-07T06:00:00Z")); // ordered 5 Oct
		long l1 = data.line(o1, apparel, 4, 100_000);
		long l2 = data.line(o1, home, 2, 200_000);
		long o2 = data.order("DASH-2", bob, Instant.parse("2026-10-12T06:00:00Z")); // ordered 10 Oct
		data.line(o2, beauty, 5, 50_000);
		long o3 = data.order("DASH-3", alice, Instant.parse("2026-09-22T06:00:00Z")); // ordered 20 Sep: outside
		long l4 = data.line(o3, apparel, 10, 100_000);

		UUID r1 = returnRequest("RMA-D1", o1, alice, "REFUNDED", "2026-10-08T00:00:00Z", "2026-10-10T00:00:00Z"); // 48 h
		item(r1, l1, 1, "WRONG_SIZE");
		item(r1, l2, 2, "DAMAGED");
		UUID r2 = returnRequest("RMA-D2", o1, alice, "REJECTED", "2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z"); // 24 h
		item(r2, l1, 1, "WRONG_SIZE");
		UUID r3 = returnRequest("RMA-D3", o1, alice, "CANCELLED", "2026-10-14T00:00:00Z", "2026-10-14T01:00:00Z");
		item(r3, l1, 2, "CHANGED_MIND"); // cancelled: excluded everywhere
		UUID r4 = returnRequest("RMA-D4", o3, alice, "APPROVED", "2026-10-15T00:00:00Z", null);
		item(r4, l4, 3, "DEFECTIVE"); // order outside the range: counts as a reason, not in the return rate

		jdbc.update("INSERT INTO customer_flag (customer_id, return_id, return_count, flagged_at) VALUES (?, ?, 4, ?)",
				alice, r1, at("2026-10-08T00:00:00Z"));
		jdbc.update("INSERT INTO customer_flag (customer_id, return_id, return_count, flagged_at) VALUES (?, ?, 5, ?)",
				alice, r2, at("2026-10-12T00:00:00Z"));
		jdbc.update("INSERT INTO customer_flag (customer_id, return_id, return_count, flagged_at, cleared_at, cleared_by) "
				+ "VALUES (?, ?, 4, ?, ?, 'manager')", bob, r4, at("2026-10-15T00:00:00Z"), at("2026-10-16T00:00:00Z"));
		loaded = true;
	}

	@Test
	void dashboardNumbersMatchTheFixture() throws Exception {
		mvc.perform(get("/api/v1/manager/dashboard").param("from", "2026-10-01").param("to", "2026-10-31").with(as(manager)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.from").value("2026-10-01"))
				// Return rate: Apparel 2 of 4 (R1 + R2; cancelled R3 and the September order excluded), Home 2 of 2, Beauty 0 of 5.
				.andExpect(jsonPath("$.returnRateByCategory.length()").value(3))
				.andExpect(jsonPath("$.returnRateByCategory[0].category").value("Apparel"))
				.andExpect(jsonPath("$.returnRateByCategory[0].returnable").value(true))
				.andExpect(jsonPath("$.returnRateByCategory[0].soldUnits").value(4))
				.andExpect(jsonPath("$.returnRateByCategory[0].returnedUnits").value(2))
				.andExpect(jsonPath("$.returnRateByCategory[0].returnRate").value(0.5))
				.andExpect(jsonPath("$.returnRateByCategory[1].category").value("Beauty"))
				.andExpect(jsonPath("$.returnRateByCategory[1].returnRate").value(0.0))
				.andExpect(jsonPath("$.returnRateByCategory[2].category").value("Home"))
				.andExpect(jsonPath("$.returnRateByCategory[2].returnRate").value(1.0))
				// Reasons: WRONG_SIZE ×2, then DAMAGED and DEFECTIVE ×1 (CHANGED_MIND only on a cancelled return).
				.andExpect(jsonPath("$.topReasons.length()").value(3))
				.andExpect(jsonPath("$.topReasons[0].reasonCode").value("WRONG_SIZE"))
				.andExpect(jsonPath("$.topReasons[0].count").value(2))
				.andExpect(jsonPath("$.topReasons[1].reasonCode").value("DAMAGED"))
				.andExpect(jsonPath("$.topReasons[2].reasonCode").value("DEFECTIVE"))
				// Cycle time: REFUNDED 48 h and REJECTED 24 h → 36 h (the cancelled return does not count).
				.andExpect(jsonPath("$.cycleTime.closedReturns").value(2))
				.andExpect(jsonPath("$.cycleTime.averageHours").value(36.0))
				// Flagged: only Alice has active flags; Bob's flag was cleared.
				.andExpect(jsonPath("$.flaggedCustomers.length()").value(1))
				.andExpect(jsonPath("$.flaggedCustomers[0].maskedEmail").value("a***@example.com"))
				.andExpect(jsonPath("$.flaggedCustomers[0].activeFlags").value(2))
				.andExpect(jsonPath("$.flaggedCustomers[0].highestReturnCount").value(5));
	}

	@Test
	void rangeOutsideTheFixtureIsEmptyAndBadRangesAreRejected() throws Exception {
		mvc.perform(get("/api/v1/manager/dashboard").param("from", "2026-08-01").param("to", "2026-08-31").with(as(manager)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.returnRateByCategory.length()").value(0))
				.andExpect(jsonPath("$.topReasons.length()").value(0))
				.andExpect(jsonPath("$.cycleTime.closedReturns").value(0))
				.andExpect(jsonPath("$.cycleTime.averageHours").doesNotExist());
		mvc.perform(get("/api/v1/manager/dashboard").param("from", "2026-10-31").param("to", "2026-10-01").with(as(manager)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/manager/dashboard").param("from", "2024-01-01").param("to", "2026-10-01").with(as(manager)))
				.andExpect(status().isBadRequest());
	}
}
