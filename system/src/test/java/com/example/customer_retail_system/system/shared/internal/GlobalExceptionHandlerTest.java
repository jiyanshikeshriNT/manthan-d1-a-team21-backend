package com.example.customer_retail_system.system.shared.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import({ GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ThrowingController.class })
@ActiveProfiles("test")
@WithMockUser
class GlobalExceptionHandlerTest {

	@Autowired
	MockMvc mvc;

	@Test
	void domainExceptionBecomesProblemDetailWithCode() throws Exception {
		mvc.perform(get("/test/not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
				.andExpect(jsonPath("$.title").value("Order not found"))
				.andExpect(jsonPath("$.detail").value("We couldn't find an order with those details."))
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void domainExceptionPropertiesAreExposed() throws Exception {
		mvc.perform(get("/test/not-returnable"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("ITEM_NOT_RETURNABLE"))
				.andExpect(jsonPath("$.items[0].code").value("WINDOW_EXPIRED"));
	}

	@Test
	void invalidTransitionIs409() throws Exception {
		mvc.perform(get("/test/transition"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
	}

	@Test
	void optimisticLockFailureIsConcurrentModification() throws Exception {
		mvc.perform(get("/test/optimistic"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@Test
	void beanValidationFailureIsValidationFailed() throws Exception {
		mvc.perform(post("/test/validate").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void unreadableBodyIsValidationFailed() throws Exception {
		mvc.perform(post("/test/validate").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	record Body(@NotBlank String name) {
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/test/not-found")
		void notFound() {
			throw new DomainException(ErrorCode.ORDER_NOT_FOUND, "We couldn't find an order with those details.");
		}

		@GetMapping("/test/not-returnable")
		void notReturnable() {
			throw new DomainException(ErrorCode.ITEM_NOT_RETURNABLE, "Some items can't be returned.",
					Map.of("items", List.of(Map.of("orderLineId", 7, "code", "WINDOW_EXPIRED"))));
		}

		@GetMapping("/test/transition")
		void transition() {
			throw new DomainException(ErrorCode.INVALID_TRANSITION, "This return can no longer be cancelled.");
		}

		@GetMapping("/test/optimistic")
		void optimistic() {
			throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
		}

		@PostMapping("/test/validate")
		void validate(@Valid @RequestBody Body body) {
		}
	}
}
