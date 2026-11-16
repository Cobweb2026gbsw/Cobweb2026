package com.drewdrew0414.global.openai;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * /api/openai/**, /api/ai/**는 현재 모든 역할에서 예약 차단합니다.
 * 대소문자·쿼리 유무에 의한 차이를 없애고 경로만 검사합니다.
 * 인코딩된 구분자·세미콜론·비정규 경로는 기존 StrictHttpFirewall이 먼저 거절합니다.
 */
public final class OpenAiWebRequestMatcher implements RequestMatcher {
    @Override public boolean matches(HttpServletRequest request) {
        String path = request.getServletPath();
        if (request.getPathInfo() != null) path += request.getPathInfo();
        if (path.isEmpty()) {
            // MockMvc 및 기본 서블릿 매핑에서도 같은 예약 경계를 적용합니다.
            path = request.getRequestURI();
            String context = request.getContextPath();
            if (!context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
            try {
                path = UriUtils.decode(path, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalidEncoding) {
                // 잘못된 percent-encoding 원문은 로그나 예외에 복사하지 않고 거절합니다.
                return true;
            }
        }
        path = path.toLowerCase(Locale.ROOT);
        return path.equals("/api/openai") || path.startsWith("/api/openai/")
                || path.equals("/api/ai") || path.startsWith("/api/ai/");
    }
}
