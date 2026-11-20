package com.example.customer_retail_system.system.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Money helpers. All amounts are {@code long} paise; rounding is HALF_UP to the paisa.
 * Never use double/float for money.
 */
public final class Money {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private Money() {
	}

	/** {@code unitPaise × quantity}, failing on overflow. */
	public static long times(long unitPaise, int quantity) {
		requireNonNegative(unitPaise);
		if (quantity < 0) {
			throw new IllegalArgumentException("quantity must be >= 0");
		}
		return Math.multiplyExact(unitPaise, (long) quantity);
	}

	/** {@code percent}% of {@code paise}, rounded HALF_UP to the paisa (e.g. 50% of 999 = 500). */
	public static long percentOf(long paise, int percent) {
		requireNonNegative(paise);
		if (percent < 0 || percent > 100) {
			throw new IllegalArgumentException("percent must be within 0..100");
		}
		return BigDecimal.valueOf(paise)
				.multiply(BigDecimal.valueOf(percent))
				.divide(HUNDRED, 0, RoundingMode.HALF_UP)
				.longValueExact();
	}

	/** Exact rupees → paise; more than two decimal places is rejected rather than rounded. */
	public static long fromRupees(BigDecimal rupees) {
		BigDecimal paise = rupees.multiply(HUNDRED);
		try {
			return paise.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
		}
		catch (ArithmeticException ex) {
			throw new IllegalArgumentException("Amount must have at most 2 decimal places: " + rupees, ex);
		}
	}

	public static BigDecimal toRupees(long paise) {
		return BigDecimal.valueOf(paise, 2);
	}

	/** Display form, e.g. {@code ₹12,345.50}. */
	public static String format(long paise) {
		return String.format(Locale.ROOT, "₹%,.2f", toRupees(paise));
	}

	private static void requireNonNegative(long paise) {
		if (paise < 0) {
			throw new IllegalArgumentException("amount must be >= 0 paise");
		}
	}
}
