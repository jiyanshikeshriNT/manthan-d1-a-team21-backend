package com.example.customer_retail_system.system.returns.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Human-friendly, unique RMA numbers ({@code RMA-100001}) from a database sequence. */
@Component
class RmaNumberGenerator {

	private final JdbcTemplate jdbc;

	RmaNumberGenerator(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	String next() {
		Long n = jdbc.queryForObject("SELECT nextval('rma_number_seq')", Long.class);
		return "RMA-" + n;
	}
}
