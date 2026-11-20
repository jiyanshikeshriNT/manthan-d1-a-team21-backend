package com.example.customer_retail_system.system.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.customer_retail_system.system.TestData;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerSessionIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestData data;
	String orderNumber;
	String email;
	long orderId;

	@BeforeEach
	void order() {
		data = new TestData(jdbc);
		email = TestData.uniqueEmail();
		orderNumber = TestData.uniqueOrderNumber();
		long customer = data.customer(email);
		orderId = data.order(orderNumber, customer, TestData.daysAgo(3));
		long cat = data.category("Apparel", true, 30);
		data.line(orderId, data.product(cat, "Linen Shirt", 129_900), 1, 129_900);
	}

	ResultActions session(String order, String mail, String ip) throws Exception {
		return mvc.perform(post("/api/v1/customer/session")
				.with(r -> { r.setRemoteAddr(ip); return r; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderNumber\":\"" + order + "\",\"email\":\"" + mail + "\"}"));
	}

	@Test
	void matchingOrderAndEmailIssuesTokenValidForTwoHours() throws Exception {
		Instant before = Instant.now();
		String body = session(orderNumber, email, TestData.nextIp())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isString())
				.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");
		Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
		assertThat(expiresAt).isBetween(before.plus(Duration.ofHours(2)), Instant.now().plus(Duration.ofHours(2)));
		assertThat(token).hasSizeGreaterThanOrEqualTo(43);

		// Stored hashed, never raw.
		List<String> stored = jdbc.queryForList("SELECT token_hash FROM customer_access_token WHERE order_id = ?",
				String.class, orderId);
		assertThat(stored).containsExactly(AccessTokenService.hash(token)).doesNotContain(token);
	}

	@Test
	void emailAndOrderNumberAreMatchedCaseInsensitively() throws Exception {
		session(orderNumber.toLowerCase(), email.toUpperCase(), TestData.nextIp())
				.andExpect(status().isOk());
	}

	@Test
	void wrongEmailAndUnknownOrderGetTheSameGeneric404() throws Exception {
		String wrongEmail = session(orderNumber, "someone.else@example.com", TestData.nextIp())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
				.andReturn().getResponse().getContentAsString();
		String unknownOrder = session("NO-SUCH-ORDER", email, TestData.nextIp())
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();
		assertThat(wrongEmail).isEqualTo(unknownOrder);
		assertThat((String) JsonPath.read(wrongEmail, "$.detail")).isEqualTo(CustomerSessionService.NOT_FOUND_COPY);
	}

	@Test
	void sixthAttemptFromOneIpIsRateLimited() throws Exception {
		String ip = TestData.nextIp();
		for (int i = 0; i < 5; i++) {
			session("GUESS-" + TestData.next(), email, ip).andExpect(status().isNotFound());
		}
		session(orderNumber, email, ip) // even the right details are refused now
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		session(orderNumber, email, TestData.nextIp()).andExpect(status().isOk()); // other IPs unaffected
	}

	@Test
	void sixthAttemptOnOneOrderFromManyIpsIsRateLimited() throws Exception {
		for (int i = 0; i < 5; i++) {
			session(orderNumber, "guess" + i + "@example.com", TestData.nextIp()).andExpect(status().isNotFound());
		}
		session(orderNumber, email, TestData.nextIp())
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	void malformedRequestIsValidationFailed() throws Exception {
		session("", "not-an-email", TestData.nextIp())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void expiredTokenIs401() throws Exception {
		String raw = "expired-token-" + TestData.next();
		jdbc.update("INSERT INTO customer_access_token (token_hash, order_id, expires_at) VALUES (?, ?, ?)",
				AccessTokenService.hash(raw), orderId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
		mvc.perform(get("/api/v1/customer/orders/" + orderNumber).header("Authorization", "Bearer " + raw))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.detail").value(ProblemResponses.EXPIRED_TOKEN_COPY));
	}

	@Test
	void unknownTokenAndMissingTokenAre401() throws Exception {
		mvc.perform(get("/api/v1/customer/orders/" + orderNumber).header("Authorization", "Bearer not-a-token"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/customer/orders/" + orderNumber))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.detail").value(ProblemResponses.AUTH_REQUIRED_COPY));
	}

	@Test
	void tokenIsScopedToItsOrder() throws Exception {
		String otherEmail = TestData.uniqueEmail();
		String otherOrder = TestData.uniqueOrderNumber();
		data.order(otherOrder, data.customer(otherEmail), TestData.daysAgo(1));
		String token = TestData.token(mvc, orderNumber, email);

		mvc.perform(get("/api/v1/customer/orders/" + otherOrder).header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	void customerTokenCannotReachStaffEndpointsAndStaffCannotReachCustomerOnes() throws Exception {
		String token = TestData.token(mvc, orderNumber, email);
		mvc.perform(get("/api/v1/warehouse/anything").header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		String user = "wh" + TestData.next();
		data.staffUser(user, "s3cret-pass", "WAREHOUSE");
		mvc.perform(get("/api/v1/customer/orders/" + orderNumber).with(httpBasic(user, "s3cret-pass")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/finance/anything").with(httpBasic(user, "s3cret-pass")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/warehouse/anything").with(httpBasic(user, "wrong")))
				.andExpect(status().isUnauthorized());
	}
}
