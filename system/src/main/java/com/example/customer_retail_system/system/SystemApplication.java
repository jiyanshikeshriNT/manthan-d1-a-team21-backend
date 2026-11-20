package com.example.customer_retail_system.system;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SystemApplication {

	public static void main(String[] args) {
		// The JVM zone must not leak into JDBC sessions: legacy host IDs such as "Asia/Calcutta"
		// are rejected by PostgreSQL. Business time comes only from the injected Clock (app.zone).
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(SystemApplication.class, args);
	}

}
