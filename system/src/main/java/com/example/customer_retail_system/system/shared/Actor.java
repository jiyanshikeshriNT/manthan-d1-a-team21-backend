package com.example.customer_retail_system.system.shared;

/** Who performed an action, as recorded in the audit log. */
public record Actor(String id, String role) {

	public static Actor system() {
		return new Actor("system", "SYSTEM");
	}

	public static Actor customer(long customerId) {
		return new Actor("customer:" + customerId, "CUSTOMER");
	}

	public static Actor staff(String username, String role) {
		return new Actor("staff:" + username, role);
	}
}
