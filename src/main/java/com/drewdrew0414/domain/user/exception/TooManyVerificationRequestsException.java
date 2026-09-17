package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 같은 이메일 주소로 짧은 시간 안에 인증 코드를 반복 요청했을 때 발생하는 예외입니다.
 * 메일 발송 API를 무제한으로 열어두면 SMTP 비용과 서버 자원이 소모되고, 특정 주소에 메일을
 * 쏟아붓는 악용도 가능하므로 HTTP 429(Too Many Requests)로 요청을 잠시 제한합니다.
 */
public class TooManyVerificationRequestsException extends CustomException {
    public TooManyVerificationRequestsException() {
        super(ErrorCode.TOO_MANY_VERIFICATION_REQUESTS);
    }
}
