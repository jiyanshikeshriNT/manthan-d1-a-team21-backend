package com.example.customer_retail_system.system.audit.internal;

import java.util.List;

import org.springframework.data.repository.Repository;

/**
 * Append-only by construction: extends the marker {@link Repository}, not CrudRepository, so no
 * update or delete operations exist.
 */
public interface AuditLogRepository extends Repository<AuditLogEntry, Long> {

	AuditLogEntry save(AuditLogEntry entry);

	List<AuditLogEntry> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, String entityId);

	long countByEntityTypeAndEntityId(String entityType, String entityId);

	List<AuditLogEntry> findTop500ByEntityIdOrderByIdAsc(String entityId);
}
