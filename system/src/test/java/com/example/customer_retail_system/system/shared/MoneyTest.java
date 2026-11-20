package com.example.customer_retail_system.system.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

	@Test
	void percentOfRoundsHalfUpToThePaisa() {
		assertThat(Money.percentOf(999, 50)).isEqualTo(500); // 499.5 -> 500
		assertThat(Money.percentOf(998, 50)).isEqualTo(499);
		assertThat(Money.percentOf(1, 50)).isEqualTo(1); // 0.5 -> 1
		assertThat(Money.percentOf(129_900, 100)).isEqualTo(129_900);
		assertThat(Money.percentOf(129_900, 0)).isZero();
	}

	@Test
	void percentOfRejectsOutOfRangeInput() {
		assertThatThrownBy(() -> Money.percentOf(100, 101)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.percentOf(-1, 50)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void timesMultipliesAndDetectsOverflow() {
		assertThat(Money.times(129_900, 3)).isEqualTo(389_700);
		assertThat(Money.times(129_900, 0)).isZero();
		assertThatThrownBy(() -> Money.times(Long.MAX_VALUE, 2)).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> Money.times(100, -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rupeeConversionIsExact() {
		assertThat(Money.fromRupees(new BigDecimal("10000"))).isEqualTo(1_000_000);
		assertThat(Money.fromRupees(new BigDecimal("10000.01"))).isEqualTo(1_000_001);
		assertThat(Money.toRupees(1_000_001)).isEqualByComparingTo("10000.01");
		assertThatThrownBy(() -> Money.fromRupees(new BigDecimal("1.005")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void formatsForDisplay() {
		assertThat(Money.format(1_234_550)).isEqualTo("₹12,345.50");
		assertThat(Money.format(5)).isEqualTo("₹0.05");
	}
}
