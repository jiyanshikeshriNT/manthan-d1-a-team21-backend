package com.example.customer_retail_system.system.customer.internal;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

	/** Case-insensitive lookup: the argument is normalised the same way emails are stored. */
	default Optional<Customer> findByEmailIgnoringCase(String email) {
		return findByEmail(Customer.normalizeEmail(email));
	}

	Optional<Customer> findByEmail(String email);
}
