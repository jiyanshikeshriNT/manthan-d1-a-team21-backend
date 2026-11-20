package com.example.customer_retail_system.system.refund.internal;

import com.example.customer_retail_system.gateway.api.FaultMode;
import com.example.customer_retail_system.gateway.api.GatewayFaultControl;
import com.example.customer_retail_system.gateway.api.GatewayFaultControl.FaultSettings;
import com.example.customer_retail_system.system.audit.AuditEvent;
import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.shared.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gateway fault injection for tests and the SRE drill (G18). Role MANAGER; never registered in the
 * {@code prod} profile, and only usable with a gateway that offers {@link GatewayFaultControl}.
 */
@RestController
@Profile("!prod")
@RequestMapping("/api/v1/admin/gateway/faults")
class GatewayFaultController {

	private final ObjectProvider<GatewayFaultControl> control;
	private final AuditLog auditLog;
	private final TransactionTemplate tx;

	GatewayFaultController(ObjectProvider<GatewayFaultControl> control, AuditLog auditLog,
			PlatformTransactionManager txManager) {
		this.control = control;
		this.auditLog = auditLog;
		this.tx = new TransactionTemplate(txManager);
	}

	record FaultRequest(@NotNull FaultMode mode, @PositiveOrZero @Max(60_000) Long latencyMs) {
	}

	@GetMapping
	FaultSettings current() {
		return control().current();
	}

	@PutMapping
	FaultSettings apply(@Valid @RequestBody FaultRequest request, Authentication auth) {
		FaultSettings settings = new FaultSettings(request.mode(), request.latencyMs() == null ? 0 : request.latencyMs());
		control().apply(settings);
		tx.executeWithoutResult(s -> auditLog.record(new AuditEvent("GATEWAY", "faults", "FAULTS_CHANGED", null,
				settings.mode().name(), Actor.staff(auth.getName(), "MANAGER"),
				"Gateway fault mode " + settings.mode() + ", latency " + settings.latencyMs() + " ms")));
		return settings;
	}

	private GatewayFaultControl control() {
		GatewayFaultControl c = control.getIfAvailable();
		if (c == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fault injection is not available for this gateway");
		}
		return c;
	}
}
