package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/** 로그인·인증 코드 확인의 공유 제한을 넘으면 429를 반환합니다. */
public class TooManyAuthAttemptsException extends CustomException {
    public TooManyAuthAttemptsException() {
        super(ErrorCode.TOO_MANY_AUTH_ATTEMPTS);
    }
}
