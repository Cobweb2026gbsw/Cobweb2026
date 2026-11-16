package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.global.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 비밀값 없이 로컬 HTTPS 연결 정보만 제공합니다. 호스트 이름은 클라이언트가 현재 주소를 유지합니다. */
@RestController
@RequestMapping("/api/auth")
public class LoginOptionsController {
    private final Integer httpsPort;

    public LoginOptionsController(@Value("${local.https.enabled:false}") boolean localHttpsEnabled,
                                  @Value("${local.https.port:8443}") int localHttpsPort,
                                  Environment environment) {
        boolean localConnector = environment.matchesProfiles("local") && !environment.matchesProfiles("prod");
        this.httpsPort = localConnector && localHttpsEnabled && localHttpsPort >= 1 && localHttpsPort <= 65535 ? localHttpsPort : null;
    }

    public record LoginOptions(boolean secureCookieRequired, Integer httpsPort) {}

    @GetMapping("/login-options")
    public ApiResponse<LoginOptions> options() {
        return ApiResponse.success(new LoginOptions(true, httpsPort));
    }
}
