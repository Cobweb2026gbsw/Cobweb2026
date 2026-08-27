package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * OAuth 로그인 시작 시 브라우저에 저장한 state와 제공자가 콜백으로 돌려준 state가 다를 때 발생합니다.
 * 공격자가 자신의 인가 코드를 다른 사용자의 브라우저에 주입하는 로그인 CSRF를 막기 위해,
 * 두 값이 정확히 일치하지 않으면 외부 제공자 API를 호출하기 전에 로그인 흐름을 중단합니다.
 */
public class InvalidOAuthStateException extends CustomException {
    public InvalidOAuthStateException() {
        super(ErrorCode.INVALID_OAUTH_STATE);
    }
}
