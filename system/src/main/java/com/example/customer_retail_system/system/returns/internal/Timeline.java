package com.example.customer_retail_system.system.returns.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.customer_retail_system.system.audit.AuditRecord;
import com.example.customer_retail_system.system.returns.ReturnStatus;

/**
 * Customer-facing timeline built from the return's audit history. The customer sees six steps;
 * internal states map onto them (PENDING_REVIEW shows as "Requested" + "Under review", G7).
 * A rejected or cancelled return shows the steps it reached, then a final "failed" step.
 */
final class Timeline {

	static final List<String> STEPS = List.of("Requested", "Approved", "In transit", "Received", "Inspected", "Refunded");

	record Entry(String step, String state, Instant at, String note) {
	}

	private Timeline() {
	}

	static int stepIndex(ReturnStatus status) {
		return switch (status) {
			case REQUESTED, PENDING_REVIEW -> 0;
			case APPROVED -> 1;
			case IN_TRANSIT -> 2;
			case RECEIVED -> 3;
			case INSPECTED, AWAITING_FINANCE, REFUND_IN_PROGRESS -> 4;
			case REFUNDED -> 5;
			case REJECTED, CANCELLED -> -1;
		};
	}

	static String statusLabel(ReturnStatus status) {
		return switch (status) {
			case REJECTED -> "Rejected";
			case CANCELLED -> "Cancelled";
			default -> STEPS.get(stepIndex(status));
		};
	}

	static String note(ReturnStatus status) {
		return switch (status) {
			case PENDING_REVIEW -> "Under review";
			case AWAITING_FINANCE -> "Awaiting refund approval";
			case REFUND_IN_PROGRESS -> "Refund processing";
			default -> null;
		};
	}

	static List<Entry> build(ReturnStatus status, List<AuditRecord> history) {
		Instant[] reached = new Instant[STEPS.size()];
		AuditRecord closing = null;
		for (AuditRecord r : history) {
			if (r.toState() == null) {
				continue;
			}
			ReturnStatus to = ReturnStatus.valueOf(r.toState());
			int idx = stepIndex(to);
			if (idx >= 0 && reached[idx] == null) {
				reached[idx] = r.createdAt();
			}
			if (to == ReturnStatus.REJECTED || to == ReturnStatus.CANCELLED) {
				closing = r;
			}
		}

		List<Entry> entries = new ArrayList<>();
		if (status == ReturnStatus.REJECTED || status == ReturnStatus.CANCELLED) {
			for (int i = 0; i < STEPS.size() && reached[i] != null; i++) {
				entries.add(new Entry(STEPS.get(i), "done", reached[i], null));
			}
			entries.add(new Entry(statusLabel(status), "failed", closing == null ? null : closing.createdAt(),
					closing == null ? null : closing.details()));
			return entries;
		}

		int current = stepIndex(status);
		for (int i = 0; i < STEPS.size(); i++) {
			if (i < current || status == ReturnStatus.REFUNDED) {
				entries.add(new Entry(STEPS.get(i), "done", reached[i], null));
			}
			else if (i == current) {
				entries.add(new Entry(STEPS.get(i), "current", reached[i], note(status)));
			}
			else {
				entries.add(new Entry(STEPS.get(i), "pending", null, null));
			}
		}
		return entries;
	}
}
