package com.example.customer_retail_system.system.returns.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.example.customer_retail_system.system.TestData;
import com.jayway.jsonpath.JsonPath;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ReturnsApiTestBase {

	static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F' };

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	/**
	 * A fresh customer and an order delivered 5 days ago with: two shirts (qty 2, returnable),
	 * shoes (qty 1, returnable), briefs (innerwear, never returnable), a phone (3-day window, expired).
	 */
	record Fixture(String orderNumber, String email, long orderId, long customerId, String token,
			long shirts, long shoes, long briefs, long phone) {
	}

	Fixture fixture() throws Exception {
		TestData data = new TestData(jdbc);
		String email = TestData.uniqueEmail();
		String orderNumber = TestData.uniqueOrderNumber();
		long customerId = data.customer(email);
		long orderId = data.order(orderNumber, customerId, TestData.daysAgo(5));
		long apparel = data.category("Apparel", true, 30);
		long shirts = data.line(orderId, data.product(apparel, "Linen Shirt", 129_900), 2, 129_900);
		long shoes = data.line(orderId, data.product(apparel, "Running Shoes", 349_900), 1, 349_900);
		long briefs = data.line(orderId, data.product(data.category("Innerwear", false, null), "Briefs", 49_900), 1, 49_900);
		long phone = data.line(orderId, data.product(data.category("Electronics", true, 3), "Phone", 1_599_900), 1, 1_599_900);
		return new Fixture(orderNumber, email, orderId, customerId, TestData.token(mvc, orderNumber, email),
				shirts, shoes, briefs, phone);
	}

	static String item(long orderLineId, int qty, String reasonCode) {
		return "{\"orderLineId\":" + orderLineId + ",\"qty\":" + qty + ",\"reasonCode\":\"" + reasonCode + "\"}";
	}

	static String request(String orderNumber, String refundMethod, String... items) {
		return "{\"orderNumber\":\"" + orderNumber + "\",\"refundMethod\":\"" + refundMethod + "\",\"items\":["
				+ Arrays.stream(items).collect(Collectors.joining(",")) + "]}";
	}

	static MockMultipartFile photo(long orderLineId, byte[] content) {
		return new MockMultipartFile("photos_" + orderLineId, "photo.jpg", "image/jpeg", content);
	}

	ResultActions create(String token, String idempotencyKey, String requestJson, MockMultipartFile... photos)
			throws Exception {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/customer/returns");
		builder.file(new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, requestJson.getBytes()));
		for (MockMultipartFile p : photos) {
			builder.file(p);
		}
		builder.header("Authorization", "Bearer " + token);
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return mvc.perform(builder);
	}

	/** Creates a single-item return and returns its RMA. */
	String createReturn(Fixture f, long orderLineId, int qty) throws Exception {
		String body = create(f.token(), null, request(f.orderNumber(), "ORIGINAL_PAYMENT", item(orderLineId, qty, "WRONG_SIZE")))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.rma");
	}

	ResultActions getReturn(String token, String rma) throws Exception {
		return mvc.perform(get("/api/v1/customer/returns/" + rma).header("Authorization", "Bearer " + token));
	}

	ResultActions action(String token, String rma, String action) throws Exception {
		return mvc.perform(post("/api/v1/customer/returns/" + rma + "/" + action).header("Authorization", "Bearer " + token));
	}

	ResultActions getOrder(Fixture f) throws Exception {
		return mvc.perform(get("/api/v1/customer/orders/" + f.orderNumber()).header("Authorization", "Bearer " + f.token()));
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	String returnId(String rma) {
		return jdbc.queryForObject("SELECT CAST(id AS VARCHAR) FROM return_request WHERE rma_number = ?", String.class, rma);
	}
}
