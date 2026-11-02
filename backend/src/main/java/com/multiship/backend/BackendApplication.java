package com.multiship.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.multiship.backend.config")
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.scheduling.annotation.EnableAsync
public class BackendApplication {

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}


	public static void main(String[] args) {
		// BUG-096 / 156 / 170 — most timestamp writers use LocalDateTime.now()
		// (the JVM zone) and the browser treats every zone-less value as UTC.
		// Production containers already run in UTC; a developer machine in
		// another zone showed every "recorded" time hours off. Pin the zone
		// before any clock is read so the two agree everywhere.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
		SpringApplication.run(BackendApplication.class, args);
	}

}
