package com.example.customer_retail_system.system.returns.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.customer_retail_system.system.audit.AuditRecord;
import com.example.customer_retail_system.system.returns.ReturnStatus;
import org.junit.jupiter.api.Test;

class TimelineTest {

	static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

	/** Audit history for the path REQUESTED → s1 → s2 …, one minute apart. */
	static List<AuditRecord> history(ReturnStatus... path) {
		List<AuditRecord> h = new ArrayList<>();
		h.add(new AuditRecord(1, "RETURN", "x", "CREATED", null, "REQUESTED", "customer:1", "CUSTOMER", null, null, T0));
		ReturnStatus from = ReturnStatus.REQUESTED;
		for (int i = 0; i < path.length; i++) {
			h.add(new AuditRecord(i + 2, "RETURN", "x", "STATUS_CHANGED", from.name(), path[i].name(), "system",
					"SYSTEM", "reason " + path[i], null, T0.plusSeconds(60L * (i + 1))));
			from = path[i];
		}
		return h;
	}

	static List<String> states(List<Timeline.Entry> entries) {
		return entries.stream().map(e -> e.step() + ":" + e.state()).toList();
	}

	@Test
	void underReviewShowsRequestedAsCurrentWithNote() {
		var t = Timeline.build(ReturnStatus.PENDING_REVIEW, history(ReturnStatus.PENDING_REVIEW));
		assertThat(states(t)).containsExactly("Requested:current", "Approved:pending", "In transit:pending",
				"Received:pending", "Inspected:pending", "Refunded:pending");
		assertThat(t.get(0).note()).isEqualTo("Under review");
		assertThat(t.get(0).at()).isEqualTo(T0);
		assertThat(Timeline.statusLabel(ReturnStatus.PENDING_REVIEW)).isEqualTo("Requested");
	}

	@Test
	void inTransitMarksEarlierStepsDoneWithTimestamps() {
		var t = Timeline.build(ReturnStatus.IN_TRANSIT, history(ReturnStatus.APPROVED, ReturnStatus.IN_TRANSIT));
		assertThat(states(t)).startsWith("Requested:done", "Approved:done", "In transit:current", "Received:pending");
		assertThat(t.get(1).at()).isEqualTo(T0.plusSeconds(60));
		assertThat(t.get(2).at()).isEqualTo(T0.plusSeconds(120));
		assertThat(t.get(3).at()).isNull();
	}

	@Test
	void reviewThenApprovalUsesFirstTimeEachStepWasReached() {
		var t = Timeline.build(ReturnStatus.APPROVED, history(ReturnStatus.PENDING_REVIEW, ReturnStatus.APPROVED));
		assertThat(t.get(0).at()).isEqualTo(T0);
		assertThat(t.get(1).at()).isEqualTo(T0.plusSeconds(120));
	}

	@Test
	void awaitingFinanceShowsInspectedCurrentWithNote() {
		var t = Timeline.build(ReturnStatus.AWAITING_FINANCE, history(ReturnStatus.APPROVED, ReturnStatus.IN_TRANSIT,
				ReturnStatus.RECEIVED, ReturnStatus.INSPECTED, ReturnStatus.AWAITING_FINANCE));
		assertThat(t.get(4).state()).isEqualTo("current");
		assertThat(t.get(4).note()).isEqualTo("Awaiting refund approval");
	}

	@Test
	void refundedMarksEveryStepDone() {
		var t = Timeline.build(ReturnStatus.REFUNDED, history(ReturnStatus.APPROVED, ReturnStatus.IN_TRANSIT,
				ReturnStatus.RECEIVED, ReturnStatus.INSPECTED, ReturnStatus.REFUND_IN_PROGRESS, ReturnStatus.REFUNDED));
		assertThat(t).allMatch(e -> e.state().equals("done")).allMatch(e -> e.at() != null);
	}

	@Test
	void cancelledShowsReachedStepsThenAFailedFinalStep() {
		var t = Timeline.build(ReturnStatus.CANCELLED, history(ReturnStatus.APPROVED, ReturnStatus.CANCELLED));
		assertThat(states(t)).containsExactly("Requested:done", "Approved:done", "Cancelled:failed");
		assertThat(t.get(2).at()).isEqualTo(T0.plusSeconds(120));
		assertThat(t.get(2).note()).isEqualTo("reason CANCELLED");
	}

	@Test
	void rejectedAfterInspectionKeepsInspectionSteps() {
		var t = Timeline.build(ReturnStatus.REJECTED, history(ReturnStatus.APPROVED, ReturnStatus.IN_TRANSIT,
				ReturnStatus.RECEIVED, ReturnStatus.INSPECTED, ReturnStatus.REJECTED));
		assertThat(states(t)).containsExactly("Requested:done", "Approved:done", "In transit:done", "Received:done",
				"Inspected:done", "Rejected:failed");
	}
}
