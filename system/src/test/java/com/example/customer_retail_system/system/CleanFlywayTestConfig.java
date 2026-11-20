package com.example.customer_retail_system.system;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/** Wipes the test database before migrating so reruns against local PostgreSQL start empty. */
@Configuration(proxyBeanMethods = false)
@Profile("test")
class CleanFlywayTestConfig {

	@Bean
	FlywayMigrationStrategy cleanMigrate() {
		return flyway -> {
			flyway.clean();
			flyway.migrate();
		};
	}
}
