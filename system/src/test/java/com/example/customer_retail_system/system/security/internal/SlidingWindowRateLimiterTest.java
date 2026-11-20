package com.example.customer_retail_system.system.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

	/** A clock the test can move forward. */
	static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-20T08:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	final MutableClock clock = new MutableClock();
	final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock, 5, Duration.ofMinutes(1));

	@Test
	void allowsFiveThenRefusesTheSixth() {
		for (int i = 0; i < 5; i++) {
			assertThat(limiter.tryAcquire("ip:1")).as("attempt %d", i + 1).isTrue();
		}
		assertThat(limiter.tryAcquire("ip:1")).isFalse();
	}

	@Test
	void windowSlides() {
		for (int i = 0; i < 5; i++) {
			limiter.tryAcquire("k");
			clock.now = clock.now.plusSeconds(10); // attempts at 0,10,20,30,40 s
		}
		assertThat(limiter.tryAcquire("k")).isFalse(); // at 50 s: 5 in the last minute
		clock.now = Instant.parse("2026-11-20T08:01:00.001Z"); // the 0 s attempt has left the window
		assertThat(limiter.tryAcquire("k")).isTrue();
		assertThat(limiter.tryAcquire("k")).isFalse();
	}

	@Test
	void refusedAttemptsDoNotExtendTheBlock() {
		for (int i = 0; i < 5; i++) {
			limiter.tryAcquire("k");
		}
		for (int i = 0; i < 20; i++) {
			assertThat(limiter.tryAcquire("k")).isFalse();
		}
		clock.now = clock.now.plus(Duration.ofMinutes(1)).plusMillis(1);
		assertThat(limiter.tryAcquire("k")).isTrue();
	}

	@Test
	void keysAreIndependent() {
		for (int i = 0; i < 5; i++) {
			limiter.tryAcquire("ip:1");
		}
		assertThat(limiter.tryAcquire("ip:2")).isTrue();
		assertThat(limiter.tryAcquire("order:X")).isTrue();
	}
}
