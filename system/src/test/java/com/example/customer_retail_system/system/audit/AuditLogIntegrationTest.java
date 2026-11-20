package com.example.customer_retail_system.system.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;

import com.example.customer_retail_system.system.audit.internal.AuditLogRepository;
import com.example.customer_retail_system.system.shared.Actor;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class AuditLogIntegrationTest {

	@Autowired
	AuditLog auditLog;

	@Autowired
	AuditLogRepository repository;

	@Autowired
	PlatformTransactionManager txManager;

	static AuditEvent event(String entityId, String details) {
		return new AuditEvent("TEST", entityId, "STATUS_CHANGED", "A", "B", Actor.customer(42), details);
	}

	@Test
	void recordsInsideTheCallersTransactionAndMasksPii() {
		String id = UUID.randomUUID().toString();
		new TransactionTemplate(txManager).executeWithoutResult(s ->
				auditLog.record(event(id, "Customer asha.rao@example.com, phone 9876543210")));

		var history = auditLog.history("TEST", id);
		assertThat(history).singleElement().satisfies(r -> {
			assertThat(r.fromState()).isEqualTo("A");
			assertThat(r.toState()).isEqualTo("B");
			assertThat(r.actor()).isEqualTo("customer:42");
			assertThat(r.actorRole()).isEqualTo("CUSTOMER");
			assertThat(r.details()).isEqualTo("Customer a***@example.com, phone ***10");
			assertThat(r.createdAt()).isNotNull();
		});
	}

	@Test
	void refusesToRecordOutsideATransaction() {
		assertThatThrownBy(() -> auditLog.record(event(UUID.randomUUID().toString(), "x")))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void auditRowRollsBackWithTheBusinessTransaction() {
		String id = UUID.randomUUID().toString();
		assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
			auditLog.record(event(id, "will roll back"));
			throw new IllegalStateException("business failure after the audit write");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(repository.countByEntityTypeAndEntityId("TEST", id)).isZero();
	}

	@Test
	void repositoryExposesNoUpdateOrDeleteOperations() {
		assertThat(Arrays.stream(AuditLogRepository.class.getMethods()).map(m -> m.getName()))
				.noneMatch(n -> n.startsWith("delete") || n.startsWith("update") || n.equals("saveAll"));
	}
}
