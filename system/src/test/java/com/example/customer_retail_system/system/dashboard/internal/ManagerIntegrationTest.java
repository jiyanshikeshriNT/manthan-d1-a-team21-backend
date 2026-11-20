package com.example.customer_retail_system.system.dashboard.internal;

import static com.example.customer_retail_system.system.ApiFlow.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.customer_retail_system.gateway.api.GatewayFaultControl;
import com.example.customer_retail_system.gateway.api.FaultMode;
import com.example.customer_retail_system.system.ApiFlow;
import com.example.customer_retail_system.system.ApiFlow.Item;
import com.example.customer_retail_system.system.ApiFlow.Line;
import com.example.customer_retail_system.system.ApiFlow.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManagerIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	GatewayFaultControl faults;

	ApiFlow flow;
	String manager;

	@BeforeEach
	void setUp() {
		flow = new ApiFlow(mvc, jdbc);
		manager = flow.staff("MANAGER");
	}

	@AfterEach
	void resetGateway() {
		faults.apply(GatewayFaultControl.FaultSettings.none());
	}

	/** One customer, six returnable lines; the 4th+ return requests in 30 days are flagged. */
	Order sixLineOrder() throws Exception {
		Line[] lines = new Line[6];
		for (int i = 0; i < 6; i++) {
			lines[i] = Line.of("Item " + i, 1, 49_900);
		}
		return flow.order(lines);
	}

	@Test
	void reviewQueueApprovesRejectsAndRefusesStaleDecisions() throws Exception {
		Order o = sixLineOrder();
		for (int i = 0; i < 3; i++) {
			flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(i), 1, "CHANGED_MIND"));
		}
		String toApprove = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(3), 1, "CHANGED_MIND"));
		String toReject = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(4), 1, "CHANGED_MIND"));
		String cancelled = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(5), 1, "CHANGED_MIND"));
		assertThat(flow.returnStatus(toApprove)).isEqualTo("PENDING_REVIEW");

		mvc.perform(get("/api/v1/manager/reviews").with(as(manager)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.rma == '%s')].orderNumber", toApprove).value(o.number()))
				.andExpect(jsonPath("$[?(@.rma == '%s')].flagReason", toApprove)
						.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.startsWith("Customer has 4 return requests"))));

		mvc.perform(post("/api/v1/manager/reviews/" + toApprove + "/approve").with(as(manager)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
		mvc.perform(post("/api/v1/manager/reviews/" + toApprove + "/approve").with(as(manager)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")); // repeat: no-op
		mvc.perform(post("/api/v1/manager/reviews/" + toApprove + "/reject").with(as(manager))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed my mind\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
		assertThat(jdbc.queryForObject("SELECT actor FROM audit_log WHERE entity_id = ? AND to_state = 'APPROVED'",
				String.class, flow.returnId(toApprove))).isEqualTo("staff:" + manager);

		mvc.perform(post("/api/v1/manager/reviews/" + toReject + "/reject").with(as(manager))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/manager/reviews/" + toReject + "/reject").with(as(manager))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Serial returner\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.timeline[1].note").value("Rejected after manager review: Serial returner"));

		// Failure mode 10: the customer cancels first, then the manager's approval is stale.
		mvc.perform(post("/api/v1/customer/returns/" + cancelled + "/cancel").header("Authorization", "Bearer " + o.token()))
				.andExpect(status().isOk());
		mvc.perform(post("/api/v1/manager/reviews/" + cancelled + "/approve").with(as(manager)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("This return is no longer awaiting review."));
		assertThat(flow.returnStatus(cancelled)).isEqualTo("CANCELLED");
	}

	@Test
	void auditTrailIsQueryableByEntityId() throws Exception {
		Order o = flow.order(Line.of("Shirt", 1, 99_900));
		String rma = flow.createReturn(o, "STORE_CREDIT", new Item(o.lines().get(0), 1, "WRONG_SIZE"));
		flow.ship(o, rma);

		mvc.perform(get("/api/v1/manager/audit").param("entityId", flow.returnId(rma)).with(as(manager)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].action").value("CREATED"))
				.andExpect(jsonPath("$[1].toState").value("APPROVED"))
				.andExpect(jsonPath("$[2].toState").value("IN_TRANSIT"))
				.andExpect(jsonPath("$[2].actor").value("customer:" + o.customerId()));
		mvc.perform(get("/api/v1/manager/audit").with(as(manager))).andExpect(status().isBadRequest());
	}

	@Test
	void managerCanInjectGatewayFaultsAndEveryChangeIsAudited() throws Exception {
		long before = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'GATEWAY'", Long.class);
		mvc.perform(put("/api/v1/admin/gateway/faults").with(as(manager))
				.contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SLOW\",\"latencyMs\":25}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mode").value("SLOW"));
		mvc.perform(get("/api/v1/admin/gateway/faults").with(as(manager)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mode").value("SLOW"))
				.andExpect(jsonPath("$.latencyMs").value(25));
		assertThat(faults.current().mode()).isEqualTo(FaultMode.SLOW);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_type = 'GATEWAY'", Long.class))
				.isEqualTo(before + 1);

		mvc.perform(put("/api/v1/admin/gateway/faults").with(as(manager))
				.contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MELTDOWN\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/admin/gateway/faults").with(as(manager))
				.contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NONE\",\"latencyMs\":-1}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void roleAccessMatrix() throws Exception {
		String warehouse = flow.staff("WAREHOUSE");
		String finance = flow.staff("FINANCE");
		Order o = flow.order(Line.of("Cap", 1, 29_900));

		// Allowed
		expect(get("/api/v1/manager/dashboard").with(as(manager)), 200);
		expect(get("/api/v1/manager/reviews").with(as(manager)), 200);
		expect(get("/api/v1/admin/gateway/faults").with(as(manager)), 200);
		expect(get("/api/v1/finance/refunds").with(as(finance)), 200);
		expect(get("/api/v1/finance/ledger").with(as(finance)), 200);
		expect(get("/api/v1/warehouse/labels/RR-RMA-1-00000000").with(as(warehouse)), 400); // authorised, bad label

		// Forbidden: wrong staff role
		expect(get("/api/v1/finance/refunds").with(as(warehouse)), 403);
		expect(get("/api/v1/manager/dashboard").with(as(warehouse)), 403);
		expect(put("/api/v1/admin/gateway/faults").with(as(warehouse)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"FAIL\"}"), 403);
		expect(get("/api/v1/warehouse/labels/x").with(as(finance)), 403);
		expect(get("/api/v1/manager/reviews").with(as(finance)), 403);
		expect(put("/api/v1/admin/gateway/faults").with(as(finance)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\":\"FAIL\"}"), 403);
		expect(get("/api/v1/finance/refunds").with(as(manager)), 403);
		expect(post("/api/v1/warehouse/returns/RMA-1/receive").with(as(manager)), 403);
		expect(get("/api/v1/customer/orders/" + o.number()).with(as(manager)), 403);

		// Forbidden: customers on staff APIs
		expect(get("/api/v1/manager/dashboard").header("Authorization", "Bearer " + o.token()), 403);
		expect(post("/api/v1/finance/refunds/1/approve").header("Authorization", "Bearer " + o.token()), 403);
		expect(get("/api/v1/warehouse/labels/x").header("Authorization", "Bearer " + o.token()), 403);

		// Unauthenticated
		expect(get("/api/v1/finance/refunds"), 401);
		expect(get("/api/v1/manager/dashboard"), 401);
		assertThat(faults.current().mode()).isEqualTo(FaultMode.NONE);
	}

	private void expect(MockHttpServletRequestBuilder request, int status) throws Exception {
		mvc.perform(request).andExpect(status().is(status));
	}
}
