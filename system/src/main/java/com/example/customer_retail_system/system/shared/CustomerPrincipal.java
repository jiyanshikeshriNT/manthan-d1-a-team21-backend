package com.example.customer_retail_system.system.shared;

/**
 * The authenticated customer: a bearer access token is scoped to exactly one order (G12).
 * Lives in {@code shared} so web controllers in any module can read it without depending on
 * the security module.
 */
public record CustomerPrincipal(long orderId) {
}
