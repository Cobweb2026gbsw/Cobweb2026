package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/** 이메일 검증 정보를 제공하지 않는 공급자의 신규 계정을 안전하게 보류합니다. */
public class OAuthSignupUnavailableException extends CustomException {
    public OAuthSignupUnavailableException() {
        super(ErrorCode.OAUTH_SIGNUP_UNAVAILABLE);
    }
}
