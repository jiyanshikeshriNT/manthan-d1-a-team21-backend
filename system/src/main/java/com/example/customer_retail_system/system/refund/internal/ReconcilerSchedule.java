package com.example.customer_retail_system.system.refund.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the reconciler every {@code app.refund.reconcile-interval-ms} (default 30 s). Off in tests. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.refund", name = "reconciler-enabled", havingValue = "true", matchIfMissing = true)
class ReconcilerSchedule {

	private final RefundReconciler reconciler;

	ReconcilerSchedule(RefundReconciler reconciler) {
		this.reconciler = reconciler;
	}

	@Scheduled(initialDelayString = "${app.refund.reconcile-interval-ms:30000}",
			fixedDelayString = "${app.refund.reconcile-interval-ms:30000}")
	void reconcile() {
		reconciler.reconcileDue();
	}
}
