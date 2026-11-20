package com.example.customer_retail_system.system.security.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.CustomerPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque customer access tokens (G12): 256 random bits, returned once to the client; only the
 * SHA-256 hash is stored. Each token is scoped to one order and expires after the configured TTL.
 */
@Service
public class AccessTokenService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final AppProperties properties;

	AccessTokenService(JdbcTemplate jdbc, Clock clock, AppProperties properties) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.properties = properties;
	}

	public record IssuedToken(String token, Instant expiresAt) {
	}

	@Transactional
	public IssuedToken issue(long orderId) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = clock.instant().plus(properties.session().tokenTtl());
		jdbc.update("INSERT INTO customer_access_token (token_hash, order_id, expires_at) VALUES (?, ?, ?)",
				new Object[] { hash(token), orderId, expiresAt.atOffset(ZoneOffset.UTC) },
				new int[] { Types.VARCHAR, Types.BIGINT, Types.TIMESTAMP_WITH_TIMEZONE });
		return new IssuedToken(token, expiresAt);
	}

	/** The principal for a valid, unexpired token; empty otherwise. */
	public Optional<CustomerPrincipal> resolve(String token) {
		if (token == null || token.isBlank() || token.length() > 128) {
			return Optional.empty();
		}
		List<Long> orderIds = jdbc.queryForList(
				"SELECT order_id FROM customer_access_token WHERE token_hash = ? AND expires_at > ?",
				Long.class, hash(token), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
		return orderIds.stream().findFirst().map(CustomerPrincipal::new);
	}

	static String hash(String token) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
