package com.example.customer_retail_system.system.audit;

import com.example.customer_retail_system.system.shared.Actor;

/** One fact to append to the audit log. {@code fromState}/{@code toState} are null when not a state change. */
public record AuditEvent(
		String entityType,
		String entityId,
		String action,
		String fromState,
		String toState,
		Actor actor,
		String details) {
}
