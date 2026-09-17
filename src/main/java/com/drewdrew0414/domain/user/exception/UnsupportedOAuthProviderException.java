package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * URL에 google/naver/github 이외의 제공자 이름이 들어왔을 때 발생하는 예외입니다.
 * Enum.valueOf가 던지는 일반 예외를 그대로 두면 500으로 처리되므로, 잘못된 클라이언트 요청임을
 * 명확히 나타내는 400 응답으로 변환하기 위한 도메인 예외를 따로 둡니다.
 */
public class UnsupportedOAuthProviderException extends CustomException {
    public UnsupportedOAuthProviderException() {
        super(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
    }
}
