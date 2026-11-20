package com.example.customer_retail_system.system.dashboard.internal;

import java.time.Clock;
import java.time.LocalDate;

import com.example.customer_retail_system.system.dashboard.internal.DashboardModels.Dashboard;
import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.DateRange;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manager dashboard (role MANAGER). */
@RestController
@RequestMapping("/api/v1/manager")
class DashboardController {

	private final DashboardQueries queries;
	private final Clock clock;
	private final AppProperties properties;

	DashboardController(DashboardQueries queries, Clock clock, AppProperties properties) {
		this.queries = queries;
		this.clock = clock;
		this.properties = properties;
	}

	/** Dates are inclusive, in Asia/Kolkata; defaults to the last 30 days. */
	@GetMapping("/dashboard")
	Dashboard dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return queries.dashboard(DateRange.resolve(from, to, clock, properties.zone(), 30));
	}
}
