package com.example.customer_retail_system.system.returns.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStatus;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {

	@EntityGraph(attributePaths = "items")
	Optional<ReturnRequest> findByRmaNumber(String rmaNumber);

	Optional<ReturnRequest> findByClientRequestKey(String clientRequestKey);

	@EntityGraph(attributePaths = "items")
	Optional<ReturnRequest> findWithItemsById(UUID id);

	@EntityGraph(attributePaths = "items")
	List<ReturnRequest> findByStatusOrderByCreatedAtAsc(ReturnStatus status);

	List<ReturnRequest> findByIdIn(Collection<UUID> ids);

	/** Return requests a customer made after {@code since}, excluding one status (CANCELLED) — G8. */
	long countByCustomerIdAndCreatedAtAfterAndStatusNot(Long customerId, Instant since, ReturnStatus excluded);

	/** [orderLineId, sum(requestedQty)] over the order's returns whose status is not in {@code excluded}. */
	@Query("""
			select i.orderLineId, sum(i.requestedQty) from ReturnItem i
			where i.returnRequest.orderId = :orderId and i.returnRequest.status not in :excluded
			group by i.orderLineId""")
	List<Object[]> sumRequestedQtyByOrderLine(@Param("orderId") Long orderId,
			@Param("excluded") Collection<ReturnStatus> excluded);
}
