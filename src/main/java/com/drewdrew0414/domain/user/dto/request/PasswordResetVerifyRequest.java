package com.drewdrew0414.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/*
 * 비밀번호 재설정의 두 번째 단계: 발송된 코드를 확인하고 resetToken을 발급받기 위한 요청 DTO입니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)
public class PasswordResetVerifyRequest {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "인증 코드를 입력해주세요.")
    private String code;
}
