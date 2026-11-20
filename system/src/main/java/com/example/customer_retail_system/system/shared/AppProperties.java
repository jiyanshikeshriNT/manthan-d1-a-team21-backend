package com.example.customer_retail_system.system.shared;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed {@code app.*} settings. Business thresholds live here so nothing is hard-coded.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
		@NotNull ZoneId zone,
		@Valid @NotNull Storage storage,
		@Valid @NotNull Label label,
		@Valid @NotNull Returns returns,
		@Valid @NotNull Refund refund,
		@Valid @NotNull Flagging flagging,
		@Valid @NotNull Session session,
		@Valid @NotNull Seed seed) {

	/**
	 * Customer order lookup (G12): at most {@code maxAttempts} per {@code attemptWindow} per client IP
	 * and per order number; issued tokens live for {@code tokenTtl}.
	 */
	public record Session(@Positive int maxAttempts, @NotNull Duration attemptWindow, @NotNull Duration tokenTtl) {
	}

	/** Where uploaded photos are stored (server-generated names only). */
	public record Storage(@NotBlank String dir) {
	}

	/** HMAC secret used to sign return label codes. */
	public record Label(@NotBlank String secret) {
	}

	/** Default return window when a category does not override it. */
	public record Returns(@Positive int defaultWindowDays) {
	}

	/**
	 * Refund totals strictly greater than {@code approvalThresholdPaise} need finance approval.
	 * Gateway calls time out after {@code gatewayTimeout}; unknown outcomes are retried with
	 * exponential backoff from {@code initialBackoff} until {@code maxAttempts}; PENDING/PROCESSING
	 * refunds untouched for {@code stuckAfter} are picked up by the reconciler.
	 */
	public record Refund(
			@Positive long approvalThresholdPaise,
			@NotNull Duration gatewayTimeout,
			@Positive int maxAttempts,
			@NotNull Duration stuckAfter,
			@NotNull Duration initialBackoff,
			boolean reconcilerEnabled) {
	}

	/** More than {@code maxRequests} return requests within {@code windowDays} flags a customer. */
	public record Flagging(@Positive int maxRequests, @Positive int windowDays) {
	}

	/** Deterministic seed-data settings (used only by the {@code seed} profile). */
	public record Seed(long randomSeed, @NotBlank String staffPassword) {
	}
}
