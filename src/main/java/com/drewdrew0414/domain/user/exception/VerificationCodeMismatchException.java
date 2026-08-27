package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 사용자가 입력한 인증 코드가 발송된 코드와 일치하지 않을 때 발생하는 예외입니다.
 */
public class VerificationCodeMismatchException extends CustomException {
    public VerificationCodeMismatchException() {
        super(ErrorCode.VERIFICATION_CODE_MISMATCH); // ErrorCode에 미리 정의해둔 400 + 메시지를 그대로 물려받음
    }
}
