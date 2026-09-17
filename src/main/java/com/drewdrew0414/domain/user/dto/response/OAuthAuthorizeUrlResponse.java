package com.drewdrew0414.domain.user.dto.response;

import lombok.Getter;

/*
 * 프론트엔드가 사용자를 리다이렉트시킬 소셜 로그인 동의 화면 URL을 담은 응답 DTO입니다.
 */
@Getter
// authorizeUrl 필드의 getter를 자동 생성 (Lombok)
public class OAuthAuthorizeUrlResponse {
    private final String authorizeUrl;

    public OAuthAuthorizeUrlResponse(String authorizeUrl) {
        this.authorizeUrl = authorizeUrl;
    }
}
