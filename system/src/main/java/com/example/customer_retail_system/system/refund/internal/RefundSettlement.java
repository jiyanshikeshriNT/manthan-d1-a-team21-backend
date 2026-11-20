package com.example.customer_retail_system.system.refund.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.example.customer_retail_system.system.audit.AuditEvent;
import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.ledger.LedgerService;
import com.example.customer_retail_system.system.order.OrderService;
import com.example.customer_retail_system.system.order.OrderView;
import com.example.customer_retail_system.system.refund.RefundStatus;
import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.returns.RefundMethod;
import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.returns.ReturnsApi;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import com.example.customer_retail_system.system.shared.Money;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short database transactions around a gateway call (ADR-001): each method is one transaction
 * and every state change is conditional, so racing callers can never both win.
 */
@Component
class RefundSettlement {

	static final String AUDIT_ENTITY_TYPE = "REFUND";
	private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

	private final RefundRepository refunds;
	private final RefundViews views;
	private final ReturnsApi returns;
	private final ReturnStateMachine stateMachine;
	private final LedgerService ledger;
	private final AuditLog auditLog;
	private final OrderService orders;
	private final Clock clock;
	private final AppProperties properties;

	RefundSettlement(RefundRepository refunds, RefundViews views, ReturnsApi returns, ReturnStateMachine stateMachine,
			LedgerService ledger, AuditLog auditLog, OrderService orders, Clock clock, AppProperties properties) {
		this.refunds = refunds;
		this.views = views;
		this.returns = returns;
		this.stateMachine = stateMachine;
		this.ledger = ledger;
		this.auditLog = auditLog;
		this.orders = orders;
		this.clock = clock;
		this.properties = properties;
	}

	/** What the executor needs to call the gateway, read in its own short transaction. */
	record Snapshot(long id, UUID returnId, String rma, long amountPaise, RefundMethod method, String idempotencyKey,
			String originalPaymentRef, RefundStatus status, int attempts) {
	}

	@Transactional(readOnly = true)
	Snapshot snapshot(long id) {
		Refund r = require(id);
		ReturnRequest ret = returns.requireById(r.getReturnId());
		String paymentRef = orders.findById(ret.getOrderId()).map(OrderView::paymentRef).orElse(null);
		return new Snapshot(r.getId(), r.getReturnId(), ret.getRmaNumber(), r.getAmountPaise(), r.getMethod(),
				r.getIdempotencyKey(), paymentRef, r.getStatus(), r.getAttempts());
	}

	@Transactional(readOnly = true)
	RefundView view(long id) {
		return views.of(require(id));
	}

	@Transactional(readOnly = true)
	List<Long> dueForReconcile() {
		Instant now = clock.instant();
		return refunds.findDueForReconcile(now, now.minus(properties.refund().stuckAfter()));
	}

	/** tx1: PENDING/UNKNOWN → PROCESSING. False if another caller already owns (or finished) it. */
	@Transactional
	boolean claim(long id) {
		return refunds.claim(id, EnumSet.of(RefundStatus.PENDING, RefundStatus.UNKNOWN), clock.instant()) == 1;
	}

	/**
	 * tx2 on success: SUCCEEDED + balanced ledger pair + return → REFUNDED + audit, atomically.
	 * Conditional on PROCESSING/UNKNOWN, so a refund can only ever be settled (and posted) once.
	 */
	@Transactional
	boolean succeed(long id, String gatewayTxnId) {
		Instant now = clock.instant();
		if (refunds.succeed(id, EnumSet.of(RefundStatus.PROCESSING, RefundStatus.UNKNOWN), gatewayTxnId, now) != 1) {
			return false;
		}
		Refund r = require(id);
		ReturnRequest ret = returns.requireById(r.getReturnId());
		boolean storeCredit = r.getMethod() == RefundMethod.STORE_CREDIT;
		ledger.postRefund(r.getId(), ret.getCustomerId(), r.getAmountPaise(), storeCredit);
		String details = storeCredit ? "Store credit of " + Money.format(r.getAmountPaise()) + " posted"
				: "Refund of " + Money.format(r.getAmountPaise()) + " paid (gateway txn " + gatewayTxnId + ")";
		stateMachine.transition(ret, ReturnStatus.REFUNDED, Actor.system(), details);
		audit(id, "SUCCEEDED", null, RefundStatus.SUCCEEDED, Actor.system(), details);
		return true;
	}

	/** Timeout/5xx: the outcome is unknown, never FAILED. Retried later with exponential backoff. */
	@Transactional
	void markUnknown(long id, String error) {
		Refund r = require(id);
		Instant now = clock.instant();
		if (refunds.move(id, EnumSet.of(RefundStatus.PROCESSING), RefundStatus.UNKNOWN, truncate(error),
				now.plus(backoff(r.getAttempts())), now) == 1) {
			audit(id, "OUTCOME_UNKNOWN", RefundStatus.PROCESSING, RefundStatus.UNKNOWN, Actor.system(), error);
		}
	}

	/** A failed status check also counts as an attempt, so an outage eventually hands over to finance. */
	@Transactional
	void deferUnknown(long id, String error) {
		Refund r = require(id);
		Instant now = clock.instant();
		refunds.deferUnknown(id, truncate(error), now.plus(backoff(r.getAttempts() + 1)), now);
	}

	@Transactional
	void fail(long id, String error) {
		if (refunds.move(id, EnumSet.of(RefundStatus.PROCESSING, RefundStatus.UNKNOWN), RefundStatus.FAILED,
				truncate(error), null, clock.instant()) == 1) {
			audit(id, "FAILED", null, RefundStatus.FAILED, Actor.system(), error);
		}
	}

	/** A PROCESSING refund untouched for {@code stuckAfter} (crash between tx1 and tx2) becomes UNKNOWN. */
	@Transactional
	void releaseIfStuck(long id) {
		Instant now = clock.instant();
		refunds.releaseStuck(id, now.minus(properties.refund().stuckAfter()), now);
	}

	Refund require(long id) {
		return refunds.findById(id)
				.orElseThrow(() -> new DomainException(ErrorCode.REFUND_NOT_FOUND, "We couldn't find that refund."));
	}

	void audit(long refundId, String action, RefundStatus from, RefundStatus to, Actor actor, String details) {
		auditLog.record(new AuditEvent(AUDIT_ENTITY_TYPE, String.valueOf(refundId), action,
				from == null ? null : from.name(), to == null ? null : to.name(), actor, details));
	}

	/** initialBackoff × 2^(attempts−1), capped at 10 minutes. */
	Duration backoff(int attempts) {
		Duration d = properties.refund().initialBackoff().multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 10));
		return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
	}

	private static String truncate(String s) {
		return s == null || s.length() <= 500 ? s : s.substring(0, 500);
	}
}
