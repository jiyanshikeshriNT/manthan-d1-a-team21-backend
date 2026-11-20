package com.example.customer_retail_system.system.returns.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.CreateReturnRequest;
import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.ReturnDto;
import com.example.customer_retail_system.system.shared.CustomerPrincipal;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/v1/customer/returns")
class CustomerReturnController {

	static final String PHOTO_PART_PREFIX = "photos_";

	private final ReturnService service;

	CustomerReturnController(ReturnService service) {
		this.service = service;
	}

	/**
	 * Multipart: a JSON part {@code request} plus optional photo parts named
	 * {@code photos_{orderLineId}} (up to 3 per item). Send an {@code Idempotency-Key} header so a
	 * retried submit returns the original return instead of creating another.
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<ReturnDto> create(@AuthenticationPrincipal CustomerPrincipal principal,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestPart("request") CreateReturnRequest request, MultipartHttpServletRequest multipart) {
		ReturnService.CreateOutcome outcome = service.create(principal, idempotencyKey, request, photoParts(multipart));
		var response = ResponseEntity.status(HttpStatus.CREATED)
				.header("Location", "/api/v1/customer/returns/" + outcome.body().rma());
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.body(outcome.body());
	}

	@GetMapping("/{rma}")
	ReturnDto get(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable String rma) {
		return service.get(principal, rma);
	}

	private static Map<Long, List<MultipartFile>> photoParts(MultipartHttpServletRequest multipart) {
		Map<Long, List<MultipartFile>> photos = new HashMap<>();
		multipart.getMultiFileMap().forEach((name, files) -> {
			if (!name.startsWith(PHOTO_PART_PREFIX)) {
				return;
			}
			try {
				photos.put(Long.parseLong(name.substring(PHOTO_PART_PREFIX.length())), files);
			}
			catch (NumberFormatException ex) {
				throw new DomainException(ErrorCode.VALIDATION_FAILED,
						"Photo parts must be named photos_{orderLineId}.");
			}
		});
		return photos;
	}
}
