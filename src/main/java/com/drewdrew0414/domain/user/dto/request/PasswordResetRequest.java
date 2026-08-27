package com.drewdrew0414.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/*
 * 비밀번호 재설정의 첫 단계: 인증 코드를 받을 이메일을 지정하는 요청 DTO입니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)
public class PasswordResetRequest {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;
}
