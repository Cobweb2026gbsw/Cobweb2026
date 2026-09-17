package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 코드 검증(verify) 시 해당 이메일/목적(purpose)으로 발송된 인증 요청 자체가 없을 때 발생하는 예외입니다.
 * 코드를 요청한 적 없이 바로 검증을 시도한 경우에 해당합니다.
 */
public class EmailVerificationNotFoundException extends CustomException {
    public EmailVerificationNotFoundException() {
        super(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND); // ErrorCode에 미리 정의해둔 404 + 메시지를 그대로 물려받음
    }
}
