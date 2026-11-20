package com.example.customer_retail_system.system.eligibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class EligibilityEngineTest {

	static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	/** "Today" is 20 Nov 2026, 14:00 IST. */
	static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
	static final Instant NOW = TODAY.atTime(14, 0).atZone(IST).toInstant();

	final EligibilityEngine engine = new EligibilityEngine(IST, 30);

	static Instant deliveredDaysAgo(int days) {
		return TODAY.minusDays(days).atTime(10, 0).atZone(IST).toInstant();
	}

	static EligibilityInput apparel(Instant deliveredAt) {
		return new EligibilityInput("Apparel", true, 30, deliveredAt, 1, 0);
	}

	static EligibilityInput electronics(Instant deliveredAt) {
		return new EligibilityInput("Electronics", true, 10, deliveredAt, 1, 0);
	}

	@ParameterizedTest(name = "apparel day {0} → eligible={1}")
	@CsvSource({ "0, true", "29, true", "30, true", "31, false" })
	void thirtyDayWindowBoundaries(int daysAgo, boolean eligible) {
		EligibilityResult r = engine.evaluate(apparel(deliveredDaysAgo(daysAgo)), NOW);
		assertThat(r.eligible()).isEqualTo(eligible);
		assertThat(r.code()).isEqualTo(eligible ? EligibilityCode.ELIGIBLE : EligibilityCode.WINDOW_EXPIRED);
		assertThat(r.deadline()).isEqualTo(TODAY.minusDays(daysAgo).plusDays(30));
	}

	@ParameterizedTest(name = "electronics day {0} → eligible={1}")
	@CsvSource({ "9, true", "10, true", "11, false" })
	void electronicsTenDayWindow(int daysAgo, boolean eligible) {
		EligibilityResult r = engine.evaluate(electronics(deliveredDaysAgo(daysAgo)), NOW);
		assertThat(r.eligible()).isEqualTo(eligible);
	}

	@Test
	void eligibleMessageShowsDeadlineAndDaysLeft() {
		EligibilityResult r = engine.evaluate(apparel(deliveredDaysAgo(5)), NOW);
		assertThat(r.message()).isEqualTo("Returnable until 15 Dec 2026 (25 days left).");
		assertThat(r.returnableQty()).isEqualTo(1);
		assertThat(engine.evaluate(apparel(deliveredDaysAgo(29)), NOW).message()).endsWith("(1 day left).");
		assertThat(engine.evaluate(apparel(deliveredDaysAgo(30)), NOW).message())
				.isEqualTo("Returnable until 20 Nov 2026 (last day).");
	}

	@Test
	void expiredMessageNamesWindowAndClosingDate() {
		EligibilityResult r = engine.evaluate(electronics(deliveredDaysAgo(11)), NOW);
		assertThat(r.message()).isEqualTo("The 10-day return window for this item closed on 19 Nov 2026.");
		assertThat(r.returnableQty()).isZero();
	}

	@Test
	void innerwearIsNeverReturnable() {
		EligibilityResult r = engine.evaluate(new EligibilityInput("Innerwear", false, null, deliveredDaysAgo(1), 2, 0), NOW);
		assertThat(r.eligible()).isFalse();
		assertThat(r.code()).isEqualTo(EligibilityCode.CATEGORY_NOT_RETURNABLE);
		assertThat(r.message()).isEqualTo("For hygiene and safety reasons, Innerwear items can't be returned.");
		assertThat(r.deadline()).isNull();
	}

	@Test
	void perishablesAreNeverReturnableEvenWhenUndelivered() {
		EligibilityResult r = engine.evaluate(new EligibilityInput("Perishables", false, null, null, 1, 0), NOW);
		assertThat(r.code()).isEqualTo(EligibilityCode.CATEGORY_NOT_RETURNABLE);
	}

	@Test
	void undeliveredItemIsNotEligible() {
		EligibilityResult r = engine.evaluate(apparel(null), NOW);
		assertThat(r.code()).isEqualTo(EligibilityCode.NOT_DELIVERED);
		assertThat(r.message()).isEqualTo("You can start a return once this item is delivered.");
	}

	@Test
	void partialQuantityRemainsReturnable() {
		EligibilityInput threeOrderedOneReturned = new EligibilityInput("Apparel", true, 30, deliveredDaysAgo(3), 3, 1);
		EligibilityResult r = engine.evaluate(threeOrderedOneReturned, NOW);
		assertThat(r.eligible()).isTrue();
		assertThat(r.returnableQty()).isEqualTo(2);
	}

	@Test
	void allUnitsAlreadyInAReturn() {
		EligibilityResult r = engine.evaluate(new EligibilityInput("Apparel", true, 30, deliveredDaysAgo(3), 2, 2), NOW);
		assertThat(r.code()).isEqualTo(EligibilityCode.ALREADY_IN_RETURN);
		assertThat(r.message()).isEqualTo("All units of this item are already part of a return.");
		assertThat(r.returnableQty()).isZero();
	}

	@Test
	void expiredWindowWinsOverQuantity() {
		EligibilityResult r = engine.evaluate(new EligibilityInput("Apparel", true, 30, deliveredDaysAgo(40), 2, 2), NOW);
		assertThat(r.code()).isEqualTo(EligibilityCode.WINDOW_EXPIRED);
	}

	@Test
	void nullWindowUsesConfiguredDefault() {
		EligibilityInput noWindow = new EligibilityInput("Home", true, null, deliveredDaysAgo(30), 1, 0);
		assertThat(engine.evaluate(noWindow, NOW).eligible()).isTrue();
		EligibilityInput day31 = new EligibilityInput("Home", true, null, deliveredDaysAgo(31), 1, 0);
		assertThat(engine.evaluate(day31, NOW).eligible()).isFalse();
	}

	/**
	 * Both deliveries fall on 10 Nov in UTC, but on different IST dates. Just after IST midnight on
	 * 21 Nov (still 20 Nov in UTC), the 23:59 IST delivery is day 11 (expired) and the 00:00 IST one
	 * is day 10 (last day). A UTC-based count would treat them the same.
	 */
	@Test
	void istMidnightEdge() {
		Instant lastMinuteOf10Nov = LocalDate.of(2026, 11, 10).atTime(LocalTime.of(23, 59)).atZone(IST).toInstant();
		Instant firstMinuteOf11Nov = LocalDate.of(2026, 11, 11).atStartOfDay(IST).toInstant();
		assertThat(lastMinuteOf10Nov.toString()).isEqualTo("2026-11-10T18:29:00Z");
		assertThat(firstMinuteOf11Nov.toString()).isEqualTo("2026-11-10T18:30:00Z");

		// "now" = 00:05 IST on 21 Nov — already the next day in IST, still 20 Nov in UTC.
		Instant justAfterIstMidnight = LocalDate.of(2026, 11, 21).atTime(0, 5).atZone(IST).toInstant();

		EligibilityResult day11 = engine.evaluate(electronics(lastMinuteOf10Nov), justAfterIstMidnight);
		EligibilityResult day10 = engine.evaluate(electronics(firstMinuteOf11Nov), justAfterIstMidnight);
		assertThat(day11.code()).isEqualTo(EligibilityCode.WINDOW_EXPIRED);
		assertThat(day10.code()).isEqualTo(EligibilityCode.ELIGIBLE);
		assertThat(day10.message()).endsWith("(last day).");
	}

	@Test
	void deliveryTimestampInTheFutureCountsAsDayZero() {
		EligibilityResult r = engine.evaluate(apparel(NOW.plusSeconds(3_600 * 30)), NOW);
		assertThat(r.eligible()).isTrue();
	}
}
