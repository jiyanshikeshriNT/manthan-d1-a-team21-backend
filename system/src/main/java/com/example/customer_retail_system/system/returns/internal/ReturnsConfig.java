package com.example.customer_retail_system.system.returns.internal;

import java.nio.file.Path;

import com.example.customer_retail_system.system.shared.AppProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReturnsConfig {

	@Bean
	PhotoStorage photoStorage(AppProperties properties) {
		return new PhotoStorage(Path.of(properties.storage().dir()));
	}
}
