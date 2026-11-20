package com.example.customer_retail_system.system.refund.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.customer_retail_system.system.refund.RefundStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every state change is a conditional update ({@code WHERE status IN (...)}) that bumps the version;
 * the caller checks the returned row count — 0 means someone else got there first (ADR-001).
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

	Optional<Refund> findByReturnId(UUID returnId);

	List<Refund> findByStatusOrderByCreatedAtAsc(RefundStatus status);

	List<Refund> findAllByOrderByCreatedAtDesc();

	@Query("select r.status from Refund r where r.id = :id")
	Optional<RefundStatus> findStatus(@Param("id") long id);

	/** Refunds the reconciler should look at now. */
	@Query("""
			select r.id from Refund r
			where (r.status = com.example.customer_retail_system.system.refund.RefundStatus.UNKNOWN
			       and (r.nextAttemptAt is null or r.nextAttemptAt <= :now))
			   or (r.status in (com.example.customer_retail_system.system.refund.RefundStatus.PROCESSING,
			                    com.example.customer_retail_system.system.refund.RefundStatus.PENDING)
			       and r.updatedAt < :stuckBefore)
			order by r.id""")
	List<Long> findDueForReconcile(@Param("now") Instant now, @Param("stuckBefore") Instant stuckBefore);

	/** tx1: PENDING/UNKNOWN → PROCESSING and count the attempt. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Refund r set r.status = com.example.customer_retail_system.system.refund.RefundStatus.PROCESSING,
			  r.attempts = r.attempts + 1, r.updatedAt = :now, r.version = r.version + 1
			where r.id = :id and r.status in :from""")
	int claim(@Param("id") long id, @Param("from") Collection<RefundStatus> from, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Refund r set r.status = com.example.customer_retail_system.system.refund.RefundStatus.SUCCEEDED,
			  r.gatewayTxnId = :txnId, r.lastError = null, r.nextAttemptAt = null, r.updatedAt = :now,
			  r.version = r.version + 1
			where r.id = :id and r.status in :from""")
	int succeed(@Param("id") long id, @Param("from") Collection<RefundStatus> from, @Param("txnId") String txnId,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Refund r set r.status = :to, r.lastError = :error, r.nextAttemptAt = :nextAttemptAt,
			  r.updatedAt = :now, r.version = r.version + 1
			where r.id = :id and r.status in :from""")
	int move(@Param("id") long id, @Param("from") Collection<RefundStatus> from, @Param("to") RefundStatus to,
			@Param("error") String error, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("now") Instant now);

	/** Stale in-flight refund (crash between tx1 and tx2) → UNKNOWN, so the reconciler can settle it. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Refund r set r.status = com.example.customer_retail_system.system.refund.RefundStatus.UNKNOWN,
			  r.lastError = 'Processing did not complete; checking with the gateway', r.updatedAt = :now,
			  r.version = r.version + 1
			where r.id = :id and r.status = com.example.customer_retail_system.system.refund.RefundStatus.PROCESSING
			  and r.updatedAt < :stuckBefore""")
	int releaseStuck(@Param("id") long id, @Param("stuckBefore") Instant stuckBefore, @Param("now") Instant now);

	/** A status check failed: count it as an attempt and back off. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Refund r set r.attempts = r.attempts + 1, r.lastError = :error, r.nextAttemptAt = :nextAttemptAt,
			  r.updatedAt = :now, r.version = r.version + 1
			where r.id = :id and r.status = com.example.customer_retail_system.system.refund.RefundStatus.UNKNOWN""")
	int deferUnknown(@Param("id") long id, @Param("error") String error, @Param("nextAttemptAt") Instant nextAttemptAt,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Refund r set r.status = com.example.customer_retail_system.system.refund.RefundStatus.PENDING,
			  r.approvedBy = :approvedBy, r.approvedAt = :now, r.updatedAt = :now, r.version = r.version + 1
			where r.id = :id and r.status = com.example.customer_retail_system.system.refund.RefundStatus.AWAITING_APPROVAL""")
	int approve(@Param("id") long id, @Param("approvedBy") String approvedBy, @Param("now") Instant now);

	/** Finance retry of a FAILED refund: back to PENDING with a fresh attempt budget, same key. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Refund r set r.status = com.example.customer_retail_system.system.refund.RefundStatus.PENDING,
			  r.attempts = 0, r.lastError = null, r.nextAttemptAt = null, r.updatedAt = :now, r.version = r.version + 1
			where r.id = :id and r.status = com.example.customer_retail_system.system.refund.RefundStatus.FAILED""")
	int resetForRetry(@Param("id") long id, @Param("now") Instant now);
}
