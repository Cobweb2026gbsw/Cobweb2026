package com.drewdrew0414.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/*
 * 비밀번호 재설정의 마지막 단계: resetToken으로 실제 비밀번호를 변경하는 요청 DTO입니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)
public class PasswordResetConfirmRequest {
    @NotBlank(message = "재설정 토큰이 필요합니다.")
    // PasswordResetVerifyRequest 처리 응답(PasswordResetTokenResponse)에서 받은 값을 그대로 실어 보내야 함
    private String resetToken;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 8자 이상이어야합니다.")
    private String newPassword;
}
