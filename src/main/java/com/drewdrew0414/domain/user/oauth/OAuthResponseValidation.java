package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.global.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 외부 제공자의 빈/잘못된 인증 응답을 계정 식별자로 신뢰하지 않습니다. */
public final class OAuthResponseValidation {
    private OAuthResponseValidation() {}

    public static void requireToken(String token) {
        if(token==null || token.isBlank()) throw unavailable(null);
    }

    public static OAuthUserInfo requireIdentity(Provider expected,OAuthUserInfo info) {
        if(info==null || info.provider()!=expected || info.providerId()==null || info.providerId().isBlank()
                || info.providerId().length()>255) throw unavailable(null);
        return info;
    }

    /** 제공자 URL·HTTP 본문·인가 코드·토큰을 사용자 메시지로 전달하지 않습니다. */
    public static ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY,"소셜 인증 제공자 응답을 확인할 수 없습니다.",cause);
    }
}
