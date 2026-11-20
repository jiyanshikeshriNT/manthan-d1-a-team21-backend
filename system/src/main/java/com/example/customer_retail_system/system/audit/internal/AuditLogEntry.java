package com.example.customer_retail_system.system.audit.internal;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/** Immutable audit row: no setters, and Hibernate never issues UPDATEs for it. */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLogEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "entity_type", nullable = false, length = 40)
	private String entityType;

	@Column(name = "entity_id", nullable = false, length = 64)
	private String entityId;

	@Column(nullable = false, length = 40)
	private String action;

	@Column(name = "from_state", length = 30)
	private String fromState;

	@Column(name = "to_state", length = 30)
	private String toState;

	@Column(nullable = false, length = 100)
	private String actor;

	@Column(name = "actor_role", nullable = false, length = 20)
	private String actorRole;

	@Column(length = 2000)
	private String details;

	@Column(name = "correlation_id", length = 64)
	private String correlationId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AuditLogEntry() {
	}

	public AuditLogEntry(String entityType, String entityId, String action, String fromState, String toState,
			String actor, String actorRole, String details, String correlationId, Instant createdAt) {
		this.entityType = entityType;
		this.entityId = entityId;
		this.action = action;
		this.fromState = fromState;
		this.toState = toState;
		this.actor = actor;
		this.actorRole = actorRole;
		this.details = details;
		this.correlationId = correlationId;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getEntityType() {
		return entityType;
	}

	public String getEntityId() {
		return entityId;
	}

	public String getAction() {
		return action;
	}

	public String getFromState() {
		return fromState;
	}

	public String getToState() {
		return toState;
	}

	public String getActor() {
		return actor;
	}

	public String getActorRole() {
		return actorRole;
	}

	public String getDetails() {
		return details;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
