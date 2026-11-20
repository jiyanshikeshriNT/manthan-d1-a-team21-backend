package com.example.customer_retail_system.system.eligibility;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure return-eligibility rules (G1–G3). No I/O and no hidden clock: "now" is passed in.
 * <p>
 * Checks, in order: category returnable → delivered → window → remaining quantity.
 * The window is anchored on the delivery <em>date</em> in {@code zone};
 * {@code daysSince = DAYS.between(deliveredDate, today)} and the line is eligible iff
 * {@code daysSince <= window} (day 30 is OK, day 31 is refused).
 */
public final class EligibilityEngine {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private final ZoneId zone;
	private final int defaultWindowDays;

	public EligibilityEngine(ZoneId zone, int defaultWindowDays) {
		this.zone = Objects.requireNonNull(zone, "zone");
		if (defaultWindowDays <= 0) {
			throw new IllegalArgumentException("defaultWindowDays must be > 0");
		}
		this.defaultWindowDays = defaultWindowDays;
	}

	public EligibilityResult evaluate(EligibilityInput in, Instant now) {
		if (!in.returnable()) {
			return refused(EligibilityCode.CATEGORY_NOT_RETURNABLE,
					"For hygiene and safety reasons, %s items can't be returned.".formatted(in.categoryName()), null);
		}
		if (in.deliveredAt() == null) {
			return refused(EligibilityCode.NOT_DELIVERED,
					"You can start a return once this item is delivered.", null);
		}
		int window = in.returnWindowDays() != null ? in.returnWindowDays() : defaultWindowDays;
		LocalDate delivered = LocalDate.ofInstant(in.deliveredAt(), zone);
		LocalDate today = LocalDate.ofInstant(now, zone);
		LocalDate deadline = delivered.plusDays(window);
		long daysSince = Math.max(0, ChronoUnit.DAYS.between(delivered, today));

		if (daysSince > window) {
			return refused(EligibilityCode.WINDOW_EXPIRED,
					"The %d-day return window for this item closed on %s.".formatted(window, DATE.format(deadline)),
					deadline);
		}
		int remaining = in.orderedQty() - in.alreadyReturnedQty();
		if (remaining <= 0) {
			return refused(EligibilityCode.ALREADY_IN_RETURN,
					"All units of this item are already part of a return.", deadline);
		}
		return new EligibilityResult(true, EligibilityCode.ELIGIBLE,
				"Returnable until %s (%s).".formatted(DATE.format(deadline), daysLeft(window - daysSince)),
				remaining, deadline);
	}

	private static String daysLeft(long days) {
		if (days == 0) {
			return "last day";
		}
		return days == 1 ? "1 day left" : days + " days left";
	}

	private static EligibilityResult refused(EligibilityCode code, String message, LocalDate deadline) {
		return new EligibilityResult(false, code, message, 0, deadline);
	}
}
