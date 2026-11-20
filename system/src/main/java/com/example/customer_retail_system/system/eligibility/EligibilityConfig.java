package com.example.customer_retail_system.system.eligibility;

import com.example.customer_retail_system.system.shared.AppProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EligibilityConfig {

	@Bean
	EligibilityEngine eligibilityEngine(AppProperties properties) {
		return new EligibilityEngine(properties.zone(), properties.returns().defaultWindowDays());
	}
}
