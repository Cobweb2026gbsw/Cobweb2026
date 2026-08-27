package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 로그인 시 입력한 비밀번호가 저장된 해시값과 일치하지 않을 때 발생하는 예외입니다.
 * AuthService.login()에서 passwordEncoder.matches()가 false를 반환하면 이 예외를 던지고,
 * 동시에 user.increaseLoginFailedCount()로 실패 횟수를 올리고 UserLog에 FAIL_PASSWORD_MISMATCH로 기록하게 됩니다.
 * CustomException을 상속해서 ErrorCode.PASSWORD_MISMATCH(401)에 해당하는 상태코드와 메시지를 갖습니다.
 */
public class PasswordMismatchException extends CustomException {
    public PasswordMismatchException() {
        super(ErrorCode.PASSWORD_MISMATCH); // ErrorCode에 미리 정의해둔 401 + 메시지를 그대로 물려받음
    }
}
