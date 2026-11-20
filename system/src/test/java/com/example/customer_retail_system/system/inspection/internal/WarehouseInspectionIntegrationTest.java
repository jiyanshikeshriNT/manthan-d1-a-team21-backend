package com.example.customer_retail_system.system.inspection.internal;

import static com.example.customer_retail_system.system.ApiFlow.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.example.customer_retail_system.system.ApiFlow;
import com.example.customer_retail_system.system.ApiFlow.Item;
import com.example.customer_retail_system.system.ApiFlow.Line;
import com.example.customer_retail_system.system.ApiFlow.Order;
import com.example.customer_retail_system.system.ApiFlow.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseInspectionIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiFlow flow;
	String warehouse;

	@BeforeEach
	void setUp() {
		flow = new ApiFlow(mvc, jdbc);
		warehouse = flow.staff("WAREHOUSE");
	}

	@Test
	void labelLookupVerifiesTheSignature() throws Exception {
		Order o = flow.order(Line.of("Linen Shirt", 1, 129_900));
		String rma = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(0), 1, "WRONG_SIZE"));
		String label = flow.labelCode(o, rma);

		mvc.perform(get("/api/v1/warehouse/labels/" + label).with(as(warehouse)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rma").value(rma))
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.items[0].productName").value("Linen Shirt"))
				.andExpect(jsonPath("$.items[0].unitPricePaise").value(129_900));

		String forged = label.substring(0, label.length() - 8) + "00000000";
		mvc.perform(get("/api/v1/warehouse/labels/" + forged).with(as(warehouse)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LABEL_INVALID"));
		mvc.perform(get("/api/v1/warehouse/labels/RR-RMA-999999-deadbeef").with(as(warehouse)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LABEL_INVALID"));
	}

	@Test
	void qrPhotoOfTheLabelDecodesToTheSignedCode() throws Exception {
		Order o = flow.order(Line.of("Kurta", 1, 89_900));
		String rma = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(0), 1, "WRONG_SIZE"));
		byte[] png = mvc.perform(get("/api/v1/customer/returns/" + rma + "/label/qr.png")
				.header("Authorization", "Bearer " + o.token())).andReturn().getResponse().getContentAsByteArray();

		mvc.perform(multipart("/api/v1/warehouse/labels/decode")
				.file(new MockMultipartFile("image", "label.png", MediaType.IMAGE_PNG_VALUE, png)).with(as(warehouse)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rma").value(rma))
				.andExpect(jsonPath("$.labelCode").value(flow.labelCode(o, rma)));

		mvc.perform(multipart("/api/v1/warehouse/labels/decode")
				.file(new MockMultipartFile("image", "x.png", MediaType.IMAGE_PNG_VALUE, "not an image".getBytes()))
				.with(as(warehouse)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LABEL_INVALID"));
	}

	@Test
	void receiveRequiresAShippedReturnAndReScanIsIdempotent() throws Exception {
		Order o = flow.order(Line.of("Jeans", 1, 199_900));
		String rma = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(0), 1, "WRONG_SIZE"));

		flow.receive(warehouse, rma) // still APPROVED: the customer never shipped it
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

		flow.ship(o, rma);
		flow.receive(warehouse, rma).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
		long auditRows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Long.class, flow.returnId(rma));
		flow.receive(warehouse, rma).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Long.class, flow.returnId(rma)))
				.isEqualTo(auditRows);
		assertThat(jdbc.queryForObject("SELECT actor FROM audit_log WHERE entity_id = ? AND to_state = 'RECEIVED'",
				String.class, flow.returnId(rma))).isEqualTo("staff:" + warehouse);
	}

	@Test
	void receiveWithAnotherReturnsLabelIsRefused() throws Exception {
		Order o = flow.order(Line.of("Shirt", 1, 99_900), Line.of("Socks", 1, 19_900));
		String first = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(0), 1, "WRONG_SIZE"));
		String second = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(1), 1, "WRONG_SIZE"));
		flow.ship(o, first);

		mvc.perform(post("/api/v1/warehouse/returns/" + first + "/receive").with(as(warehouse))
				.contentType(MediaType.APPLICATION_JSON).content("{\"labelCode\":\"" + flow.labelCode(o, second) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LABEL_INVALID"));
		assertThat(flow.returnStatus(first)).isEqualTo("IN_TRANSIT");
	}

	@Test
	void gradeCRefundsHalfRoundedHalfUpAndStoreCreditIsPaidImmediately() throws Exception {
		Order o = flow.order(Line.of("Blender", 1, 99_999)); // ₹999.99
		String rma = flow.receivedReturn(o, "STORE_CREDIT", warehouse, new Item(o.lines().get(0), 1, "DEFECTIVE"));

		flow.inspect(warehouse, rma, new Verdict(flow.itemId(rma, o.lines().get(0)), "C", "ACCEPTED", 1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].refundPaise").value(50_000)) // 49,999.5 → 50,000
				.andExpect(jsonPath("$.totalRefundPaise").value(50_000))
				.andExpect(jsonPath("$.status").value("REFUNDED"))
				.andExpect(jsonPath("$.refund.status").value("SUCCEEDED"));
		assertThat(flow.gatewayPayments(rma)).isZero(); // store credit never calls the gateway
		assertThat(flow.ledger(rma)).containsExactly("REFUND_EXPENSE:DEBIT:50000", "STORE_CREDIT_LIABILITY:CREDIT:50000");
	}

	@Test
	void partialAcceptanceRefundsOnlyAcceptedUnitsThroughTheGateway() throws Exception {
		Order o = flow.order(Line.of("Tee", 3, 100_000), Line.of("Jacket", 1, 250_000));
		String rma = flow.receivedReturn(o, "ORIGINAL_PAYMENT", warehouse,
				new Item(o.lines().get(0), 3, "WRONG_SIZE"), new Item(o.lines().get(1), 1, "DAMAGED"));

		flow.inspect(warehouse, rma,
				new Verdict(flow.itemId(rma, o.lines().get(0)), "B", "ACCEPTED", 2),
				new Verdict(flow.itemId(rma, o.lines().get(1)), "C", "REJECTED", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalRefundPaise").value(200_000))
				.andExpect(jsonPath("$.status").value("REFUNDED"));

		Map<String, Object> refund = flow.refund(rma);
		assertThat(refund.get("STATUS")).isEqualTo("SUCCEEDED");
		assertThat(((Number) refund.get("AMOUNT_PAISE")).longValue()).isEqualTo(200_000);
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
		assertThat(flow.ledger(rma)).containsExactly("REFUND_EXPENSE:DEBIT:200000", "GATEWAY_CLEARING:CREDIT:200000");
		assertThat(jdbc.queryForList("SELECT accepted_qty || ':' || refund_paise || ':' || decision FROM return_item i "
				+ "JOIN return_request r ON r.id = i.return_id WHERE r.rma_number = ? ORDER BY i.id", String.class, rma))
				.containsExactly("2:200000:ACCEPTED", "0:0:REJECTED");
	}

	@Test
	void everythingRejectedClosesTheReturnWithoutARefund() throws Exception {
		Order o = flow.order(Line.of("Watch", 1, 450_000));
		String rma = flow.receivedReturn(o, "ORIGINAL_PAYMENT", warehouse, new Item(o.lines().get(0), 1, "DEFECTIVE"));

		flow.inspect(warehouse, rma, new Verdict(flow.itemId(rma, o.lines().get(0)), "C", "REJECTED", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.totalRefundPaise").value(0))
				.andExpect(jsonPath("$.refund").doesNotExist());
		assertThat(flow.refund(rma)).isEmpty();
		assertThat(jdbc.queryForList("SELECT to_state FROM audit_log WHERE entity_id = ? ORDER BY id", String.class,
				flow.returnId(rma))).endsWith("INSPECTED", "REJECTED");
	}

	@Test
	void inspectionIsValidatedAndOnlyAllowedOnReceivedReturns() throws Exception {
		Order o = flow.order(Line.of("Lamp", 2, 150_000), Line.of("Rug", 1, 300_000));
		String rma = flow.createReturn(o, "STORE_CREDIT",
				new Item(o.lines().get(0), 2, "DAMAGED"), new Item(o.lines().get(1), 1, "DAMAGED"));
		long lamp = flow.itemId(rma, o.lines().get(0));
		long rug = flow.itemId(rma, o.lines().get(1));

		flow.inspect(warehouse, rma, new Verdict(lamp, "A", "ACCEPTED", 2), new Verdict(rug, "A", "ACCEPTED", 1))
				.andExpect(status().isConflict()) // APPROVED, not received yet
				.andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

		flow.ship(o, rma);
		flow.receive(warehouse, rma).andExpect(status().isOk());
		flow.inspect(warehouse, rma, new Verdict(lamp, "A", "ACCEPTED", 2)) // rug missing
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		flow.inspect(warehouse, rma, new Verdict(lamp, "A", "ACCEPTED", 3), new Verdict(rug, "A", "ACCEPTED", 1))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Accepted quantity for item " + lamp + " must be between 1 and 2."));
		flow.inspect(warehouse, rma, new Verdict(lamp, "A", "REJECTED", 1), new Verdict(rug, "A", "ACCEPTED", 1))
				.andExpect(status().isBadRequest());
		assertThat(flow.returnStatus(rma)).isEqualTo("RECEIVED");
	}
}
