package com.example.customer_retail_system.system.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiMaskerTest {

	@Test
	void masksEmails() {
		assertThat(PiiMasker.mask("Contacted asha.rao@example.com about RMA-100001"))
				.isEqualTo("Contacted a***@example.com about RMA-100001");
	}

	@Test
	void masksPhoneNumbersKeepingLastTwoDigits() {
		assertThat(PiiMasker.mask("Call +91 98765 43210 now")).isEqualTo("Call ***10 now");
		assertThat(PiiMasker.mask("phone 9876543210")).isEqualTo("phone ***10");
	}

	@Test
	void leavesOrdinaryTextAndShortNumbersAlone() {
		assertThat(PiiMasker.mask("Customer has 4 return requests in 30 days (RMA-100123)"))
				.isEqualTo("Customer has 4 return requests in 30 days (RMA-100123)");
		assertThat(PiiMasker.mask(null)).isNull();
	}
}
