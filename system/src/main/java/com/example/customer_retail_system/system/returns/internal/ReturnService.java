package com.example.customer_retail_system.system.returns.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.catalog.CatalogService;
import com.example.customer_retail_system.system.catalog.ProductInfo;
import com.example.customer_retail_system.system.customer.CustomerService;
import com.example.customer_retail_system.system.order.OrderEligibilityService;
import com.example.customer_retail_system.system.order.OrderEligibilityService.LineEligibility;
import com.example.customer_retail_system.system.order.OrderService;
import com.example.customer_retail_system.system.order.OrderView;
import com.example.customer_retail_system.system.returns.ReasonCode;
import com.example.customer_retail_system.system.returns.ReturnItem;
import com.example.customer_retail_system.system.returns.ReturnRequest;
import com.example.customer_retail_system.system.returns.ReturnStateMachine;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.CreateReturnRequest;
import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.ItemDto;
import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.LabelDto;
import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.ReturnDto;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.AppProperties;
import com.example.customer_retail_system.system.shared.CustomerPrincipal;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Customer return use cases (LLD §3 flows 2–3). */
@Service
public class ReturnService {

	static final String ORDER_NOT_FOUND_COPY = "We couldn't find an order matching those details.";
	static final String RETURN_NOT_FOUND_COPY = "We couldn't find that return.";
	static final String NOT_RETURNABLE_COPY = "Some items in your request can't be returned.";
	static final String LABEL_INSTRUCTIONS =
			"Print this label and attach it to the parcel, or show the QR code at the drop-off point.";

	/** Statuses in which the customer may print the label. */
	static final Set<ReturnStatus> LABEL_AVAILABLE = EnumSet.of(ReturnStatus.APPROVED, ReturnStatus.IN_TRANSIT,
			ReturnStatus.RECEIVED, ReturnStatus.INSPECTED, ReturnStatus.AWAITING_FINANCE,
			ReturnStatus.REFUND_IN_PROGRESS, ReturnStatus.REFUNDED);

	private final ReturnRequestRepository returns;
	private final OrderService orders;
	private final OrderEligibilityService eligibility;
	private final CustomerService customers;
	private final ReturnStateMachine stateMachine;
	private final AuditLog auditLog;
	private final PhotoStorage photos;
	private final LabelCodec labels;
	private final RmaNumberGenerator rmaNumbers;
	private final Clock clock;
	private final AppProperties properties;
	private final CatalogService catalog;

	ReturnService(ReturnRequestRepository returns, OrderService orders, OrderEligibilityService eligibility,
			CustomerService customers, ReturnStateMachine stateMachine, AuditLog auditLog, PhotoStorage photos,
			LabelCodec labels, RmaNumberGenerator rmaNumbers, Clock clock, AppProperties properties,
			CatalogService catalog) {
		this.catalog = catalog;
		this.returns = returns;
		this.orders = orders;
		this.eligibility = eligibility;
		this.customers = customers;
		this.stateMachine = stateMachine;
		this.auditLog = auditLog;
		this.photos = photos;
		this.labels = labels;
		this.rmaNumbers = rmaNumbers;
		this.clock = clock;
		this.properties = properties;
	}

	record CreateOutcome(ReturnDto body, boolean replayed) {
	}

