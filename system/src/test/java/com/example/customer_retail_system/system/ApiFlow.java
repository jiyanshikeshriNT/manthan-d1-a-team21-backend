package com.example.customer_retail_system.system;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Drives the real HTTP API through a whole return (customer → warehouse → finance → manager) for
 * integration tests. Every fixture is unique, so tests sharing the H2 database never collide.
 */
public final class ApiFlow {

	public static final String STAFF_PASSWORD = "Staff#Pass-1";

	private final MockMvc mvc;
	private final JdbcTemplate jdbc;
	private final TestData data;

	public ApiFlow(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.data = new TestData(jdbc);
	}

	public record Line(String product, String category, boolean returnable, Integer windowDays, int qty,
			long unitPricePaise) {

		public static Line of(String product, int qty, long unitPricePaise) {
			return new Line(product, "Apparel", true, 30, qty, unitPricePaise);
		}
	}

	/** An order with its customer's bearer token and the order-line ids, in the order given. */
	public record Order(String number, String email, long id, long customerId, String token, List<Long> lines) {
	}

	public record Item(long orderLineId, int qty, String reason) {
	}

	/** grade A/B/C, decision ACCEPTED/REJECTED; acceptedQty may be null. */
	public record Verdict(long itemId, String grade, String decision, Integer acceptedQty) {
	}

	public String staff(String role) {
		String username = role.toLowerCase() + TestData.next();
		data.staffUser(username, STAFF_PASSWORD, role);
		return username;
	}

	public static RequestPostProcessor as(String username) {
		return httpBasic(username, STAFF_PASSWORD);
	}

	public Order order(Instant deliveredAt, Line... lines) throws Exception {
		String email = TestData.uniqueEmail();
		String number = TestData.uniqueOrderNumber();
		long customerId = data.customer(email);
		long orderId = data.order(number, customerId, deliveredAt);
		List<Long> ids = Arrays.stream(lines).map(l -> data.line(orderId,
				data.product(data.category(l.category(), l.returnable(), l.windowDays()), l.product(), l.unitPricePaise()),
				l.qty(), l.unitPricePaise())).toList();
		return new Order(number, email, orderId, customerId, TestData.token(mvc, number, email), ids);
	}

	public Order order(Line... lines) throws Exception {
		return order(TestData.daysAgo(3), lines);
	}

	public ResultActions requestReturn(Order o, String refundMethod, Item... items) throws Exception {
		String json = "{\"orderNumber\":\"" + o.number() + "\",\"refundMethod\":\"" + refundMethod + "\",\"items\":["
				+ Arrays.stream(items).map(i -> "{\"orderLineId\":" + i.orderLineId() + ",\"qty\":" + i.qty()
						+ ",\"reasonCode\":\"" + i.reason() + "\"}").collect(Collectors.joining(",")) + "]}";
		return mvc.perform(multipart("/api/v1/customer/returns")
				.file(new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes()))
				.header("Authorization", "Bearer " + o.token()));
	}

	/** Creates a return and returns its RMA. */
	public String createReturn(Order o, String refundMethod, Item... items) throws Exception {
		return JsonPath.read(requestReturn(o, refundMethod, items).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.rma");
	}

	public void ship(Order o, String rma) throws Exception {
		mvc.perform(post("/api/v1/customer/returns/" + rma + "/ship").header("Authorization", "Bearer " + o.token()))
				.andExpect(status().isOk());
	}

	public String labelCode(Order o, String rma) throws Exception {
		return JsonPath.read(mvc.perform(get("/api/v1/customer/returns/" + rma + "/label")
				.header("Authorization", "Bearer " + o.token())).andReturn().getResponse().getContentAsString(), "$.labelCode");
	}

	public ResultActions receive(String warehouseUser, String rma) throws Exception {
		return mvc.perform(post("/api/v1/warehouse/returns/" + rma + "/receive").with(as(warehouseUser)));
	}

	public ResultActions inspect(String warehouseUser, String rma, Verdict... verdicts) throws Exception {
		String json = "{\"items\":[" + Arrays.stream(verdicts).map(v -> "{\"itemId\":" + v.itemId() + ",\"grade\":\""
				+ v.grade() + "\",\"decision\":\"" + v.decision() + "\""
				+ (v.acceptedQty() == null ? "" : ",\"acceptedQty\":" + v.acceptedQty()) + "}")
				.collect(Collectors.joining(",")) + "]}";
		return mvc.perform(post("/api/v1/warehouse/returns/" + rma + "/inspection").with(as(warehouseUser))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Customer creates + ships a return, warehouse receives it; returns the RMA (status RECEIVED). */
	public String receivedReturn(Order o, String refundMethod, String warehouseUser, Item... items) throws Exception {
		String rma = createReturn(o, refundMethod, items);
		ship(o, rma);
		receive(warehouseUser, rma).andExpect(status().isOk());
		return rma;
	}

	public long itemId(String rma, long orderLineId) {
		return jdbc.queryForObject("SELECT i.id FROM return_item i JOIN return_request r ON r.id = i.return_id "
				+ "WHERE r.rma_number = ? AND i.order_line_id = ?", Long.class, rma, orderLineId);
	}

	public String returnStatus(String rma) {
		return jdbc.queryForObject("SELECT status FROM return_request WHERE rma_number = ?", String.class, rma);
	}

	public String returnId(String rma) {
		return jdbc.queryForObject("SELECT CAST(id AS VARCHAR) FROM return_request WHERE rma_number = ?", String.class, rma);
	}

	/** The refund row of a return (empty map if none). */
	public Map<String, Object> refund(String rma) {
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT f.* FROM refund f JOIN return_request r "
				+ "ON r.id = f.return_id WHERE r.rma_number = ?", rma);
		return rows.isEmpty() ? Map.of() : rows.get(0);
	}

	public long refundId(String rma) {
		return ((Number) refund(rma).get("id")).longValue();
	}

	public long gatewayPayments(String rma) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM gw_payment WHERE idempotency_key = ?", Long.class,
				"RR-REFUND-" + returnId(rma));
	}

	/** Ledger rows of a return's refund as "ACCOUNT:DIRECTION:amount", in posting order. */
	public List<String> ledger(String rma) {
		return jdbc.queryForList("SELECT l.account || ':' || l.direction || ':' || CAST(l.amount_paise AS VARCHAR) "
				+ "FROM ledger_entry l JOIN refund f ON f.id = l.refund_id JOIN return_request r ON r.id = f.return_id "
				+ "WHERE r.rma_number = ? ORDER BY l.id", String.class, rma);
	}

	public JdbcTemplate jdbc() {
		return jdbc;
	}
}
