package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 회원가입 시 해당 이메일로 완료된(verified) 이메일 인증 기록이 없을 때 발생하는 예외입니다.
 * UserService.signup()에서 EmailVerificationService.isVerified()가 false를 반환하면 던져집니다.
 */
public class EmailNotVerifiedException extends CustomException {
    public EmailNotVerifiedException() {
        super(ErrorCode.EMAIL_NOT_VERIFIED); // ErrorCode에 미리 정의해둔 400 + 메시지를 그대로 물려받음
    }
}
