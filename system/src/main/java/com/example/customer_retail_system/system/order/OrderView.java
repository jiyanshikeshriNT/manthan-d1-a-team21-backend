package com.example.customer_retail_system.system.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read model of an order exposed to other modules (entities never leave the module). */
public record OrderView(
		long id,
		String orderNumber,
		long customerId,
		Instant orderedAt,
		Instant deliveredAt,
		String paymentMethod,
		String paymentRef,
		List<OrderLineView> lines) {

	public record OrderLineView(long id, long productId, int quantity, long unitPricePaise) {
	}

	public Optional<OrderLineView> line(long orderLineId) {
		return lines.stream().filter(l -> l.id() == orderLineId).findFirst();
	}
}
