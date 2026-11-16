package com.drewdrew0414.domain.user.dto.response;

import lombok.Getter;

/*
 * 비밀번호 재설정 코드 확인에 성공했을 때 내려주는 응답 DTO입니다.
 * 클라이언트는 이 resetToken을 마지막 확정(confirm) 요청에 그대로 실어 보내야 합니다.
 */
@Getter
// resetToken 필드의 getter를 자동 생성 (Lombok)
public class PasswordResetTokenResponse {
    private final String resetToken;

    public PasswordResetTokenResponse(String resetToken) {
        this.resetToken = resetToken;
    }
}
