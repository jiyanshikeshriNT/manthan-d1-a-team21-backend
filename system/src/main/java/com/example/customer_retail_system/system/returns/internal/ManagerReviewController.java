package com.example.customer_retail_system.system.returns.internal;

import java.util.List;

import com.example.customer_retail_system.system.returns.internal.ReturnApiModels.ReturnDto;
import com.example.customer_retail_system.system.shared.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Review queue for flagged customers' returns (role MANAGER). */
@RestController
@RequestMapping("/api/v1/manager/reviews")
class ManagerReviewController {

	private final ManagerReviewService reviews;

	ManagerReviewController(ManagerReviewService reviews) {
		this.reviews = reviews;
	}

	record RejectRequest(@NotBlank @Size(max = 500) String reason) {
	}

	@GetMapping
	List<ManagerReviewService.ReviewItem> pending() {
		return reviews.pending();
	}

	@PostMapping("/{rma}/approve")
	ReturnDto approve(@PathVariable String rma, Authentication auth) {
		return reviews.approve(rma, actor(auth));
	}

	@PostMapping("/{rma}/reject")
	ReturnDto reject(@PathVariable String rma, @Valid @RequestBody RejectRequest request, Authentication auth) {
		return reviews.reject(rma, request.reason().trim(), actor(auth));
	}

	private static Actor actor(Authentication auth) {
		return Actor.staff(auth.getName(), "MANAGER");
	}
}
