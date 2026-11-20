package com.example.customer_retail_system.system.order.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A customer's order. Other modules are referenced by id only (customer, product) to keep
 * module boundaries clean.
 */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_number", nullable = false, unique = true, length = 32)
	private String orderNumber;

	@Column(name = "customer_id", nullable = false)
	private Long customerId;

	@Column(name = "ordered_at", nullable = false)
	private Instant orderedAt;

	/** Null until delivered; the return window is anchored on this (G1). */
	@Column(name = "delivered_at")
	private Instant deliveredAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, length = 20)
	private PaymentMethod paymentMethod;

	@Column(name = "payment_ref", nullable = false, length = 64)
	private String paymentRef;

	@Version
	@Column(nullable = false)
	private long version;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<OrderLine> lines = new ArrayList<>();

	protected CustomerOrder() {
	}

	public CustomerOrder(String orderNumber, Long customerId, Instant orderedAt, Instant deliveredAt,
			PaymentMethod paymentMethod, String paymentRef) {
		this.orderNumber = orderNumber;
		this.customerId = customerId;
		this.orderedAt = orderedAt;
		this.deliveredAt = deliveredAt;
		this.paymentMethod = paymentMethod;
		this.paymentRef = paymentRef;
	}

	public OrderLine addLine(Long productId, int quantity, long unitPricePaise) {
		OrderLine line = new OrderLine(this, productId, quantity, unitPricePaise);
		lines.add(line);
		return line;
	}

	public void markDelivered(Instant at) {
		this.deliveredAt = at;
	}

	public Long getId() {
		return id;
	}

	public String getOrderNumber() {
		return orderNumber;
	}

	public Long getCustomerId() {
		return customerId;
	}

	public Instant getOrderedAt() {
		return orderedAt;
	}

	public Instant getDeliveredAt() {
		return deliveredAt;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public String getPaymentRef() {
		return paymentRef;
	}

	public long getVersion() {
		return version;
	}

	public List<OrderLine> getLines() {
		return Collections.unmodifiableList(lines);
	}
}
