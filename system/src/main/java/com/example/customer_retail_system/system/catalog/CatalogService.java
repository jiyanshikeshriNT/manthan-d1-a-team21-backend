package com.example.customer_retail_system.system.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.customer_retail_system.system.catalog.internal.Product;
import com.example.customer_retail_system.system.catalog.internal.ProductRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the catalog module. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

	private final ProductRepository products;

	CatalogService(ProductRepository products) {
		this.products = products;
	}

	/** Products by id (with category policy) in a single query. Unknown ids are absent. */
	public Map<Long, ProductInfo> findProducts(Collection<Long> productIds) {
		if (productIds.isEmpty()) {
			return Map.of();
		}
		return products.findByIdIn(productIds).stream()
				.map(CatalogService::toInfo)
				.collect(Collectors.toMap(ProductInfo::productId, Function.identity()));
	}

	private static ProductInfo toInfo(Product p) {
		return new ProductInfo(p.getId(), p.getSku(), p.getName(), p.getCategory().getCode(),
				p.getCategory().getName(), p.getCategory().isReturnable(), p.getCategory().getReturnWindowDays());
	}
}
