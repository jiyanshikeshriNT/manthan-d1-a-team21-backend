package com.example.customer_retail_system.system.refund.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.audit.AuditRecord;
import com.example.customer_retail_system.system.catalog.CatalogService;
import com.example.customer_retail_system.system.catalog.ProductInfo;
import com.example.customer_retail_system.system.order.OrderService;
import com.example.customer_retail_system.system.order.OrderView;
import com.example.customer_retail_system.system.refund.RefundView;
import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.returns.ReturnsApi;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for the finance review drawer: the refund plus the inspected return it pays for
 * (graded items, per-item refunds, and whether the customer was flagged).
 */
@Component
class FinanceRefundDetails {

	record Item(long itemId, String sku, String productName, int requestedQty, long unitPricePaise, String reasonCode,
			String reasonText, int photoCount, String conditionGrade, String decision, Integer acceptedQty,
			Long refundPaise, String inspectionNote) {
	}

	record Detail(RefundView refund, UUID returnId, String rma, String orderNumber, String returnStatus,
			boolean flagged, String flagReason, Instant requestedAt, List<Item> items) {
	}

	private final RefundSettlement settlement;
	private final RefundViews views;
	private final ReturnsApi returns;
	private final OrderService orders;
	private final CatalogService catalog;
	private final AuditLog auditLog;

	FinanceRefundDetails(RefundSettlement settlement, RefundViews views, ReturnsApi returns, OrderService orders,
			CatalogService catalog, AuditLog auditLog) {
		this.settlement = settlement;
		this.views = views;
		this.returns = returns;
		this.orders = orders;
		this.catalog = catalog;
		this.auditLog = auditLog;
	}

	@Transactional(readOnly = true)
	Detail detail(long refundId) {
		Refund refund = settlement.require(refundId);
		ReturnRequest ret = returns.requireById(refund.getReturnId());
		OrderView order = orders.findById(ret.getOrderId()).orElseThrow();
		Map<Long, OrderView.OrderLineView> lines = order.lines().stream()
				.collect(Collectors.toMap(OrderView.OrderLineView::id, Function.identity()));
		Map<Long, ProductInfo> products = catalog
				.findProducts(order.lines().stream().map(OrderView.OrderLineView::productId).toList());
		List<Item> items = ret.getItems().stream().map(i -> {
			OrderView.OrderLineView line = lines.get(i.getOrderLineId());
			ProductInfo p = products.get(line.productId());
			return new Item(i.getId(), p.sku(), p.name(), i.getRequestedQty(), line.unitPricePaise(),
					i.getReasonCode().name(), i.getReasonText(), i.getPhotos().size(), i.getConditionGrade(),
					i.getDecision(), i.getAcceptedQty(), i.getRefundPaise(), i.getInspectionNote());
		}).toList();
		String flagReason = !ret.isFlagged() ? null
				: auditLog.history(ReturnStateMachine.AUDIT_ENTITY_TYPE, ret.getId().toString()).stream()
						.filter(a -> ReturnStatus.PENDING_REVIEW.name().equals(a.toState()))
						.map(AuditRecord::details).findFirst().orElse(null);
		return new Detail(views.of(refund), ret.getId(), ret.getRmaNumber(), order.orderNumber(),
				ret.getStatus().name(), ret.isFlagged(), flagReason, ret.getCreatedAt(), items);
	}
}
