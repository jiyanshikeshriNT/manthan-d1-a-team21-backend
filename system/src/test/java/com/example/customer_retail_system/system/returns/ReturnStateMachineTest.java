package com.example.customer_retail_system.system.returns;

import static com.example.customer_retail_system.system.returns.ReturnStatus.APPROVED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.AWAITING_FINANCE;
import static com.example.customer_retail_system.system.returns.ReturnStatus.CANCELLED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.INSPECTED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.IN_TRANSIT;
import static com.example.customer_retail_system.system.returns.ReturnStatus.PENDING_REVIEW;
import static com.example.customer_retail_system.system.returns.ReturnStatus.RECEIVED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REFUNDED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REFUND_IN_PROGRESS;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REJECTED;
import static com.example.customer_retail_system.system.returns.ReturnStatus.REQUESTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.example.customer_retail_system.system.audit.AuditEvent;
import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class ReturnStateMachineTest {

	/** Written out independently from CLAUDE.md so a change to the production table must be deliberate. */
	static final Map<ReturnStatus, Set<ReturnStatus>> EXPECTED = Map.ofEntries(
			Map.entry(REQUESTED, EnumSet.of(APPROVED, PENDING_REVIEW, CANCELLED)),
			Map.entry(PENDING_REVIEW, EnumSet.of(APPROVED, REJECTED, CANCELLED)),
			Map.entry(APPROVED, EnumSet.of(IN_TRANSIT, CANCELLED)),
			Map.entry(IN_TRANSIT, EnumSet.of(RECEIVED)),
			Map.entry(RECEIVED, EnumSet.of(INSPECTED)),
			Map.entry(INSPECTED, EnumSet.of(AWAITING_FINANCE, REFUND_IN_PROGRESS, REJECTED)),
			Map.entry(AWAITING_FINANCE, EnumSet.of(REFUND_IN_PROGRESS, REJECTED)),
			Map.entry(REFUND_IN_PROGRESS, EnumSet.of(REFUNDED)),
			Map.entry(REFUNDED, EnumSet.noneOf(ReturnStatus.class)),
			Map.entry(REJECTED, EnumSet.noneOf(ReturnStatus.class)),
			Map.entry(CANCELLED, EnumSet.noneOf(ReturnStatus.class)));

	static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

	final AuditLog audit = mock(AuditLog.class);
	final ReturnStateMachine machine = new ReturnStateMachine(audit, Clock.fixed(NOW, ZoneOffset.UTC));

	/** Every (from, to) pair: 11 × 11 = 121 cases. */
	static Stream<Arguments> allPairs() {
		List<Arguments> pairs = new ArrayList<>();
		for (ReturnStatus from : ReturnStatus.values()) {
			for (ReturnStatus to : ReturnStatus.values()) {
				pairs.add(Arguments.of(from, to, EXPECTED.get(from).contains(to)));
			}
		}
		return pairs.stream();
	}

	@ParameterizedTest(name = "{0} → {1} allowed={2}")
	@MethodSource("allPairs")
	void transitionTable(ReturnStatus from, ReturnStatus to, boolean allowed) {
		assertThat(ReturnStateMachine.isAllowed(from, to)).isEqualTo(allowed);

		ReturnRequest ret = returnIn(from);
		if (allowed) {
			machine.transition(ret, to, Actor.system(), "test");
			assertThat(ret.getStatus()).isEqualTo(to);
		}
		else {
			assertThatThrownBy(() -> machine.transition(ret, to, Actor.system(), "test"))
					.isInstanceOfSatisfying(DomainException.class,
							ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_TRANSITION));
			assertThat(ret.getStatus()).isEqualTo(from);
		}
	}

	@Test
	void everyStatusIsCoveredAndTerminalStatesHaveNoExits() {
		assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(ReturnStatus.values());
		for (ReturnStatus s : ReturnStatus.values()) {
			assertThat(ReturnStateMachine.allowedFrom(s).isEmpty()).isEqualTo(s.isTerminal());
		}
	}

	@Test
	void legalTransitionWritesOneAuditRowWithFromAndTo() {
		ReturnRequest ret = returnIn(REQUESTED);
		machine.transition(ret, APPROVED, Actor.system(), "auto-approved");

		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(audit).record(captor.capture());
		AuditEvent e = captor.getValue();
		assertThat(e.entityType()).isEqualTo("RETURN");
		assertThat(e.entityId()).isEqualTo(ret.getId().toString());
		assertThat(e.fromState()).isEqualTo("REQUESTED");
		assertThat(e.toState()).isEqualTo("APPROVED");
		assertThat(e.details()).isEqualTo("auto-approved");
		assertThat(ret.getUpdatedAt()).isEqualTo(NOW);
		assertThat(ret.getClosedAt()).isNull();
	}

	@Test
	void terminalTransitionSetsClosedAt() {
		ReturnRequest ret = returnIn(APPROVED);
		machine.transition(ret, CANCELLED, Actor.customer(7), "Cancelled by customer");
		assertThat(ret.getClosedAt()).isEqualTo(NOW);
	}

	@Test
	void illegalTransitionWritesNoAuditRowAndExplainsWhy() {
		ReturnRequest ret = returnIn(IN_TRANSIT);
		assertThatThrownBy(() -> machine.transition(ret, CANCELLED, Actor.customer(7), "cancel"))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.getMessage()).isEqualTo(
							"This return can't be cancelled because it is already on its way to us.");
					assertThat(ex.properties()).containsEntry("currentStatus", "IN_TRANSIT");
				});
		verify(audit, never()).record(any());
	}

	@Test
	void startAuditsCreation() {
		ReturnRequest ret = returnIn(REQUESTED);
		machine.start(ret, Actor.customer(7), "2 items");
		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(audit).record(captor.capture());
		assertThat(captor.getValue().action()).isEqualTo("CREATED");
		assertThat(captor.getValue().fromState()).isNull();
		assertThat(captor.getValue().toState()).isEqualTo("REQUESTED");
	}

	/** A fresh return placed directly in {@code target} (test shares the package of the package-private setter). */
	static ReturnRequest returnIn(ReturnStatus target) {
		ReturnRequest ret = new ReturnRequest(UUID.randomUUID(), "RMA-1", 1, 1, RefundMethod.STORE_CREDIT, false,
				"RR-RMA-1-00000000", null, NOW);
		if (target != REQUESTED) {
			ret.changeStatus(target, NOW);
		}
		return ret;
	}
}
