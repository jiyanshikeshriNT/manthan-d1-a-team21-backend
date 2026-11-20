package com.example.customer_retail_system.system.ledger;

import java.time.LocalDate;
import java.util.List;

/** Ledger entries in a date range with debit/credit totals; {@code balanced} iff they are equal. */
public record LedgerReport(LocalDate from, LocalDate to, List<LedgerEntryView> entries, long totalDebitPaise,
		long totalCreditPaise, boolean balanced) {
}
