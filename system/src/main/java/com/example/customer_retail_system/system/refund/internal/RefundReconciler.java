package com.example.customer_retail_system.system.refund.internal;

import java.util.List;
import java.util.Optional;

import com.example.customer_retail_system.gateway.api.PaymentGateway;
import com.example.customer_retail_system.gateway.api.PaymentResult;
import com.example.customer_retail_system.gateway.api.PaymentStatus;
import com.example.customer_retail_system.system.refund.RefundStatus;
import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.returns.RefundMethod;
import com.example.customer_retail_system.system.shared.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Settles refunds whose outcome is unknown (LLD §3 flow 6). It always asks the gateway for the
 * status of the deterministic key first; only if the gateway has no record does it pay again —
 * with the same key, so a payment can never happen twice. Also rescues refunds left PENDING or
 * PROCESSING by a crash. After {@code maxAttempts} the refund goes FAILED for finance to retry.
 */
@Component
class RefundReconciler {

	private static final Logger log = LoggerFactory.getLogger(RefundReconciler.class);

	private final RefundSettlement settlement;
	private final RefundExecutor executor;
	private final PaymentGateway gateway;
	private final AppProperties properties;

	RefundReconciler(RefundSettlement settlement, RefundExecutor executor, PaymentGateway gateway,
			AppProperties properties) {
		this.settlement = settlement;
		this.executor = executor;
		this.gateway = gateway;
		this.properties = properties;
	}

	/** @return how many refunds were looked at */
	int reconcileDue() {
		List<Long> due = settlement.dueForReconcile();
		for (Long id : due) {
			try {
				reconcile(id);
			}
			catch (RuntimeException ex) {
				log.error("Reconciling refund {} failed", id, ex);
			}
		}
		return due.size();
	}

	RefundView reconcile(long id) {
		RefundExecutor.requireNoTransaction();
		settlement.releaseIfStuck(id);
		RefundSettlement.Snapshot s = settlement.snapshot(id);
		if (s.status() == RefundStatus.PENDING) {
			return executor.execute(id); // approved/created but never executed (crash before execution)
		}
		if (s.status() != RefundStatus.UNKNOWN) {
			return settlement.view(id);
		}
		if (s.attempts() >= properties.refund().maxAttempts()) {
			settlement.fail(id, "Gave up after " + s.attempts() + " attempts; finance can retry with the same key");
			return settlement.view(id);
		}
		if (s.method() == RefundMethod.STORE_CREDIT) {
			return executor.execute(id);
		}
		try {
			Optional<PaymentResult> known = executor.call(() -> gateway.status(s.idempotencyKey()));
			if (known.isEmpty()) {
				return executor.execute(id); // gateway never saw it: pay now, same key
			}
			if (known.get().status() == PaymentStatus.SUCCEEDED) {
				settlement.succeed(id, known.get().transactionId());
			}
			else {
				settlement.fail(id, "Declined by gateway: " + known.get().message());
			}
		}
		catch (RefundExecutor.UnknownOutcome ex) {
			settlement.deferUnknown(id, "Status check failed: " + ex.getMessage());
		}
		return settlement.view(id);
	}
}
