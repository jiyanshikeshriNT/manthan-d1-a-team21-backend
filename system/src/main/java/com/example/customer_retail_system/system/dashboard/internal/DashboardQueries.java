package com.example.customer_retail_system.system.dashboard.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.customer_retail_system.system.dashboard.internal.DashboardModels.CategoryReturnRate;
import com.example.customer_retail_system.system.dashboard.internal.DashboardModels.CycleTime;
import com.example.customer_retail_system.system.dashboard.internal.DashboardModels.Dashboard;
import com.example.customer_retail_system.system.dashboard.internal.DashboardModels.FlaggedCustomer;
import com.example.customer_retail_system.system.dashboard.internal.DashboardModels.ReasonCount;
import com.example.customer_retail_system.system.shared.DateRange;
import com.example.customer_retail_system.system.shared.PiiMasker;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate, read-only SQL for the manager dashboard (G17), portable across PostgreSQL and H2.
 * The dashboard is a read model, so it reads the other modules' tables directly instead of
 * depending on their code. Indexes: customer_order(ordered_at), return_request(created_at,
 * closed_at, status), customer_flag(cleared_at, customer_id).
 */
@Component
class DashboardQueries {

	private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

	private final JdbcTemplate jdbc;

	DashboardQueries(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	Dashboard dashboard(DateRange range) {
		return new Dashboard(range.from(), range.to(), returnRateByCategory(range), topReasons(range), cycleTime(range),
				flaggedCustomers());
	}

	/** Returned units (in returns that were not cancelled) ÷ sold units, for orders placed in the range. */
	List<CategoryReturnRate> returnRateByCategory(DateRange range) {
		return jdbc.query("""
				SELECT k.name AS category, k.returnable AS returnable, SUM(l.quantity) AS sold,
				       COALESCE(SUM(rq.qty), 0) AS returned
				FROM order_line l
				JOIN customer_order o ON o.id = l.order_id
				JOIN product p ON p.id = l.product_id
				JOIN category k ON k.id = p.category_id
				LEFT JOIN (SELECT i.order_line_id, SUM(i.requested_qty) AS qty
				           FROM return_item i JOIN return_request r ON r.id = i.return_id
				           WHERE r.status <> 'CANCELLED'
				           GROUP BY i.order_line_id) rq ON rq.order_line_id = l.id
				WHERE o.ordered_at >= ? AND o.ordered_at < ?
				GROUP BY k.name, k.returnable
				ORDER BY k.name""",
				(rs, i) -> {
					long sold = rs.getLong("sold");
					long returned = rs.getLong("returned");
					BigDecimal rate = sold == 0 ? BigDecimal.ZERO.setScale(4)
							: BigDecimal.valueOf(returned).divide(BigDecimal.valueOf(sold), 4, RoundingMode.HALF_UP);
					return new CategoryReturnRate(rs.getString("category"), rs.getBoolean("returnable"), sold, returned,
							rate);
				},
				utc(range.start()), utc(range.endExclusive()));
	}

	/** Item count per reason code for returns requested in the range (cancelled requests excluded). */
	List<ReasonCount> topReasons(DateRange range) {
		return jdbc.query("""
				SELECT i.reason_code, COUNT(*) AS n
				FROM return_item i JOIN return_request r ON r.id = i.return_id
				WHERE r.created_at >= ? AND r.created_at < ? AND r.status <> 'CANCELLED'
				GROUP BY i.reason_code
				ORDER BY n DESC, i.reason_code""",
				(rs, i) -> new ReasonCount(rs.getString(1), rs.getLong(2)),
				utc(range.start()), utc(range.endExclusive()));
	}

	CycleTime cycleTime(DateRange range) {
		return jdbc.queryForObject("""
				SELECT COUNT(*), AVG(EXTRACT(EPOCH FROM r.closed_at) - EXTRACT(EPOCH FROM r.created_at))
				FROM return_request r
				WHERE r.status IN ('REFUNDED', 'REJECTED') AND r.closed_at >= ? AND r.closed_at < ?""",
				(rs, i) -> {
					Object avg = rs.getObject(2);
					BigDecimal hours = avg == null ? null
							: new BigDecimal(avg.toString()).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
					return new CycleTime(rs.getLong(1), hours);
				},
				utc(range.start()), utc(range.endExclusive()));
	}

	/** Customers with at least one active (uncleared) flag, most recently flagged first. Emails are masked. */
	List<FlaggedCustomer> flaggedCustomers() {
		return jdbc.query("""
				SELECT f.customer_id, c.email, COUNT(*) AS active_flags, MAX(f.return_count) AS max_count,
				       MAX(f.flagged_at) AS last_flagged
				FROM customer_flag f JOIN customer c ON c.id = f.customer_id
				WHERE f.cleared_at IS NULL
				GROUP BY f.customer_id, c.email
				ORDER BY last_flagged DESC, f.customer_id""",
				(rs, i) -> new FlaggedCustomer(rs.getLong(1), PiiMasker.mask(rs.getString(2)), rs.getLong(3),
						rs.getInt(4), rs.getObject(5, OffsetDateTime.class).toInstant()));
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
