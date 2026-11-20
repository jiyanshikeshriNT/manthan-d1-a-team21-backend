package com.example.customer_retail_system.system.returns.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.customer_retail_system.system.TestData;
import com.example.customer_retail_system.system.returns.ReasonCode;
import com.example.customer_retail_system.system.returns.RefundMethod;
import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.CreateReturnRequest;
import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.CustomerPrincipal;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

class ReturnCreationIntegrationTest extends ReturnsApiTestBase {

	@Autowired
	ReturnService service;

	@Autowired
	AppProperties properties;

	@Test
	void eligibleRequestIsAutoApprovedWithLabelTimelineAndPhoto() throws Exception {
		Fixture f = fixture();
		String body = create(f.token(), null, request(f.orderNumber(), "ORIGINAL_PAYMENT", item(f.shirts(), 1, "WRONG_SIZE")),
				photo(f.shirts(), JPEG))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/customer/returns/RMA-")))
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.statusLabel").value("Approved"))
				.andExpect(jsonPath("$.orderNumber").value(f.orderNumber()))
				.andExpect(jsonPath("$.refundMethod").value("ORIGINAL_PAYMENT"))
				.andExpect(jsonPath("$.labelCode").value(org.hamcrest.Matchers.matchesPattern("RR-RMA-\\d+-[0-9a-f]{8}")))
				.andExpect(jsonPath("$.items[0].orderLineId").value(f.shirts()))
				.andExpect(jsonPath("$.items[0].photoCount").value(1))
				.andExpect(jsonPath("$.timeline[0].step").value("Requested"))
				.andExpect(jsonPath("$.timeline[0].state").value("done"))
				.andExpect(jsonPath("$.timeline[1].step").value("Approved"))
				.andExpect(jsonPath("$.timeline[1].state").value("current"))
				.andExpect(jsonPath("$.timeline[2].state").value("pending"))
				.andExpect(jsonPath("$.timeline.length()").value(6))
				.andReturn().getResponse().getContentAsString();
		String rma = JsonPath.read(body, "$.rma");
		String id = returnId(rma);

		// Audit: one row for creation, one for the transition — both in the same transaction.
		List<Map<String, Object>> audit = jdbc.queryForList(
				"SELECT action, from_state, to_state, actor FROM audit_log WHERE entity_type = 'RETURN' AND entity_id = ? ORDER BY id", id);
		assertThat(audit).extracting(r -> r.get("action") + ":" + r.get("from_state") + "->" + r.get("to_state"))
				.containsExactly("CREATED:null->REQUESTED", "STATUS_CHANGED:REQUESTED->APPROVED");
		assertThat(audit.get(0).get("actor")).isEqualTo("customer:" + f.customerId());

		// Photo stored under a server-generated name.
		String stored = jdbc.queryForObject("SELECT p.stored_name FROM return_photo p JOIN return_item i "
				+ "ON i.id = p.return_item_id WHERE i.return_id = CAST(? AS UUID)", String.class, id);
		assertThat(Path.of(properties.storage().dir()).resolve(stored)).exists();

		// The order view now shows one unit left (ReturnedQuantities port implemented by returns).
		getOrder(f).andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.returnableQty", f.shirts()).value(1));
	}

