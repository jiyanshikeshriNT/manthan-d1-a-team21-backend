package com.example.customer_retail_system.system.customer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CustomerRepositoryTest {

	static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	@Autowired
	CustomerRepository customers;

	@Test
	void emailIsStoredLowerCaseAndFoundCaseInsensitively() {
		customers.saveAndFlush(new Customer("  Asha.Rao@Example.COM ", "Asha Rao", "+919800000001", T0));

		Customer found = customers.findByEmailIgnoringCase("ASHA.RAO@example.com").orElseThrow();
		assertThat(found.getEmail()).isEqualTo("asha.rao@example.com");
		assertThat(found.getCreatedAt()).isEqualTo(T0);
	}

	@Test
	void unknownEmailIsEmpty() {
		assertThat(customers.findByEmailIgnoringCase("nobody@example.com")).isEmpty();
	}

	@Test
	void emailIsUniqueRegardlessOfCase() {
		customers.saveAndFlush(new Customer("dup@example.com", "One", null, T0));
		assertThatThrownBy(() -> customers.saveAndFlush(new Customer("DUP@example.com", "Two", null, T0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
