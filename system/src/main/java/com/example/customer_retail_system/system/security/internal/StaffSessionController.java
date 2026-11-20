package com.example.customer_retail_system.system.security.internal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff identity for the web portal: lets the UI verify HTTP Basic credentials and learn the role
 * (WAREHOUSE, FINANCE or MANAGER) before showing a workspace. Any staff role may call it.
 */
@RestController
@RequestMapping("/api/v1/staff")
class StaffSessionController {

	private static final String ROLE_PREFIX = "ROLE_";

	record StaffMe(String username, String role) {
	}

	@GetMapping("/me")
	StaffMe me(Authentication auth) {
		String role = auth.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.filter(a -> a.startsWith(ROLE_PREFIX))
				.map(a -> a.substring(ROLE_PREFIX.length()))
				.findFirst()
				.orElse(null);
		return new StaffMe(auth.getName(), role);
	}
}
