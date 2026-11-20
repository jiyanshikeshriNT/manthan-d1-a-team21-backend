package com.example.customer_retail_system.system.customer.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A customer was flagged for manual review by a return request (G8). Active while {@code clearedAt} is null. */
@Entity
@Table(name = "customer_flag")
public class CustomerFlag {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "customer_id", nullable = false)
	private Long customerId;

	@Column(name = "return_id", nullable = false)
	private UUID returnId;

	@Column(name = "return_count", nullable = false)
	private int returnCount;

	@Column(name = "flagged_at", nullable = false)
	private Instant flaggedAt;

	@Column(name = "cleared_at")
	private Instant clearedAt;

	@Column(name = "cleared_by", length = 64)
	private String clearedBy;

	protected CustomerFlag() {
	}

	public CustomerFlag(long customerId, UUID returnId, int returnCount, Instant flaggedAt) {
		this.customerId = customerId;
		this.returnId = returnId;
		this.returnCount = returnCount;
		this.flaggedAt = flaggedAt;
	}

	public Long getId() {
		return id;
	}

	public long getCustomerId() {
		return customerId;
	}

	public UUID getReturnId() {
		return returnId;
	}

	public int getReturnCount() {
		return returnCount;
	}

	public Instant getFlaggedAt() {
		return flaggedAt;
	}

	public Instant getClearedAt() {
		return clearedAt;
	}

	public String getClearedBy() {
		return clearedBy;
	}
}
