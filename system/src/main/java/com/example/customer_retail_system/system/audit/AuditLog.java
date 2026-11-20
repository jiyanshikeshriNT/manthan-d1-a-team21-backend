package com.example.customer_retail_system.system.audit;

import java.time.Clock;
import java.util.List;

import com.example.customer_retail_system.system.audit.internal.AuditLogEntry;
import com.example.customer_retail_system.system.audit.internal.AuditLogRepository;
import com.example.customer_retail_system.system.shared.PiiMasker;
import org.slf4j.MDC;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the append-only audit log (G15). {@link #record} must run inside the caller's
 * transaction ({@code MANDATORY}), so an audit row commits or rolls back together with the
 * state change it describes. Details are PII-masked before they are stored.
 */
@Service
public class AuditLog {

	public static final String CORRELATION_ID_MDC_KEY = "correlationId";

	private final AuditLogRepository repository;
	private final Clock clock;

	AuditLog(AuditLogRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditEvent event) {
		repository.save(new AuditLogEntry(event.entityType(), event.entityId(), event.action(), event.fromState(),
				event.toState(), event.actor().id(), event.actor().role(), truncate(PiiMasker.mask(event.details())),
				MDC.get(CORRELATION_ID_MDC_KEY), clock.instant()));
	}

	@Transactional(readOnly = true)
	public List<AuditRecord> history(String entityType, String entityId) {
		return repository.findByEntityTypeAndEntityIdOrderByIdAsc(entityType, entityId).stream()
				.map(AuditLog::toRecord).toList();
	}

	/** Every audit row for an entity id, whatever its type (manager audit query); at most 500. */
	@Transactional(readOnly = true)
	public List<AuditRecord> historyForEntityId(String entityId) {
		return repository.findTop500ByEntityIdOrderByIdAsc(entityId).stream().map(AuditLog::toRecord).toList();
	}

	private static AuditRecord toRecord(AuditLogEntry e) {
		return new AuditRecord(e.getId(), e.getEntityType(), e.getEntityId(), e.getAction(), e.getFromState(),
				e.getToState(), e.getActor(), e.getActorRole(), e.getDetails(), e.getCorrelationId(), e.getCreatedAt());
	}

	private static String truncate(String details) {
		return details == null || details.length() <= 2000 ? details : details.substring(0, 2000);
	}
}
