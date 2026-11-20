package com.example.customer_retail_system.system.order.internal;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

	@EntityGraph(attributePaths = "lines")
	Optional<CustomerOrder> findByOrderNumber(String orderNumber);
}
