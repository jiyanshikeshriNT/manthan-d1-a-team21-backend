package com.example.customer_retail_system.system.refund.internal;

import java.time.LocalDate;
import java.util.List;

import com.example.customer_retail_system.system.ledger.LedgerReport;
import com.example.customer_retail_system.system.refund.RefundStatus;
import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.shared.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finance API (role FINANCE). Payments run after the approving transaction has committed. */
@RestController
@RequestMapping("/api/v1/finance")
class FinanceController {

	private final FinanceService finance;
	private final RefundExecutor executor;
	private final FinanceRefundDetails details;

	FinanceController(FinanceService finance, RefundExecutor executor, FinanceRefundDetails details) {
		this.finance = finance;
		this.executor = executor;
		this.details = details;
	}

	record RejectRequest(@NotBlank @Size(max = 500) String reason) {
	}

	@GetMapping("/refunds")
	List<RefundView> refunds(@RequestParam(required = false) RefundStatus status) {
		return finance.list(status);
	}

	/** The refund with the inspected return behind it (graded items, flag), for the review drawer. */
	@GetMapping("/refunds/{id}")
	FinanceRefundDetails.Detail refund(@PathVariable long id) {
		return details.detail(id);
	}

	/** Approving twice is a no-op that returns the current state (no second payment). */
	@PostMapping("/refunds/{id}/approve")
	RefundView approve(@PathVariable long id, Authentication auth) {
		return finance.approve(id, actor(auth)) ? executor.execute(id) : finance.view(id);
	}

	@PostMapping("/refunds/{id}/reject")
	RefundView reject(@PathVariable long id, @Valid @RequestBody RejectRequest request, Authentication auth) {
		finance.reject(id, request.reason().trim(), actor(auth));
		return finance.view(id);
	}

	/** Retries a FAILED or UNKNOWN refund with the same idempotency key. */
	@PostMapping("/refunds/{id}/retry")
	RefundView retry(@PathVariable long id, Authentication auth) {
		return finance.prepareRetry(id, actor(auth)) ? executor.execute(id) : finance.view(id);
	}

	/** Ledger entries between two dates (inclusive, Asia/Kolkata); defaults to the last 30 days. */
	@GetMapping("/ledger")
	LedgerReport ledger(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return finance.ledger(from, to);
	}

	private static Actor actor(Authentication auth) {
		return Actor.staff(auth.getName(), "FINANCE");
	}
}
