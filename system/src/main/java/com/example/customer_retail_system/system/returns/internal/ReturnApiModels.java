package com.example.customer_retail_system.system.returns.internal;

import java.time.Instant;
import java.util.List;

import com.example.customer_retail_system.system.returns.ReasonCode;
import com.example.customer_retail_system.system.returns.RefundMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request/response bodies of the customer returns API (LLD §4). Entities never leave the module. */
final class ReturnApiModels {

	private ReturnApiModels() {
	}

	/** The JSON {@code request} part of {@code POST /customer/returns}. */
	record CreateReturnRequest(
			@NotBlank @Size(max = 32) String orderNumber,
			@NotNull RefundMethod refundMethod,
			@NotEmpty @Size(max = 50) List<@Valid @NotNull Item> items) {

		record Item(
				@NotNull Long orderLineId,
				@NotNull @Positive Integer qty,
				@NotNull ReasonCode reasonCode,
				@Size(max = 500) String reasonText) {
		}
	}

	record ReturnDto(
			String rma,
			String orderNumber,
			String status,
			String statusLabel,
			String refundMethod,
			/** Null until the return is approved (no label to print while under review). */
			String labelCode,
			Instant createdAt,
			/** Sum of the per-item refunds; null until the return has been inspected. */
			Long totalRefundPaise,
			List<ItemDto> items,
			List<Timeline.Entry> timeline) {
	}

	/**
	 * One returned item. Inspection fields ({@code conditionGrade}, {@code decision}, {@code acceptedQty},
	 * {@code refundPaise}) stay null until the warehouse has inspected the return.
	 */
	record ItemDto(long returnItemId, long orderLineId, String sku, String productName, long unitPricePaise,
			int quantity, String reasonCode, String reasonText, int photoCount, String conditionGrade,
			String decision, Integer acceptedQty, Long refundPaise) {
	}

	record LabelDto(String rma, String labelCode, String qrCodeUrl, String instructions) {
	}
}
