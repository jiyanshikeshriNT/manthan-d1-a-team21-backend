package com.example.customer_retail_system.system.order;

import java.util.Map;

/**
 * Port implemented by the returns module (dependency inversion keeps {@code order} free of a
 * dependency on {@code returns}): units of each order line already in non-cancelled,
 * non-rejected returns (G3).
 */
public interface ReturnedQuantities {

	/** orderLineId → units already in active returns; lines with none may be absent. */
	Map<Long, Integer> activeReturnedQuantities(long orderId);
}
