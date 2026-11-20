package com.example.customer_retail_system.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneId;

import com.example.customer_retail_system.system.shared.AppProperties;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemApplicationTests {

	@Autowired
	Clock clock;

	@Autowired
	AppProperties properties;

	@Autowired
	MockMvc mvc;

	@Test
	void contextLoads() {
		assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
	}

	@Test
	void appPropertiesAreBound() {
		assertThat(properties.refund().approvalThresholdPaise()).isEqualTo(1_000_000L);
		assertThat(properties.returns().defaultWindowDays()).isEqualTo(30);
		assertThat(properties.flagging().maxRequests()).isEqualTo(3);
		assertThat(properties.flagging().windowDays()).isEqualTo(30);
		assertThat(properties.label().secret()).isEqualTo("test-label-secret");
	}

	@Test
	@WithMockUser
	void openApiDocsAreServed() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").exists());
	}
}
