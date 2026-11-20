package com.example.customer_retail_system.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Flyway V1 applies on H2 (MODE=PostgreSQL) and Hibernate's {@code ddl-auto=validate} accepts
 * the entity mappings against it (context start would fail otherwise).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FlywayMigrationTest {

	@Autowired
	Flyway flyway;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void v1AppliesSuccessfully() {
		MigrationInfo[] applied = flyway.info().applied();
		assertThat(applied).extracting(m -> m.getVersion().getVersion()).contains("1");
		assertThat(applied).allMatch(m -> m.getState() == MigrationState.SUCCESS);
		assertThat(flyway.info().pending()).isEmpty();
	}

	@Test
	void coreTablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT LOWER(table_name) FROM information_schema.tables WHERE LOWER(table_schema) = 'public'",
				String.class);
		assertThat(tables).containsAll(Arrays.asList(
				"category", "product", "customer", "customer_order", "order_line", "staff_user"));
	}

	@Test
	void emailMustBeStoredLowerCase() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO customer (email, name, created_at) VALUES ('Mixed@Example.com', 'X', CURRENT_TIMESTAMP)"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void staffRoleIsConstrained() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO staff_user (username, password_hash, role) VALUES ('x', 'h', 'ADMIN')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void categoryWindowMustBePositive() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO category (code, name, returnable, return_window_days) VALUES ('BAD', 'Bad', TRUE, 0)"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
