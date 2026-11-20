package com.example.customer_retail_system.system.security.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401/403 responses as RFC 7807 ProblemDetail with {@code code}, matching GlobalExceptionHandler.
 * Security errors happen before MVC, so they are written here. Only fixed strings are emitted.
 */
final class ProblemResponses {

	static final String EXPIRED_TOKEN_COPY = "Your session has expired. Please look up your order again.";
	static final String AUTH_REQUIRED_COPY = "Please sign in to continue.";
	static final String FORBIDDEN_COPY = "You don't have access to this resource.";

	private ProblemResponses() {
	}

	static AuthenticationEntryPoint entryPoint() {
		return (request, response, ex) -> write(response, ErrorCode.UNAUTHORIZED,
				request.getAttribute(CustomerTokenFilter.INVALID_TOKEN_ATTRIBUTE) != null
						? EXPIRED_TOKEN_COPY : AUTH_REQUIRED_COPY);
	}

	static AccessDeniedHandler accessDeniedHandler() {
		return (request, response, ex) -> write(response, ErrorCode.FORBIDDEN, FORBIDDEN_COPY);
	}

	private static void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
		int status = code.status().value();
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + code.title() + "\",\"status\":" + status
				+ ",\"detail\":\"" + detail + "\",\"code\":\"" + code.name() + "\"}");
	}
}
