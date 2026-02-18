package com.jimin.board;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Why: CI 환경에서 MySQL 없이 H2 In-Memory DB로 테스트
//      application-test.properties 사용 (src/test/resources/)
@SpringBootTest
@ActiveProfiles("test")
class BoardApplicationTests {

	@Test
	void contextLoads() {
	}

}
