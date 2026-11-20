package com.example.customer_retail_system.system.shared;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class SharedConfig {

	/** The only source of "now" in the application; tests replace it with a fixed clock. */
	@Bean
	Clock clock(AppProperties properties) {
		return Clock.system(properties.zone());
	}
}
