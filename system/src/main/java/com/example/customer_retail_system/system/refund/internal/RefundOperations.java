package com.example.customer_retail_system.system.refund.internal;

import com.example.customer_retail_system.system.refund.RefundView;

import org.springframework.stereotype.Component;

/** Lets the module's public API reach the package-private executor and view mapper. */
@Component
public class RefundOperations {

	private final RefundExecutor executor;
	private final RefundViews views;

	RefundOperations(RefundExecutor executor, RefundViews views) {
		this.executor = executor;
		this.views = views;
	}

	public RefundView execute(long refundId) {
		return executor.execute(refundId);
	}

	public RefundView view(Refund refund) {
		return views.of(refund);
	}
}
