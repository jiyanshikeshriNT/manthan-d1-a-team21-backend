package com.example.customer_retail_system.system.returns;

import static com.example.customer_retail_system.system.returns.ReturnStatus.APPROVED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.AWAITING_FINANCE;
import static com.example.customer_retail_system.system.returns.ReturnStatus.CANCELLED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.INSPECTED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.IN_TRANSIT;
import static com.example.customer_retail_system.system.returns.ReturnStatus.PENDING_REVIEW;
import static com.example.customer_retail_system.system.returns.ReturnStatus.RECEIVED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REFUNDED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REFUND_IN_PROGRESS;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REJECTED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REQUESTED;

import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.example.customer_retail_system.system.audit.AuditEvent;
import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for return status changes. Keep {@link #TRANSITIONS} identical to the
 * list in CLAUDE.md, the transition-table test and LLD.md.
 */
@Component
public class ReturnStateMachine {

	public static final String AUDIT_ENTITY_TYPE = "RETURN";

	private static final Map<ReturnStatus, Set<ReturnStatus>> TRANSITIONS;

	static {
		Map<ReturnStatus, Set<ReturnStatus>> t = new EnumMap<>(ReturnStatus.class);
		for (ReturnStatus s : ReturnStatus.values()) {
			t.put(s, EnumSet.noneOf(ReturnStatus.class));
		}
		t.put(REQUESTED, EnumSet.of(APPROVED, PENDING_REVIEW, CANCELLED));
		t.put(PENDING_REVIEW, EnumSet.of(APPROVED, REJECTED, CANCELLED));
		t.put(APPROVED, EnumSet.of(IN_TRANSIT, CANCELLED));
		t.put(IN_TRANSIT, EnumSet.of(RECEIVED));
		t.put(RECEIVED, EnumSet.of(INSPECTED));
		t.put(INSPECTED, EnumSet.of(AWAITING_FINANCE, REFUND_IN_PROGRESS, REJECTED));
		t.put(AWAITING_FINANCE, EnumSet.of(REFUND_IN_PROGRESS, REJECTED));
		t.put(REFUND_IN_PROGRESS, EnumSet.of(REFUNDED));
		t.replaceAll((k, v) -> Collections.unmodifiableSet(v));
		TRANSITIONS = Collections.unmodifiableMap(t);
	}

	private final AuditLog auditLog;
	private final Clock clock;

	public ReturnStateMachine(AuditLog auditLog, Clock clock) {
		this.auditLog = auditLog;
		this.clock = clock;
	}

	public static boolean isAllowed(ReturnStatus from, ReturnStatus to) {
		return TRANSITIONS.get(from).contains(to);
	}

	public static Set<ReturnStatus> allowedFrom(ReturnStatus from) {
		return TRANSITIONS.get(from);
	}

	/** Audits the creation of a new return (status REQUESTED) in the caller's transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void start(ReturnRequest ret, Actor actor, String details) {
		if (ret.getStatus() != REQUESTED) {
			throw new IllegalStateException("A new return must start in REQUESTED");
		}
		auditLog.record(new AuditEvent(AUDIT_ENTITY_TYPE, ret.getId().toString(), "CREATED", null,
				REQUESTED.name(), actor, details));
	}

	/**
	 * Validates and applies {@code ret.status → target}, then writes the audit row in the same
	 * transaction. The version bump happens on flush ({@code @Version}), so a concurrent change to the
	 * same return fails with a 409 instead of being lost.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void transition(ReturnRequest ret, ReturnStatus target, Actor actor, String reason) {
		ReturnStatus from = ret.getStatus();
		if (!isAllowed(from, target)) {
			throw new DomainException(ErrorCode.INVALID_TRANSITION, refusal(from, target),
					Map.of("currentStatus", from.name(), "requestedStatus", target.name()));
		}
		ret.changeStatus(target, clock.instant());
		auditLog.record(new AuditEvent(AUDIT_ENTITY_TYPE, ret.getId().toString(), "STATUS_CHANGED", from.name(),
				target.name(), actor, reason));
	}

	/** Customer/staff-facing copy for refused transitions. */
	static String refusal(ReturnStatus from, ReturnStatus to) {
		if (from.isTerminal()) {
			return "This return is already closed.";
		}
		if (to == CANCELLED) {
			return "This return can't be cancelled because it is already on its way to us.";
		}
		if (to == IN_TRANSIT && (from == REQUESTED || from == PENDING_REVIEW)) {
			return "Your return is still being reviewed. You can ship it once it's approved.";
		}
		if (to == IN_TRANSIT) {
			return "This return has already been shipped.";
		}
		return "This return can't move from " + from + " to " + to + ".";
	}
}
