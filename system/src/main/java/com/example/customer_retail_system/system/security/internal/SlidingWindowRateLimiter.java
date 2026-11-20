package com.example.customer_retail_system.system.security.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter: at most {@code limit} acquisitions per {@code window} per key.
 * Single-instance only (fine for the modular monolith; a shared store is needed if scaled out).
 */
public final class SlidingWindowRateLimiter {

	private static final int EVICT_THRESHOLD = 10_000;

	private final Clock clock;
	private final int limit;
	private final Duration window;
	private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

	public SlidingWindowRateLimiter(Clock clock, int limit, Duration window) {
		this.clock = clock;
		this.limit = limit;
		this.window = window;
	}

	/** Records an attempt for {@code key}; false (and nothing recorded) if the key is over its limit. */
	public boolean tryAcquire(String key) {
		Instant now = clock.instant();
		Instant cutoff = now.minus(window);
		if (hits.size() > EVICT_THRESHOLD) {
			evictIdle(cutoff);
		}
		Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (q) {
			while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
				q.pollFirst();
			}
			if (q.size() >= limit) {
				return false;
			}
			q.addLast(now);
			return true;
		}
	}

	private void evictIdle(Instant cutoff) {
		hits.entrySet().removeIf(e -> {
			synchronized (e.getValue()) {
				return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(cutoff);
			}
		});
	}
}
