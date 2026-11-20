package com.example.customer_retail_system.system.ledger;

import java.time.Clock;
import java.util.List;

import com.example.customer_retail_system.system.ledger.internal.LedgerEntry;
import com.example.customer_retail_system.system.ledger.internal.LedgerEntryRepository;
import com.example.customer_retail_system.system.shared.DateRange;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the double-entry ledger (LLD §2). Each successful refund posts exactly one balanced
 * pair: DEBIT REFUND_EXPENSE and CREDIT GATEWAY_CLEARING (original payment) or
 * STORE_CREDIT_LIABILITY (store credit). {@code UNIQUE(refund_id, account, direction)} makes a second
 * posting impossible.
 */
@Service
public class LedgerService {

	private final LedgerEntryRepository entries;
	private final Clock clock;

	LedgerService(LedgerEntryRepository entries, Clock clock) {
		this.entries = entries;
		this.clock = clock;
	}

	/** Posts the refund pair in the caller's transaction (the one that marks the refund SUCCEEDED). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void postRefund(long refundId, long customerId, long amountPaise, boolean storeCredit) {
		var now = clock.instant();
		entries.save(new LedgerEntry(refundId, customerId, LedgerAccount.REFUND_EXPENSE, EntryDirection.DEBIT,
				amountPaise, now));
		entries.save(new LedgerEntry(refundId, customerId,
				storeCredit ? LedgerAccount.STORE_CREDIT_LIABILITY : LedgerAccount.GATEWAY_CLEARING,
				EntryDirection.CREDIT, amountPaise, now));
	}

	@Transactional(readOnly = true)
	public List<LedgerEntryView> entriesForRefund(long refundId) {
		return entries.findByRefundIdOrderByIdAsc(refundId).stream().map(LedgerService::toView).toList();
	}

	@Transactional(readOnly = true)
	public LedgerReport report(DateRange range) {
		List<LedgerEntryView> views = entries
				.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(range.start(), range.endExclusive())
				.stream().map(LedgerService::toView).toList();
		long debits = views.stream().filter(v -> v.direction() == EntryDirection.DEBIT)
				.mapToLong(LedgerEntryView::amountPaise).sum();
		long credits = views.stream().filter(v -> v.direction() == EntryDirection.CREDIT)
				.mapToLong(LedgerEntryView::amountPaise).sum();
		return new LedgerReport(range.from(), range.to(), views, debits, credits, debits == credits);
	}

	private static LedgerEntryView toView(LedgerEntry e) {
		return new LedgerEntryView(e.getId(), e.getRefundId(), e.getCustomerId(), e.getAccount(), e.getDirection(),
				e.getAmountPaise(), e.getCreatedAt());
	}
}
