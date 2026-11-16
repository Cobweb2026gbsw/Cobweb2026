package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/** LAN 기기의 localhost로 콜백을 보내는 잘못된 로컬 구성은 쿠키를 만들기 전에 거절합니다. */
public final class OAuthOriginMismatchException extends CustomException {
    public OAuthOriginMismatchException() { super(ErrorCode.OAUTH_ORIGIN_MISMATCH); }
}
