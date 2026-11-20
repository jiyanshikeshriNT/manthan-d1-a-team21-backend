package com.example.customer_retail_system.system.order;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.catalog.CatalogService;
import com.example.customer_retail_system.system.catalog.ProductInfo;
import com.example.customer_retail_system.system.eligibility.EligibilityEngine;
import com.example.customer_retail_system.system.eligibility.EligibilityInput;
import com.example.customer_retail_system.system.eligibility.EligibilityResult;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-line return eligibility for an order. The single place that combines catalog policy,
 * delivery date, quantities already being returned and the clock; used both by the customer
 * order view and by return creation, so the two can never disagree.
 */
@Service
@Transactional(readOnly = true)
public class OrderEligibilityService {

	private final CatalogService catalog;
	private final EligibilityEngine engine;
	private final ObjectProvider<ReturnedQuantities> returnedQuantities;
	private final Clock clock;

	OrderEligibilityService(CatalogService catalog, EligibilityEngine engine,
			ObjectProvider<ReturnedQuantities> returnedQuantities, Clock clock) {
		this.catalog = catalog;
		this.engine = engine;
		this.returnedQuantities = returnedQuantities;
		this.clock = clock;
	}

	public record LineEligibility(OrderView.OrderLineView line, ProductInfo product, EligibilityResult eligibility) {
	}

	public List<LineEligibility> evaluate(OrderView order) {
		Map<Long, ProductInfo> products = catalog.findProducts(
				order.lines().stream().map(OrderView.OrderLineView::productId).toList());
		Map<Long, Integer> returned = returnedQuantities.getIfAvailable(() -> orderId -> Map.of())
				.activeReturnedQuantities(order.id());
		var now = clock.instant();
		return order.lines().stream().map(line -> {
			ProductInfo product = products.get(line.productId());
			EligibilityInput input = new EligibilityInput(product.categoryName(), product.returnable(),
					product.returnWindowDays(), order.deliveredAt(), line.quantity(),
					returned.getOrDefault(line.id(), 0));
			return new LineEligibility(line, product, engine.evaluate(input, now));
		}).toList();
	}
}
