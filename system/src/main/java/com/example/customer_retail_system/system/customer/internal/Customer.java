package com.example.customer_retail_system.system.customer.internal;

import java.time.Instant;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer")
public class Customer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Always stored lower-case (DB CHECK enforces it) so lookups are case-insensitive. */
	@Column(nullable = false, unique = true, length = 254)
	private String email;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 20)
	private String phone;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Customer() {
	}

	public Customer(String email, String name, String phone, Instant createdAt) {
		this.email = normalizeEmail(email);
		this.name = name;
		this.phone = phone;
		this.createdAt = createdAt;
	}

	public static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getName() {
		return name;
	}

	public String getPhone() {
		return phone;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
