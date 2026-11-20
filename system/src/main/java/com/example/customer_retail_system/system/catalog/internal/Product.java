package com.example.customer_retail_system.system.catalog.internal;

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
@Table(name = "product")
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 32)
	private String sku;

	@Column(nullable = false, length = 200)
	private String name;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	@Column(name = "price_paise", nullable = false)
	private long pricePaise;

	protected Product() {
	}

	public Product(String sku, String name, Category category, long pricePaise) {
		this.sku = sku;
		this.name = name;
		this.category = category;
		this.pricePaise = pricePaise;
	}

	public Long getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getName() {
		return name;
	}

	public Category getCategory() {
		return category;
	}

	public long getPricePaise() {
		return pricePaise;
	}
}
