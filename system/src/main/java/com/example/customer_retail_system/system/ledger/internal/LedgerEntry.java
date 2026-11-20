package com.example.customer_retail_system.system.ledger.internal;

import java.time.Instant;

import com.example.customer_retail_system.system.ledger.EntryDirection;
import com.example.customer_retail_system.system.ledger.LedgerAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/** Immutable ledger posting. */
@Entity
@Immutable
@Table(name = "ledger_entry")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "refund_id", nullable = false)
	private Long refundId;

	@Column(name = "customer_id", nullable = false)
	private Long customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private LedgerAccount account;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 6)
	private EntryDirection direction;

	@Column(name = "amount_paise", nullable = false)
	private long amountPaise;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected LedgerEntry() {
	}

	public LedgerEntry(long refundId, long customerId, LedgerAccount account, EntryDirection direction,
			long amountPaise, Instant createdAt) {
		if (amountPaise <= 0) {
			throw new IllegalArgumentException("ledger amounts must be > 0");
		}
		this.refundId = refundId;
		this.customerId = customerId;
		this.account = account;
		this.direction = direction;
		this.amountPaise = amountPaise;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public long getRefundId() {
		return refundId;
	}

	public long getCustomerId() {
		return customerId;
	}

	public LedgerAccount getAccount() {
		return account;
	}

	public EntryDirection getDirection() {
		return direction;
	}

	public long getAmountPaise() {
		return amountPaise;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
