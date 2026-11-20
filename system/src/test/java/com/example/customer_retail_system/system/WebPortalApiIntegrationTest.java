package com.example.customer_retail_system.system;

import static com.example.customer_retail_system.system.ApiFlow.as;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.customer_retail_system.system.ApiFlow.Item;
import com.example.customer_retail_system.system.ApiFlow.Line;
import com.example.customer_retail_system.system.ApiFlow.Order;
import com.example.customer_retail_system.system.ApiFlow.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Endpoints and fields the web portal relies on: staff identity, enriched return views, finance detail. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebPortalApiIntegrationTest {

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
	void staffMeReturnsTheSignedInRole() throws Exception {
		for (String role : new String[] { "WAREHOUSE", "FINANCE", "MANAGER" }) {
			String user = flow.staff(role);
			mvc.perform(get("/api/v1/staff/me").with(as(user)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.username").value(user))
					.andExpect(jsonPath("$.role").value(role));
		}
		mvc.perform(get("/api/v1/staff/me").with(httpBasic(warehouse, "wrong-password")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/v1/staff/me")).andExpect(status().isUnauthorized());

		Order o = flow.order(Line.of("Cap", 1, 29_900));
		mvc.perform(get("/api/v1/staff/me").header("Authorization", "Bearer " + o.token()))
				.andExpect(status().isForbidden());
	}

	@Test
	void customerReturnShowsProductsAndInspectionResults() throws Exception {
		Order o = flow.order(Line.of("Linen shirt", 2, 99_900));
		String rma = flow.receivedReturn(o, "ORIGINAL_PAYMENT", warehouse, new Item(o.lines().get(0), 2, "DAMAGED"));

		mvc.perform(get("/api/v1/customer/returns/" + rma).header("Authorization", "Bearer " + o.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalRefundPaise").doesNotExist())
				.andExpect(jsonPath("$.items[0].productName").value("Linen shirt"))
				.andExpect(jsonPath("$.items[0].unitPricePaise").value(99_900))
				.andExpect(jsonPath("$.items[0].conditionGrade").doesNotExist());

		flow.inspect(warehouse, rma, new Verdict(flow.itemId(rma, o.lines().get(0)), "C", "ACCEPTED", null))
				.andExpect(status().isOk());

		mvc.perform(get("/api/v1/customer/returns/" + rma).header("Authorization", "Bearer " + o.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalRefundPaise").value(99_900))
				.andExpect(jsonPath("$.items[0].conditionGrade").value("C"))
				.andExpect(jsonPath("$.items[0].decision").value("ACCEPTED"))
				.andExpect(jsonPath("$.items[0].acceptedQty").value(2))
				.andExpect(jsonPath("$.items[0].refundPaise").value(99_900));
	}

	@Test
	void warehouseCanLoadAReturnByRma() throws Exception {
		Order o = flow.order(Line.of("Mug", 1, 39_900));
		String rma = flow.receivedReturn(o, "STORE_CREDIT", warehouse, new Item(o.lines().get(0), 1, "WRONG_ITEM"));

		mvc.perform(get("/api/v1/warehouse/returns/" + rma).with(as(warehouse)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rma").value(rma))
				.andExpect(jsonPath("$.status").value("RECEIVED"))
				.andExpect(jsonPath("$.items[0].productName").value("Mug"));
		mvc.perform(get("/api/v1/warehouse/returns/RMA-0").with(as(warehouse)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RETURN_NOT_FOUND"));
	}

	@Test
	void financeSeesTheInspectedReturnBehindARefund() throws Exception {
		String finance = flow.staff("FINANCE");
		Order o = flow.order(Line.of("Television", 1, 1_500_000));
		String rma = flow.receivedReturn(o, "ORIGINAL_PAYMENT", warehouse, new Item(o.lines().get(0), 1, "DEFECTIVE"));
		flow.inspect(warehouse, rma, new Verdict(flow.itemId(rma, o.lines().get(0)), "A", "ACCEPTED", null))
				.andExpect(status().isOk());
		long refundId = flow.refundId(rma);

		mvc.perform(get("/api/v1/finance/refunds/" + refundId).with(as(finance)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refund.id").value(refundId))
				.andExpect(jsonPath("$.refund.status").value("AWAITING_APPROVAL"))
				.andExpect(jsonPath("$.refund.amountPaise").value(1_500_000))
				.andExpect(jsonPath("$.rma").value(rma))
				.andExpect(jsonPath("$.orderNumber").value(o.number()))
				.andExpect(jsonPath("$.returnStatus").value("AWAITING_FINANCE"))
				.andExpect(jsonPath("$.flagged").value(false))
				.andExpect(jsonPath("$.items[0].productName").value("Television"))
				.andExpect(jsonPath("$.items[0].conditionGrade").value("A"))
				.andExpect(jsonPath("$.items[0].refundPaise").value(1_500_000));
		mvc.perform(get("/api/v1/finance/refunds/999999999").with(as(finance)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/finance/refunds/" + refundId).with(as(warehouse)))
				.andExpect(status().isForbidden());
	}
}