	/**
	 * Creates a return for the caller's order. All-or-nothing: if any selected line is ineligible
	 * the whole request is refused with per-item reasons. Replaying an Idempotency-Key returns the
	 * original return instead of creating a second one.
	 */
	@Transactional
	public CreateOutcome create(CustomerPrincipal principal, String idempotencyKey, CreateReturnRequest request,
			Map<Long, List<MultipartFile>> photosByLine) {
		OrderView order = orders.findByNumber(request.orderNumber())
				.filter(o -> o.id() == principal.orderId())
				.orElseThrow(() -> new DomainException(ErrorCode.ORDER_NOT_FOUND, ORDER_NOT_FOUND_COPY));
		String clientKey = clientKey(order.id(), idempotencyKey);
		if (clientKey != null) {
			var existing = returns.findByClientRequestKey(clientKey);
			if (existing.isPresent()) {
				return new CreateOutcome(toDto(existing.get(), order.orderNumber()), true);
			}
		}
		validateShape(order, request, photosByLine);

		// Serialise concurrent requests on this order (G3); re-check the key once we hold the lock.
		order = orders.lockForUpdate(order.id());
		if (clientKey != null) {
			var existing = returns.findByClientRequestKey(clientKey);
			if (existing.isPresent()) {
				return new CreateOutcome(toDto(existing.get(), order.orderNumber()), true);
			}
		}
		checkEligibility(order, request);

		Instant now = clock.instant();
		int requestsInWindow = Math.toIntExact(returns.countByCustomerIdAndCreatedAtAfterAndStatusNot(
				order.customerId(), now.minus(Duration.ofDays(properties.flagging().windowDays())),
				ReturnStatus.CANCELLED)) + 1; // the new request counts (G8)
		boolean flagged = requestsInWindow > properties.flagging().maxRequests();

		String rma = rmaNumbers.next();
		ReturnRequest ret = new ReturnRequest(UUID.randomUUID(), rma, order.id(), order.customerId(),
				request.refundMethod(), flagged, labels.encode(rma), clientKey, now);
		for (CreateReturnRequest.Item item : request.items()) {
			ReturnItem returnItem = ret.addItem(item.orderLineId(), item.qty(), item.reasonCode(),
					blankToNull(item.reasonText()));
			for (MultipartFile file : photosByLine.getOrDefault(item.orderLineId(), List.of())) {
				PhotoStorage.StoredPhoto stored = photos.store(file);
				returnItem.addPhoto(stored.storedName(), stored.contentType(), stored.sizeBytes());
			}
		}
		returns.saveAndFlush(ret);

		Actor customer = Actor.customer(order.customerId());
		stateMachine.start(ret, customer, "Return requested for " + request.items().size() + " item(s)");
		if (flagged) {
			stateMachine.transition(ret, ReturnStatus.PENDING_REVIEW, Actor.system(),
					"Customer has %d return requests in the last %d days; manual review required"
							.formatted(requestsInWindow, properties.flagging().windowDays()));
			customers.recordFlag(order.customerId(), ret.getId(), requestsInWindow, now);
		}
		else {
			stateMachine.transition(ret, ReturnStatus.APPROVED, Actor.system(), "All items eligible; approved automatically");
		}
		return new CreateOutcome(toDto(ret, order.orderNumber()), false);
	}

	@Transactional(readOnly = true)
	public ReturnDto get(CustomerPrincipal principal, String rma) {
		ReturnRequest ret = load(principal, rma);
		return toDto(ret, orderNumber(ret));
	}

	@Transactional(readOnly = true)
	public LabelDto label(CustomerPrincipal principal, String rma) {
		ReturnRequest ret = requireLabel(load(principal, rma));
		return new LabelDto(ret.getRmaNumber(), ret.getLabelCode(),
				"/api/v1/customer/returns/" + ret.getRmaNumber() + "/label/qr.png", LABEL_INSTRUCTIONS);
	}

	@Transactional(readOnly = true)
	public byte[] labelQrPng(CustomerPrincipal principal, String rma) {
		return QrCodes.png(requireLabel(load(principal, rma)).getLabelCode());
	}

	/** Customer hands the parcel to the carrier: APPROVED → IN_TRANSIT. Repeating it is a no-op. */
	@Transactional
	public ReturnDto ship(CustomerPrincipal principal, String rma) {
		ReturnRequest ret = load(principal, rma);
		if (ret.getStatus() != ReturnStatus.IN_TRANSIT) {
			stateMachine.transition(ret, ReturnStatus.IN_TRANSIT, Actor.customer(ret.getCustomerId()),
					"Customer handed the parcel to the carrier");
		}
		return toDto(ret, orderNumber(ret));
	}

	/** Allowed only before IN_TRANSIT (G11). Repeating it is a no-op. */
	@Transactional
	public ReturnDto cancel(CustomerPrincipal principal, String rma) {
		ReturnRequest ret = load(principal, rma);
		if (ret.getStatus() != ReturnStatus.CANCELLED) {
			stateMachine.transition(ret, ReturnStatus.CANCELLED, Actor.customer(ret.getCustomerId()),
					"Cancelled by customer");
		}
		return toDto(ret, orderNumber(ret));
	}

	// --- helpers --------------------------------------------------------------------------------

	ReturnRequest load(CustomerPrincipal principal, String rma) {
		return returns.findByRmaNumber(rma)
				.filter(r -> r.getOrderId() == principal.orderId())
				.orElseThrow(() -> new DomainException(ErrorCode.RETURN_NOT_FOUND, RETURN_NOT_FOUND_COPY));
	}

	private static ReturnRequest requireLabel(ReturnRequest ret) {
		if (!LABEL_AVAILABLE.contains(ret.getStatus())) {
			String copy = ret.getStatus() == ReturnStatus.REQUESTED || ret.getStatus() == ReturnStatus.PENDING_REVIEW
					? "Your return label will be available once your return is approved."
					: "This return no longer has an active label.";
			throw new DomainException(ErrorCode.INVALID_TRANSITION, copy,
					Map.of("currentStatus", ret.getStatus().name()));
		}
		return ret;
	}

