package com.example.customer_retail_system.system.inspection.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.customer_retail_system.system.catalog.CatalogService;
import com.example.customer_retail_system.system.catalog.ProductInfo;
import com.example.customer_retail_system.system.inspection.internal.WarehouseModels.InspectionRequest;
import com.example.customer_retail_system.system.inspection.internal.WarehouseModels.ItemView;
import com.example.customer_retail_system.system.inspection.internal.WarehouseModels.WarehouseReturnView;
import com.example.customer_retail_system.system.order.OrderService;
import com.example.customer_retail_system.system.order.OrderView;
import com.example.customer_retail_system.system.refund.RefundService;
import com.example.customer_retail_system.system.returns.InspectionDecision;
import com.example.customer_retail_system.system.returns.ReturnItem;
import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.returns.ReturnsApi;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import com.example.customer_retail_system.system.shared.Money;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Warehouse actions (LLD §3 flow 4): label lookup, receive, inspection with refund calculation. */
@Service
class InspectionService {

	private final ReturnsApi returns;
	private final ReturnStateMachine stateMachine;
	private final OrderService orders;
	private final CatalogService catalog;
	private final RefundService refunds;

	InspectionService(ReturnsApi returns, ReturnStateMachine stateMachine, OrderService orders, CatalogService catalog,
			RefundService refunds) {
		this.returns = returns;
		this.stateMachine = stateMachine;
		this.orders = orders;
		this.catalog = catalog;
		this.refunds = refunds;
	}

	/** Result of an inspection: the view, plus a refund to execute after commit when no approval is needed. */
	record InspectionOutcome(String rma, Long refundIdToExecute) {
	}

	@Transactional(readOnly = true)
	WarehouseReturnView byLabel(String labelCode) {
		String rma = returns.rmaFromLabel(labelCode);
		ReturnRequest ret = returns.findByRma(rma)
				.orElseThrow(() -> new DomainException(ErrorCode.LABEL_INVALID, "This label isn't valid. Check you scanned the right parcel."));
		return view(ret);
	}

	@Transactional(readOnly = true)
	WarehouseReturnView byRma(String rma) {
		return view(returns.requireByRma(rma));
	}

	/** IN_TRANSIT → RECEIVED. Scanning a return that is already RECEIVED is a no-op (no new audit row). */
	@Transactional
	WarehouseReturnView receive(String rma, String labelCode, Actor actor) {
		if (labelCode != null && !labelCode.isBlank() && !returns.rmaFromLabel(labelCode).equals(rma)) {
			throw new DomainException(ErrorCode.LABEL_INVALID, "This label belongs to a different return.");
		}
		ReturnRequest ret = returns.requireByRma(rma);
		if (ret.getStatus() != ReturnStatus.RECEIVED) {
			stateMachine.transition(ret, ReturnStatus.RECEIVED, actor, "Parcel received at the warehouse");
		}
		return view(ret);
	}

	/**
	 * Grades every item, computes refunds ({@code unitPricePaise × acceptedQty × grade%}, HALF_UP),
	 * then RECEIVED → INSPECTED and either → REJECTED (nothing accepted, no refund) or hands over to
	 * the refund module, which routes to finance approval or immediate execution.
	 */
	@Transactional
	InspectionOutcome inspect(String rma, InspectionRequest request, Actor actor) {
		ReturnRequest ret = returns.requireByRma(rma);
		if (ret.getStatus() != ReturnStatus.RECEIVED) {
			throw new DomainException(ErrorCode.INVALID_TRANSITION,
					"Only received returns can be inspected. This return is " + ret.getStatus() + ".",
					Map.of("currentStatus", ret.getStatus().name()));
		}
		Map<Long, InspectionRequest.Item> byItem = new HashMap<>();
		for (InspectionRequest.Item i : request.items()) {
			if (byItem.put(i.itemId(), i) != null) {
				throw validation("Each item can be inspected only once.");
			}
		}
		if (byItem.size() != ret.getItems().size()
				|| !ret.getItems().stream().allMatch(it -> byItem.containsKey(it.getId()))) {
			throw validation("Inspect every item in the return exactly once.");
		}
		OrderView order = orders.findById(ret.getOrderId()).orElseThrow();

		long total = 0;
		int acceptedUnits = 0;
		for (ReturnItem item : ret.getItems()) {
			InspectionRequest.Item verdict = byItem.get(item.getId());
			int accepted = acceptedQty(item, verdict);
			long unitPrice = order.line(item.getOrderLineId()).orElseThrow().unitPricePaise();
			long refund = Money.percentOf(Money.times(unitPrice, accepted), verdict.grade().refundPercent());
			item.recordInspection(verdict.grade(), verdict.decision(), accepted, refund, blankToNull(verdict.note()));
			total = Math.addExact(total, refund);
			acceptedUnits += accepted;
		}
		ret.recordTotalRefund(total);
		stateMachine.transition(ret, ReturnStatus.INSPECTED, actor,
				"Inspected: %d unit(s) accepted, refund %s".formatted(acceptedUnits, Money.format(total)));

		if (total == 0) {
			stateMachine.transition(ret, ReturnStatus.REJECTED, actor, "All items were rejected at inspection");
			return new InspectionOutcome(rma, null);
		}
		RefundService.RefundRouting routing = refunds.openForInspectedReturn(ret, actor);
		return new InspectionOutcome(rma, routing.requiresApproval() ? null : routing.refundId());
	}

	private static int acceptedQty(ReturnItem item, InspectionRequest.Item verdict) {
		if (verdict.decision() == InspectionDecision.REJECTED) {
			if (verdict.acceptedQty() != null && verdict.acceptedQty() != 0) {
				throw validation("A rejected item can't have accepted units (item " + item.getId() + ").");
			}
			return 0;
		}
		if (verdict.acceptedQty() == null) {
			return item.getRequestedQty();
		}
		if (verdict.acceptedQty() < 1 || verdict.acceptedQty() > item.getRequestedQty()) {
			throw validation("Accepted quantity for item " + item.getId() + " must be between 1 and "
					+ item.getRequestedQty() + ".");
		}
		return verdict.acceptedQty();
	}

	WarehouseReturnView view(ReturnRequest ret) {
		OrderView order = orders.findById(ret.getOrderId()).orElseThrow();
		Map<Long, ProductInfo> products = catalog.findProducts(order.lines().stream().map(OrderView.OrderLineView::productId).toList());
		Map<Long, OrderView.OrderLineView> lines = order.lines().stream()
				.collect(Collectors.toMap(OrderView.OrderLineView::id, Function.identity()));
		List<ItemView> items = ret.getItems().stream().map(i -> {
			OrderView.OrderLineView line = lines.get(i.getOrderLineId());
			ProductInfo p = products.get(line.productId());
			return new ItemView(i.getId(), i.getOrderLineId(), p.sku(), p.name(), i.getRequestedQty(),
					line.unitPricePaise(), i.getReasonCode().name(), i.getReasonText(), i.getPhotos().size(),
					i.getConditionGrade(), i.getDecision(), i.getAcceptedQty(), i.getRefundPaise(), i.getInspectionNote());
		}).toList();
		return new WarehouseReturnView(ret.getId(), ret.getRmaNumber(), order.orderNumber(), ret.getStatus().name(),
				ret.getRefundMethod().name(), ret.getTotalRefundPaise(), items,
				refunds.findByReturnId(ret.getId()).orElse(null));
	}

	private static DomainException validation(String message) {
		return new DomainException(ErrorCode.VALIDATION_FAILED, message);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
