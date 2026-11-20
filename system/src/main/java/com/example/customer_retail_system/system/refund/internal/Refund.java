package com.example.customer_retail_system.system.refund.internal;

import java.time.Instant;
import java.util.UUID;

import com.example.customer_retail_system.system.refund.RefundStatus;
import com.example.customer_retail_system.system.returns.RefundMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One refund per return ({@code return_id} UNIQUE). After creation its state only changes through
 * the conditional updates in {@link RefundRepository} (see ADR-001), never through setters.
 */
@Entity
@Table(name = "refund")
public class Refund {

	public static final String KEY_PREFIX = "RR-REFUND-";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "return_id", nullable = false, unique = true)
	private UUID returnId;

	@Column(name = "amount_paise", nullable = false)
	private long amountPaise;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RefundMethod method;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RefundStatus status;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 80)
	private String idempotencyKey;

	@Column(name = "gateway_txn_id", length = 64)
	private String gatewayTxnId;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "requires_approval", nullable = false)
	private boolean requiresApproval;

	@Column(name = "approved_by", length = 100)
	private String approvedBy;

	@Column(name = "approved_at")
	private Instant approvedAt;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	protected Refund() {
	}

	public Refund(UUID returnId, long amountPaise, RefundMethod method, boolean requiresApproval, Instant now) {
		this.returnId = returnId;
		this.amountPaise = amountPaise;
		this.method = method;
		this.requiresApproval = requiresApproval;
		this.status = requiresApproval ? RefundStatus.AWAITING_APPROVAL : RefundStatus.PENDING;
		this.idempotencyKey = keyFor(returnId);
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Deterministic gateway key: derived from the return, never random, never per attempt (ADR-001). */
	public static String keyFor(UUID returnId) {
		return KEY_PREFIX + returnId;
	}

	public Long getId() {
		return id;
	}

	public UUID getReturnId() {
		return returnId;
	}

	public long getAmountPaise() {
		return amountPaise;
	}

	public RefundMethod getMethod() {
		return method;
	}

	public RefundStatus getStatus() {
		return status;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getGatewayTxnId() {
		return gatewayTxnId;
	}

	public int getAttempts() {
		return attempts;
	}

	public boolean isRequiresApproval() {
		return requiresApproval;
	}

	public String getApprovedBy() {
		return approvedBy;
	}

	public Instant getApprovedAt() {
		return approvedAt;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}
}
