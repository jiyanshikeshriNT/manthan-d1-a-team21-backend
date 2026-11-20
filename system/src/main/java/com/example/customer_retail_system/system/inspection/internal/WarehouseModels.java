package com.example.customer_retail_system.system.inspection.internal;

import java.util.List;
import java.util.UUID;

import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.returns.ConditionGrade;
import com.example.customer_retail_system.system.returns.InspectionDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request/response bodies of the warehouse API (LLD §4). */
final class WarehouseModels {

	private WarehouseModels() {
	}

	/** Optional body of {@code receive}: the scanned label, checked against the RMA in the path (failure mode 9). */
	record ReceiveRequest(@Size(max = 64) String labelCode) {
	}

	record InspectionRequest(@NotEmpty @Size(max = 50) List<@Valid @NotNull Item> items) {

		record Item(
				@NotNull Long itemId,
				@NotNull ConditionGrade grade,
				@NotNull InspectionDecision decision,
				@PositiveOrZero Integer acceptedQty,
				@Size(max = 500) String note) {
		}
	}

	record DecodedLabel(String labelCode, String rma) {
	}

	record ItemView(long itemId, long orderLineId, String sku, String productName, int requestedQty,
			long unitPricePaise, String reasonCode, String reasonText, int photoCount, String conditionGrade,
			String decision, Integer acceptedQty, Long refundPaise, String inspectionNote) {
	}

	record WarehouseReturnView(UUID returnId, String rma, String orderNumber, String status, String refundMethod,
			Long totalRefundPaise, List<ItemView> items, RefundView refund) {
	}
}
