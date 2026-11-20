package com.example.customer_retail_system.system.security.internal;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Stateless API security (G16): customers use a bearer access token; staff use HTTP Basic with
 * BCrypt users from {@code staff_user}, one role each. CSRF is off because nothing uses cookies.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, AccessTokenService tokens) throws Exception {
		var entryPoint = ProblemResponses.entryPoint();
		http.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(b -> b.authenticationEntryPoint(entryPoint))
				.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint)
						.accessDeniedHandler(ProblemResponses.accessDeniedHandler()))
				.addFilterBefore(new CustomerTokenFilter(tokens), BasicAuthenticationFilter.class)
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.POST, "/api/v1/customer/session").permitAll()
						.requestMatchers("/api/v1/customer/**").hasRole("CUSTOMER")
						.requestMatchers("/api/v1/warehouse/**").hasRole("WAREHOUSE")
						.requestMatchers("/api/v1/finance/**").hasRole("FINANCE")
						.requestMatchers("/api/v1/manager/**", "/api/v1/admin/**").hasRole("MANAGER")
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
								"/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error")
						.permitAll()
						.anyRequest().authenticated());
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** Staff users from {@code staff_user}; replaces Boot's generated default user. */
	@Bean
	UserDetailsService staffUsers(JdbcTemplate jdbc) {
		return username -> {
			List<org.springframework.security.core.userdetails.UserDetails> users = jdbc.query(
					"SELECT username, password_hash, role FROM staff_user WHERE username = ?",
					(rs, i) -> User.withUsername(rs.getString(1)).password(rs.getString(2))
							.roles(rs.getString(3)).build(),
					username);
			return users.stream().findFirst()
					.orElseThrow(() -> new UsernameNotFoundException("Unknown staff user"));
		};
	}
}
