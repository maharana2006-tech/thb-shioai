package com.multiship.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        // Sprint 49 Tier 0: JwtService rejects empty / short / compromised
        // secrets on startup. Supply a valid throwaway one for context-loads.
        "jwt.secret=test-only-jwt-secret-do-not-use-in-production-32b"
})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
