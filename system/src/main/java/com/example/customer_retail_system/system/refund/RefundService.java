package com.example.customer_retail_system.system.refund;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.example.customer_retail_system.system.audit.AuditEvent;
import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.refund.internal.Refund;
import com.example.customer_retail_system.system.refund.internal.RefundOperations;
import com.example.customer_retail_system.system.refund.internal.RefundRepository;
import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.Money;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the refund module for other modules. */
@Service
public class RefundService {

	private final RefundRepository refunds;
	private final RefundOperations operations;
	private final ReturnStateMachine stateMachine;
	private final AuditLog auditLog;
	private final Clock clock;
	private final AppProperties properties;

	RefundService(RefundRepository refunds, RefundOperations operations, ReturnStateMachine stateMachine,
			AuditLog auditLog, Clock clock, AppProperties properties) {
		this.refunds = refunds;
		this.operations = operations;
		this.stateMachine = stateMachine;
		this.auditLog = auditLog;
		this.clock = clock;
		this.properties = properties;
	}

	public record RefundRouting(long refundId, boolean requiresApproval) {
	}

	/**
	 * Creates the (single) refund for an INSPECTED return in the caller's transaction and routes it:
	 * total strictly above {@code app.refund.approval-threshold-paise} → AWAITING_FINANCE (refund
	 * AWAITING_APPROVAL); otherwise → REFUND_IN_PROGRESS (refund PENDING), to be executed by the
	 * caller <em>after</em> the transaction commits.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public RefundRouting openForInspectedReturn(ReturnRequest ret, Actor actor) {
		long total = ret.getTotalRefundPaise();
		boolean requiresApproval = total > properties.refund().approvalThresholdPaise();
		Refund refund = refunds.saveAndFlush(new Refund(ret.getId(), total, ret.getRefundMethod(), requiresApproval,
				clock.instant()));
		String details = requiresApproval
				? "Refund of " + Money.format(total) + " needs finance approval (over "
						+ Money.format(properties.refund().approvalThresholdPaise()) + ")"
				: "Refund of " + Money.format(total) + " started";
		stateMachine.transition(ret, requiresApproval ? ReturnStatus.AWAITING_FINANCE : ReturnStatus.REFUND_IN_PROGRESS,
				actor, details);
		auditLog.record(new AuditEvent("REFUND", String.valueOf(refund.getId()), "CREATED", null,
				refund.getStatus().name(), actor, details));
		return new RefundRouting(refund.getId(), requiresApproval);
	}

	/** Executes a refund; must be called outside any transaction (the gateway is called here). */
	public RefundView execute(long refundId) {
		return operations.execute(refundId);
	}

	@Transactional(readOnly = true)
	public Optional<RefundView> findByReturnId(UUID returnId) {
		return refunds.findByReturnId(returnId).map(operations::view);
	}
}
