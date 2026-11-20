package com.example.customer_retail_system.system.returns.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.audit.AuditRecord;
import com.example.customer_retail_system.system.order.OrderService;
import com.example.customer_retail_system.system.order.OrderView;
import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.ReturnDto;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operations-manager review of flagged returns (G7, G8). Decisions apply only to returns still in
 * PENDING_REVIEW: a stale decision (e.g. the customer cancelled meanwhile) gets 409 (failure mode 10);
 * repeating the same decision is a no-op.
 */
@Service
class ManagerReviewService {

	private final ReturnRequestRepository returns;
	private final ReturnStateMachine stateMachine;
	private final ReturnService returnService;
	private final OrderService orders;
	private final AuditLog auditLog;

	ManagerReviewService(ReturnRequestRepository returns, ReturnStateMachine stateMachine, ReturnService returnService,
			OrderService orders, AuditLog auditLog) {
		this.returns = returns;
		this.stateMachine = stateMachine;
		this.returnService = returnService;
		this.orders = orders;
		this.auditLog = auditLog;
	}

	record ReviewItem(UUID returnId, String rma, String orderNumber, long customerId, Instant requestedAt, int itemCount,
			int units, String refundMethod, String flagReason) {
	}

	@Transactional(readOnly = true)
	List<ReviewItem> pending() {
		return returns.findByStatusOrderByCreatedAtAsc(ReturnStatus.PENDING_REVIEW).stream().map(r -> {
			String orderNumber = orders.findById(r.getOrderId()).map(OrderView::orderNumber).orElse(null);
			String reason = auditLog.history(ReturnStateMachine.AUDIT_ENTITY_TYPE, r.getId().toString()).stream()
					.filter(a -> ReturnStatus.PENDING_REVIEW.name().equals(a.toState()))
					.map(AuditRecord::details).findFirst().orElse(null);
			int units = r.getItems().stream().mapToInt(i -> i.getRequestedQty()).sum();
			return new ReviewItem(r.getId(), r.getRmaNumber(), orderNumber, r.getCustomerId(), r.getCreatedAt(),
					r.getItems().size(), units, r.getRefundMethod().name(), reason);
		}).toList();
	}

	@Transactional
	ReturnDto approve(String rma, Actor actor) {
		return decide(rma, ReturnStatus.APPROVED, actor, "Approved after manager review");
	}

	@Transactional
	ReturnDto reject(String rma, String reason, Actor actor) {
		return decide(rma, ReturnStatus.REJECTED, actor, "Rejected after manager review: " + reason);
	}

	private ReturnDto decide(String rma, ReturnStatus target, Actor actor, String details) {
		ReturnRequest ret = returns.findByRmaNumber(rma)
				.orElseThrow(() -> new DomainException(ErrorCode.RETURN_NOT_FOUND, ReturnService.RETURN_NOT_FOUND_COPY));
		if (ret.getStatus() == ReturnStatus.PENDING_REVIEW) {
			stateMachine.transition(ret, target, actor, details);
		}
		else if (ret.getStatus() != target) {
			throw new DomainException(ErrorCode.INVALID_TRANSITION, "This return is no longer awaiting review.",
					Map.of("currentStatus", ret.getStatus().name()));
		}
		return returnService.toDto(ret, returnService.orderNumber(ret));
	}
}
