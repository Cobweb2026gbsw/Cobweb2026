package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.dto.request.PasswordResetConfirmRequest;
import com.drewdrew0414.domain.user.dto.request.PasswordResetRequest;
import com.drewdrew0414.domain.user.dto.request.PasswordResetVerifyRequest;
import com.drewdrew0414.domain.user.dto.response.PasswordResetTokenResponse;
import com.drewdrew0414.domain.user.service.PasswordResetService;
import com.drewdrew0414.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * 비밀번호 재설정 API의 진입점입니다.
 * request(코드 발송) -> verify-code(코드 확인, resetToken 발급) -> confirm(실제 변경) 3단계로 이루어집니다.
 */
@RestController
// @Controller + @ResponseBody, JSON으로 바로 응답
@RequestMapping("/api/auth/password-reset")
// 이 컨트롤러의 모든 API는 /api/auth/password-reset으로 시작 (/api/auth/**라서 SecurityConfig에서 별도 허용 안 해도 됨)
@RequiredArgsConstructor
// final 필드(passwordResetService)를 받는 생성자를 자동 생성 (의존성 주입용)
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/request")
    // 1단계: 이메일로 인증 코드 발송
    public ApiResponse<Void> request(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request.getEmail());
        return ApiResponse.success(null);
    }

    @PostMapping("/verify-code")
    // 2단계: 코드 확인 후 1회용 resetToken 발급
    public ApiResponse<PasswordResetTokenResponse> verifyCode(@Valid @RequestBody PasswordResetVerifyRequest request) {
        String resetToken = passwordResetService.verifyCodeAndIssueToken(request.getEmail(), request.getCode());
        return ApiResponse.success(new PasswordResetTokenResponse(resetToken));
    }

    @PostMapping("/confirm")
    // 3단계: resetToken으로 실제 비밀번호 변경
    public ApiResponse<Void> confirm(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request.getResetToken(), request.getNewPassword());
        return ApiResponse.success(null);
    }
}
