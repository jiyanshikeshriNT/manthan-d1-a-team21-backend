package com.example.customer_retail_system.system.catalog.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Product category carrying its return policy (G2): nothing about returnability is hard-coded. */
@Entity
@Table(name = "category")
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 32)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false)
	private boolean returnable;

	/** Null means "use the default window" (or not returnable at all). */
	@Column(name = "return_window_days")
	private Integer returnWindowDays;

	protected Category() {
	}

	public Category(String code, String name, boolean returnable, Integer returnWindowDays) {
		this.code = code;
		this.name = name;
		this.returnable = returnable;
		this.returnWindowDays = returnWindowDays;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public boolean isReturnable() {
		return returnable;
	}

	public Integer getReturnWindowDays() {
		return returnWindowDays;
	}
}
