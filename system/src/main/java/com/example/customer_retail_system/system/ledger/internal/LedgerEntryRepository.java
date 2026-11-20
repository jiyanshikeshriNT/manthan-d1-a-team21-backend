package com.example.customer_retail_system.system.ledger.internal;

import java.time.Instant;
import java.util.List;

import org.springframework.data.repository.Repository;

/** Append-only: no update or delete operations exist. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

	LedgerEntry save(LedgerEntry entry);

	List<LedgerEntry> findByRefundIdOrderByIdAsc(Long refundId);

	List<LedgerEntry> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(Instant from, Instant to);
}
