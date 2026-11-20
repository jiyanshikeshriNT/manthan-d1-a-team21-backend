package com.example.customer_retail_system.system.security.internal;

import java.time.Clock;
import java.util.Optional;

import com.example.customer_retail_system.system.customer.CustomerService;
import com.example.customer_retail_system.system.order.OrderService;
import com.example.customer_retail_system.system.order.OrderView;
import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.stereotype.Service;

/**
 * Order lookup by order number + email (G12). Every miss gets the same generic 404 so the
 * endpoint cannot be used to discover which order numbers or emails exist.
 */
@Service
class CustomerSessionService {

	static final String NOT_FOUND_COPY =
			"We couldn't find an order matching those details. Check the order number and email address.";
	static final String RATE_LIMITED_COPY = "Too many attempts. Please wait a minute and try again.";

	private final OrderService orders;
	private final CustomerService customers;
	private final AccessTokenService tokens;
	private final SlidingWindowRateLimiter limiter;

	CustomerSessionService(OrderService orders, CustomerService customers, AccessTokenService tokens,
			Clock clock, AppProperties properties) {
		this.orders = orders;
		this.customers = customers;
		this.tokens = tokens;
		this.limiter = new SlidingWindowRateLimiter(clock, properties.session().maxAttempts(),
				properties.session().attemptWindow());
	}

	AccessTokenService.IssuedToken open(String orderNumber, String email, String clientIp) {
		String normalizedOrder = OrderService.normalizeOrderNumber(orderNumber);
		// IP first: a blocked client must not also burn the order's budget (which would lock the owner out).
		if (!limiter.tryAcquire("ip:" + clientIp) || !limiter.tryAcquire("order:" + normalizedOrder)) {
			throw new DomainException(ErrorCode.RATE_LIMITED, RATE_LIMITED_COPY);
		}
		Optional<OrderView> order = orders.findByNumber(normalizedOrder);
		if (order.isEmpty() || !customers.emailMatches(order.get().customerId(), email)) {
			throw new DomainException(ErrorCode.ORDER_NOT_FOUND, NOT_FOUND_COPY);
		}
		return tokens.issue(order.get().id());
	}
}
