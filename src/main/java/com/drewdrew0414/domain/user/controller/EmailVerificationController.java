package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.dto.request.EmailVerificationConfirmRequest;
import com.drewdrew0414.domain.user.dto.request.EmailVerificationSendRequest;
import com.drewdrew0414.domain.user.service.EmailVerificationService;
import com.drewdrew0414.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * 이메일 인증 코드 발송/확인 API의 진입점입니다.
 * 회원가입(JOIN), 비밀번호 재설정(PASSWORD_RESET) 양쪽에서 공통으로 사용합니다.
 */
@RestController
// @Controller + @ResponseBody, JSON으로 바로 응답
@RequestMapping("/api/email-verifications")
// 이 컨트롤러의 모든 API는 /api/email-verifications로 시작
@RequiredArgsConstructor
// final 필드(emailVerificationService)를 받는 생성자를 자동 생성 (의존성 주입용)
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping
    // POST /api/email-verifications -> 인증 코드 발송
    public ApiResponse<Void> send(@Valid @RequestBody EmailVerificationSendRequest request) {
        // @Valid로 DTO의 @NotBlank/@Email 등 검증 어노테이션을 발동시킴
        emailVerificationService.sendCode(request.getEmail(), request.getPurpose());
        return ApiResponse.success(null); // 성공 응답에 특별히 실어 보낼 데이터가 없어서 null
    }

    @PostMapping("/verify")
    // POST /api/email-verifications/verify -> 발송된 코드 확인
    public ApiResponse<Void> verify(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        emailVerificationService.verifyCode(request.getEmail(), request.getCode(), request.getPurpose());
        return ApiResponse.success(null);
    }
}
