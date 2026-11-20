package com.example.customer_retail_system.system.returns.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.List;

import javax.imageio.ImageIO;

import com.example.customer_retail_system.system.TestData;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.shared.Actor;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.IllegalTransactionStateException;

class ReturnLifecycleIntegrationTest extends ReturnsApiTestBase {

	@Autowired
	LabelCodec labels;

	@Autowired
	ReturnRequestRepository returns;

	@Autowired
	ReturnStateMachine stateMachine;

	List<String> auditTrail(String rma) {
		return jdbc.queryForList("SELECT from_state || '->' || to_state FROM audit_log WHERE entity_type = 'RETURN' "
				+ "AND entity_id = ? AND action = 'STATUS_CHANGED' ORDER BY id", String.class, returnId(rma));
	}

	@Test
	void timelineEndpointShowsStatusAndSteps() throws Exception {
		Fixture f = fixture();
		String rma = createReturn(f, f.shirts(), 1);
		getReturn(f.token(), rma)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rma").value(rma))
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.timeline[0].at").isNotEmpty())
				.andExpect(jsonPath("$.timeline[1].state").value("current"))
				.andExpect(jsonPath("$.flagged").doesNotExist()); // never exposed to the customer
	}

	@Test
	void returnsOfAnotherOrderAreNotFound() throws Exception {
		Fixture mine = fixture();
		Fixture other = fixture();
		String rma = createReturn(other, other.shoes(), 1);
		for (String path : List.of("", "/label", "/label/qr.png")) {
			mvc.perform(get("/api/v1/customer/returns/" + rma + path).header("Authorization", "Bearer " + mine.token()))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("RETURN_NOT_FOUND"));
		}
		action(mine.token(), rma, "cancel").andExpect(status().isNotFound());
		assertThat(returns.findByRmaNumber(rma).orElseThrow().getStatus()).isEqualTo(ReturnStatus.APPROVED);
	}

	@Test
	void labelJsonAndQrCarryTheSignedLabelCode() throws Exception {
		Fixture f = fixture();
		String rma = createReturn(f, f.shirts(), 2);
		String body = mvc.perform(get("/api/v1/customer/returns/" + rma + "/label").header("Authorization", "Bearer " + f.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.qrCodeUrl").value("/api/v1/customer/returns/" + rma + "/label/qr.png"))
				.andReturn().getResponse().getContentAsString();
		String labelCode = JsonPath.read(body, "$.labelCode");
		assertThat(labels.verify(labelCode)).contains(rma);

		byte[] png = mvc.perform(get("/api/v1/customer/returns/" + rma + "/label/qr.png").header("Authorization", "Bearer " + f.token()))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andReturn().getResponse().getContentAsByteArray();
		var image = ImageIO.read(new ByteArrayInputStream(png));
		assertThat(image.getWidth()).isEqualTo(QrCodes.SIZE_PX);
		assertThat(QrCodes.decode(image)).contains(labelCode);
	}

	@Test
	void shipMovesToInTransitAndRepeatingIsANoOp() throws Exception {
		Fixture f = fixture();
		String rma = createReturn(f, f.shirts(), 1);
		action(f.token(), rma, "ship")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IN_TRANSIT"))
				.andExpect(jsonPath("$.timeline[2].step").value("In transit"))
				.andExpect(jsonPath("$.timeline[2].state").value("current"));
		action(f.token(), rma, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_TRANSIT"));
		assertThat(auditTrail(rma)).containsExactly("REQUESTED->APPROVED", "APPROVED->IN_TRANSIT");
	}

	@Test
	void cancelAfterShippingIsRefusedAndWritesNoAuditRow() throws Exception {
		Fixture f = fixture();
		String rma = createReturn(f, f.shirts(), 1);
		action(f.token(), rma, "ship").andExpect(status().isOk());
		action(f.token(), rma, "cancel")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
				.andExpect(jsonPath("$.detail").value("This return can't be cancelled because it is already on its way to us."))
				.andExpect(jsonPath("$.currentStatus").value("IN_TRANSIT"));
		assertThat(returns.findByRmaNumber(rma).orElseThrow().getStatus()).isEqualTo(ReturnStatus.IN_TRANSIT);
		// Exactly one audit row per transition that happened: created, approved, shipped.
		assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", returnId(rma))).isEqualTo(3);
	}

	@Test
	void cancelBeforeShippingClosesTheReturnAndReleasesTheQuantity() throws Exception {
		Fixture f = fixture();
		String rma = createReturn(f, f.shirts(), 2);
		getOrder(f).andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.code", f.shirts()).value("ALREADY_IN_RETURN"));

		action(f.token(), rma, "cancel")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.statusLabel").value("Cancelled"))
				.andExpect(jsonPath("$.labelCode").doesNotExist())
				.andExpect(jsonPath("$.timeline.length()").value(3))
				.andExpect(jsonPath("$.timeline[2].step").value("Cancelled"))
				.andExpect(jsonPath("$.timeline[2].state").value("failed"))
				.andExpect(jsonPath("$.timeline[2].note").value("Cancelled by customer"));
		action(f.token(), rma, "cancel").andExpect(status().isOk()); // repeat is a no-op

		assertThat(returns.findByRmaNumber(rma).orElseThrow().getClosedAt()).isNotNull();
		assertThat(auditTrail(rma)).containsExactly("REQUESTED->APPROVED", "APPROVED->CANCELLED");
		getOrder(f).andExpect(jsonPath("$.lines[?(@.orderLineId == %d)].eligibility.returnableQty", f.shirts()).value(2));
		mvc.perform(get("/api/v1/customer/returns/" + rma + "/label").header("Authorization", "Bearer " + f.token()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("This return no longer has an active label."));
		action(f.token(), rma, "ship")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("This return is already closed."));
	}

	@Test
	void flaggedReturnCannotBeShippedOrLabelledUntilApprovedButCanBeCancelled() throws Exception {
		TestData data = new TestData(jdbc);
		String email = TestData.uniqueEmail();
		String orderNumber = TestData.uniqueOrderNumber();
		long orderId = data.order(orderNumber, data.customer(email), TestData.daysAgo(1));
		long cat = data.category("Beauty", true, 30);
		long[] lines = new long[4];
		for (int i = 0; i < 4; i++) {
			lines[i] = data.line(orderId, data.product(cat, "Serum " + i, 39_900), 1, 39_900);
		}
		String token = TestData.token(mvc, orderNumber, email);
		String rma = null;
		for (long line : lines) {
			rma = JsonPath.read(create(token, null, request(orderNumber, "STORE_CREDIT", item(line, 1, "CHANGED_MIND")))
					.andReturn().getResponse().getContentAsString(), "$.rma");
		}
		getReturn(token, rma).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

		action(token, rma, "ship")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Your return is still being reviewed. You can ship it once it's approved."));
		mvc.perform(get("/api/v1/customer/returns/" + rma + "/label").header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Your return label will be available once your return is approved."));
		action(token, rma, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void cancelledReturnsDoNotCountTowardsFlagging() throws Exception {
		TestData data = new TestData(jdbc);
		String email = TestData.uniqueEmail();
		String orderNumber = TestData.uniqueOrderNumber();
		long orderId = data.order(orderNumber, data.customer(email), TestData.daysAgo(1));
		long cat = data.category("Home", true, 30);
		long[] lines = new long[4];
		for (int i = 0; i < 4; i++) {
			lines[i] = data.line(orderId, data.product(cat, "Mug " + i, 29_900), 1, 29_900);
		}
		String token = TestData.token(mvc, orderNumber, email);
		String first = JsonPath.read(create(token, null, request(orderNumber, "STORE_CREDIT", item(lines[0], 1, "CHANGED_MIND")))
				.andReturn().getResponse().getContentAsString(), "$.rma");
		create(token, null, request(orderNumber, "STORE_CREDIT", item(lines[1], 1, "CHANGED_MIND")));
		create(token, null, request(orderNumber, "STORE_CREDIT", item(lines[2], 1, "CHANGED_MIND")));
		action(token, first, "cancel").andExpect(status().isOk());

		create(token, null, request(orderNumber, "STORE_CREDIT", item(lines[3], 1, "CHANGED_MIND")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("APPROVED")); // 3 active requests incl. this one → not flagged
	}

	@Test
	void stateMachineRefusesToRunOutsideATransaction() throws Exception {
		Fixture f = fixture();
		String rma = createReturn(f, f.shirts(), 1);
		var ret = returns.findByRmaNumber(rma).orElseThrow();
		assertThatThrownBy(() -> stateMachine.transition(ret, ReturnStatus.IN_TRANSIT, Actor.system(), "no tx"))
				.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(returns.findByRmaNumber(rma).orElseThrow().getStatus()).isEqualTo(ReturnStatus.APPROVED);
	}
}
