package com.example.customer_retail_system.system.shared.internal;

import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps exceptions to RFC 7807 ProblemDetail responses that always carry a {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
		return problem(ex.code(), ex.getMessage(), ex.properties());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException ex) {
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> Map.of("field", e.getField(),
						"message", e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()))
				.toList();
		return problem(ErrorCode.VALIDATION_FAILED, "Some fields need your attention.", Map.of("errors", errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
		return problem(ErrorCode.VALIDATION_FAILED, "The request body could not be read.", Map.of());
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> handleConcurrency(OptimisticLockingFailureException ex) {
		return problem(ErrorCode.CONCURRENT_MODIFICATION,
				"This record was changed by someone else. Refresh and try again.", Map.of());
	}

	private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, Map<String, Object> props) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
		body.setTitle(code.title());
		body.setProperty("code", code.name());
		props.forEach(body::setProperty);
		return ResponseEntity.status(code.status()).body(body);
	}
}
