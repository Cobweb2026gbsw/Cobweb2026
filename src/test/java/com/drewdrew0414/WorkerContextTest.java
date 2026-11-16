package com.drewdrew0414;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 별도 채점 워커는 Servlet/HttpSecurity 없이도 앱 컨텍스트를 기동할 수 있어야 합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class WorkerContextTest {
    @Test void startsWithoutWebSecurityChain() {
        // 컨텍스트가 성공적으로 로드되면 워커의 non-web 보안 빈 조건을 검증한 것입니다.
    }
}
