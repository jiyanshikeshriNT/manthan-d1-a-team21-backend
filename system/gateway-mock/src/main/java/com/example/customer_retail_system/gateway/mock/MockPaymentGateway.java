package com.example.customer_retail_system.gateway.mock;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.customer_retail_system.gateway.api.FaultMode;
import com.example.customer_retail_system.gateway.api.GatewayFaultControl;
import com.example.customer_retail_system.gateway.api.GatewayUnavailableException;
import com.example.customer_retail_system.gateway.api.PaymentGateway;
import com.example.customer_retail_system.gateway.api.PaymentRequest;
import com.example.customer_retail_system.gateway.api.PaymentResult;
import com.example.customer_retail_system.gateway.api.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * In-process mock gateway. Charges are recorded in {@code gw_payment} keyed by idempotency key
 * (UNIQUE), so repeating a key returns the original payment and never charges twice — even under
 * concurrency. Each statement auto-commits, like an external system: a charge survives even if the
 * caller's own transaction later fails. Fault modes simulate outages, timeouts and lost responses.
 */
public class MockPaymentGateway implements PaymentGateway, GatewayFaultControl {

	private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);
	private static final long DEFAULT_TIMEOUT_DELAY_MS = 5_000;

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private volatile FaultSettings settings = FaultSettings.none();

	public MockPaymentGateway(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public PaymentResult pay(PaymentRequest request) {
		FaultSettings s = settings;
		if (s.mode() == FaultMode.FAIL) {
			throw new GatewayUnavailableException("Mock gateway unavailable (503, FAIL mode)");
		}
		if (s.mode() == FaultMode.SLOW) {
			sleep(s.latencyMs());
		}
		Optional<PaymentResult> existing = find(request.idempotencyKey());
		if (existing.isPresent()) {
			return existing.get(); // idempotent replay: no second charge
		}
		if (s.mode() == FaultMode.DECLINE) {
			return new PaymentResult(request.idempotencyKey(), PaymentStatus.DECLINED, null, request.amountPaise(),
					"Declined by issuer (DECLINE mode)");
		}
		PaymentResult charged = charge(request);
		if (s.mode() == FaultMode.TIMEOUT) {
			sleep(s.latencyMs() > 0 ? s.latencyMs() : DEFAULT_TIMEOUT_DELAY_MS);
		}
		if (s.mode() == FaultMode.FAIL_AFTER_CHARGE) {
			throw new GatewayUnavailableException("Mock gateway error after charging (502, FAIL_AFTER_CHARGE mode)");
		}
		return charged;
	}

	@Override
	public Optional<PaymentResult> status(String idempotencyKey) {
		if (settings.mode() == FaultMode.FAIL) {
			throw new GatewayUnavailableException("Mock gateway unavailable (503, FAIL mode)");
		}
		return find(idempotencyKey);
	}

	@Override
	public FaultSettings current() {
		return settings;
	}

	@Override
	public void apply(FaultSettings newSettings) {
		log.warn("Mock gateway fault mode set to {} (latency {} ms)", newSettings.mode(), newSettings.latencyMs());
		this.settings = newSettings;
	}

	private PaymentResult charge(PaymentRequest request) {
		String txnId = "GWTXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
		try {
			jdbc.update("INSERT INTO gw_payment (idempotency_key, amount_paise, reference, status, txn_id, created_at) "
					+ "VALUES (?, ?, ?, 'SUCCEEDED', ?, ?)", request.idempotencyKey(), request.amountPaise(),
					request.reference(), txnId, clock.instant().atOffset(ZoneOffset.UTC));
		}
		catch (DuplicateKeyException race) {
			return find(request.idempotencyKey()).orElseThrow(); // a concurrent call with the same key won
		}
		return new PaymentResult(request.idempotencyKey(), PaymentStatus.SUCCEEDED, txnId, request.amountPaise(), null);
	}

	private Optional<PaymentResult> find(String key) {
		List<PaymentResult> rows = jdbc.query(
				"SELECT idempotency_key, status, txn_id, amount_paise FROM gw_payment WHERE idempotency_key = ?",
				(rs, i) -> new PaymentResult(rs.getString(1), PaymentStatus.valueOf(rs.getString(2)), rs.getString(3),
						rs.getLong(4), null),
				key);
		return rows.stream().findFirst();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