	String orderNumber(ReturnRequest ret) {
		return orders.findById(ret.getOrderId()).map(OrderView::orderNumber).orElse(null);
	}

	ReturnDto toDto(ReturnRequest ret, String orderNumber) {
		var history = auditLog.history(ReturnStateMachine.AUDIT_ENTITY_TYPE, ret.getId().toString());
		Map<Long, OrderView.OrderLineView> lines = orders.findById(ret.getOrderId())
				.map(o -> o.lines().stream()
						.collect(Collectors.toMap(OrderView.OrderLineView::id, Function.identity())))
				.orElse(Map.of());
		Map<Long, ProductInfo> products = catalog
				.findProducts(lines.values().stream().map(OrderView.OrderLineView::productId).toList());
		List<ItemDto> items = ret.getItems().stream().map(i -> {
			OrderView.OrderLineView line = lines.get(i.getOrderLineId());
			ProductInfo product = line == null ? null : products.get(line.productId());
			return new ItemDto(i.getId(), i.getOrderLineId(), product == null ? null : product.sku(),
					product == null ? null : product.name(), line == null ? 0 : line.unitPricePaise(),
					i.getRequestedQty(), i.getReasonCode().name(), i.getReasonText(), i.getPhotos().size(),
					i.getConditionGrade(), i.getDecision(), i.getAcceptedQty(), i.getRefundPaise());
		}).toList();
		return new ReturnDto(ret.getRmaNumber(), orderNumber, ret.getStatus().name(),
				Timeline.statusLabel(ret.getStatus()), ret.getRefundMethod().name(),
				LABEL_AVAILABLE.contains(ret.getStatus()) ? ret.getLabelCode() : null, ret.getCreatedAt(),
				ret.getTotalRefundPaise(), items, Timeline.build(ret.getStatus(), history));
	}

	private static String clientKey(long orderId, String idempotencyKey) {
		if (idempotencyKey == null) {
			return null;
		}
		String key = idempotencyKey.trim();
		if (key.isEmpty() || key.length() > 100) {
			throw new DomainException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be 1 to 100 characters.");
		}
		return orderId + ":" + key;
	}

	private static void validateShape(OrderView order, CreateReturnRequest request,
			Map<Long, List<MultipartFile>> photosByLine) {
		Set<Long> seen = new HashSet<>();
		for (CreateReturnRequest.Item item : request.items()) {
			if (!seen.add(item.orderLineId())) {
				throw validation("Each item can appear only once in a return.");
			}
			if (order.line(item.orderLineId()).isEmpty()) {
				throw validation("Item " + item.orderLineId() + " is not part of this order.");
			}
			if (item.reasonCode() == ReasonCode.OTHER && blankToNull(item.reasonText()) == null) {
				throw validation("Please tell us why you're returning item " + item.orderLineId() + ".");
			}
		}
		for (Map.Entry<Long, List<MultipartFile>> e : photosByLine.entrySet()) {
			if (!seen.contains(e.getKey())) {
				throw validation("Photos were attached for an item that isn't in this return.");
			}
			if (e.getValue().size() > PhotoStorage.MAX_PER_ITEM) {
				throw new DomainException(ErrorCode.PHOTO_INVALID,
						"You can add up to " + PhotoStorage.MAX_PER_ITEM + " photos per item.");
			}
		}
	}

	private void checkEligibility(OrderView order, CreateReturnRequest request) {
		Map<Long, LineEligibility> byLine = eligibility.evaluate(order).stream()
				.collect(Collectors.toMap(le -> le.line().id(), Function.identity()));
		List<Map<String, Object>> refusals = new ArrayList<>();
		for (CreateReturnRequest.Item item : request.items()) {
			var e = byLine.get(item.orderLineId()).eligibility();
			if (!e.eligible()) {
				refusals.add(refusal(item.orderLineId(), e.code().name(), e.message()));
			}
			else if (item.qty() > e.returnableQty()) {
				String copy = e.returnableQty() == 1 ? "Only 1 unit of this item can be returned."
						: "Only " + e.returnableQty() + " units of this item can be returned.";
				refusals.add(refusal(item.orderLineId(), "QUANTITY_EXCEEDED", copy));
			}
		}
		if (!refusals.isEmpty()) {
			throw new DomainException(ErrorCode.ITEM_NOT_RETURNABLE, NOT_RETURNABLE_COPY, Map.of("items", refusals));
		}
	}

	private static Map<String, Object> refusal(long orderLineId, String code, String message) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("orderLineId", orderLineId);
		m.put("code", code);
		m.put("message", message);
		return m;
	}

	private static DomainException validation(String message) {
		return new DomainException(ErrorCode.VALIDATION_FAILED, message);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
