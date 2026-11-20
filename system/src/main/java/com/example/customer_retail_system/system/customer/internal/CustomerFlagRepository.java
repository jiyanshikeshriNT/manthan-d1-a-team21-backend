package com.example.customer_retail_system.system.customer.internal;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerFlagRepository extends JpaRepository<CustomerFlag, Long> {

	List<CustomerFlag> findByCustomerIdOrderByIdAsc(Long customerId);
}
