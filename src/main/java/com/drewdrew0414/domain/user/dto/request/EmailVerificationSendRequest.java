package com.drewdrew0414.domain.user.dto.request;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

/*
 * 이메일 인증 코드 발송 요청 DTO입니다. purpose에 따라 회원가입용/비밀번호 재설정용 코드로 나뉩니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)
public class EmailVerificationSendRequest {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotNull(message = "인증 목적을 지정해주세요.")
    // JSON의 "JOIN"/"PASSWORD_RESET" 문자열이 VerificationPurpose enum으로 자동 변환됨
    private VerificationPurpose purpose;
}
