package com.example.customer_retail_system.system.customer;

import java.util.Optional;

import com.example.customer_retail_system.system.customer.internal.Customer;
import com.example.customer_retail_system.system.customer.internal.CustomerRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the customer module. */
@Service
@Transactional(readOnly = true)
public class CustomerService {

	private final CustomerRepository customers;

	CustomerService(CustomerRepository customers) {
		this.customers = customers;
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

	private static CustomerInfo toInfo(Customer c) {
		return new CustomerInfo(c.getId(), c.getEmail(), c.getName());
	}
}
