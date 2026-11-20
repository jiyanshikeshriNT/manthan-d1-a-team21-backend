package com.example.customer_retail_system.system.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

	Optional<Product> findBySku(String sku);

	/** Products with their category loaded, for eligibility lookups over an order's lines. */
	@EntityGraph(attributePaths = "category")
	List<Product> findByIdIn(Collection<Long> ids);
}
