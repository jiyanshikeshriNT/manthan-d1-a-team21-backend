package com.example.customer_retail_system.system.security.internal;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <token>} as a customer scoped to one order.
 * An invalid or expired token leaves the request unauthenticated (→ 401 from the entry point).
 * Not a {@code @Component}: it is registered only inside the security filter chain.
 */
class CustomerTokenFilter extends OncePerRequestFilter {

	static final String INVALID_TOKEN_ATTRIBUTE = CustomerTokenFilter.class.getName() + ".invalid";

	private static final String BEARER = "Bearer ";

	private final AccessTokenService tokens;

	CustomerTokenFilter(AccessTokenService tokens) {
		this.tokens = tokens;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			tokens.resolve(header.substring(BEARER.length()).trim()).ifPresentOrElse(principal -> {
				var auth = new UsernamePasswordAuthenticationToken(principal, null,
						List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
				SecurityContextHolder.getContext().setAuthentication(auth);
			}, () -> request.setAttribute(INVALID_TOKEN_ATTRIBUTE, Boolean.TRUE));
		}
		chain.doFilter(request, response);
	}
}
