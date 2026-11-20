package com.example.customer_retail_system.system.customer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.example.customer_retail_system.system.customer.internal.Customer;
import com.example.customer_retail_system.system.customer.internal.CustomerFlag;
import com.example.customer_retail_system.system.customer.internal.CustomerFlagRepository;
import com.example.customer_retail_system.system.customer.internal.CustomerRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the customer module. */
@Service
@Transactional(readOnly = true)
public class CustomerService {

	private final CustomerRepository customers;
	private final CustomerFlagRepository flags;

	CustomerService(CustomerRepository customers, CustomerFlagRepository flags) {
		this.customers = customers;
		this.flags = flags;
	}

	public Optional<CustomerInfo> findById(long customerId) {
		return customers.findById(customerId).map(CustomerService::toInfo);
	}

	/** True iff {@code email} (any case, surrounding spaces ignored) is this customer's email. */
	public boolean emailMatches(long customerId, String email) {
		if (email == null) {
			return false;
		}
		String normalized = Customer.normalizeEmail(email);
		return customers.findById(customerId).map(c -> c.getEmail().equals(normalized)).orElse(false);
	}

	/**
	 * Persists a flag (G8) in the caller's transaction. Flags route a return to manual review;
	 * they never block it. Kept for the manager dashboard's "flagged customers".
	 */
	@Transactional
	public void recordFlag(long customerId, UUID returnId, int returnCount, Instant flaggedAt) {
		flags.save(new CustomerFlag(customerId, returnId, returnCount, flaggedAt));
	}

	private static CustomerInfo toInfo(Customer c) {
		return new CustomerInfo(c.getId(), c.getEmail(), c.getName());
	}
}
