package com.example.customer_retail_system.system.ledger;

import java.time.Instant;

public record LedgerEntryView(long id, long refundId, long customerId, LedgerAccount account, EntryDirection direction,
		long amountPaise, Instant createdAt) {
}
