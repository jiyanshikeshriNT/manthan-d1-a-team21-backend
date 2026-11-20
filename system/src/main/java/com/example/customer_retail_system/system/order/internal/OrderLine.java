package com.example.customer_retail_system.system.order.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_line")
public class OrderLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private CustomerOrder order;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(nullable = false)
	private int quantity;

	/** Price actually paid per unit; refunds are computed from this, not the current list price. */
	@Column(name = "unit_price_paise", nullable = false)
	private long unitPricePaise;

	protected OrderLine() {
	}

	OrderLine(CustomerOrder order, Long productId, int quantity, long unitPricePaise) {
		this.order = order;
		this.productId = productId;
		this.quantity = quantity;
		this.unitPricePaise = unitPricePaise;
	}

	public Long getId() {
		return id;
	}

	public CustomerOrder getOrder() {
		return order;
	}

	public Long getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}

	public long getUnitPricePaise() {
		return unitPricePaise;
	}
}
