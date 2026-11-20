package com.example.customer_retail_system.system.refund.internal;

import static com.example.customer_retail_system.system.ApiFlow.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.customer_retail_system.gateway.api.FaultMode;
import com.example.customer_retail_system.gateway.api.GatewayFaultControl;
import com.example.customer_retail_system.gateway.api.GatewayFaultControl.FaultSettings;
import com.example.customer_retail_system.system.ApiFlow;
import com.example.customer_retail_system.system.ApiFlow.Item;
import com.example.customer_retail_system.system.ApiFlow.Line;
import com.example.customer_retail_system.system.ApiFlow.Order;
import com.example.customer_retail_system.system.ApiFlow.Verdict;
import com.example.customer_retail_system.system.refund.RefundService;
import com.example.customer_retail_system.system.refund.RefundStatus;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundFlowIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	GatewayFaultControl faults;

	@Autowired
	RefundService refunds;

	@Autowired
	RefundReconciler reconciler;

	@Autowired
	RefundSettlement settlement;

	@Autowired
	PlatformTransactionManager txManager;

	ApiFlow flow;
	String warehouse;
	String finance;

	@BeforeEach
	void setUp() {
		flow = new ApiFlow(mvc, jdbc);
		warehouse = flow.staff("WAREHOUSE");
		finance = flow.staff("FINANCE");
	}

	@AfterEach
	void resetGateway() {
		faults.apply(FaultSettings.none());
	}

	/** Customer → ship → receive → inspect (grade A, all accepted) for one line priced {@code unitPaise}. */
	String inspectedReturn(long unitPaise, String refundMethod) throws Exception {
		Order o = flow.order(Line.of("Item", 1, unitPaise));
		String rma = flow.receivedReturn(o, refundMethod, warehouse, new Item(o.lines().get(0), 1, "DEFECTIVE"));
		flow.inspect(warehouse, rma, new Verdict(flow.itemId(rma, o.lines().get(0)), "A", "ACCEPTED", 1))
				.andExpect(status().isOk());
		return rma;
	}

	ResultActions financeAction(long refundId, String action) throws Exception {
		return mvc.perform(post("/api/v1/finance/refunds/" + refundId + "/" + action).with(as(finance)));
	}

	String refundStatus(String rma) {
		return (String) flow.refund(rma).get("status");
	}

	@Test
	void approvalThresholdIsStrictlyAboveTenThousandRupees() throws Exception {
		String exactly = inspectedReturn(1_000_000, "ORIGINAL_PAYMENT"); // ₹10,000.00 → automatic
		assertThat(flow.returnStatus(exactly)).isEqualTo("REFUNDED");
		assertThat(refundStatus(exactly)).isEqualTo("SUCCEEDED");
		assertThat(flow.refund(exactly).get("requires_approval")).isEqualTo(false);

		String above = inspectedReturn(1_000_001, "ORIGINAL_PAYMENT"); // ₹10,000.01 → finance
		assertThat(flow.returnStatus(above)).isEqualTo("AWAITING_FINANCE");
		assertThat(refundStatus(above)).isEqualTo("AWAITING_APPROVAL");
		assertThat(flow.gatewayPayments(above)).isZero();
		mvc.perform(get("/api/v1/finance/refunds").param("status", "AWAITING_APPROVAL").with(as(finance)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.rma == '%s')].amountPaise", above).value(1_000_001));
	}

	@Test
	void doubleApprovalPaysOnce() throws Exception {
		String rma = inspectedReturn(2_500_000, "ORIGINAL_PAYMENT");
		long id = flow.refundId(rma);

		financeAction(id, "approve").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.approvedBy").value("staff:" + finance));
		financeAction(id, "approve").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

		assertThat(flow.returnStatus(rma)).isEqualTo("REFUNDED");
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
		assertThat(flow.ledger(rma)).containsExactly("REFUND_EXPENSE:DEBIT:2500000", "GATEWAY_CLEARING:CREDIT:2500000");
	}

	@Test
	void financeRejectionNeedsAReasonClosesTheReturnAndBeatsALaterApproval() throws Exception {
		String rma = inspectedReturn(1_500_000, "ORIGINAL_PAYMENT");
		long id = flow.refundId(rma);

		mvc.perform(post("/api/v1/finance/refunds/" + id + "/reject").with(as(finance))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/finance/refunds/" + id + "/reject").with(as(finance))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Serial number mismatch\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));
		assertThat(flow.returnStatus(rma)).isEqualTo("REJECTED");

		financeAction(id, "approve")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
				.andExpect(jsonPath("$.currentStatus").value("REJECTED"));
		assertThat(flow.gatewayPayments(rma)).isZero();
		assertThat(flow.ledger(rma)).isEmpty();
	}

	@Test
	void tenConcurrentExecutionsPayOnceAndPostOneLedgerPair() throws Exception {
		faults.apply(new FaultSettings(FaultMode.FAIL, 0));
		String rma = inspectedReturn(450_000, "ORIGINAL_PAYMENT");
		assertThat(refundStatus(rma)).isEqualTo("UNKNOWN"); // gateway down: outcome unknown, not FAILED
		faults.apply(FaultSettings.none());

		long id = flow.refundId(rma);
		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<RefundStatus>> results = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return refunds.execute(id).status();
			}));
		}
		start.countDown();
		for (Future<RefundStatus> f : results) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(refundStatus(rma)).isEqualTo("SUCCEEDED");
		assertThat(flow.returnStatus(rma)).isEqualTo("REFUNDED");
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
		assertThat(flow.ledger(rma)).hasSize(2);
	}

	@Test
	void timeoutAfterChargingIsSettledOnceByTheReconciler() throws Exception {
		faults.apply(new FaultSettings(FaultMode.TIMEOUT, 1_500)); // caller gives up after 500 ms
		String rma = inspectedReturn(320_000, "ORIGINAL_PAYMENT");
		assertThat(refundStatus(rma)).isEqualTo("UNKNOWN");
		assertThat(flow.returnStatus(rma)).isEqualTo("REFUND_IN_PROGRESS");
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1); // the gateway did charge
		assertThat(flow.ledger(rma)).isEmpty();
		faults.apply(FaultSettings.none());

		reconciler.reconcile(flow.refundId(rma));
		reconciler.reconcile(flow.refundId(rma)); // a second run changes nothing

		assertThat(refundStatus(rma)).isEqualTo("SUCCEEDED");
		assertThat(flow.returnStatus(rma)).isEqualTo("REFUNDED");
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
		assertThat(flow.ledger(rma)).containsExactly("REFUND_EXPENSE:DEBIT:320000", "GATEWAY_CLEARING:CREDIT:320000");
	}

	@Test
	void failureAfterChargeNeverLeadsToASecondPayment() throws Exception {
		faults.apply(new FaultSettings(FaultMode.FAIL_AFTER_CHARGE, 0));
		String rma = inspectedReturn(275_000, "ORIGINAL_PAYMENT");
		assertThat(refundStatus(rma)).isEqualTo("UNKNOWN");
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);

		// Finance retries while the fault is still active: same key → the gateway returns the original payment.
		financeAction(flow.refundId(rma), "retry").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
		financeAction(flow.refundId(rma), "retry").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
		assertThat(flow.ledger(rma)).hasSize(2);
		assertThat(flow.returnStatus(rma)).isEqualTo("REFUNDED");
	}

	@Test
	void outageExhaustsAttemptsThenFinanceRetryPaysWithTheSameKey() throws Exception {
		faults.apply(new FaultSettings(FaultMode.FAIL, 0));
		String rma = inspectedReturn(180_000, "ORIGINAL_PAYMENT");
		long id = flow.refundId(rma);
		String key = (String) flow.refund(rma).get("idempotency_key");

		for (int i = 0; i < 4; i++) {
			reconciler.reconcile(id); // status checks fail: stays UNKNOWN, attempts 2..5
			assertThat(refundStatus(rma)).isEqualTo("UNKNOWN");
		}
		assertThat(flow.refund(rma).get("next_attempt_at")).isNotNull();
		reconciler.reconcile(id); // max attempts reached
		assertThat(refundStatus(rma)).isEqualTo("FAILED");
		assertThat(flow.gatewayPayments(rma)).isZero();

		faults.apply(FaultSettings.none());
		financeAction(id, "retry").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
		assertThat(flow.refund(rma).get("idempotency_key")).isEqualTo(key).isEqualTo("RR-REFUND-" + flow.returnId(rma));
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
		assertThat(flow.returnStatus(rma)).isEqualTo("REFUNDED");
	}

	@Test
	void declinedRefundFailsAndCanBeRetried() throws Exception {
		faults.apply(new FaultSettings(FaultMode.DECLINE, 0));
		String rma = inspectedReturn(99_900, "ORIGINAL_PAYMENT");
		assertThat(refundStatus(rma)).isEqualTo("FAILED");
		assertThat((String) flow.refund(rma).get("last_error")).startsWith("Declined by gateway");
		assertThat(flow.returnStatus(rma)).isEqualTo("REFUND_IN_PROGRESS");

		faults.apply(FaultSettings.none());
		financeAction(flow.refundId(rma), "retry").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
	}

	@Test
	void reconcilerRescuesARefundLeftProcessingByACrash() throws Exception {
		faults.apply(new FaultSettings(FaultMode.FAIL, 0));
		String rma = inspectedReturn(210_000, "ORIGINAL_PAYMENT");
		faults.apply(FaultSettings.none());
		long id = flow.refundId(rma);
		// Simulate a crash between tx1 and tx2, three minutes ago.
		jdbc.update("UPDATE refund SET status = 'PROCESSING', updated_at = ? WHERE id = ?",
				java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(3), id);
		assertThat(settlement.dueForReconcile()).contains(id);

		reconciler.reconcile(id);
		assertThat(refundStatus(rma)).isEqualTo("SUCCEEDED");
		assertThat(flow.gatewayPayments(rma)).isEqualTo(1);
	}

	@Test
	void ledgerIsBalancedAndQueryable() throws Exception {
		inspectedReturn(123_400, "STORE_CREDIT");
		inspectedReturn(56_700, "ORIGINAL_PAYMENT");
		mvc.perform(get("/api/v1/finance/ledger").with(as(finance)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanced").value(true))
				.andExpect(jsonPath("$.entries[?(@.account == 'STORE_CREDIT_LIABILITY' && @.amountPaise == 123400)]").exists());
		Long imbalance = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_paise "
				+ "ELSE -amount_paise END), 0) FROM ledger_entry", Long.class);
		assertThat(imbalance).isZero();
		mvc.perform(get("/api/v1/finance/ledger").param("from", "2026-12-01").param("to", "2026-11-01").with(as(finance)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theGatewayIsNeverCalledInsideATransaction() throws Exception {
		faults.apply(new FaultSettings(FaultMode.FAIL, 0));
		String rma = inspectedReturn(77_700, "ORIGINAL_PAYMENT");
		faults.apply(FaultSettings.none());
		long id = flow.refundId(rma);
		assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> refunds.execute(id)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("inside a database transaction");
		assertThat(flow.gatewayPayments(rma)).isZero();
	}
}
