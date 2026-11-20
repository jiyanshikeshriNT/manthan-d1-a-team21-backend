package com.example.customer_retail_system.system.shared;

import java.util.Map;
import java.util.Objects;

/**
 * The single business exception type. {@code message} is customer/staff-facing microcopy;
 * {@code properties} carries extra structured detail (e.g. per-item refusal reasons).
 */
public class DomainException extends RuntimeException {

	private final ErrorCode code;
	private final Map<String, Object> properties;

	public DomainException(ErrorCode code, String message) {
		this(code, message, Map.of());
	}

	public DomainException(ErrorCode code, String message, Map<String, Object> properties) {
		super(message);
		this.code = Objects.requireNonNull(code, "code");
		this.properties = Map.copyOf(properties);
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}
}
