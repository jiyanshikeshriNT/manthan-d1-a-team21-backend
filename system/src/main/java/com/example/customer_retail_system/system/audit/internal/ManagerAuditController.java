package com.example.customer_retail_system.system.audit.internal;

import java.util.List;

import com.example.customer_retail_system.system.audit.AuditLog;
import com.example.customer_retail_system.system.audit.AuditRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit trail lookup for the operations manager (role MANAGER). */
@RestController
@Validated
@RequestMapping("/api/v1/manager")
class ManagerAuditController {

	private final AuditLog auditLog;

	ManagerAuditController(AuditLog auditLog) {
		this.auditLog = auditLog;
	}

	/** All audit rows for a return (UUID) or refund (id), oldest first. */
	@GetMapping("/audit")
	List<AuditRecord> audit(@RequestParam @NotBlank @Size(max = 64) String entityId) {
		return auditLog.historyForEntityId(entityId.trim());
	}
}
