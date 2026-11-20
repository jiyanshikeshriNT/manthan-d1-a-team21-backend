package com.example.customer_retail_system.system.order;

import java.util.Locale;
import java.util.Optional;

import com.example.customer_retail_system.system.order.internal.CustomerOrder;
import com.example.customer_retail_system.system.order.internal.CustomerOrderRepository;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the order module. */
@Service
@Transactional(readOnly = true)
public class OrderService {

	private final CustomerOrderRepository orders;

	OrderService(CustomerOrderRepository orders) {
		this.orders = orders;
	}

	public static String normalizeOrderNumber(String orderNumber) {
		return orderNumber == null ? "" : orderNumber.trim().toUpperCase(Locale.ROOT);
	}

	public Optional<OrderView> findByNumber(String orderNumber) {
		return orders.findByOrderNumber(normalizeOrderNumber(orderNumber)).map(OrderService::toView);
	}

	public Optional<OrderView> findById(long orderId) {
		return orders.findWithLinesById(orderId).map(OrderService::toView);
	}

	/**
	 * Locks the order row ({@code SELECT … FOR UPDATE}) for the rest of the caller's transaction,
	 * so concurrent return requests on the same order are serialised (G3).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public OrderView lockForUpdate(long orderId) {
		return orders.lockById(orderId).map(OrderService::toView)
				.orElseThrow(() -> new DomainException(ErrorCode.ORDER_NOT_FOUND,
						"We couldn't find an order matching those details."));
	}

	private static OrderView toView(CustomerOrder o) {
		return new OrderView(o.getId(), o.getOrderNumber(), o.getCustomerId(), o.getOrderedAt(), o.getDeliveredAt(),
				o.getPaymentMethod().name(),
				o.getLines().stream()
						.map(l -> new OrderView.OrderLineView(l.getId(), l.getProductId(), l.getQuantity(),
								l.getUnitPricePaise()))
						.toList());
	}
}
