package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.dto.request.LoginRequest;
import com.drewdrew0414.domain.user.dto.request.SignupRequest;
import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.service.AuthService;
import com.drewdrew0414.domain.user.service.UserService;
import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/*
 * 인증 관련 HTTP API의 진입점입니다.
 * 클라이언트(프론트엔드)로부터 회원가입/로그인/토큰재발급/로그아웃 요청을 받아서
 * 각각 UserService, AuthService에 위임하고 결과를 응답으로 돌려주는 역할만 합니다.
 * 실제 판단 로직은 여기 두지 않고 Service 계층에 맡기는 게 원칙입니다.
 */

@RestController                // @Controller + @ResponseBody, JSON으로 바로 응답
@RequestMapping("/api/auth")   // 이 컨트롤러의 모든 API는 /api/auth로 시작
@RequiredArgsConstructor       // final 필드(userService, authService)를 받는 생성자를 자동 생성 (의존성 주입용)
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    @PostMapping("/signup")
    public ApiResponse<Long> signup(@Valid @RequestBody SignupRequest request) { // @Valid로 DTO 검증 어노테이션 발동
        return ApiResponse.success(userService.signup(request));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest, // 클라이언트 IP/User-Agent를 뽑아내기 위해 원본 요청 객체를 받음
                                            HttpServletResponse httpResponse) { // refreshToken 쿠키를 심기 위해 원본 응답 객체를 받음
        String clientIp = resolveClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        TokenResponse tokenResponse = authService.login(
                request.getUsername(), request.getPassword(), clientIp, userAgent, httpResponse);

        return ApiResponse.success(tokenResponse);
    }

    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(@CookieValue(value = "refreshToken", required = false) String refreshToken, // 쿠키에서 자동으로 꺼내줌
                                              HttpServletResponse httpResponse) {
        return ApiResponse.success(authService.reissue(refreshToken, httpResponse));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CustomUserDetails userDetails, // JwtAuthenticationFilter가 채워준 인증 정보
                                    HttpServletResponse httpResponse) {
        authService.logout(userDetails.getUserId(), httpResponse);
        return ApiResponse.success(null);
    }

    // 클라이언트가 직접 넣은 전달 헤더는 감사 로그의 신뢰 근거가 아닙니다.
    // 신뢰 프록시 처리가 별도로 구성되지 않은 현재 설정에서는 실제 연결 상대 주소를 기록합니다.
    // 운영 프록시 뒤에서는 프록시 주소가 기록되며, 원래 주소 추적은 프록시 로그와 대조합니다.
    private String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