	@Test
	void mixedBasketWithAnIneligibleItemIsRefusedWithPerItemReasons() throws Exception {
		Fixture f = fixture();
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT",
				item(f.shirts(), 1, "WRONG_SIZE"), item(f.briefs(), 1, "DAMAGED"), item(f.phone(), 1, "DEFECTIVE")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("ITEM_NOT_RETURNABLE"))
				.andExpect(jsonPath("$.detail").value(ReturnService.NOT_RETURNABLE_COPY))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[?(@.orderLineId == %d)].code", f.briefs()).value("CATEGORY_NOT_RETURNABLE"))
				.andExpect(jsonPath("$.items[?(@.orderLineId == %d)].code", f.phone()).value("WINDOW_EXPIRED"))
				.andExpect(jsonPath("$.items[?(@.orderLineId == %d)].message", f.phone())
						.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.startsWith("The 3-day return window"))));
		assertThat(count("SELECT COUNT(*) FROM return_request WHERE order_id = ?", f.orderId())).isZero();
	}

	@Test
	void quantityAboveWhatIsReturnableIsRefused() throws Exception {
		Fixture f = fixture();
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT", item(f.shirts(), 3, "WRONG_SIZE")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.items[0].code").value("QUANTITY_EXCEEDED"))
				.andExpect(jsonPath("$.items[0].message").value("Only 2 units of this item can be returned."));
	}

	@Test
	void fourthReturnRequestInThirtyDaysIsFlaggedForReview() throws Exception {
		TestData data = new TestData(jdbc);
		String email = TestData.uniqueEmail();
		String orderNumber = TestData.uniqueOrderNumber();
		long customerId = data.customer(email);
		long orderId = data.order(orderNumber, customerId, TestData.daysAgo(2));
		long cat = data.category("Home", true, 30);
		long[] lines = new long[4];
		for (int i = 0; i < 4; i++) {
			lines[i] = data.line(orderId, data.product(cat, "Item " + i, 50_000), 1, 50_000);
		}
		String token = TestData.token(mvc, orderNumber, email);

		for (int i = 0; i < 3; i++) {
			create(token, null, request(orderNumber, "STORE_CREDIT", item(lines[i], 1, "CHANGED_MIND")))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.status").value("APPROVED"));
		}
		String body = create(token, null, request(orderNumber, "STORE_CREDIT", item(lines[3], 1, "CHANGED_MIND")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
				.andExpect(jsonPath("$.statusLabel").value("Requested"))
				.andExpect(jsonPath("$.labelCode").doesNotExist())
				.andExpect(jsonPath("$.timeline[0].state").value("current"))
				.andExpect(jsonPath("$.timeline[0].note").value("Under review"))
				.andReturn().getResponse().getContentAsString();
		String rma = JsonPath.read(body, "$.rma");

		assertThat(jdbc.queryForObject("SELECT flagged FROM return_request WHERE rma_number = ?", Boolean.class, rma)).isTrue();
		List<Map<String, Object>> flags = jdbc.queryForList(
				"SELECT return_count, CAST(return_id AS VARCHAR) AS rid FROM customer_flag WHERE customer_id = ?", customerId);
		assertThat(flags).singleElement().satisfies(fl -> {
			assertThat(((Number) fl.get("return_count")).intValue()).isEqualTo(4);
			assertThat(fl.get("rid")).isEqualTo(returnId(rma));
		});
		assertThat(count("SELECT COUNT(*) FROM return_request WHERE customer_id = ? AND flagged = FALSE", customerId)).isEqualTo(3);
	}

	@Test
	void replayingTheSameIdempotencyKeyReturnsTheSameReturn() throws Exception {
		Fixture f = fixture();
		String json = request(f.orderNumber(), "ORIGINAL_PAYMENT", item(f.shoes(), 1, "DEFECTIVE"));
		String first = create(f.token(), "submit-123", json).andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andReturn().getResponse().getContentAsString();
		String second = create(f.token(), "submit-123", json).andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString();

		assertThat((String) JsonPath.read(second, "$.rma")).isEqualTo(JsonPath.read(first, "$.rma"));
		assertThat(count("SELECT COUNT(*) FROM return_request WHERE order_id = ?", f.orderId())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", returnId(JsonPath.read(first, "$.rma")))).isEqualTo(2);
	}

	@Test
	void idempotencyKeysAreScopedPerOrder() throws Exception {
		Fixture a = fixture();
		Fixture b = fixture();
		String rmaA = JsonPath.read(create(a.token(), "same-key", request(a.orderNumber(), "STORE_CREDIT", item(a.shoes(), 1, "DEFECTIVE")))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.rma");
		String rmaB = JsonPath.read(create(b.token(), "same-key", request(b.orderNumber(), "STORE_CREDIT", item(b.shoes(), 1, "DEFECTIVE")))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.rma");
		assertThat(rmaB).isNotEqualTo(rmaA);
	}

	@Test
	void concurrentCreatesCannotOverReturnALine() throws Exception {
		Fixture f = fixture(); // shoes: quantity 1
		CustomerPrincipal principal = new CustomerPrincipal(f.orderId());
		CreateReturnRequest request = new CreateReturnRequest(f.orderNumber(), RefundMethod.STORE_CREDIT,
				List.of(new CreateReturnRequest.Item(f.shoes(), 1, ReasonCode.DEFECTIVE, null)));

		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			String key = "race-" + i; // different keys: these are genuinely separate requests
			Callable<String> call = () -> {
				start.await();
				try {
					return service.create(principal, key, request, Map.of()).body().rma();
				}
				catch (DomainException ex) {
					return ex.code().name() + ":" + ((List<?>) ex.properties().get("items")).toString();
				}
			};
			results.add(pool.submit(call));
		}
		start.countDown();
		List<String> outcomes = new ArrayList<>();
		for (Future<String> r : results) {
			outcomes.add(r.get(30, TimeUnit.SECONDS));
		}
		pool.shutdown();

		assertThat(outcomes.stream().filter(o -> o.startsWith("RMA-"))).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o.startsWith(ErrorCode.ITEM_NOT_RETURNABLE.name())))
				.hasSize(threads - 1)
				.allMatch(o -> o.contains("ALREADY_IN_RETURN"));
		assertThat(count("SELECT COALESCE(SUM(requested_qty), 0) FROM return_item WHERE order_line_id = ?", f.shoes())).isEqualTo(1);
	}

	@Test
	void invalidPhotoRejectsTheWholeRequestAndLeavesNoFileBehind() throws Exception {
		Fixture f = fixture();
		long filesBefore = filesInStorage();
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT", item(f.shirts(), 1, "DAMAGED"), item(f.shoes(), 1, "DAMAGED")),
				photo(f.shirts(), JPEG), photo(f.shoes(), "not an image".getBytes()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PHOTO_INVALID"))
				.andExpect(jsonPath("$.detail").value("Photos must be JPEG or PNG images."));
		assertThat(count("SELECT COUNT(*) FROM return_request WHERE order_id = ?", f.orderId())).isZero();
		assertThat(filesInStorage()).isEqualTo(filesBefore); // the valid JPEG written first was cleaned up on rollback
	}

	@Test
	void moreThanThreePhotosPerItemIsRefused() throws Exception {
		Fixture f = fixture();
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT", item(f.shirts(), 1, "DAMAGED")),
				photo(f.shirts(), JPEG), photo(f.shirts(), JPEG), photo(f.shirts(), JPEG), photo(f.shirts(), JPEG))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PHOTO_INVALID"))
				.andExpect(jsonPath("$.detail").value("You can add up to 3 photos per item."));
	}

	@Test
	void requestShapeIsValidated() throws Exception {
		Fixture f = fixture();
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT", item(f.shirts(), 1, "OTHER")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT", item(f.shirts(), 1, "WRONG_SIZE"), item(f.shirts(), 1, "DAMAGED")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Each item can appear only once in a return."));
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT", item(999_999_999L, 1, "WRONG_SIZE")))
				.andExpect(status().isBadRequest());
		create(f.token(), null, request(f.orderNumber(), "STORE_CREDIT", item(f.shirts(), 0, "WRONG_SIZE")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		create(f.token(), null, request(f.orderNumber(), "CASH", item(f.shirts(), 1, "WRONG_SIZE")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(count("SELECT COUNT(*) FROM return_request WHERE order_id = ?", f.orderId())).isZero();
	}

	@Test
	void requestForAnotherOrderThanTheTokensIsNotFound() throws Exception {
		Fixture mine = fixture();
		Fixture other = fixture();
		create(mine.token(), null, request(other.orderNumber(), "STORE_CREDIT", item(other.shoes(), 1, "DEFECTIVE")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
	}

	private long filesInStorage() throws Exception {
		Path dir = Path.of(properties.storage().dir());
		if (!Files.exists(dir)) {
			return 0;
		}
		try (var files = Files.list(dir)) {
			return files.count();
		}
	}
}
