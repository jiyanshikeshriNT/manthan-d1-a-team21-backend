package com.example.customer_retail_system.system.security.internal;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer")
class CustomerSessionController {

	private final CustomerSessionService sessions;

	CustomerSessionController(CustomerSessionService sessions) {
		this.sessions = sessions;
	}

	record SessionRequest(
			@NotBlank @Size(max = 32) String orderNumber,
			@NotBlank @Email @Size(max = 254) String email) {
	}

	record SessionResponse(String accessToken, Instant expiresAt) {
	}

	/** Public: look up an order and receive a bearer token scoped to it. */
	@PostMapping("/session")
	SessionResponse open(@Valid @RequestBody SessionRequest request, HttpServletRequest http) {
		AccessTokenService.IssuedToken token = sessions.open(request.orderNumber(), request.email(), http.getRemoteAddr());
		return new SessionResponse(token.token(), token.expiresAt());
	}
}
