package com.example.customer_retail_system.system.order.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import com.example.customer_retail_system.system.TestData;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerOrderIntegrationTest {

	static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void orderShowsPerLineEligibility() throws Exception {
		TestData data = new TestData(jdbc);
		String email = TestData.uniqueEmail();
		String orderNumber = TestData.uniqueOrderNumber();
		var deliveredAt = TestData.daysAgo(5);
		long order = data.order(orderNumber, data.customer(email), deliveredAt);
		long apparel = data.line(order, data.product(data.category("Apparel", true, 30), "Linen Shirt", 129_900), 2, 129_900);
		long phone = data.line(order, data.product(data.category("Electronics", true, 3), "Phone", 1_599_900), 1, 1_499_900);
		long briefs = data.line(order, data.product(data.category("Innerwear", false, null), "Briefs", 49_900), 1, 49_900);
		String token = TestData.token(mvc, orderNumber, email);
		String deadline = LocalDate.ofInstant(deliveredAt, IST).plusDays(30).toString();

		mvc.perform(get("/api/v1/customer/orders/" + orderNumber).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderNumber").value(orderNumber))
				.andExpect(jsonPath("$.lines.length()").value(3))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.code", apparel).value("ELIGIBLE"))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.returnableQty", apparel).value(2))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.deadline", apparel).value(deadline))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].unitPricePaise", phone).value(1_499_900))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.code", phone).value("WINDOW_EXPIRED"))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.eligible", phone).value(false))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.code", briefs).value("CATEGORY_NOT_RETURNABLE"))
				.andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.message", briefs)
						.value("For hygiene and safety reasons, Innerwear items can't be returned."));
	}

	@Test
	void undeliveredOrderIsNotYetReturnable() throws Exception {
		TestData data = new TestData(jdbc);
		String email = TestData.uniqueEmail();
		String orderNumber = TestData.uniqueOrderNumber();
		long order = data.order(orderNumber, data.customer(email), null);
		data.line(order, data.product(data.category("Home", true, 30), "Kettle", 99_900), 1, 99_900);
		String token = TestData.token(mvc, orderNumber, email);

		mvc.perform(get("/api/v1/customer/orders/" + orderNumber.toLowerCase()).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deliveredAt").doesNotExist())
				.andExpect(jsonPath("$.lines[0].eligibility.code").value("NOT_DELIVERED"))
				.andExpect(jsonPath("$.lines[0].eligibility.deadline").doesNotExist());
	}
}
