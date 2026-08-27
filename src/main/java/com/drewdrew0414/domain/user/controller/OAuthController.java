package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.dto.response.OAuthAuthorizeUrlResponse;
import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.exception.UnsupportedOAuthProviderException;
import com.drewdrew0414.domain.user.service.OAuthService;
import com.drewdrew0414.global.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.CookieValue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/*
 * 소셜 로그인(구글/깃허브/네이버) API의 진입점입니다.
 * 1) /authorize-url : 프론트엔드가 제공자 로그인 화면으로 리다이렉트시킬 URL을 받아갑니다.
 * 2) /callback       : 제공자가 인가 코드를 들고 돌아오는 리다이렉트 대상. 로그인/회원가입을 완료하고
 *                       프론트엔드로 accessToken을 실어 다시 리다이렉트합니다.
 */
@RestController
// @Controller + @ResponseBody, JSON으로 바로 응답 (다만 callback()은 sendRedirect로 직접 응답을 써버려서 예외적으로 body가 없음)
@RequestMapping("/api/auth/oauth")
// 이 컨트롤러의 모든 API는 /api/auth/oauth로 시작 (/api/auth/**라서 SecurityConfig에서 별도 허용 안 해도 됨)
@RequiredArgsConstructor
// final 필드(oAuthService)를 받는 생성자를 자동 생성 (의존성 주입용)
public class OAuthController {

    private final OAuthService oAuthService;

    @GetMapping("/{provider}/authorize-url")
    // {provider}는 "google"/"naver"/"github" 같은 소문자 문자열로 받고, 아래서 Provider enum으로 직접 변환
    public ApiResponse<OAuthAuthorizeUrlResponse> authorizeUrl(@PathVariable String provider,
                                                                HttpServletResponse httpResponse) {
        Provider p = parseProvider(provider);
        return ApiResponse.success(new OAuthAuthorizeUrlResponse(oAuthService.buildAuthorizeUrl(p, httpResponse)));
    }

    @GetMapping("/{provider}/callback")
    // 제공자 로그인 동의 후 브라우저가 자동으로 이동해오는 리다이렉트 대상.
    // JSON을 반환하는 대신 프론트엔드 페이지로 다시 리다이렉트해야 하므로 반환 타입이 void
    public void callback(@PathVariable String provider,
                          @RequestParam String code, // 제공자가 발급한 인가 코드
                          @RequestParam(required = false) String state, // authorize-url 발급 시 실어 보낸 값 그대로 돌아옴
                          @CookieValue(value = "oauthState", required = false) String savedState,
                          HttpServletResponse httpResponse) throws IOException {
        Provider p = parseProvider(provider);
        TokenResponse tokenResponse = oAuthService.loginOrSignup(p, code, state, savedState, httpResponse);

        String accessToken = URLEncoder.encode(tokenResponse.getAccessToken(), StandardCharsets.UTF_8);
        // 쿼리 파라미터로 실어 보내는 값이라 URL 인코딩 필수 (JWT에는 '.' 등 특수문자가 섞여 있음)
        // URL 쿼리는 서버 접근 로그와 Referer 헤더에 남을 수 있으므로 브라우저가 서버로 전송하지 않는 fragment(#)에 담습니다.
        // 이 방식은 개발용 테스트 페이지를 위한 임시 전달 방식이며, 운영 프론트에서는 1회용 교환 코드 방식이 더 안전합니다.
        httpResponse.sendRedirect("/index.html#accessToken=" + accessToken);
    }

    private Provider parseProvider(String provider) {
        try {
            return Provider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            // 지원하지 않는 경로 값이 일반 500 오류로 보이지 않도록 명시적인 400 도메인 예외로 변환
            throw new UnsupportedOAuthProviderException();
        }
    }
}
