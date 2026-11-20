package com.example.customer_retail_system.system.order.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.customer_retail_system.system.order.OrderEligibilityService;
import com.example.customer_retail_system.system.order.OrderService;
import com.example.customer_retail_system.system.order.OrderView;
import com.example.customer_retail_system.system.shared.CustomerPrincipal;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/orders")
class CustomerOrderController {

	private final OrderService orders;
	private final OrderEligibilityService eligibility;

	CustomerOrderController(OrderService orders, OrderEligibilityService eligibility) {
		this.orders = orders;
		this.eligibility = eligibility;
	}

	record EligibilityDto(boolean eligible, String code, String message, int returnableQty, LocalDate deadline) {
	}

	record LineDto(long orderLineId, String sku, String productName, String category, int quantity,
			long unitPricePaise, EligibilityDto eligibility) {
	}

	record OrderDto(String orderNumber, Instant orderedAt, Instant deliveredAt, List<LineDto> lines) {
	}

	/** The order the caller's token is scoped to, with per-line return eligibility. */
	@GetMapping("/{orderNumber}")
	OrderDto get(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable String orderNumber) {
		OrderView order = orders.findByNumber(orderNumber)
				.filter(o -> o.id() == principal.orderId())
				.orElseThrow(() -> new DomainException(ErrorCode.ORDER_NOT_FOUND,
						"We couldn't find an order matching those details."));
		List<LineDto> lines = eligibility.evaluate(order).stream().map(le -> {
			var e = le.eligibility();
			return new LineDto(le.line().id(), le.product().sku(), le.product().name(), le.product().categoryName(),
					le.line().quantity(), le.line().unitPricePaise(),
					new EligibilityDto(e.eligible(), e.code().name(), e.message(), e.returnableQty(), e.deadline()));
		}).toList();
		return new OrderDto(order.orderNumber(), order.orderedAt(), order.deliveredAt(), lines);
	}
}
