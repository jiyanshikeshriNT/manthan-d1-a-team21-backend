package com.example.customer_retail_system.system.returns;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The return aggregate. Its status can only be changed by {@link ReturnStateMachine} (the setter is
 * package-private), which validates the transition and writes the audit row in the same transaction.
 */
@Entity
@Table(name = "return_request")
public class ReturnRequest {

	@Id
	private UUID id;

	@Column(name = "rma_number", nullable = false, unique = true, length = 20)
	private String rmaNumber;

	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "customer_id", nullable = false)
	private Long customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReturnStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "refund_method", nullable = false, length = 20)
	private RefundMethod refundMethod;

	@Column(nullable = false)
	private boolean flagged;

	@Column(name = "label_code", nullable = false, unique = true, length = 64)
	private String labelCode;

	/** {@code {orderId}:{Idempotency-Key}} — scoped per order so keys from different orders never collide. */
	@Column(name = "client_request_key", unique = true, length = 160)
	private String clientRequestKey;

	@Column(name = "total_refund_paise")
	private Long totalRefundPaise;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	/** Wrapper type so Spring Data treats a fresh aggregate (assigned UUID) as new. */
	@Version
	@Column(nullable = false)
	private Long version;

	@OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<ReturnItem> items = new ArrayList<>();

	protected ReturnRequest() {
	}

	public ReturnRequest(UUID id, String rmaNumber, long orderId, long customerId, RefundMethod refundMethod,
			boolean flagged, String labelCode, String clientRequestKey, Instant createdAt) {
		this.id = id;
		this.rmaNumber = rmaNumber;
		this.orderId = orderId;
		this.customerId = customerId;
		this.status = ReturnStatus.REQUESTED;
		this.refundMethod = refundMethod;
		this.flagged = flagged;
		this.labelCode = labelCode;
		this.clientRequestKey = clientRequestKey;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public ReturnItem addItem(long orderLineId, int requestedQty, ReasonCode reasonCode, String reasonText) {
		ReturnItem item = new ReturnItem(this, orderLineId, requestedQty, reasonCode, reasonText);
		items.add(item);
		return item;
	}

	/** Total refund after grading (sum of item refunds), set at inspection. */
	public void recordTotalRefund(long totalRefundPaise) {
		if (totalRefundPaise < 0) {
			throw new IllegalArgumentException("total refund must be >= 0");
		}
		this.totalRefundPaise = totalRefundPaise;
	}

	/** Only {@link ReturnStateMachine} calls this. */
	void changeStatus(ReturnStatus target, Instant at) {
		this.status = target;
		this.updatedAt = at;
		if (target.isTerminal()) {
			this.closedAt = at;
		}
	}

	public UUID getId() {
		return id;
	}

	public String getRmaNumber() {
		return rmaNumber;
	}

	public long getOrderId() {
		return orderId;
	}

	public long getCustomerId() {
		return customerId;
	}

	public ReturnStatus getStatus() {
		return status;
	}

	public RefundMethod getRefundMethod() {
		return refundMethod;
	}

	public boolean isFlagged() {
		return flagged;
	}

	public String getLabelCode() {
		return labelCode;
	}

	public String getClientRequestKey() {
		return clientRequestKey;
	}

	public Long getTotalRefundPaise() {
		return totalRefundPaise;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

	public Long getVersion() {
		return version;
	}

	public List<ReturnItem> getItems() {
		return Collections.unmodifiableList(items);
	}
}
