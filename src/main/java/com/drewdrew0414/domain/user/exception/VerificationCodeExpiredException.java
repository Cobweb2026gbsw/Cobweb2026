package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 입력한 인증 코드는 일치하지만 유효 시간(5분)이 지나 만료됐을 때 발생하는 예외입니다.
 */
public class VerificationCodeExpiredException extends CustomException {
    public VerificationCodeExpiredException() {
        super(ErrorCode.VERIFICATION_CODE_EXPIRED); // ErrorCode에 미리 정의해둔 400 + 메시지를 그대로 물려받음
    }
}
