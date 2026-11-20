package com.example.customer_retail_system.system.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * An inclusive range of business dates ({@code from}..{@code to} in {@code app.zone}) and the
 * matching half-open instant range [{@code start}, {@code endExclusive}) for queries.
 */
public record DateRange(LocalDate from, LocalDate to, Instant start, Instant endExclusive) {

	public static final int MAX_DAYS = 366;

	/** Defaults to the last {@code defaultDays} days ending today; rejects inverted or over-long ranges. */
	public static DateRange resolve(LocalDate from, LocalDate to, Clock clock, ZoneId zone, int defaultDays) {
		LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), zone);
		LocalDate begin = from != null ? from : end.minusDays(defaultDays - 1L);
		if (begin.isAfter(end)) {
			throw new DomainException(ErrorCode.VALIDATION_FAILED, "'from' must be on or before 'to'.");
		}
		if (ChronoUnit.DAYS.between(begin, end) >= MAX_DAYS) {
			throw new DomainException(ErrorCode.VALIDATION_FAILED, "The date range can be at most " + MAX_DAYS + " days.");
		}
		return new DateRange(begin, end, begin.atStartOfDay(zone).toInstant(),
				end.plusDays(1).atStartOfDay(zone).toInstant());
	}
}
