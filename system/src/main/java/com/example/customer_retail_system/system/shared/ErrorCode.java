package com.example.customer_retail_system.system.shared;

import org.springframework.http.HttpStatus;

/** Stable API error codes (LLD §4). The code is returned in every ProblemDetail as {@code code}. */
public enum ErrorCode {

	ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
	RETURN_NOT_FOUND(HttpStatus.NOT_FOUND, "Return not found"),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
	ITEM_NOT_RETURNABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Item not returnable"),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
	PHOTO_INVALID(HttpStatus.BAD_REQUEST, "Photo invalid"),
	LABEL_INVALID(HttpStatus.BAD_REQUEST, "Label invalid"),
	INVALID_TRANSITION(HttpStatus.CONFLICT, "Invalid transition"),
	CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
	GATEWAY_PENDING(HttpStatus.ACCEPTED, "Refund pending with gateway");

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}
}
