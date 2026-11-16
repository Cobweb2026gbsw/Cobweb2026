package com.drewdrew0414.domain.user.dto.request;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/*
 * 발송된 이메일 인증 코드를 확인하는 요청 DTO입니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)
public class EmailVerificationConfirmRequest {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
    private String email;

    @NotBlank(message = "인증 코드를 입력해주세요.")
    @Pattern(regexp = "[0-9]{8}", message = "인증 코드는 숫자 8자리여야 합니다.")
    private String code;

    @NotNull(message = "인증 목적을 지정해주세요.")
    // 발송할 때 쓴 purpose와 같아야 EmailVerificationService가 같은 레코드를 찾아 검증함
    private VerificationPurpose purpose;
}
