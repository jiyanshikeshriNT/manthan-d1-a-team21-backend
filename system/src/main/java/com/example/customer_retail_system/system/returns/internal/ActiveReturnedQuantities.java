package com.example.customer_retail_system.system.returns.internal;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import com.example.customer_retail_system.system.order.ReturnedQuantities;
import com.example.customer_retail_system.system.returns.ReturnStatus;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Units of each order line already in returns that are neither CANCELLED nor REJECTED (G3). */
@Component
class ActiveReturnedQuantities implements ReturnedQuantities {

	private static final EnumSet<ReturnStatus> RELEASED = EnumSet.of(ReturnStatus.CANCELLED, ReturnStatus.REJECTED);

	private final ReturnRequestRepository returns;

	ActiveReturnedQuantities(ReturnRequestRepository returns) {
		this.returns = returns;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, Integer> activeReturnedQuantities(long orderId) {
		Map<Long, Integer> result = new HashMap<>();
		for (Object[] row : returns.sumRequestedQtyByOrderLine(orderId, RELEASED)) {
			result.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
		}
		return result;
	}
}
