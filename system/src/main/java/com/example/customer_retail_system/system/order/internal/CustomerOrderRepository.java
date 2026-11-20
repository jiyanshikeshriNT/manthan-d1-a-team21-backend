package com.example.customer_retail_system.system.order.internal;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

	@EntityGraph(attributePaths = "lines")
	Optional<CustomerOrder> findByOrderNumber(String orderNumber);

	@EntityGraph(attributePaths = "lines")
	Optional<CustomerOrder> findWithLinesById(Long id);

	/** {@code SELECT … FOR UPDATE} on the order row; lines load lazily inside the same transaction. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from CustomerOrder o where o.id = :id")
	Optional<CustomerOrder> lockById(@Param("id") Long id);
}
