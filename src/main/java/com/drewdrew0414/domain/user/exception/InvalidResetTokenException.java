package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 비밀번호 재설정 토큰이 존재하지 않거나, 이미 사용됐거나, 만료됐을 때 발생하는 예외입니다.
 * PasswordReset.isValid()가 false를 반환하는 모든 경우를 포괄합니다.
 */
public class InvalidResetTokenException extends CustomException {
    public InvalidResetTokenException() {
        super(ErrorCode.INVALID_RESET_TOKEN); // ErrorCode에 미리 정의해둔 401 + 메시지를 그대로 물려받음
    }
}
