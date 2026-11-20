package com.example.customer_retail_system.system.shared.internal;

import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

	/** Optimistic ({@code @Version}) and pessimistic (lock timeout) conflicts alike. */
	@ExceptionHandler(ConcurrencyFailureException.class)
	ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyFailureException ex) {
		return problem(ErrorCode.CONCURRENT_MODIFICATION,
				"This record was changed by someone else. Refresh and try again.", Map.of());
	}

	@ExceptionHandler({ MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class,
			MultipartException.class })
	ResponseEntity<ProblemDetail> handleBadRequestShape(Exception ex) {
		if (ex instanceof MaxUploadSizeExceededException) {
			return problem(ErrorCode.PHOTO_INVALID, "Each photo must be 5 MB or smaller.", Map.of());
		}
		return problem(ErrorCode.VALIDATION_FAILED, "The request is missing a part or has an invalid value.", Map.of());
	}

	private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, Map<String, Object> props) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
		body.setTitle(code.title());
		body.setProperty("code", code.name());
		props.forEach(body::setProperty);
		return ResponseEntity.status(code.status()).body(body);
	}
}
