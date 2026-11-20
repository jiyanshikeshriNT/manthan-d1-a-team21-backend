package com.example.customer_retail_system.gateway.mock;

import java.time.Clock;

import com.example.customer_retail_system.gateway.api.PaymentGateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers the mock gateway when no other {@link PaymentGateway} is defined. Its table comes from
 * {@code classpath:db/migration/gateway/V100__gateway.sql}, picked up by the application's Flyway.
 */
@AutoConfiguration
public class MockGatewayAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(PaymentGateway.class)
	MockPaymentGateway mockPaymentGateway(JdbcTemplate jdbc, ObjectProvider<Clock> clock) {
		return new MockPaymentGateway(jdbc, clock.getIfAvailable(Clock::systemUTC));
	}
}
