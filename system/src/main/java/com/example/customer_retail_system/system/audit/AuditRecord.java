package com.example.customer_retail_system.system.audit;

import java.time.Instant;

/** A stored audit row (read model). */
public record AuditRecord(
		long id,
		String entityType,
		String entityId,
		String action,
		String fromState,
		String toState,
		String actor,
		String actorRole,
		String details,
		String correlationId,
		Instant createdAt) {
}
