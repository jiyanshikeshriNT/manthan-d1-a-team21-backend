package com.example.customer_retail_system.system.inspection.internal;

import java.io.IOException;

import com.example.customer_retail_system.system.inspection.internal.WarehouseModels.DecodedLabel;
import com.example.customer_retail_system.system.inspection.internal.WarehouseModels.InspectionRequest;
import com.example.customer_retail_system.system.inspection.internal.WarehouseModels.ReceiveRequest;
import com.example.customer_retail_system.system.inspection.internal.WarehouseModels.WarehouseReturnView;
import com.example.customer_retail_system.system.refund.RefundService;
import com.example.customer_retail_system.system.returns.ReturnsApi;
import com.example.customer_retail_system.system.shared.Actor;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Warehouse API (role WAREHOUSE). */
@RestController
@RequestMapping("/api/v1/warehouse")
class WarehouseController {

	private final InspectionService inspections;
	private final RefundService refunds;
	private final ReturnsApi returns;

	WarehouseController(InspectionService inspections, RefundService refunds, ReturnsApi returns) {
		this.inspections = inspections;
		this.refunds = refunds;
		this.returns = returns;
	}

	/** Look up the return a scanned label belongs to; 400 LABEL_INVALID for forged or mistyped labels. */
	@GetMapping("/labels/{labelCode}")
	WarehouseReturnView byLabel(@PathVariable String labelCode) {
		return inspections.byLabel(labelCode);
	}

	/** Decode the QR code in a photo of a label (multipart part {@code image}). */
	@PostMapping(path = "/labels/decode", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	DecodedLabel decode(@RequestPart("image") MultipartFile image) throws IOException {
		if (image.isEmpty()) {
			throw new DomainException(ErrorCode.LABEL_INVALID, "Upload a photo of the label.");
		}
		String labelCode = returns.decodeLabelImage(image.getBytes());
		return new DecodedLabel(labelCode, returns.rmaFromLabel(labelCode));
	}

	/** Current state of a return by RMA (used to reload after a conflict, or when the RMA is typed in). */
	@GetMapping("/returns/{rma}")
	WarehouseReturnView byRma(@PathVariable String rma) {
		return inspections.byRma(rma);
	}

	/** IN_TRANSIT → RECEIVED; re-scanning a received parcel is a no-op. Optional body {@code {labelCode}}. */
	@PostMapping("/returns/{rma}/receive")
	WarehouseReturnView receive(@PathVariable String rma, @Valid @RequestBody(required = false) ReceiveRequest body,
			Authentication auth) {
		return inspections.receive(rma, body == null ? null : body.labelCode(), actor(auth));
	}

	/**
	 * Records the inspection. When no finance approval is needed the refund is executed right after
	 * the inspection commits — never inside its transaction.
	 */
	@PostMapping("/returns/{rma}/inspection")
	WarehouseReturnView inspect(@PathVariable String rma, @Valid @RequestBody InspectionRequest request,
			Authentication auth) {
		InspectionService.InspectionOutcome outcome = inspections.inspect(rma, request, actor(auth));
		if (outcome.refundIdToExecute() != null) {
			refunds.execute(outcome.refundIdToExecute());
		}
		return inspections.byRma(outcome.rma());
	}

	private static Actor actor(Authentication auth) {
		return Actor.staff(auth.getName(), "WAREHOUSE");
	}
}
