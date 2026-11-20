package com.example.customer_retail_system.system.refund.internal;

import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.returns.ReturnsApi;

import org.springframework.stereotype.Component;

/** Maps refund entities to {@link RefundView}s, resolving RMA numbers in one query. */
@Component
class RefundViews {

	private final ReturnsApi returns;

	RefundViews(ReturnsApi returns) {
		this.returns = returns;
	}

	RefundView of(Refund r) {
		return of(r, returns.rmaNumbers(List.of(r.getReturnId())).get(r.getReturnId()));
	}

	List<RefundView> of(List<Refund> refunds) {
		Map<java.util.UUID, String> rmas = returns.rmaNumbers(refunds.stream().map(Refund::getReturnId).toList());
		return refunds.stream().map(r -> of(r, rmas.get(r.getReturnId()))).toList();
	}

	private static RefundView of(Refund r, String rma) {
		return new RefundView(r.getId(), r.getReturnId(), rma, r.getAmountPaise(), r.getMethod().name(), r.getStatus(),
				r.getAttempts(), r.isRequiresApproval(), r.getApprovedBy(), r.getApprovedAt(), r.getGatewayTxnId(),
				r.getLastError(), r.getNextAttemptAt(), r.getCreatedAt(), r.getUpdatedAt());
	}
}
