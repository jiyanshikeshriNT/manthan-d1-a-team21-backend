package com.example.customer_retail_system.system.refund.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.ledger.LedgerReport;
import com.example.customer_retail_system.system.ledger.LedgerService;
import com.example.customer_retail_system.system.refund.RefundStatus;
import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.returns.ReturnsApi;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.DateRange;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import com.example.customer_retail_system.system.shared.Money;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance actions (LLD §3 flow 7). Approve/reject are conditional updates from AWAITING_APPROVAL, so
 * a double approval is a no-op and an approve/reject race has exactly one winner (failure modes 2, 3).
 * Methods that lead to a payment return {@code true}; the caller executes the refund after commit.
 */
@Service
class FinanceService {

	private final RefundRepository refunds;
	private final RefundSettlement settlement;
	private final RefundViews views;
	private final ReturnsApi returns;
	private final ReturnStateMachine stateMachine;
	private final LedgerService ledger;
	private final Clock clock;
	private final AppProperties properties;

	FinanceService(RefundRepository refunds, RefundSettlement settlement, RefundViews views, ReturnsApi returns,
			ReturnStateMachine stateMachine, LedgerService ledger, Clock clock, AppProperties properties) {
		this.refunds = refunds;
		this.settlement = settlement;
		this.views = views;
		this.returns = returns;
		this.stateMachine = stateMachine;
		this.ledger = ledger;
		this.clock = clock;
		this.properties = properties;
	}

	@Transactional(readOnly = true)
	List<RefundView> list(RefundStatus status) {
		return views.of(status == null ? refunds.findAllByOrderByCreatedAtDesc()
				: refunds.findByStatusOrderByCreatedAtAsc(status));
	}

	/** AWAITING_APPROVAL → PENDING and return AWAITING_FINANCE → REFUND_IN_PROGRESS. */
	@Transactional
	boolean approve(long id, Actor actor) {
		Refund refund = settlement.require(id);
		if (refunds.approve(id, actor.id(), clock.instant()) == 1) {
			ReturnRequest ret = returns.requireById(refund.getReturnId());
			String details = "Refund of " + Money.format(refund.getAmountPaise()) + " approved by finance";
			stateMachine.transition(ret, ReturnStatus.REFUND_IN_PROGRESS, actor, details);
			settlement.audit(id, "APPROVED", RefundStatus.AWAITING_APPROVAL, RefundStatus.PENDING, actor, details);
			return true;
		}
		RefundStatus current = currentStatus(id);
		return switch (current) {
			case PENDING, PROCESSING, UNKNOWN, SUCCEEDED -> false; // already approved: no-op
			case REJECTED -> throw conflict(current, "This refund has already been rejected.");
			case FAILED -> throw conflict(current, "This refund failed. Use retry instead.");
			case AWAITING_APPROVAL -> throw conflict(current, "This refund changed while you were approving it. Try again.");
		};
	}

	/** AWAITING_APPROVAL → REJECTED and the return → REJECTED, with a required reason. */
	@Transactional
	void reject(long id, String reason, Actor actor) {
		Refund refund = settlement.require(id);
		if (refunds.move(id, java.util.EnumSet.of(RefundStatus.AWAITING_APPROVAL), RefundStatus.REJECTED, reason, null,
				clock.instant()) == 1) {
			ReturnRequest ret = returns.requireById(refund.getReturnId());
			String details = "Refund rejected by finance: " + reason;
			stateMachine.transition(ret, ReturnStatus.REJECTED, actor, details);
			settlement.audit(id, "REJECTED", RefundStatus.AWAITING_APPROVAL, RefundStatus.REJECTED, actor, details);
			return;
		}
		RefundStatus current = currentStatus(id);
		if (current != RefundStatus.REJECTED) {
			throw conflict(current, "This refund has already been approved and can't be rejected.");
		}
	}

	/** Manual retry with the same key: FAILED is reset to PENDING; UNKNOWN/PENDING are executed as they are. */
	@Transactional
	boolean prepareRetry(long id, Actor actor) {
		RefundStatus current = settlement.require(id).getStatus();
		return switch (current) {
			case FAILED -> {
				if (refunds.resetForRetry(id, clock.instant()) != 1) {
					yield false;
				}
				settlement.audit(id, "RETRY", RefundStatus.FAILED, RefundStatus.PENDING, actor, "Retried by finance");
				yield true;
			}
			case UNKNOWN, PENDING -> true;
			case PROCESSING, SUCCEEDED -> false;
			case AWAITING_APPROVAL -> throw conflict(current, "Approve this refund before retrying it.");
			case REJECTED -> throw conflict(current, "This refund was rejected and can't be retried.");
		};
	}

	@Transactional(readOnly = true)
	RefundView view(long id) {
		return views.of(settlement.require(id));
	}

	LedgerReport ledger(LocalDate from, LocalDate to) {
		return ledger.report(DateRange.resolve(from, to, clock, properties.zone(), 30));
	}

	private RefundStatus currentStatus(long id) {
		return refunds.findStatus(id).orElseThrow();
	}

	private static DomainException conflict(RefundStatus current, String message) {
		return new DomainException(ErrorCode.INVALID_TRANSITION, message, Map.of("currentStatus", current.name()));
	}
}
