package com.example.customer_retail_system.system.refund.internal;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.example.customer_retail_system.gateway.api.PaymentGateway;
import com.example.customer_retail_system.gateway.api.PaymentRequest;
import com.example.customer_retail_system.gateway.api.PaymentResult;
import com.example.customer_retail_system.gateway.api.PaymentStatus;
import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.returns.RefundMethod;
import com.example.customer_retail_system.system.shared.AppProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executes a refund exactly once (LLD §3 flow 5, ADR-001): tx1 claims it, the gateway is called
 * <em>outside any transaction</em> with the deterministic key and a timeout, then tx2 records the
 * outcome. Calling this concurrently, twice, or after a timeout is always safe.
 */
@Component
class RefundExecutor {

	private static final Logger log = LoggerFactory.getLogger(RefundExecutor.class);

	private final RefundSettlement settlement;
	private final PaymentGateway gateway;
	private final AppProperties properties;
	private final ExecutorService gatewayCalls;

	RefundExecutor(RefundSettlement settlement, PaymentGateway gateway, AppProperties properties) {
		this.settlement = settlement;
		this.gateway = gateway;
		this.properties = properties;
		AtomicInteger n = new AtomicInteger();
		this.gatewayCalls = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "gateway-call-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	/** The gateway's answer was not definite (timeout, 5xx, lost response). */
	static final class UnknownOutcome extends Exception {
		UnknownOutcome(String message, Throwable cause) {
			super(message, cause);
		}
	}

	RefundView execute(long refundId) {
		requireNoTransaction();
		if (!settlement.claim(refundId)) {
			return settlement.view(refundId); // already processing or settled: never a second payment
		}
		RefundSettlement.Snapshot s = settlement.snapshot(refundId);
		if (s.method() == RefundMethod.STORE_CREDIT) {
			settlement.succeed(refundId, null); // no gateway: ledger liability only (G9)
			return settlement.view(refundId);
		}
		try {
			PaymentResult result = call(() -> gateway.pay(
					new PaymentRequest(s.idempotencyKey(), s.amountPaise(), s.rma(), s.originalPaymentRef())));
			if (result.status() == PaymentStatus.SUCCEEDED) {
				settlement.succeed(refundId, result.transactionId());
			}
			else {
				settlement.fail(refundId, "Declined by gateway: " + result.message());
			}
		}
		catch (UnknownOutcome ex) {
			log.warn("Refund {} outcome unknown: {}", refundId, ex.getMessage());
			settlement.markUnknown(refundId, ex.getMessage());
		}
		return settlement.view(refundId);
	}

	/** Runs a gateway call with the configured timeout; anything but a definite answer is UnknownOutcome. */
	<T> T call(Supplier<T> gatewayCall) throws UnknownOutcome {
		Future<T> future = gatewayCalls.submit(gatewayCall::get);
		long timeoutMs = properties.refund().gatewayTimeout().toMillis();
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(false); // like a real network call, it may still complete on the gateway side
			throw new UnknownOutcome("Gateway did not answer within " + timeoutMs + " ms", ex);
		}
		catch (ExecutionException ex) {
			throw new UnknownOutcome("Gateway error: " + ex.getCause().getMessage(), ex.getCause());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new UnknownOutcome("Interrupted while waiting for the gateway", ex);
		}
	}

	static void requireNoTransaction() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Never call the payment gateway inside a database transaction");
		}
	}

	@PreDestroy
	void shutdown() {
		gatewayCalls.shutdownNow();
	}
}
